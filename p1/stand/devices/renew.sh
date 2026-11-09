#!/usr/bin/env bash
# Live certificate renewal of a USB-connected phone through the app host (S3 §5.3–5.5,
# task 5): the stand issues short-lived certificates, the phone enrolls (enroll.sh),
# and a minute later the sample's StandRenewActivity runs DeviceTrust.ensureFresh():
# renew/challenge → new TEE key attested on the nonce → renew (mTLS, continuitySig by
# the old key) → confirm with the new certificate; ping / whoami / a proxied request
# before and after. Then the registry.
#
#   ./up.sh && ANDROID_SERIAL=<serial> ./renew.sh [path/to/sample-debug.apk]
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG=org.wolpmessenger.devicetrust.sample
TOKEN="${DEVICES_ADMIN_AUTH:-stand-admin-token-0123456789abcdef}"
GW=http://127.0.0.1:18091

# 1. Short certificates: notAfter = +2 h, renewAfter = +1 min.
export GATE_DIR="${GATE_DIR:-$(cd ../../../../gate && pwd)}" STAND_UID="$(id -u)" STAND_GID="$(id -g)"
STAND_CERT_TTL=2h STAND_RENEW_BEFORE=119m docker compose up -d kratosgate >/dev/null
for _ in $(seq 1 60); do curl -sf "$GW/readyz" >/dev/null && break; sleep 1; done

# 2. Enrollment (fresh app state). The session cookie is kept for the app host requests.
ENROLL_LOG=$(mktemp); trap 'rm -f "$ENROLL_LOG"' EXIT
KEEP_COOKIE=1 ./enroll.sh "$@" | tee "$ENROLL_LOG"
grep -q 'RESULT OK' "$ENROLL_LOG" || { echo "enrollment failed"; exit 1; }
COOKIE=$(sed -n 's/^cookie //p' "$ENROLL_LOG")
ID=$(sed -n 's/^identity \([^,]*\),.*/\1/p' "$ENROLL_LOG")

echo -n "waiting for renewAfter (65 s) "; for _ in $(seq 1 13); do echo -n .; sleep 5; done; echo

# 3. Renewal on the phone.
pin() { openssl pkey -in "$1" -pubout -outform DER 2>/dev/null | openssl dgst -sha256 -binary | base64; }
PINS="sha256/$(pin out/server.key),sha256/$(pin out/backup.key)"
"$ADB" reverse tcp:9443 tcp:9443 >/dev/null
"$ADB" reverse tcp:9444 tcp:9444 >/dev/null
"$ADB" logcat -c
# A new process: DeviceTrust.init() is once per process, and StandEnrollActivity set appBaseUrl to the enroll host.
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -W -n "$PKG/.StandRenewActivity" --es url https://localhost:9443 --es appUrl https://localhost:9444 \
  --es pins "$PINS" --es cookie "'$COOKIE'" >/dev/null
echo -n "renewing on the phone "
for _ in $(seq 1 60); do
  LINE=$("$ADB" logcat -d -s WOLP_STAND:I | grep -m1 'RESULT' || true)
  [[ -n "$LINE" ]] && break; echo -n .; sleep 1
done
echo; "$ADB" logcat -d -s WOLP_STAND:I | sed -n 's/.*WOLP_STAND: //p'

# 4. What the gateway recorded; back to normal certificate life.
echo "--- certificates"
docker compose exec -T db psql -U stand -d ids -Atc \
  "select c.serial, c.state, c.not_after::time(0), c.first_used_at is not null from devices.device_certs c join devices.devices d on d.id = c.device_id
   where d.identity_id = '$ID' and d.status = 'active' order by c.created_at"
echo "--- events"
docker compose exec -T db psql -U stand -d ids -Atc \
  "select ts::time(0), type, coalesce(reason,''), coalesce(actor,'') from devices.device_events where identity_id='$ID' order by id desc limit 6"
curl -s "$GW/metrics" | grep -E '^kratosgate_devices_(renew_total|check_duration_seconds_count)' | sed 's/^/  /'
docker compose up -d kratosgate >/dev/null
