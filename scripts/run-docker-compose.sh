#!/usr/bin/env bash
#
# Run the quack-on-demand stack via docker compose.
#
# Two modes, picked via BUILD:
#   BUILD=0 (default) - `docker compose pull` the published image
#                       starlakeai/quack-on-demand:$QOD_VERSION, then up.
#                       No git checkout, sbt, or node toolchain required.
#   BUILD=1           - `docker compose up -d --build` against this repo's
#                       Dockerfile. The build tags the image as
#                       starlakeai/quack-on-demand:$QOD_VERSION (same name
#                       as the published one), so this script's wait/seed/
#                       teardown paths are identical for both flows.
#
# Env vars:
#   BUILD=1             build from local Dockerfile (default 0 = pull from Hub)
#   QOD_VERSION       image tag                 (default latest)
#   ENV_FILE            .env path                 (default ./.env)
#   ENV_SEED            template if .env missing  (default .env.example)
#   LOAD_TPC            Demo seed: unset = skip; numeric value = scale
#                       factor for BOTH TPC-H and TPC-DS (e.g. LOAD_TPC=1,
#                       LOAD_TPC=10). Sets QOD_BOOTSTRAP_YAML=classpath:
#                       bootstrap-demo.yaml in the quack container so the JVM
#                       imports the bundled manifest. Then runs both
#                       load-tpch-dbgen.sh and load-tpcds-dbgen.sh inside the
#                       container.
#   NUKE                "1" tears down any existing stack and wipes
#                       ./pgdata, ./ducklake, ./certs before starting.
#                       Irreversible.            (default 0)
#   AUTO_BUMP_PG_PORT   "true" auto-bumps to 15432 when host:5432 busy
#                                                 (default true)
#   WAIT_TIMEOUT        manager readiness wait    (default 90 s)
#   PROFILES            comma-separated list of compose profiles to
#                       activate (e.g. "observability,seaweedfs"). Merges
#                       with auto-detected profiles - the `seaweedfs`
#                       profile auto-activates when QOD_S3_ENDPOINT in
#                       .env points at it, so you only need PROFILES
#                       for the OTHER opt-in profiles (`observability`).
#                                                 (default unset)
#
# Usage:
#   ./scripts/run-docker-compose.sh                            # latest
#   QOD_VERSION=0.1.0 ./scripts/run-docker-compose.sh          # pinned
#   BUILD=1 ./scripts/run-docker-compose.sh                    # local build
#   LOAD_TPC=1 ./scripts/run-docker-compose.sh                 # + TPC-H + TPC-DS SF=1
#   LOAD_TPC=10 ./scripts/run-docker-compose.sh                # + TPC-H + TPC-DS SF=10
#   NUKE=1 ./scripts/run-docker-compose.sh                     # wipe + fresh boot
#   PROFILES=observability ./scripts/run-docker-compose.sh     # + Prometheus + Grafana

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

QOD_VERSION="${QOD_VERSION:-latest}"
NUKE="${NUKE:-0}"

# ---- Optional nuke: tear down + wipe before starting ----
# Container uids (postgres uid 70, root) own the bind-mount contents, so
# a plain `rm -rf` from the host user fails with EACCES. Wipe via an
# ephemeral root container that has write access to the mount.
if [[ "$NUKE" == "1" ]]; then
  echo "NUKE=1: tearing down any existing stack..."
  # `down` must enumerate every profile that could have services running;
  # otherwise containers in skipped profiles linger. Always include the
  # known opt-in profiles so a teardown is exhaustive regardless of which
  # combination was used last time.
  docker compose -f docker-compose.yml \
    --profile seaweedfs --profile observability \
    down --remove-orphans 2>/dev/null || true
  if [[ -d "$REPO_DIR/pgdata" || -d "$REPO_DIR/ducklake" || -d "$REPO_DIR/certs" \
     || -d "$REPO_DIR/seaweedfs" || -d "$REPO_DIR/seaweedfs-config" ]]; then
    echo "wiping ./pgdata, ./ducklake, ./certs, ./seaweedfs, ./seaweedfs-config via ephemeral container..."
    docker run --rm -v "$REPO_DIR:/work" alpine sh -c \
      'rm -rf /work/pgdata /work/ducklake /work/certs /work/seaweedfs /work/seaweedfs-config'
  fi
  # Pre-create the bind-mount dirs with the right ownership. Without
  # this, docker auto-creates them root-owned on `up`, and the
  # manager's `quack` user (uid 1000, set in the Dockerfile) gets
  # EACCES on `./certs` (TLS cert write fails -> FlightSQL edge
  # silently dies) and `./ducklake` (TPC-H seed `mkdir` fails). Chown
  # via the ephemeral container so it works even when the host user
  # is not uid 1000. Postgres re-chowns ./pgdata to uid 70 on its own
  # init, so we leave that one root-owned. ./seaweedfs and
  # ./seaweedfs-config are root-owned inside the container (seaweedfs
  # image runs as root), so they stay root-owned here too.
  mkdir -p "$REPO_DIR/pgdata" "$REPO_DIR/ducklake" "$REPO_DIR/certs"
  docker run --rm -v "$REPO_DIR:/work" alpine sh -c \
    'chown 1000:1000 /work/ducklake /work/certs'
  echo "booting from a clean slate."
fi

# If the user didn't pin a version and is on the pull path (not BUILD=1),
# probe Docker Hub: when `:latest` doesn't exist yet (no release cut),
# fall back to `:latest-snapshot` from the CI. Keeps the first-run UX
# smooth before 0.1.0 ships.
if [[ "${BUILD:-0}" != "1" ]] && [[ "$QOD_VERSION" == "latest" ]]; then
  registry_image="starlakeai/quack-on-demand"
  if ! docker manifest inspect "$registry_image:latest" >/dev/null 2>&1; then
    echo "starlakeai/quack-on-demand:latest not on Docker Hub yet; falling back to :latest-snapshot"
    QOD_VERSION="latest-snapshot"
  fi
fi
ENV_FILE="${ENV_FILE:-.env}"
ENV_SEED="${ENV_SEED:-.env.example}"
LOAD_TPC="${LOAD_TPC:-}"
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

# Ensure QOD_VERSION is in the env compose will read. We set it as a
# process env var, which compose picks up before reading the .env file.
export QOD_VERSION

# ---- Rewrite host-loopback proxy URLs for container reachability ----
# A common corporate setup runs cntlm/squid on the host's loopback (e.g.
# http://127.0.0.1:3128). Inside the container 127.0.0.1 is the
# container's own loopback, not the host - the proxy is unreachable.
# Rewrite to host.docker.internal so the extra_hosts entry in compose
# resolves to the host gateway. Touches only loopback addresses; remote
# proxy URLs pass through unchanged.
rewrite_loopback_proxy() {
  local raw="${1:-}"
  [[ -z "$raw" ]] && { echo ""; return; }
  if [[ "$raw" =~ ^([a-zA-Z]+://)(127\.0\.0\.1|localhost)(.*)$ ]]; then
    echo "${BASH_REMATCH[1]}host.docker.internal${BASH_REMATCH[3]}"
  else
    echo "$raw"
  fi
}
LOOPBACK_PROXY_PORTS=()
for var in HTTP_PROXY HTTPS_PROXY http_proxy https_proxy; do
  original="${!var:-}"
  [[ -z "$original" ]] && continue
  rewritten="$(rewrite_loopback_proxy "$original")"
  if [[ "$rewritten" != "$original" ]]; then
    echo "rewriting $var: $original -> $rewritten (container can't reach host loopback directly)"
    export "$var=$rewritten"
    if [[ "$rewritten" =~ :([0-9]+) ]]; then
      LOOPBACK_PROXY_PORTS+=("${BASH_REMATCH[1]}")
    fi
  else
    export "$var"
  fi
done
for var in NO_PROXY no_proxy; do
  [[ -n "${!var:-}" ]] && export "$var"
done

# ---- Auto-bridge loopback-only proxies onto the docker bridge ----
# cntlm/squid often bind 127.0.0.1 only; the URL rewrite above makes the
# container LOOK UP the right name but the proxy still refuses
# connections from 172.17.0.1. Spawn a socat passthrough that listens on
# the docker bridge IP and forwards to the host loopback, but only when
# the proxy is reachable from loopback AND not yet from the bridge.
# Skipped entirely on non-proxied or already-routable setups.
BRIDGE_IP="172.17.0.1"
DOCKER_BRIDGE_GATEWAY="$(docker network inspect bridge \
  -f '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>/dev/null || true)"
[[ -n "$DOCKER_BRIDGE_GATEWAY" ]] && BRIDGE_IP="$DOCKER_BRIDGE_GATEWAY"

probe_tcp() { (echo > "/dev/tcp/$1/$2") >/dev/null 2>&1; }

if (( ${#LOOPBACK_PROXY_PORTS[@]} > 0 )); then
  # Dedupe ports (HTTP+HTTPS commonly share one).
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
      continue  # someone else is already listening on the bridge IP
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

# ---- Compose profile resolution -------------------------------------------
# Two sources:
#   1. Auto: when .env's QOD_S3_ENDPOINT points at the in-compose seaweedfs
#      service, activate the `seaweedfs` profile so the manager doesn't come
#      up writing to s3:// against a never-started SeaweedFS container.
#   2. Explicit: PROFILES=foo,bar from the caller's env. Merges with the
#      auto-detected set. De-duplicated. Lets the user add `observability`
#      etc. without touching .env.
declare -A _profile_set=()
s3_endpoint="$(grep -E '^[[:space:]]*QOD_S3_ENDPOINT[[:space:]]*=' "$ENV_FILE" 2>/dev/null \
  | tail -1 | sed -E 's/[[:space:]]*#.*$//; s/^[[:space:]]*QOD_S3_ENDPOINT[[:space:]]*=[[:space:]]*//; s/[[:space:]]*$//' || true)"
if [[ "$s3_endpoint" == seaweedfs:* ]]; then
  echo "detected QOD_S3_ENDPOINT=$s3_endpoint -> auto-activating 'seaweedfs' compose profile"
  _profile_set[seaweedfs]=1
fi
if [[ -n "${PROFILES:-}" ]]; then
  IFS=',' read -ra _user_profiles <<< "$PROFILES"
  for p in "${_user_profiles[@]}"; do
    p="${p//[[:space:]]/}"
    [[ -n "$p" ]] || continue
    if [[ -z "${_profile_set[$p]:-}" ]]; then
      echo "PROFILES: activating '$p' compose profile"
      _profile_set[$p]=1
    fi
  done
fi
COMPOSE_PROFILES=()
for p in "${!_profile_set[@]}"; do
  COMPOSE_PROFILES+=("--profile" "$p")
done

# ---- Inject QOD_BOOTSTRAP_YAML before up when LOAD_TPC is requested ----
# The JVM reads this at startup, so it must be in .env before `docker compose up`.
if [[ -n "$LOAD_TPC" && "$LOAD_TPC" != "0" && "$LOAD_TPC" != "false" ]]; then
  if ! grep -qE '^[[:space:]]*QOD_BOOTSTRAP_YAML[[:space:]]*=' "$ENV_FILE" 2>/dev/null; then
    printf '\nQOD_BOOTSTRAP_YAML=classpath:bootstrap-demo.yaml  # added by run-docker-compose.sh\n' >> "$ENV_FILE"
    echo "injected QOD_BOOTSTRAP_YAML=classpath:bootstrap-demo.yaml into $ENV_FILE"
  fi
fi

# ---- Acquire image + up ----
if [[ "${BUILD:-0}" == "1" ]]; then
  echo "BUILD=1: starting stack with 'docker compose up -d --build'..."
  docker compose -f "$COMPOSE_FILE" "${COMPOSE_PROFILES[@]}" up -d --build
else
  echo "pulling starlakeai/quack-on-demand:$QOD_VERSION + postgres:16-alpine..."
  docker compose -f "$COMPOSE_FILE" "${COMPOSE_PROFILES[@]}" pull
  echo "starting stack..."
  docker compose -f "$COMPOSE_FILE" "${COMPOSE_PROFILES[@]}" up -d
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

# ---- Optional demo seed (LOAD_TPC=N) ----
# LOAD_TPC is presence-triggered with the value doubling as the scale factor
# for BOTH TPC-H and TPC-DS. Accepts positive integers (LOAD_TPC=1,
# LOAD_TPC=10, ...). Unset / empty / 0 / false skips the seed.
if [[ -n "$LOAD_TPC" && "$LOAD_TPC" != "0" && "$LOAD_TPC" != "false" ]]; then
  if ! [[ "$LOAD_TPC" =~ ^[0-9]+$ ]] || [[ "$LOAD_TPC" -lt 1 ]]; then
    echo "ERROR: LOAD_TPC must be a positive integer scale factor (got: '$LOAD_TPC')." >&2
    exit 1
  fi
  tpc_sf="$LOAD_TPC"
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

  # The manager image is JRE-only and does not ship psql. Pre-create
  # both demo tenant-db Postgres databases from the postgres container
  # (where psql is present) so duckdb ATTACH does not fail on a missing
  # database. Idempotent: the \gexec form only runs CREATE DATABASE when
  # pg_database.datname is absent.
  for demo_db in acme_tpch globex_tpcds; do
    echo "ensuring demo database '$demo_db' exists on the postgres container..."
    docker compose -f "$COMPOSE_FILE" exec -T \
      -e PGPASSWORD="$pg_pass" \
      postgres psql -U "$pg_user" -d postgres -v ON_ERROR_STOP=1 -tAc \
        "SELECT format('CREATE DATABASE %I', '$demo_db') \
         WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$demo_db')" \
      | docker compose -f "$COMPOSE_FILE" exec -T \
          -e PGPASSWORD="$pg_pass" \
          postgres psql -U "$pg_user" -d postgres -v ON_ERROR_STOP=1
  done

  echo "seeding TPC-H (db=acme_tpch, schema=tpch1, SF=$tpc_sf) via docker compose exec quack ..."
  docker compose -f "$COMPOSE_FILE" exec \
    -e PG_HOST=postgres \
    -e PG_PORT=5432 \
    -e PG_USER="$pg_user" \
    -e PG_PASS="$pg_pass" \
    -e DB_NAME="acme_tpch" \
    -e SCHEMA_NAME="tpch1" \
    -e DATA_PATH="/app/ducklake/acme_tpch" \
    -e SF="$tpc_sf" \
    quack /app/scripts/load-tpch-dbgen.sh

  echo "seeding TPC-DS (db=globex_tpcds, schema=tpcds1, SF=$tpc_sf) via docker compose exec quack ..."
  docker compose -f "$COMPOSE_FILE" exec \
    -e PG_HOST=postgres \
    -e PG_PORT=5432 \
    -e PG_USER="$pg_user" \
    -e PG_PASS="$pg_pass" \
    -e DB_NAME="globex_tpcds" \
    -e SCHEMA_NAME="tpcds1" \
    -e DATA_PATH="/app/ducklake/globex_tpcds" \
    -e SF="$tpc_sf" \
    quack /app/scripts/load-tpcds-dbgen.sh
fi

# ---- Summary ----
tls="${TLS:-$(grep -E '^[[:space:]]*TLS[[:space:]]*=' "$ENV_FILE" 2>/dev/null | tail -1 | sed -E 's/[[:space:]]*#.*$//; s/^[[:space:]]*TLS[[:space:]]*=[[:space:]]*//; s/[[:space:]]*$//')}"
tls="${tls:-false}"
scheme=$([[ "$tls" == "true" ]] && echo "grpc+tls" || echo "grpc")

cat <<EOM

stack is up:
  image:      starlakeai/quack-on-demand:$QOD_VERSION
  REST + UI:  http://localhost:20900/ui/
  FlightSQL:  ${scheme}://localhost:31338  (TLS=$tls)
  Postgres:   localhost:${PG_PORT_EFFECTIVE} (external)  /  postgres:5432 (internal)
  Data:       ./pgdata + ./ducklake + ./certs (host bind mounts)
EOM

# Per-profile URL summaries. Indexed off the same _profile_set the
# resolution step populated above, so what we print matches what was
# actually activated.
if [[ -n "${_profile_set[seaweedfs]:-}" ]]; then
  cat <<EOM

seaweedfs (S3-compatible object store + UIs):
  Filer UI:   http://localhost:${SEAWEEDFS_FILER_PORT:-8888}/        (file browser)
  Master UI:  http://localhost:${SEAWEEDFS_MASTER_PORT:-9333}/        (cluster status)
  Volume UI:  http://localhost:${SEAWEEDFS_VOLUME_PORT:-8080}/ui/
  S3 API:     http://localhost:${SEAWEEDFS_S3_PORT:-8333}             (\`aws s3 ls\` / s5cmd)
  credentials: ${QOD_S3_ACCESS_KEY_ID:-quack} / ${QOD_S3_SECRET_ACCESS_KEY:-quackquack}
EOM
fi

if [[ -n "${_profile_set[observability]:-}" ]]; then
  cat <<EOM

observability (Prometheus + Grafana):
  Grafana:    http://localhost:${GRAFANA_PORT:-3000}/                 (anonymous admin)
  Prometheus: http://localhost:${PROMETHEUS_PORT:-9090}/
EOM
fi

# Tear-down hint. Mention the active profiles so \`docker compose down\`
# actually removes the corresponding containers.
profile_flags=""
for p in "${!_profile_set[@]}"; do profile_flags="$profile_flags --profile $p"; done

cat <<EOM

stop with:  docker compose -f $COMPOSE_FILE${profile_flags} down
logs with:  docker compose -f $COMPOSE_FILE logs -f quack
EOM