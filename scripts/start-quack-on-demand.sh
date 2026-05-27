#!/usr/bin/env bash
#
# Start the quack-on-demand manager.
#
# Picks the latest assembly jar from `distrib/`; if none is present and BUILD=1,
# runs `sbt assembly` first. Otherwise prints how to build and exits.
#
# Overrides via env vars (most also exposed as `application.conf` keys —
# the env-var path lets you flip a single setting without editing config):
#
#   SL_QUACK_ON_DEMAND_HOST       (default 0.0.0.0)
#   SL_QUACK_ON_DEMAND_PORT       (default 20900 — REST + UI)
#   PROXY_PORT                    (default 31338 — FlightSQL edge)
#   PROXY_TLS_ENABLED             (default true — auto-generates cert if missing)
#   PROXY_TLS_CERT_CHAIN          (default certs/server-cert.pem)
#   PROXY_TLS_PRIVATE_KEY         (default certs/server-key.pem)
#   SL_QUACK_STATE_STORAGE        (default postgres — flip to `file` to use JSON on disk)
#   SL_QUACK_RUNTIME_TYPE         (default local — `kubernetes` for K8s pods)
#   SL_QUACK_API_KEY              (unset — no auth on REST API)
#
#   JAVA_HOME                     (uses `java` on PATH if unset)
#   JAVA_OPTS                     (additional JVM flags, e.g. -Xmx2g)
#   BUILD=1                       (run `sbt assembly` first if no jar present)
#
# Usage:
#   ./scripts/start-quack-on-demand.sh                # foreground
#   BUILD=1 ./scripts/start-quack-on-demand.sh        # build then start
#   PROXY_TLS_ENABLED=false ./scripts/start-quack-on-demand.sh
#   SL_QUACK_STATE_STORAGE=file ./scripts/start-quack-on-demand.sh

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISTRIB_DIR="$REPO_DIR/distrib"

# Anchor CWD at the repo root so the JVM's child processes (spawn-quack-node.sh
# invoked via `./scripts/spawn-quack-node.sh`) resolve correctly no matter where
# the user called this script from.
cd "$REPO_DIR"

# ---- Locate assembly jar ----
JAR=""
if [[ -d "$DISTRIB_DIR" ]]; then
  JAR=$(ls -t "$DISTRIB_DIR"/quack-on-demand-assembly-*.jar 2>/dev/null | head -n1 || true)
fi

if [[ -z "${JAR:-}" ]]; then
  if [[ "${BUILD:-0}" == "1" ]]; then
    echo "no assembly jar found; running 'sbt assembly'..."
    (cd "$REPO_DIR" && sbt assembly)
    JAR=$(ls -t "$DISTRIB_DIR"/quack-on-demand-assembly-*.jar 2>/dev/null | head -n1 || true)
    [[ -n "${JAR:-}" ]] || { echo "ERROR: sbt assembly did not produce a jar in $DISTRIB_DIR" >&2; exit 1; }
  else
    echo "ERROR: no assembly jar in $DISTRIB_DIR." >&2
    echo "       Run 'sbt assembly' first, or re-run this script with BUILD=1." >&2
    exit 1
  fi
fi

echo "jar: $JAR"

# ---- Resolve java ----
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "${JAVA_BIN:-java}" >/dev/null 2>&1 || {
  echo "ERROR: java not found. Set JAVA_HOME or put java on PATH." >&2
  exit 1
}
JAVA_VERSION=$("${JAVA_BIN:-java}" -version 2>&1 | head -1)
echo "java: $JAVA_VERSION"

# ---- Sanity: Postgres reachable when stateStorage=postgres (the default) ----
state_mode="${SL_QUACK_STATE_STORAGE:-postgres}"
if [[ "$state_mode" == "postgres" ]] && command -v psql >/dev/null 2>&1; then
  # Best-effort probe. Uses the same defaults as application.conf's
  # defaultMetastore so a working setup needs zero overrides.
  pg_host="${SL_QUACK_PG_HOST:-localhost}"
  pg_port="${SL_QUACK_PG_PORT:-5432}"
  pg_user="${SL_QUACK_PG_USER:-postgres}"
  pg_pass="${SL_QUACK_PG_PASSWORD:-azizam}"
  if PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d postgres \
       -tAc 'SELECT 1' >/dev/null 2>&1; then
    echo "postgres: OK ($pg_user@$pg_host:$pg_port)  [state storage]"
  else
    echo "WARN: cannot reach Postgres at $pg_user@$pg_host:$pg_port; manager will fail at startup if it cannot persist state." >&2
    echo "      Either start Postgres, set SL_QUACK_STATE_STORAGE=file, or override SL_QUACK_PG_*." >&2
  fi
fi

# ---- Print effective settings ----
echo "REST + UI:  http://${SL_QUACK_ON_DEMAND_HOST:-0.0.0.0}:${SL_QUACK_ON_DEMAND_PORT:-20900}/ui/"
echo "FlightSQL:  ${PROXY_HOST:-0.0.0.0}:${PROXY_PORT:-31338}  (TLS=${PROXY_TLS_ENABLED:-true})"
echo "State:      $state_mode"
echo "Runtime:    ${SL_QUACK_RUNTIME_TYPE:-local}"
echo ""

# ---- Run ----
# The jar's manifest carries `Add-Opens: java.base/java.nio java.base/sun.nio.ch`
# (JEP 261) so Arrow's unsafe allocator works on Java 17+ without extra flags.
# `-Darrow.allocation.manager.type=Unsafe` pins Arrow to arrow-memory-unsafe;
# without it Arrow picks netty as default and crashes with
# `NoSuchFieldError: chunkSize` because the assembly bundles a newer Netty
# than arrow-memory-netty 14.0.1 reflects against.
exec "${JAVA_BIN:-java}" \
  -Darrow.allocation.manager.type=Unsafe \
  ${JAVA_OPTS:-} \
  -jar "$JAR"