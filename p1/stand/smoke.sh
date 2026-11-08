#!/usr/bin/env bash
# Host-side smoke test of the stand (curl as the "device").
set -euo pipefail
cd "$(dirname "$0")"
H="${WOLP_HOST:-wolp.test}"; T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
c() { curl -s --resolve "app.$H:443:127.0.0.1" --resolve "enroll.$H:443:127.0.0.1" --cacert pki/out/root.pem -A "WolpNative/smoke" "$@"; }
echo "app without client cert -> $(c -o /dev/null -w '%{http_code}' "https://app.$H/messenger-ui/") (expect 400)"
echo "login cookie: $(c -D - -o /dev/null -X POST "https://enroll.$H/p1/login" | grep -i '^set-cookie' | tr -d '\r')"
openssl ecparam -name prime256v1 -genkey -noout -out "$T/k.pem"
SPKI=$(openssl ec -in "$T/k.pem" -pubout -outform DER 2>/dev/null | base64 -w0)
c -X POST "https://enroll.$H/p1/enroll" -d "{\"spki\":\"$SPKI\",\"label\":\"smoke\"}" \
  | python3 -c 'import json,sys;d=json.load(sys.stdin);print("enrolled serial",d["serial"]);open(sys.argv[1],"w").write(d["chain"][0])' "$T/c.pem"
echo "app with cert: $(c --cert "$T/c.pem" --key "$T/k.pem" "https://app.$H/p1/echo?via=smoke")"
