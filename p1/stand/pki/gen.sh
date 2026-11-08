#!/usr/bin/env bash
# Test PKI for the P1 stand. NOT for production.
#   root.*        — "server" root CA (installed into the debug APK as a trust anchor)
#   server.*      — TLS cert for app/enroll.<host>
#   device-ca.*   — client (device) CA; the probe signs device certs with it (like KratosGate devices, S3)
set -euo pipefail
HOST="${WOLP_HOST:-wolp.test}"
cd "$(dirname "$0")"
OUT=out; mkdir -p "$OUT"; cd "$OUT"
if [[ -f root.pem && -f device-ca.pem && -f server.pem ]]; then echo "pki: exists ($(pwd))"; exit 0; fi

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 365 \
  -subj "/CN=Wolp P1 Test Root" -keyout root.key -out root.pem \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"

openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=app.${HOST}" \
  -keyout server.key -out server.csr
openssl x509 -req -in server.csr -CA root.pem -CAkey root.key -CAcreateserial -days 300 -out server.pem \
  -extfile <(printf "subjectAltName=DNS:app.%s,DNS:enroll.%s\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n" "$HOST" "$HOST")
cat server.pem root.pem > server-chain.pem

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 365 \
  -subj "/CN=Wolp P1 Device CA" -keyout device-ca.key -out device-ca.pem \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" -addext "keyUsage=critical,keyCertSign,cRLSign"

rm -f server.csr
chmod 644 *.pem *.key   # the probe container reads device-ca.key
echo "pki: generated in $(pwd)"
