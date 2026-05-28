#!/usr/bin/env bash
#
# Run the quack-on-demand Docker image against a REMOTE Postgres instance.
# Auth and TLS are off by default for fast smoke tests; both are
# parameterized so the same script also drives a production-shaped run.
#
# *** DO NOT MIX DOCKER AND NATIVE AGAINST THE SAME CATALOG DB ***
# DuckLake bakes the *absolute* DATA_PATH into Postgres metadata
# (__ducklake_data_file rows). Inside the container that path is
# /app/ducklake/$PG_DBNAME; natively it's $PWD/ducklake/$PG_DBNAME on
# the host. Once a manager writes a path, every future manager reading
# that catalog must see the same string or every query will fail with
# "could not open file ...". If you want to switch deployment modes,
# either (a) point Docker at a different PG_DBNAME (e.g. tpch_docker)
# so the two catalogs stay isolated, or (b) reset the data by dropping
# the catalog DB and the corresponding ducklake/<db>/ directory.
#
# All knobs come from env vars (with defaults). Override what you need:
#
#   PG_HOST          remote Postgres host                (required)
#   PG_PORT          remote Postgres port                (default 5432)
#   PG_USER          remote Postgres user                (default postgres)
#   PG_PASSWORD      remote Postgres password            (required)
#   PG_DBNAME        target catalog DB                   (default tpch)
#   PG_SCHEMA        DuckLake schema                     (default main)
#
#   AUTH             "true" to enable FlightSQL DB auth  (default false)
#                    When true, the manager seeds the admin user from
#                    ADMIN_USERNAME / ADMIN_PASSWORD into slkstate_user.
#   ADMIN_USERNAME   admin login (when AUTH=true)        (default admin)
#   ADMIN_PASSWORD   admin password (when AUTH=true)     (default admin — rotate!)
#   API_KEY          REST API X-API-Key                  (unset = open API + warning)
#
#   TLS              "true" to keep TLS on               (default false — quicker over plain gRPC)
#
#   IMAGE            image tag to run                    (default quack-on-demand:dev)
#   MANAGER_PORT     host port -> manager REST + UI      (default 20900)
#   EDGE_PORT        host port -> FlightSQL edge         (default 31338)
#   QUACK_MIN_PORT   first local node port               (default 21900)
#   QUACK_MAX_PORT   last  local node port               (default 22500)
#   DATA_PATH        host dir bind-mounted as            (default <CWD>/ducklake)
#                    /app/ducklake — holds the DuckLake
#                    data files; survives container
#                    restarts because it lives on the host.
#   CERTS_DIR        host dir to mount as cert store     (default <CWD>/certs)
#   CONTAINER_NAME   docker --name                        (default quack-on-demand)
#
# Usage:
#   # Default: no-auth, no-TLS smoke test
#   PG_HOST=db.internal PG_PASSWORD=hunter2 ./scripts/start-docker.sh
#
#   # Auth on, TLS on — closer to a prod-shaped boot
#   PG_HOST=my-rds.amazonaws.com PG_PASSWORD=*** \
#     AUTH=true ADMIN_PASSWORD=change-me TLS=true \
#     ./scripts/start-docker.sh
#
#   # Persist ducklake files on a specific local SSD
#   DATA_PATH=/Volumes/data/ducklake PG_HOST=… PG_PASSWORD=… ./scripts/start-docker.sh

set -euo pipefail

# ---- Required ----
: "${PG_HOST:?PG_HOST is required (remote Postgres host)}"
: "${PG_PASSWORD:?PG_PASSWORD is required}"

# ---- Defaults ----
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_DBNAME="${PG_DBNAME:-tpch}"
PG_SCHEMA="${PG_SCHEMA:-main}"

AUTH="${AUTH:-false}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin}"
TLS="${TLS:-false}"

IMAGE="${IMAGE:-quack-on-demand:dev}"
MANAGER_PORT="${MANAGER_PORT:-20900}"
EDGE_PORT="${EDGE_PORT:-31338}"
QUACK_MIN_PORT="${QUACK_MIN_PORT:-21900}"
QUACK_MAX_PORT="${QUACK_MAX_PORT:-22500}"
CONTAINER_NAME="${CONTAINER_NAME:-quack-on-demand}"

# The DuckLake data files are bind-mounted from the host so they survive
# `docker rm` / `docker run` cycles. Defaults to `<CWD>/ducklake` so the
# data lives next to wherever you invoked the script; override DATA_PATH
# to point at any host directory (NFS share, external SSD, etc.). The
# directory is created if missing and the path is canonicalized to an
# absolute one — `docker run -v` requires it.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DATA_PATH="${DATA_PATH:-$PWD/ducklake}"
CERTS_DIR="${CERTS_DIR:-$PWD/certs}"
mkdir -p "$DATA_PATH" "$CERTS_DIR"
DATA_PATH="$(cd "$DATA_PATH" && pwd)"
CERTS_DIR="$(cd "$CERTS_DIR" && pwd)"

# ---- Sanity ----
docker image inspect "$IMAGE" >/dev/null 2>&1 || {
  echo "ERROR: image '$IMAGE' not found locally." >&2
  echo "       Build it first: docker build -t $IMAGE $REPO_DIR" >&2
  exit 1
}

# ---- Stop any prior instance with the same name ----
if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
  echo "stopping previous container: $CONTAINER_NAME"
  docker rm -f "$CONTAINER_NAME" >/dev/null
fi

echo "image:        $IMAGE"
echo "container:    $CONTAINER_NAME"
echo "remote pg:    $PG_USER@$PG_HOST:$PG_PORT/$PG_DBNAME (schema: $PG_SCHEMA)"
echo "manager:      http://localhost:$MANAGER_PORT/ui/"
echo "flightsql:    localhost:$EDGE_PORT  (TLS=$TLS)"
echo "auth:         AUTH=$AUTH$([[ "$AUTH" == "true" ]] && echo " (admin: $ADMIN_USERNAME)" || echo " (trust-the-client)")"
echo "ducklake:     $DATA_PATH  (host) -> /app/ducklake  (container)"
echo "certs:        $CERTS_DIR  (host) -> /app/certs     (container)"
echo ""

# ---- Run ----
# host.docker.internal works on Docker Desktop (Mac/Windows). On Linux, the
# caller should pass the host's actual IP (or use --network=host) — this
# script doesn't add `--add-host=host.docker.internal:host-gateway` so the
# user keeps control over networking.
exec docker run --rm \
  --name "$CONTAINER_NAME" \
  -p "$MANAGER_PORT:20900" \
  -p "$EDGE_PORT:31338" \
  -p "$QUACK_MIN_PORT-$QUACK_MAX_PORT:$QUACK_MIN_PORT-$QUACK_MAX_PORT" \
  -e SL_QUACK_PG_HOST="$PG_HOST" \
  -e SL_QUACK_PG_PORT="$PG_PORT" \
  -e SL_QUACK_PG_USER="$PG_USER" \
  -e SL_QUACK_PG_PASSWORD="$PG_PASSWORD" \
  -e SL_QUACK_PG_DBNAME="$PG_DBNAME" \
  -e SL_QUACK_PG_SCHEMA="$PG_SCHEMA" \
  -e SL_QUACK_AUTH_DB_ENABLED="$AUTH" \
  -e SL_QUACK_ADMIN_USERNAME="$ADMIN_USERNAME" \
  -e SL_QUACK_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  ${API_KEY:+-e SL_QUACK_API_KEY="$API_KEY"} \
  -e PROXY_TLS_ENABLED="$TLS" \
  -e SL_QUACK_MIN_PORT="$QUACK_MIN_PORT" \
  -e SL_QUACK_MAX_PORT="$QUACK_MAX_PORT" \
  -v "$DATA_PATH:/app/ducklake" \
  -v "$CERTS_DIR:/app/certs" \
  "$IMAGE"