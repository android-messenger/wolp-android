#!/usr/bin/env bash
# Live enrollment of a USB-connected phone against the devices stand (S3 §14 task 3):
# Kratos user + browser login → onboarding password (admin API) → sample app on the
# phone runs DeviceTrust.enroll through `adb reverse` → result from logcat + registry.
#
#   ./up.sh && ANDROID_SERIAL=<serial> ./enroll.sh [path/to/sample-debug.apk]
#
# The APK is wolp-device-trust's sample (debug), which carries StandEnrollActivity.
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
APK="${1:-../../../../wolp-device-trust/sample/build/outputs/apk/debug/sample-debug.apk}"
PKG=org.wolpmessenger.devicetrust.sample
TOKEN="${DEVICES_ADMIN_AUTH:-stand-admin-token-0123456789abcdef}"
EMAIL="${STAND_EMAIL:-a37@stand.test}"; PASS="${STAND_PASSWORD:-stand-Password-2026!}"
K=http://127.0.0.1:4433; KA=http://127.0.0.1:4434; GW=http://127.0.0.1:18091
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
json() { python3 -c "import json,sys;d=json.load(sys.stdin);print($1)"; }

# 1. Kratos identity (idempotent) and a browser-flow login → ory_kratos_session cookie.
ID=$(curl -sf "$KA/admin/identities?credentials_identifier=$EMAIL" | json 'd[0]["id"] if d else ""')
if [[ -z "$ID" ]]; then
  ID=$(curl -sf -H 'Content-Type: application/json' "$KA/admin/identities" -d "{\"schema_id\":\"default\",\"traits\":{\"email\":\"$EMAIL\"},
       \"credentials\":{\"password\":{\"config\":{\"password\":\"$PASS\"}}}}" | json 'd["id"]')
fi
FLOW=$(curl -sf -c "$T/jar" -H 'Accept: application/json' "$K/self-service/login/browser")
FID=$(echo "$FLOW" | json 'd["id"]')
CSRF=$(echo "$FLOW" | json '[n["attributes"]["value"] for n in d["ui"]["nodes"] if n["attributes"].get("name")=="csrf_token"][0]')
curl -sf -b "$T/jar" -c "$T/jar" -H 'Accept: application/json' -H 'Content-Type: application/json' \
  "$K/self-service/login?flow=$FID" -d "{\"method\":\"password\",\"identifier\":\"$EMAIL\",\"password\":\"$PASS\",\"csrf_token\":\"$CSRF\"}" >/dev/null
COOKIE="ory_kratos_session=$(awk '$6=="ory_kratos_session"{print $7}' "$T/jar")"
echo "identity $ID, session cookie ok"

# 2. Onboarding password from the admin API (S3 §5.10).
PW=$(curl -sf -H "Authorization: Bearer $TOKEN" -H 'X-Admin-Actor: stand' "$GW/admin/v1/onboarding-passwords" \
     -d "{\"identityId\":\"$ID\"}" | json 'd["password"]')
echo "onboarding password issued"

# 3. Server pins: the deployed key and the spare one (S2 §10).
pin() { openssl pkey -in "$1" -pubout -outform DER 2>/dev/null | openssl dgst -sha256 -binary | base64; }
PINS="sha256/$(pin out/server.key),sha256/$(pin out/backup.key)"

# 4. The phone: fresh app state, tunnel, start.
"$ADB" install -r -t "$APK" >/dev/null
"$ADB" shell pm clear "$PKG" >/dev/null
"$ADB" reverse tcp:9443 tcp:9443 >/dev/null
"$ADB" logcat -c
"$ADB" shell am start -W -n "$PKG/.StandEnrollActivity" --es url https://localhost:9443 --es pins "$PINS" \
  --es cookie "'$COOKIE'" --es password "$PW" >/dev/null
echo -n "enrolling on the phone "
for _ in $(seq 1 60); do
  LINE=$("$ADB" logcat -d -s WOLP_STAND:I | grep -m1 'RESULT' || true)
  [[ -n "$LINE" ]] && break; echo -n .; sleep 1
done
echo; "$ADB" logcat -d -s WOLP_STAND:I | sed -n 's/.*WOLP_STAND: //p'

# 5. What the gateway recorded.
echo "--- registry"
curl -s -H "Authorization: Bearer $TOKEN" "$GW/admin/v1/devices?identityId=$ID" | python3 -m json.tool
docker compose exec -T db psql -U stand -d ids -Atc \
  "select ts::time(0), type, coalesce(reason,''), coalesce(actor,'') from devices.device_events where identity_id='$ID' order by id"
