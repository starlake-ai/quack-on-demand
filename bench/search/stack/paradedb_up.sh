#!/usr/bin/env bash
# Starts the part 2 ParadeDB stack (isolated): container qod-p2-paradedb on 127.0.0.1:27432,
# data in the named volume qod-p2-paradedb-data (inside Docker's VM, not a bind mount from the
# scratch folder, so ParadeDB's I/O does not go through the macOS file-sharing layer).
# Usage: paradedb_up.sh <p2> <tag>    (tag: 0.26.6-pg18)
# The superuser password is read by the container from <p2>/secrets/paradedb.txt; never printed.
# To change settings of a running stack: edit paradedb_setup.sql, run paradedb_settings.sh.
set -euo pipefail
P2="$1"; TAG="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -f "$P2/secrets/paradedb.txt" ] || { echo "missing $P2/secrets/paradedb.txt" >&2; exit 2; }
if docker ps -a --format '{{.Names}}' | grep -qx qod-p2-paradedb; then
  echo "container qod-p2-paradedb already exists; run paradedb_down.sh first" >&2; exit 2
fi
# Ready means accepting TCP connections: during first boot the image's entrypoint runs a
# temporary server on the Unix socket only, which a socket pg_isready would take for ready.
wait_ready() {
  for _ in $(seq 1 180); do
    if docker exec qod-p2-paradedb pg_isready -h 127.0.0.1 -U postgres > /dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "ParadeDB did not become ready" >&2; exit 1
}
docker volume create qod-p2-paradedb-data > /dev/null
docker run -d --name qod-p2-paradedb -p 127.0.0.1:27432:5432 --cpus 16 --shm-size 8g \
  -v qod-p2-paradedb-data:/var/lib/postgresql -e POSTGRES_PASSWORD_FILE=/run/secrets/pw \
  --mount type=bind,source="$P2/secrets/paradedb.txt",target=/run/secrets/pw,readonly \
  "paradedb/paradedb:$TAG" > /dev/null
wait_ready
# TLS with a self-signed certificate made inside the container (sslmode=require on the client).
if docker exec qod-p2-paradedb sh -c 'command -v openssl' > /dev/null 2>&1; then
  docker exec -u postgres qod-p2-paradedb sh -c 'cd "$PGDATA" && openssl req -x509 -nodes -newkey rsa:2048 -days 30 -subj /CN=localhost -keyout server.key -out server.crt > /dev/null 2>&1 && chmod 600 server.key'
else
  mkdir -p "$P2/paradedb-tls" && chmod 700 "$P2/paradedb-tls"
  openssl req -x509 -nodes -newkey rsa:2048 -days 30 -subj /CN=localhost \
    -keyout "$P2/paradedb-tls/server.key" -out "$P2/paradedb-tls/server.crt" > /dev/null 2>&1
  PGD="$(docker exec qod-p2-paradedb sh -c 'echo "$PGDATA"')"
  docker cp "$P2/paradedb-tls/server.key" "qod-p2-paradedb:$PGD/server.key"
  docker cp "$P2/paradedb-tls/server.crt" "qod-p2-paradedb:$PGD/server.crt"
  docker exec -u root qod-p2-paradedb sh -c 'chown postgres:postgres "$PGDATA"/server.* && chmod 600 "$PGDATA"/server.key'
fi
# -i: docker exec passes stdin only with it.
docker exec -i -u postgres qod-p2-paradedb psql -q -v ON_ERROR_STOP=1 -f - < "$HERE/paradedb_setup.sql"
docker restart qod-p2-paradedb > /dev/null
wait_ready
docker exec -u postgres qod-p2-paradedb psql -Atc "SELECT extversion FROM pg_extension WHERE extname = 'pg_search'; SHOW server_version; SHOW ssl; SHOW shared_buffers; SHOW shared_preload_libraries;"
docker inspect --format '{{.Image}}' qod-p2-paradedb
echo "ParadeDB p2 stack up"
