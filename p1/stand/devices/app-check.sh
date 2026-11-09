#!/usr/bin/env bash
# S3 task 5 on the stand: the app host (mTLS + auth_request → KratosGate check →
# Oathkeeper → echo backend), checks S3-N1, S3-N2 and acceptance criteria 4 and 7a.
#
#   ./up.sh && ./app-check.sh
#
# The device here is synthetic: a client certificate issued with the stand's device
# intermediate (out/int.key) for a key this script holds, registered directly in the
# registry. nginx and the check do not care how a certificate was issued; the real
# enrollment path is enroll.sh (phone).
set -euo pipefail
cd "$(dirname "$0")"
TOKEN="${DEVICES_ADMIN_AUTH:-stand-admin-token-0123456789abcdef}"
K=http://127.0.0.1:4433; KA=http://127.0.0.1:4434; GW=http://127.0.0.1:18091
APP=https://localhost:9444; ENROLL=https://localhost:9443
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
json() { python3 -c "import json,sys;d=json.load(sys.stdin);print($1)"; }
psql() { docker compose exec -T db psql -U stand -d ids -Atqc "$1"; }
PASS=0; FAIL=0
ok()   { echo "  ok   $*"; PASS=$((PASS + 1)); }
bad()  { echo "  FAIL $*"; FAIL=$((FAIL + 1)); }
expect() { # expect <what> <got> <want>
  if [[ "$2" == "$3" ]]; then ok "$1: $2"; else bad "$1: got '$2', want '$3'"; fi
}

# --- users: alice (owns the device) and bob, each with a session cookie ---------------
login() { # login <email> → identity id; cookie in $T/<email>.cookie
  local email=$1 pass='stand-Password-2026!' id flow fid csrf
  id=$(curl -sf "$KA/admin/identities?credentials_identifier=$email" | json 'd[0]["id"] if d else ""')
  if [[ -z "$id" ]]; then
    id=$(curl -sf -H 'Content-Type: application/json' "$KA/admin/identities" -d "{\"schema_id\":\"default\",\"traits\":{\"email\":\"$email\"},
         \"credentials\":{\"password\":{\"config\":{\"password\":\"$pass\"}}}}" | json 'd["id"]')
  fi
  flow=$(curl -sf -c "$T/$email.jar" -H 'Accept: application/json' "$K/self-service/login/browser")
  fid=$(echo "$flow" | json 'd["id"]')
  csrf=$(echo "$flow" | json '[n["attributes"]["value"] for n in d["ui"]["nodes"] if n["attributes"].get("name")=="csrf_token"][0]')
  curl -sf -b "$T/$email.jar" -c "$T/$email.jar" -H 'Accept: application/json' -H 'Content-Type: application/json' \
    "$K/self-service/login?flow=$fid" -d "{\"method\":\"password\",\"identifier\":\"$email\",\"password\":\"$pass\",\"csrf_token\":\"$csrf\"}" >/dev/null
  echo "ory_kratos_session=$(awk '$6=="ory_kratos_session"{print $7}' "$T/$email.jar")" > "$T/$email.cookie"
  echo "$id"
}
ALICE=$(login alice@stand.test); BOB=$(login bob@stand.test)
CA=$(cat "$T/alice@stand.test.cookie"); CB=$(cat "$T/bob@stand.test.cookie")
echo "alice $ALICE, bob $BOB"

# --- a synthetic device of alice: key, certificate from the device CA, registry rows --
new_device() { # new_device <identity> <not_after interval> → "<deviceId> <serial>"; key/cert in $T/<deviceId>.{key,pem}
  local identity=$1 hours=$2 id serial
  id=$(python3 -c "import os,time;A='0123456789ABCDEFGHJKMNPQRSTVWXYZ';n=(int(time.time()*1000)<<80)|int.from_bytes(os.urandom(10),'big');print(''.join(A[(n>>(5*i))&31] for i in range(25,-1,-1)))")
  serial=$(python3 -c "import os;b=bytearray(os.urandom(16));b[0]=(b[0]&0x7f)|0x40;print(b.hex())")
  openssl ecparam -name prime256v1 -genkey -noout -out "$T/$id.key" 2>/dev/null
  openssl req -new -key "$T/$id.key" -subj "/CN=$id" -out "$T/$id.csr" 2>/dev/null
  openssl x509 -req -in "$T/$id.csr" -CA out/int.pem -CAkey out/int.key -set_serial "0x$serial" -days 3 -out "$T/$id.pem" 2>/dev/null \
    -extfile <(printf "subjectAltName=URI:urn:wolp:device:%s,URI:urn:wolp:user:%s\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\nbasicConstraints=critical,CA:FALSE\n" "$id" "$identity")
  cat "$T/$id.pem" out/int.pem > "$T/$id.chain.pem"
  psql "UPDATE devices.devices SET status='revoked', revoked_at=now(), revoke_reason='replaced' WHERE identity_id='$identity' AND status='active';
        INSERT INTO devices.devices (id, identity_id, status, security_level, chain_type, verified_boot_key, os_version, patch_level, app_version_code)
          VALUES ('$id', '$identity', 'active', 'tee', 'rkp', '\x00', 160000, 202610, 10300);
        INSERT INTO devices.device_certs (serial, device_id, spki_sha256, cert_der, not_before, not_after, state)
          VALUES ('$(echo "$serial" | sed 's/^0*//')', '$id', '\x00', '\x00', now() - interval '5 minutes', now() + interval '$hours hours', 'active');"
  echo "$id $serial"
}
read -r DEV SERIAL < <(new_device "$ALICE" 24)   # renewAfter passed: X-Device-Renew expected
echo "device $DEV serial $SERIAL"

# app <path> [curl args…] → "<status>" ; headers in $T/h, body in $T/b
app() {
  local path=$1; shift
  curl -sk --http2 -o "$T/b" -D "$T/h" -w '%{http_code}' --cert "$T/$DEV.chain.pem" --key "$T/$DEV.key" "$@" "$APP$path" || true
}
hdr() { grep -i "^$1:" "$T/h" | tr -d '\r' | sed 's/^[^:]*: *//' | paste -sd, -; }
echo_hdr() { grep -i "^$1:" "$T/b" | tr -d '\r' | sed 's/^[^:]*: *//' | paste -sd, -; }

echo "--- criterion 4: TLS"
# nginx with ssl_verify_client on completes the TLS 1.3 handshake and answers 400
# itself ("No required SSL certificate was sent"): nothing reaches auth_request.
code=$(curl -sk -o "$T/b" -w '%{http_code}' "$APP/messenger/api/chats" -H "Cookie: $CA" || true)
expect "no client certificate → nginx 400, request not proxied" "$code/$(grep -qc 'No required SSL certificate was sent' "$T/b" && echo yes)" "400/yes"

echo "--- S3-N1: auth_request, headers in every location, values in @dt_4xx"
code=$(app /messenger/api/chats -H "Cookie: $CA")
expect "alice cert + alice session → echo backend" "$code" "200"
expect "  X-Device-Renew on a proxied location (wolp-device-headers.conf)" "$(hdr X-Device-Renew)" "1"
expect "  X-Device-Deny absent on success" "$(hdr X-Device-Deny)" ""
code=$(app /messenger/api/chats)
expect "no session → 401" "$code" "401"
expect "  X-Device-Deny in @dt_401 (auth_request_set survives the internal redirect)" "$(hdr X-Device-Deny)" "NO_SESSION"
expect "  JSON body" "$(cat "$T/b")" '{"error":"NO_SESSION"}'
code=$(app /messenger/api/chats -H "Cookie: $CB")
expect "alice cert + bob session → 403" "$code" "403"
expect "  X-Device-Deny" "$(hdr X-Device-Deny)" "USER_MISMATCH"
code=$(app /v1/device/ping)
expect "ping without a session (criterion 7a)" "$code" "204"
expect "  X-Device-Renew from the module, once" "$(hdr X-Device-Renew)" "1"
code=$(app /v1/device/whoami -H "Cookie: $CB")
expect "whoami with bob's session" "$code/$(json 'd["session"]+"/"+d["deviceId"]' < "$T/b")" "200/mismatch/$DEV"
code=$(app /v1/device/whoami)
expect "whoami without a session" "$(json 'd["session"]' < "$T/b")" "none"

echo "--- S3-N2: identity headers"
code=$(app /messenger/api/headers -H "Cookie: $CA" -H "X-Device-Id: FORGED" -H "X-Device-User: FORGED" -H "X-Client-Serial: FORGED" \
  -H "X-Client-Verify: FORGED" -H "X-User-Id: FORGED")
expect "request with forged headers passes" "$code" "200"
expect "  backend X-Device-Id = the real device (once, through Oathkeeper)" "$(echo_hdr X-Device-Id)" "$DEV"
expect "  backend X-User-Id = alice (Oathkeeper)" "$(echo_hdr X-User-Id)" "$ALICE"
expect "  backend sees no X-Device-User" "$(echo_hdr X-Device-User)" ""
expect "  backend sees no X-Client-Serial" "$(echo_hdr X-Client-Serial)" ""
expect "  backend sees no X-Client-Verify" "$(echo_hdr X-Client-Verify)" ""
code=$(app /firebase-push/internal/headers -H "Cookie: $CA")
expect "app-firebase-push rule: X-Device-Id reaches firebase-push" "$code/$(echo_hdr X-Device-Id)/$(echo_hdr X-User-Id)" "200/$DEV/$ALICE"
code=$(curl -sk -o "$T/b" -w '%{http_code}' "$ENROLL/v1/enroll/start" -H "X-Client-Serial: $SERIAL" -H "X-Device-Id: FORGED" -H "Cookie: $CA" -d '{}' || true)
expect "enroll host with forged X-Client-*/X-Device-* reaches the module (empty body → 400)" "$code/$(json 'd["error"]' < "$T/b")" "400/BAD_REQUEST"

echo "--- criterion 7a: expiry by the registry, not only by TLS"
psql "UPDATE devices.device_certs SET not_after = now() - interval '1 minute' WHERE device_id = '$DEV'; SELECT pg_notify('devices_revoked', '$DEV')" >/dev/null
code=$(app /messenger/api/chats -H "Cookie: $CA")
expect "certificate valid for TLS, expired in the registry" "$code/$(hdr X-Device-Deny)" "401/CERT_EXPIRED"

echo "--- criterion 4: revocation reaches the check at once (LISTEN/NOTIFY)"
read -r DEV SERIAL < <(new_device "$ALICE" 72)
code=$(app /messenger/api/chats -H "Cookie: $CA")
expect "new device accepted (and cached)" "$code/$(hdr X-Device-Renew)" "200/"
curl -sf -H "Authorization: Bearer $TOKEN" -H 'X-Admin-Actor: stand' "$GW/admin/v1/devices/$DEV/revoke" -d '{"reason":"lost"}' >/dev/null
code=$(app /messenger/api/chats -H "Cookie: $CA")
expect "after admin revoke" "$code/$(hdr X-Device-Deny)" "403/DEVICE_REVOKED"
code=$(app /v1/device/ping)
expect "ping after revoke" "$code/$(hdr X-Device-Deny)" "403/DEVICE_REVOKED"

echo "--- /internal/devices/active (HMAC), from inside the docker network only"
read -r DEV2 _ < <(new_device "$BOB" 72)
URI="/internal/devices/active?ids=$DEV,$DEV2"; TS=$(date +%s)
SIG=$(printf 'GET\n%s\n%s' "$URI" "$TS" | openssl dgst -sha256 -hmac stand-internal-hmac-secret -hex | sed 's/.*= //')
got=$(curl -sf -H "X-Timestamp: $TS" -H "X-Signature: $SIG" "$GW$URI")
expect "active of [revoked, active]" "$got" "{\"active\":[\"$DEV2\"]}"
code=$(curl -sk -o /dev/null -w '%{http_code}' --cert "$T/$DEV2.chain.pem" --key "$T/$DEV2.key" "$APP$URI" -H "Cookie: $CB")
expect "/internal/* is not reachable through the app host (goes to Oathkeeper: no rule)" "$code" "404"

echo "--- metrics"
curl -s "$GW/metrics" | grep -E '^kratosgate_devices_(check_denied_total|check_duration_seconds_count)' | sed 's/^/  /'

echo; echo "passed $PASS, failed $FAIL"
[[ $FAIL -eq 0 ]]
