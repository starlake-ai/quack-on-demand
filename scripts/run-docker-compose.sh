#!/usr/bin/env bash
#
# Run the quack-on-demand stack via docker compose.
#
# Two modes, picked via BUILD:
#   BUILD=0 (default) - `docker compose pull` the published image
#                       starlakeai/quack-on-demand:$QUACK_VERSION, then up.
#                       No git checkout, sbt, or node toolchain required.
#   BUILD=1           - `docker compose up -d --build` against this repo's
#                       Dockerfile. The build tags the image as
#                       starlakeai/quack-on-demand:$QUACK_VERSION (same name
#                       as the published one), so this script's wait/seed/
#                       teardown paths are identical for both flows.
#
# Env vars:
#   BUILD=1             build from local Dockerfile (default 0 = pull from Hub)
#   QUACK_VERSION       image tag                 (default latest)
#   ENV_FILE            .env path                 (default ./.env)
#   ENV_SEED            template if .env missing  (default .env.example)
#   LOAD_TPCH           "true" to seed TPC-H      (default false)
#   NUKE                "1" tears down any existing stack and wipes
#                       ./pgdata, ./ducklake, ./certs before starting.
#                       Irreversible.            (default 0)
#   AUTO_BUMP_PG_PORT   "true" auto-bumps to 15432 when host:5432 busy
#                                                 (default true)
#   WAIT_TIMEOUT        manager readiness wait    (default 90 s)
#
# Usage:
#   ./scripts/run-docker-compose.sh                            # latest
#   QUACK_VERSION=0.1.0 ./scripts/run-docker-compose.sh        # pinned
#   BUILD=1 ./scripts/run-docker-compose.sh                    # local build
#   LOAD_TPCH=true ./scripts/run-docker-compose.sh             # + TPC-H
#   NUKE=1 ./scripts/run-docker-compose.sh                     # wipe + fresh boot

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

QUACK_VERSION="${QUACK_VERSION:-latest}"
NUKE="${NUKE:-0}"

# ---- Optional nuke: tear down + wipe before starting ----
# Container uids (postgres uid 70, root) own the bind-mount contents, so
# a plain `rm -rf` from the host user fails with EACCES. Wipe via an
# ephemeral root container that has write access to the mount.
if [[ "$NUKE" == "1" ]]; then
  echo "NUKE=1: tearing down any existing stack..."
  docker compose -f docker-compose.yml down --remove-orphans 2>/dev/null || true
  if [[ -d "$REPO_DIR/pgdata" || -d "$REPO_DIR/ducklake" || -d "$REPO_DIR/certs" ]]; then
    echo "wiping ./pgdata, ./ducklake, ./certs via ephemeral container..."
    docker run --rm -v "$REPO_DIR:/work" alpine sh -c \
      'rm -rf /work/pgdata /work/ducklake /work/certs'
  fi
  echo "booting from a clean slate."
fi

# If the user didn't pin a version and is on the pull path (not BUILD=1),
# probe Docker Hub: when `:latest` doesn't exist yet (no release cut),
# fall back to `:latest-snapshot` from the CI. Keeps the first-run UX
# smooth before 0.1.0 ships.
if [[ "${BUILD:-0}" != "1" ]] && [[ "$QUACK_VERSION" == "latest" ]]; then
  registry_image="starlakeai/quack-on-demand"
  if ! docker manifest inspect "$registry_image:latest" >/dev/null 2>&1; then
    echo "starlakeai/quack-on-demand:latest not on Docker Hub yet; falling back to :latest-snapshot"
    QUACK_VERSION="latest-snapshot"
  fi
fi
ENV_FILE="${ENV_FILE:-.env}"
ENV_SEED="${ENV_SEED:-.env.example}"
LOAD_TPCH="${LOAD_TPCH:-false}"
AUTO_BUMP_PG_PORT="${AUTO_BUMP_PG_PORT:-true}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-90}"
COMPOSE_FILE="docker-compose.yml"

# ---- Preflight ----
command -v docker >/dev/null 2>&1 || {
  echo "ERROR: docker not found on PATH." >&2
  exit 1
}
docker compose version >/dev/null 2>&1 || {
  echo "ERROR: 'docker compose' plugin not available (need v2)." >&2
  exit 1
}
[[ -f "$COMPOSE_FILE" ]] || {
  echo "ERROR: $COMPOSE_FILE not found in $PWD." >&2
  exit 1
}

# ---- .env scaffolding ----
if [[ ! -f "$ENV_FILE" ]]; then
  if [[ -f "$ENV_SEED" ]]; then
    cp "$ENV_SEED" "$ENV_FILE"
    echo "scaffolded $ENV_FILE from $ENV_SEED - edit before exposing the stack publicly."
  else
    echo "WARN: $ENV_FILE missing and no $ENV_SEED to copy from; relying on compose defaults." >&2
  fi
fi

# Ensure QUACK_VERSION is in the env compose will read. We set it as a
# process env var, which compose picks up before reading the .env file.
export QUACK_VERSION

# ---- Port-conflict auto-bump ----
declare_pg_port() {
  if [[ -f "$ENV_FILE" ]]; then
    local raw
    raw="$(grep -E '^[[:space:]]*PG_PORT[[:space:]]*=' "$ENV_FILE" | tail -1 | sed -E 's/[[:space:]]*#.*$//; s/^[[:space:]]*PG_PORT[[:space:]]*=[[:space:]]*//; s/[[:space:]]*$//' || true)"
    echo "${raw:-5432}"
  else
    echo "5432"
  fi
}
PG_PORT_EFFECTIVE="$(declare_pg_port)"

if [[ "$PG_PORT_EFFECTIVE" == "5432" ]] && lsof -nP -iTCP:5432 -sTCP:LISTEN 2>/dev/null | grep -q LISTEN; then
  echo "host port 5432 is already in use by another process." >&2
  if [[ "$AUTO_BUMP_PG_PORT" == "true" ]] && [[ -f "$ENV_FILE" ]]; then
    if grep -qE '^[[:space:]]*PG_PORT[[:space:]]*=' "$ENV_FILE"; then
      sed -i.bak -E 's|^([[:space:]]*PG_PORT[[:space:]]*=)[[:space:]]*5432.*|\115432  # auto-bumped by run-docker-compose.sh|' "$ENV_FILE"
    else
      printf '\nPG_PORT=15432  # auto-added by run-docker-compose.sh (host owns 5432)\n' >> "$ENV_FILE"
    fi
    rm -f "$ENV_FILE.bak"
    PG_PORT_EFFECTIVE=15432
    echo "auto-bumped PG_PORT to 15432 in $ENV_FILE."
  else
    echo "set PG_PORT to a free port in $ENV_FILE (e.g. PG_PORT=15432) and retry." >&2
    exit 1
  fi
fi

# ---- Acquire image + up ----
if [[ "${BUILD:-0}" == "1" ]]; then
  echo "BUILD=1: starting stack with 'docker compose up -d --build'..."
  docker compose -f "$COMPOSE_FILE" up -d --build
else
  echo "pulling starlakeai/quack-on-demand:$QUACK_VERSION + postgres:16-alpine..."
  docker compose -f "$COMPOSE_FILE" pull
  echo "starting stack..."
  docker compose -f "$COMPOSE_FILE" up -d
fi

# ---- Wait for manager ----
echo -n "waiting for manager REST on :20900 "
deadline=$(( $(date +%s) + WAIT_TIMEOUT ))
until code="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:20900/api/health 2>/dev/null || true)"; \
      [[ -n "$code" && "$code" != "000" ]]; do
  if (( $(date +%s) > deadline )); then
    echo
    echo "ERROR: manager did not respond within ${WAIT_TIMEOUT}s." >&2
    echo "       Check 'docker compose -f $COMPOSE_FILE logs quack'." >&2
    exit 1
  fi
  echo -n "."
  sleep 2
done
echo " ok (HTTP $code)"

# ---- Optional TPC-H seed (docker compose exec quack /app/scripts/load-tpch-dbgen.sh) ----
if [[ "$LOAD_TPCH" == "true" ]]; then
  read_env() {
    local key="$1" default="$2"
    if [[ -f "$ENV_FILE" ]]; then
      local raw
      raw="$(grep -E "^[[:space:]]*$key[[:space:]]*=" "$ENV_FILE" | tail -1 | sed -E 's/[[:space:]]*#.*$//; s/^[[:space:]]*[A-Z_]+[[:space:]]*=[[:space:]]*//; s/[[:space:]]*$//' || true)"
      echo "${raw:-$default}"
    else
      echo "$default"
    fi
  }
  pg_user="$(read_env PG_USER     postgres)"
  pg_pass="$(read_env PG_PASSWORD azizam)"
  pg_dbname="$(read_env PG_DBNAME tpch)"
  tpch_schema="$(read_env TPCH_SCHEMA tpch1)"
  tpch_sf="$(read_env TPCH_SF 1)"

  echo "seeding TPC-H (schema=$tpch_schema, SF=$tpch_sf) via docker compose exec quack ..."
  docker compose -f "$COMPOSE_FILE" exec \
    -e PG_HOST=postgres \
    -e PG_PORT=5432 \
    -e PG_USER="$pg_user" \
    -e PG_PASS="$pg_pass" \
    -e DB_NAME="$pg_dbname" \
    -e SCHEMA_NAME="$tpch_schema" \
    -e DATA_PATH="/app/ducklake/$pg_dbname" \
    -e SF="$tpch_sf" \
    quack /app/scripts/load-tpch-dbgen.sh
fi

# ---- Summary ----
tls="${TLS:-$(grep -E '^[[:space:]]*TLS[[:space:]]*=' "$ENV_FILE" 2>/dev/null | tail -1 | sed -E 's/[[:space:]]*#.*$//; s/^[[:space:]]*TLS[[:space:]]*=[[:space:]]*//; s/[[:space:]]*$//')}"
tls="${tls:-false}"
scheme=$([[ "$tls" == "true" ]] && echo "grpc+tls" || echo "grpc")

cat <<EOM

stack is up:
  image:      starlakeai/quack-on-demand:$QUACK_VERSION
  REST + UI:  http://localhost:20900/ui/
  FlightSQL:  ${scheme}://localhost:31338  (TLS=$tls)
  Postgres:   localhost:${PG_PORT_EFFECTIVE} (external)  /  postgres:5432 (internal)
  Data:       ./pgdata + ./ducklake + ./certs (host bind mounts)

stop with:  docker compose -f $COMPOSE_FILE down
logs with:  docker compose -f $COMPOSE_FILE logs -f quack
EOM