#!/usr/bin/env bash
#
# Bring up the quack-on-demand stack via docker compose (postgres + quack,
# both bind-mounted onto the host so state survives container restarts).
#
# Thin wrapper around `docker compose up -d` that adds the boring boot
# preflight: ensure .env exists, detect a host-side Postgres squatting on
# the published port, optionally seed TPC-H via the `init` compose profile,
# wait until the manager REST is reachable, print the endpoint summary.
#
# *** DO NOT MIX COMPOSE AND NATIVE AGAINST THE SAME CATALOG DB ***
# DuckLake bakes the *absolute* DATA_PATH into Postgres metadata
# (__ducklake_data_file rows). Inside the container that path is
# /app/ducklake/$PG_DBNAME; natively it is $PWD/ducklake/$PG_DBNAME on
# the host. Once a manager writes a path every future manager reading
# that catalog must see the same string or every query fails with
# "could not open file ...". If you toggle between modes either (a)
# point each mode at a different PG_DBNAME (e.g. tpch_compose), or
# (b) wipe ./pgdata and ./ducklake before switching.
#
# Env vars (with defaults):
#
#   ENV_FILE          .env path                          (default ./.env)
#   ENV_SEED          template to copy when ENV_FILE is missing (default .env.example)
#
#   BUILD             "1" to add --build (rebuild image) (default 0)
#   LOAD_TPCH         "true" to seed TPC-H after boot via
#                     `docker compose exec quack /app/scripts/load-tpch-dbgen.sh`
#                     (matches the Path 2 / start-docker.sh pattern — the loader
#                     is baked into the image, sees the same /app/ducklake mount
#                     the manager will spawn nodes against, and self-skips when
#                     tpch1.lineitem is already populated)              (default false)
#
#   AUTO_BUMP_PG_PORT "true" to set PG_PORT=15432 when host:5432 is busy and
#                     PG_PORT is unset / still 5432 in .env             (default true)
#
#   WAIT_TIMEOUT      seconds to wait for the manager to respond        (default 90)
#
# Usage:
#   ./scripts/start-docker-compose.sh                       # plain up -d
#   BUILD=1 ./scripts/start-docker-compose.sh               # rebuild image first
#   LOAD_TPCH=true ./scripts/start-docker-compose.sh        # seed after boot
#   AUTO_BUMP_PG_PORT=false ./scripts/start-docker-compose.sh
#
# To stop:  ./scripts/stop-docker-compose.sh
#           (or `docker compose down` from the repo root)

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

ENV_FILE="${ENV_FILE:-.env}"
ENV_SEED="${ENV_SEED:-.env.example}"
BUILD="${BUILD:-0}"
LOAD_TPCH="${LOAD_TPCH:-false}"
AUTO_BUMP_PG_PORT="${AUTO_BUMP_PG_PORT:-true}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-90}"

# ---- Preflight ----
command -v docker >/dev/null 2>&1 || {
  echo "ERROR: docker not found on PATH." >&2
  exit 1
}
docker compose version >/dev/null 2>&1 || {
  echo "ERROR: 'docker compose' plugin not available (need v2, not legacy 'docker-compose')." >&2
  exit 1
}

# ---- .env scaffolding ----
if [[ ! -f "$ENV_FILE" ]]; then
  if [[ -f "$ENV_SEED" ]]; then
    cp "$ENV_SEED" "$ENV_FILE"
    echo "scaffolded $ENV_FILE from $ENV_SEED — edit before exposing the stack publicly."
  else
    echo "WARN: $ENV_FILE missing and no $ENV_SEED to copy from; relying on compose defaults." >&2
  fi
fi

# Pick up the current PG_PORT from .env (or 5432 default). Done with a
# simple grep so we don't need to source the file (which would clobber
# the caller's env). Comments after the value are stripped.
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

# ---- Host port collision check ----
# Only matters when PG_PORT defaults to 5432; the compose file maps it to
# 0.0.0.0:$PG_PORT->5432 so a host Postgres on 5432 prevents the bind.
if [[ "$PG_PORT_EFFECTIVE" == "5432" ]] && lsof -nP -iTCP:5432 -sTCP:LISTEN 2>/dev/null | grep -q LISTEN; then
  echo "host port 5432 is already in use by another process." >&2
  if [[ "$AUTO_BUMP_PG_PORT" == "true" ]] && [[ -f "$ENV_FILE" ]]; then
    if grep -qE '^[[:space:]]*PG_PORT[[:space:]]*=' "$ENV_FILE"; then
      # Replace existing line, preserving inline comment if any.
      sed -i.bak -E 's|^([[:space:]]*PG_PORT[[:space:]]*=)[[:space:]]*5432.*|\115432  # auto-bumped by start-docker-compose.sh (host owns 5432)|' "$ENV_FILE"
    else
      printf '\nPG_PORT=15432  # auto-added by start-docker-compose.sh (host owns 5432)\n' >> "$ENV_FILE"
    fi
    rm -f "$ENV_FILE.bak"
    PG_PORT_EFFECTIVE=15432
    echo "auto-bumped PG_PORT to 15432 in $ENV_FILE — external psql now uses localhost:15432."
  else
    echo "set PG_PORT to a free port in $ENV_FILE (e.g. PG_PORT=15432) and retry," >&2
    echo "or AUTO_BUMP_PG_PORT=true to have this script set it for you." >&2
    exit 1
  fi
fi

# ---- Bring up the stack ----
echo "starting docker compose stack..."
compose_args=(up -d)
[[ "$BUILD" == "1" ]] && compose_args=(up -d --build)
docker compose "${compose_args[@]}"

# ---- Wait for the manager to answer ----
# `/api/health` returns 401 when REST auth is on but a 401 still means the
# server is listening — we treat any HTTP response (not a connection error)
# as healthy. Loop bounded by WAIT_TIMEOUT.
echo -n "waiting for manager REST on :20900 "
deadline=$(( $(date +%s) + WAIT_TIMEOUT ))
until code="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:20900/api/health 2>/dev/null || true)"; \
      [[ -n "$code" && "$code" != "000" ]]; do
  if (( $(date +%s) > deadline )); then
    echo
    echo "ERROR: manager did not respond within ${WAIT_TIMEOUT}s." >&2
    echo "       Check 'docker compose logs quack'." >&2
    exit 1
  fi
  echo -n "."
  sleep 2
done
echo " ok (HTTP $code)"

# ---- Optional TPC-H seed ----
# Use the same pattern as Path 2 (start-docker.sh): exec the loader inside
# the running manager container. The script is baked into the image
# (Dockerfile COPY scripts/load-tpch-dbgen.sh) and sees the same
# /app/ducklake bind-mount that the manager spawns Quack nodes against, so
# the absolute DATA_PATH the catalog persists matches what the nodes later
# resolve. We read PG_USER/PG_PASSWORD/PG_DBNAME/TPCH_SCHEMA/TPCH_SF from
# .env (or compose defaults) and re-export them under the names the loader
# expects (PG_PASS, not PG_PASSWORD; DB_NAME, not PG_DBNAME; SCHEMA_NAME,
# not TPCH_SCHEMA; etc).
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
  docker compose exec \
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
  REST + UI:  http://localhost:20900/ui/
  FlightSQL:  ${scheme}://localhost:31338  (TLS=$tls)
  Postgres:   localhost:${PG_PORT_EFFECTIVE} (external)  /  postgres:5432 (internal)
  Data:       ./pgdata (catalog) + ./ducklake (parquet) + ./certs (TLS cert)

stop with:  ./scripts/stop-docker-compose.sh
logs with:  docker compose logs -f quack
EOM