#!/usr/bin/env bash
# Brings the devices stand up: generates out/ (server TLS cert from pki/stand-root,
# device CA root + intermediate via gate's device-ca), starts the stack, waits for /readyz.
set -euo pipefail
cd "$(dirname "$0")"
GATE_DIR="${GATE_DIR:-$(cd ../../../../gate && pwd)}"; export GATE_DIR
export STAND_UID="$(id -u)" STAND_GID="$(id -g)"
mkdir -p out/run   # signer socket, shared by bind mount (both containers run as this uid)

if [[ ! -f out/server.pem ]]; then
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/CN=localhost" \
    -keyout out/server.key -out out/server.csr 2>/dev/null
  openssl x509 -req -in out/server.csr -CA pki/stand-root.pem -CAkey pki/stand-root.key -CAcreateserial \
    -CAserial out/stand-root.srl -days 365 -out out/server.pem 2>/dev/null \
    -extfile <(printf "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n")
  cat out/server.pem pki/stand-root.pem > out/server-chain.pem
  # Backup pin (S2 §10 needs two): a spare key that is never deployed.
  openssl ecparam -name prime256v1 -genkey -noout -out out/backup.key 2>/dev/null
  rm -f out/server.csr
  chmod 644 out/server.key   # nginx in the container reads it
fi
if [[ ! -f out/int.pem ]]; then
  (cd "$GATE_DIR" && go build -o "$OLDPWD/out/device-ca" ./cmd/device-ca)
  (cd out && ./device-ca root -key root.key -cert root.pem >/dev/null \
          && ./device-ca intermediate -root-key root.key -root-cert root.pem -key int.key -cert int.pem >/dev/null)
fi

docker compose up -d --build
echo -n "waiting for /readyz "
for _ in $(seq 1 90); do
  if curl -sf 127.0.0.1:18091/readyz >/dev/null; then echo " ready"; exit 0; fi
  echo -n .; sleep 2
done
echo; curl -s 127.0.0.1:18091/readyz; echo; docker compose logs --tail 30 kratosgate; exit 1
