#!/usr/bin/env bash
# Devices stand + live messenger (docker-compose.messenger.yml). Run ./up.sh once first (PKI, signer).
# Needs: repos/HormigaMessanger/target/quarkus-app (./mvnw package -DskipTests, JDK 25) and the image
# wolp-stand/key-directory:local (docker build -t wolp-stand/key-directory:local <hormiga-key-directory>).
#   ./messenger-up.sh            — up (front4mess is rebuilt from repos/messenger/front)
#   ./messenger-up.sh down       — back to the probe: messenger services stopped, nginx/oathkeeper in probe mode
set -euo pipefail
cd "$(dirname "$0")"
GATE_DIR="${GATE_DIR:-$(cd ../../../../gate && pwd)}"; export GATE_DIR
export STAND_UID="$(id -u)" STAND_GID="$(id -g)" APP_PACKAGE="${APP_PACKAGE:-org.wolpmessenger}"
M=(-f docker-compose.yml -f docker-compose.messenger.yml)

if [[ "${1:-}" == down ]]; then
  docker compose "${M[@]}" stop minio-loopback messenger keydirectory front4mess kafka redis minio
  STAND_WEBCLIENT=probe docker compose up -d --force-recreate oathkeeper nginx kratosgate
  exit 0
fi

APP_DIR="${MESSENGER_APP_DIR:-../../../../HormigaMessanger/target/quarkus-app}"
[[ -f $APP_DIR/quarkus-run.jar ]] || { echo "no $APP_DIR/quarkus-run.jar — build repos/HormigaMessanger first" >&2; exit 1; }
docker image inspect wolp-stand/key-directory:local >/dev/null 2>&1 || { echo "no image wolp-stand/key-directory:local" >&2; exit 1; }

# JVM truststore of the messenger: the JDK's CAs + the stand root (its own MinIO calls, minio-loopback)
if [[ ! -f out/stand-trust.p12 || pki/stand-root.pem -nt out/stand-trust.p12 ]]; then
  docker run --rm -u "$STAND_UID:$STAND_GID" -v "$PWD/out:/out" -v "$PWD/pki:/pki:ro" eclipse-temurin:25-jre sh -c \
    'keytool -importkeystore -srckeystore "$JAVA_HOME/lib/security/cacerts" -srcstorepass changeit \
       -destkeystore /out/stand-trust.p12 -deststoretype PKCS12 -deststorepass changeit -noprompt >/dev/null 2>&1 &&
     keytool -importcert -alias wolp-stand-root -file /pki/stand-root.pem -keystore /out/stand-trust.p12 \
       -storepass changeit -noprompt >/dev/null'
fi
docker compose up -d db
until docker compose exec -T db pg_isready -U stand -q; do sleep 1; done
for db in hormiga_messenger hormiga_keys; do
  docker compose exec -T db psql -U stand -d ids -tAc "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 \
    || docker compose exec -T db psql -U stand -d ids -qc "CREATE DATABASE $db"
done
export STAND_WEBCLIENT=messenger
docker compose "${M[@]}" up -d --build
# kratos/oathkeeper/nginx read their files at start: recreate so the messenger-mode files are used
docker compose "${M[@]}" up -d --force-recreate kratos oathkeeper nginx
echo -n "waiting for messenger "
for _ in $(seq 1 90); do
  if docker compose "${M[@]}" exec -T nginx wget -qO- http://messenger:8080/q/health/ready 2>/dev/null | grep -q '"UP"'; then echo " up"; break; fi
  echo -n .; sleep 2
done
docker compose "${M[@]}" ps --format '{{.Service}}\t{{.Status}}'
echo "app (phone): https://localhost:9444/messenger-ui/   browser: https://localhost:9445/messenger-ui/   probe: /probe/ on app"
