#!/usr/bin/env bash
#
# Run quack-on-demand from a Docker image, against an EXTERNAL Postgres.
#
# Two modes, picked via BUILD:
#   BUILD=0 (default) — `docker pull` the published image
#                       starlakeai/quack-on-demand:$QUACK_VERSION
#   BUILD=1           — `docker build` this repo's Dockerfile and tag the
#                       result as starlakeai/quack-on-demand:$QUACK_VERSION
#                       (same name as the published image so the rest of
#                       this script is identical for both flows)
#
# Env vars (with defaults):
#
#   BUILD=1          build the image from local Dockerfile instead of pulling
#   QUACK_VERSION    image tag                              (default latest)
#   IMAGE            full image                             (default starlakeai/quack-on-demand)
#
#   PG_HOST          remote Postgres host                   (required)
#   PG_PORT          remote Postgres port                   (default 5432)
#   PG_USER          remote Postgres user                   (default postgres)
#   PG_PASSWORD      remote Postgres password               (required)
#   PG_DBNAME        target catalog DB                      (default tpch)
#   PG_SCHEMA        DuckLake schema                        (default main)
#
#   AUTH             "true" to enable FlightSQL DB auth     (default true)
#                    Required for the admin UI to log in.
#   ADMIN_USERNAME   admin login (when AUTH=true)           (default admin)
#   ADMIN_PASSWORD   admin password (when AUTH=true)        (default admin — rotate!)
#   API_KEY          REST API X-API-Key                     (unset = open API + warning)
#
#   TLS              "true" to enable FlightSQL edge TLS    (default false)
#                    With false, loadtest needs --url grpc://...
#
#   MANAGER_PORT     host port -> manager REST + UI         (default 20900)
#   EDGE_PORT        host port -> FlightSQL edge            (default 31338)
#   QUACK_MIN_PORT   first local node port                  (default 21900)
#   QUACK_MAX_PORT   last  local node port                  (default 22500)
#   DATA_PATH        host dir bind-mounted as /app/ducklake (default <CWD>/ducklake)
#   CERTS_DIR        host dir for auto-generated TLS cert   (default <CWD>/certs)
#   CONTAINER_NAME   docker --name                          (default quack-on-demand)
#
# *** DO NOT MIX DOCKER AND NATIVE AGAINST THE SAME CATALOG DB ***
# DuckLake bakes the absolute DATA_PATH into the Postgres metadata —
# inside the container that path is /app/ducklake/$PG_DBNAME; natively
# it is $PWD/ducklake/$PG_DBNAME on the host. Use a different PG_DBNAME
# per mode, or wipe ducklake/<db>/ between switches. See RUNNING.md for
# the recovery recipes.
#
# Usage:
#   PG_HOST=db.internal PG_PASSWORD=*** ./scripts/run-docker.sh
#   QUACK_VERSION=0.1.0 PG_HOST=... PG_PASSWORD=... ./scripts/run-docker.sh
#   QUACK_VERSION=latest-snapshot PG_HOST=... PG_PASSWORD=... ./scripts/run-docker.sh

set -euo pipefail

# ---- Required ----
: "${PG_HOST:?PG_HOST is required (remote Postgres host)}"
: "${PG_PASSWORD:?PG_PASSWORD is required}"

# ---- Defaults ----
IMAGE="${IMAGE:-starlakeai/quack-on-demand}"
QUACK_VERSION="${QUACK_VERSION:-latest}"

# If the user didn't pin a version and is on the pull path (not BUILD=1),
# probe Docker Hub: when `:latest` doesn't exist yet (no release cut),
# fall back to `:latest-snapshot` from the CI. Keeps the first-run UX
# smooth before 0.1.0 ships.
if [[ "${BUILD:-0}" != "1" ]] && [[ "$QUACK_VERSION" == "latest" ]]; then
  if ! docker manifest inspect "$IMAGE:latest" >/dev/null 2>&1; then
    echo "$IMAGE:latest not on Docker Hub yet; falling back to :latest-snapshot"
    QUACK_VERSION="latest-snapshot"
  fi
fi

PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_DBNAME="${PG_DBNAME:-tpch}"
PG_SCHEMA="${PG_SCHEMA:-main}"

AUTH="${AUTH:-true}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin}"
API_KEY="${API_KEY:-}"
TLS="${TLS:-false}"

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

# ---- Acquire image ----
if [[ "${BUILD:-0}" == "1" ]]; then
  echo "BUILD=1: building $IMAGE:$QUACK_VERSION from $REPO_DIR/Dockerfile ..."
  docker build -t "$IMAGE:$QUACK_VERSION" "$REPO_DIR"
else
  echo "pulling $IMAGE:$QUACK_VERSION ..."
  docker pull "$IMAGE:$QUACK_VERSION"
fi

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
  -e SL_QUACK_DUCKLAKE_DATA_PATH="/app/ducklake/$PG_DBNAME" \
  -e SL_QUACK_AUTH_DB_ENABLED="$AUTH" \
  -e SL_QUACK_ADMIN_USERNAME="$ADMIN_USERNAME" \
  -e SL_QUACK_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  ${API_KEY:+-e SL_QUACK_API_KEY="$API_KEY"} \
  -e PROXY_TLS_ENABLED="$TLS" \
  -e SL_QUACK_MIN_PORT="$QUACK_MIN_PORT" \
  -e SL_QUACK_MAX_PORT="$QUACK_MAX_PORT" \
  -v "$DATA_PATH:/app/ducklake" \
  -v "$CERTS_DIR:/app/certs" \
  "$IMAGE:$QUACK_VERSION"