#!/usr/bin/env bash
#
# Run quack-on-demand from a Docker image, against an EXTERNAL Postgres.
#
# One knob, QOD_VERSION, picks where the image comes from (same contract
# as run-jar.sh):
#   unset / <tag>     - `docker pull` starlakeai/quack-on-demand:<tag>
#                       (default tag: latest, falling back to latest-snapshot
#                       when no release has been cut yet)
#   BUILD             - `docker build` this repo's Dockerfile and tag the
#                       result starlakeai/quack-on-demand:local (same image
#                       name as the published one so the rest of this script
#                       is identical for both flows)
#   LOCAL             - reuse starlakeai/quack-on-demand:local from a prior
#                       BUILD without pulling or rebuilding (falls back to a
#                       build when that image is absent)
#
# Env vars (with defaults):
#
#   QOD_VERSION    image source (see modes above)         (default latest)
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
#   ADMIN_PASSWORD   admin password (when AUTH=true)        (default admin - rotate!)
#   API_KEY          REST API X-API-Key                     (unset = open API + warning)
#
#   TLS              "true" to enable FlightSQL edge TLS    (default false)
#                    With false, tpch-load-test needs --url grpc://...
#
#   MANAGER_PORT     host port -> manager REST + UI         (default 20900)
#   EDGE_PORT        host port -> FlightSQL edge            (default 31338)
#   QOD_NODE_MIN_PORT   first local node port                  (default 21900)
#   QOD_NODE_MAX_PORT   last  local node port                  (default 22500)
#   DATA_PATH        host dir bind-mounted as /app/ducklake (default <CWD>/ducklake)
#   CERTS_DIR        host dir for auto-generated TLS cert   (default <CWD>/certs)
#   CONTAINER_NAME   docker --name                          (default quack-on-demand)
#
#   NUKE             "1" stops the prior container and wipes DATA_PATH +
#                    CERTS_DIR before starting. Does NOT drop the remote
#                    Postgres DuckLake catalog tables - if you want a
#                    truly clean slate, drop PG_DBNAME on the remote
#                    server yourself. Irreversible.         (default 0)
#
#   HTTP_PROXY       optional corporate proxy. When set in the invoking
#   HTTPS_PROXY      shell, all six standard proxy vars are forwarded
#   NO_PROXY         into the container so DuckDB extension downloads
#   http_proxy       and any other outbound HTTP go through the proxy.
#   https_proxy      Leave unset on non-proxied networks.
#   no_proxy
#
# *** DO NOT MIX DOCKER AND NATIVE AGAINST THE SAME CATALOG DB ***
# DuckLake bakes the absolute DATA_PATH into the Postgres metadata -
# inside the container that path is /app/ducklake/$PG_DBNAME; natively
# it is $PWD/ducklake/$PG_DBNAME on the host. Use a different PG_DBNAME
# per mode, or wipe ducklake/<db>/ between switches. See guides/RUNNING.md for
# the recovery recipes.
#
# Usage:
#   PG_HOST=db.internal PG_PASSWORD=*** ./scripts/run-docker.sh
#   QOD_VERSION=0.1.0 PG_HOST=... PG_PASSWORD=... ./scripts/run-docker.sh
#   QOD_VERSION=latest-snapshot PG_HOST=... PG_PASSWORD=... ./scripts/run-docker.sh
#   QOD_VERSION=BUILD PG_HOST=... PG_PASSWORD=... ./scripts/run-docker.sh   # local Dockerfile build
#   QOD_VERSION=LOCAL PG_HOST=... PG_PASSWORD=... ./scripts/run-docker.sh   # reuse the :local image
#   NUKE=1 PG_HOST=... PG_PASSWORD=... ./scripts/run-docker.sh   # wipe local mounts first

set -euo pipefail

# ---- Required ----
: "${PG_HOST:?PG_HOST is required (remote Postgres host)}"
: "${PG_PASSWORD:?PG_PASSWORD is required}"

# ---- Defaults ----
IMAGE="${IMAGE:-starlakeai/quack-on-demand}"
QOD_VERSION="${QOD_VERSION:-latest}"

# The old BUILD=0/1 knob folded into QOD_VERSION=BUILD (2026-07-15, matching
# run-jar.sh). Fail loudly rather than silently pulling.
if [[ -n "${BUILD:-}" ]]; then
  echo "ERROR: BUILD is gone; use QOD_VERSION=BUILD (build the local Dockerfile)," >&2
  echo "       QOD_VERSION=LOCAL (reuse the :local image), or QOD_VERSION=<tag> (pull)." >&2
  exit 1
fi

# Resolve the BUILD / LOCAL sentinels: both run the :local tag, so the rest
# of the script (and a later plain re-run) never confuses a local build with
# a published tag.
IMAGE_SOURCE="pull"
if [[ "$QOD_VERSION" == "BUILD" ]]; then
  IMAGE_SOURCE="build"
  QOD_VERSION="local"
elif [[ "$QOD_VERSION" == "LOCAL" ]]; then
  IMAGE_SOURCE="local"
  QOD_VERSION="local"
fi

# If the user didn't pin a version and is on the pull path, probe Docker
# Hub: when `:latest` doesn't exist yet (no release cut), fall back to
# `:latest-snapshot` from the CI. Keeps the first-run UX smooth before
# 0.1.0 ships.
if [[ "$IMAGE_SOURCE" == "pull" ]] && [[ "$QOD_VERSION" == "latest" ]]; then
  if ! docker manifest inspect "$IMAGE:latest" >/dev/null 2>&1; then
    echo "$IMAGE:latest not on Docker Hub yet; falling back to :latest-snapshot"
    QOD_VERSION="latest-snapshot"
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
QOD_NODE_MIN_PORT="${QOD_NODE_MIN_PORT:-21900}"
QOD_NODE_MAX_PORT="${QOD_NODE_MAX_PORT:-22500}"
CONTAINER_NAME="${CONTAINER_NAME:-quack-on-demand}"
NUKE="${NUKE:-0}"

# The DuckLake data files are bind-mounted from the host so they survive
# `docker rm` / `docker run` cycles. Defaults to `<CWD>/ducklake` so the
# data lives next to wherever you invoked the script; override DATA_PATH
# to point at any host directory (NFS share, external SSD, etc.). The
# directory is created if missing and the path is canonicalized to an
# absolute one - `docker run -v` requires it.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DATA_PATH="${DATA_PATH:-$PWD/ducklake}"
CERTS_DIR="${CERTS_DIR:-$PWD/certs}"

mkdir -p "$DATA_PATH" "$CERTS_DIR"
DATA_PATH="$(cd "$DATA_PATH" && pwd)"
CERTS_DIR="$(cd "$CERTS_DIR" && pwd)"

# ---- Acquire image ----
case "$IMAGE_SOURCE" in
  build)
    echo "QOD_VERSION=BUILD: building $IMAGE:$QOD_VERSION from $REPO_DIR/Dockerfile ..."
    docker build -t "$IMAGE:$QOD_VERSION" "$REPO_DIR"
    ;;
  local)
    if docker image inspect "$IMAGE:$QOD_VERSION" >/dev/null 2>&1; then
      echo "QOD_VERSION=LOCAL: reusing $IMAGE:$QOD_VERSION (no pull, no build)"
    else
      echo "QOD_VERSION=LOCAL: $IMAGE:$QOD_VERSION not found; building it first..."
      docker build -t "$IMAGE:$QOD_VERSION" "$REPO_DIR"
    fi
    ;;
  *)
    echo "pulling $IMAGE:$QOD_VERSION ..."
    docker pull "$IMAGE:$QOD_VERSION"
    ;;
esac

# ---- Stop any prior instance with the same name ----
if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER_NAME"; then
  echo "stopping previous container: $CONTAINER_NAME"
  docker rm -f "$CONTAINER_NAME" >/dev/null
fi

# ---- Optional nuke: wipe local bind mounts before starting ----
# The remote Postgres DuckLake catalog is OUT OF SCOPE here - we only own
# what's on the local filesystem. If the user wants a fully clean slate
# they must drop $PG_DBNAME on the remote server themselves; otherwise
# stale `__ducklake_*` rows will point at parquet files that no longer
# exist and the next mutation will fail.
#
# Wipe via an ephemeral root container - the manager container writes
# files as its internal uid, so a host-side `rm -rf` from the invoking
# user gets EACCES on TLS keys and parquet files.
if [[ "$NUKE" == "1" ]]; then
  echo "NUKE=1: wiping contents of $DATA_PATH and $CERTS_DIR via ephemeral container..."
  docker run --rm \
    -v "$DATA_PATH:/wipe-data" \
    -v "$CERTS_DIR:/wipe-certs" \
    alpine sh -c 'find /wipe-data /wipe-certs -mindepth 1 -delete 2>/dev/null; true'
  echo "wiped. NOTE: remote Postgres catalog ($PG_USER@$PG_HOST/$PG_DBNAME) was NOT touched -"
  echo "      drop the DB on the remote server if you need a truly clean catalog."
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

# ---- Rewrite host-loopback proxy URLs ----
# Inside the container, 127.0.0.1 is the container's own loopback, not
# the host. When the proxy lives on the host's loopback (cntlm etc.),
# rewrite it to host.docker.internal and add an extra_hosts entry so the
# name resolves on Linux too (it's automatic on Docker Desktop).
rewrite_loopback_proxy() {
  local raw="${1:-}"
  [[ -z "$raw" ]] && { echo ""; return; }
  if [[ "$raw" =~ ^([a-zA-Z]+://)(127\.0\.0\.1|localhost)(.*)$ ]]; then
    echo "${BASH_REMATCH[1]}host.docker.internal${BASH_REMATCH[3]}"
  else
    echo "$raw"
  fi
}
NEED_HOST_GATEWAY=0
LOOPBACK_PROXY_PORTS=()
for var in HTTP_PROXY HTTPS_PROXY http_proxy https_proxy; do
  original="${!var:-}"
  [[ -z "$original" ]] && continue
  rewritten="$(rewrite_loopback_proxy "$original")"
  if [[ "$rewritten" != "$original" ]]; then
    echo "rewriting $var: $original -> $rewritten"
    printf -v "$var" '%s' "$rewritten"
    if [[ "$rewritten" =~ :([0-9]+) ]]; then
      LOOPBACK_PROXY_PORTS+=("${BASH_REMATCH[1]}")
    fi
    NEED_HOST_GATEWAY=1
  fi
done

# ---- Auto-bridge loopback-only proxies onto the docker bridge ----
# cntlm/squid often bind 127.0.0.1 only; the URL rewrite above makes
# the container LOOK UP the right name but the proxy still refuses
# connections from the docker bridge IP. Spawn a socat passthrough
# that listens on the bridge IP and forwards to host loopback, but
# only when the proxy is reachable from loopback AND not yet from
# the bridge. stop-docker.sh tears down these `quack-proxy-bridge-*`
# containers.
BRIDGE_IP="172.17.0.1"
DOCKER_BRIDGE_GATEWAY="$(docker network inspect bridge \
  -f '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>/dev/null || true)"
[[ -n "$DOCKER_BRIDGE_GATEWAY" ]] && BRIDGE_IP="$DOCKER_BRIDGE_GATEWAY"

probe_tcp() { (echo > "/dev/tcp/$1/$2") >/dev/null 2>&1; }

if (( ${#LOOPBACK_PROXY_PORTS[@]} > 0 )); then
  IFS=$'\n' read -r -d '' -a UNIQ_PORTS < <(
    printf '%s\n' "${LOOPBACK_PROXY_PORTS[@]}" | sort -u && printf '\0'
  )
  for port in "${UNIQ_PORTS[@]}"; do
    name="quack-proxy-bridge-$port"
    if [[ -n "$(docker ps -q -f "name=^${name}$" 2>/dev/null)" ]]; then
      echo "proxy bridge already running: $name ($BRIDGE_IP:$port -> 127.0.0.1:$port)"
      continue
    fi
    if probe_tcp "$BRIDGE_IP" "$port"; then
      continue
    fi
    if ! probe_tcp "127.0.0.1" "$port"; then
      echo "WARN: no proxy reachable on 127.0.0.1:$port; container will likely fail to reach it" >&2
      continue
    fi
    docker rm -f "$name" >/dev/null 2>&1 || true
    echo "starting proxy bridge: $BRIDGE_IP:$port -> 127.0.0.1:$port (container=$name)"
    docker run -d --rm --name "$name" --network host alpine/socat \
      "TCP-LISTEN:$port,bind=$BRIDGE_IP,fork,reuseaddr" "TCP:127.0.0.1:$port" >/dev/null
  done
fi

# ---- Run ----
# host.docker.internal works on Docker Desktop (Mac/Windows). On Linux,
# we add it explicitly via --add-host so a host-loopback proxy is
# reachable after the rewrite above.
HOST_GATEWAY_ARG=()
[[ "$NEED_HOST_GATEWAY" == "1" ]] && \
  HOST_GATEWAY_ARG=(--add-host "host.docker.internal:host-gateway")

exec docker run --rm \
  --name "$CONTAINER_NAME" \
  "${HOST_GATEWAY_ARG[@]}" \
  -p "$MANAGER_PORT:20900" \
  -p "$EDGE_PORT:31338" \
  -p "$QOD_NODE_MIN_PORT-$QOD_NODE_MAX_PORT:$QOD_NODE_MIN_PORT-$QOD_NODE_MAX_PORT" \
  -e QOD_PG_HOST="$PG_HOST" \
  -e QOD_PG_PORT="$PG_PORT" \
  -e QOD_PG_USER="$PG_USER" \
  -e QOD_PG_PASSWORD="$PG_PASSWORD" \
  -e QOD_PG_DBNAME="$PG_DBNAME" \
  -e QOD_PG_SCHEMA="$PG_SCHEMA" \
  -e QOD_DUCKLAKE_DATA_PATH="/app/ducklake/$PG_DBNAME" \
  -e QOD_AUTH_DB_ENABLED="$AUTH" \
  -e QOD_ADMIN_USERNAME="$ADMIN_USERNAME" \
  -e QOD_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  ${API_KEY:+-e QOD_API_KEY="$API_KEY"} \
  -e PROXY_TLS_ENABLED="$TLS" \
  -e QOD_MIN_PORT="$QOD_NODE_MIN_PORT" \
  -e QOD_MAX_PORT="$QOD_NODE_MAX_PORT" \
  ${HTTP_PROXY:+-e HTTP_PROXY="$HTTP_PROXY"} \
  ${HTTPS_PROXY:+-e HTTPS_PROXY="$HTTPS_PROXY"} \
  ${NO_PROXY:+-e NO_PROXY="$NO_PROXY"} \
  ${http_proxy:+-e http_proxy="$http_proxy"} \
  ${https_proxy:+-e https_proxy="$https_proxy"} \
  ${no_proxy:+-e no_proxy="$no_proxy"} \
  -v "$DATA_PATH:/app/ducklake" \
  -v "$CERTS_DIR:/app/certs" \
  "$IMAGE:$QOD_VERSION"