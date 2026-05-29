#!/usr/bin/env bash
#
# Run the quack-on-demand manager from the assembly uber-jar.
#
# Two modes, picked via BUILD:
#   BUILD=0 (default) — download the jar from Maven Central
#                       (ai.starlake:quack-on-demand_3:<QUACK_VERSION>),
#                       cache it under $JAR_CACHE_DIR, run `java -jar`.
#   BUILD=1           — run `sbt assembly` from this checkout and use the
#                       freshly-built jar in distrib/.
#
# Boot extras: Postgres reachability probe, idempotent CREATE DATABASE
# of the catalog DB, optional TPC-H seed via load-tpch-dbgen.sh before
# the JVM starts.
#
# All quack-on-demand settings come from SL_QUACK_* / PROXY_* env vars
# (see RUNNING.md Path 1 for the full list). Sensible defaults:
#   - REST + UI on :20900, FlightSQL edge on :31338 (TLS on)
#   - Postgres expected at $SL_QUACK_PG_HOST:5432 (default localhost)
#
# Env vars:
#   BUILD=1                       run `sbt assembly` first instead of downloading
#   QUACK_VERSION                 artifact version to download (default = latest
#                                 release from Maven Central; `latest-snapshot`
#                                 fetches from Central snapshots; ignored when
#                                 BUILD=1)
#   JAR_CACHE_DIR                 download cache (default ~/.cache/quack-on-demand)
#   JAVA_HOME                     uses `java` on PATH if unset
#   JAVA_OPTS                     extra JVM flags (e.g. -Xmx2g)
#
#   SL_QUACK_BOOTSTRAP_LOAD_TPCH  "true" to seed TPC-H via load-tpch-dbgen.sh
#                                 before the JVM starts (requires `duckdb` CLI)
#   SL_QUACK_BOOTSTRAP_TPCH_SF    TPC-H scale factor                       (default 1)
#   SL_QUACK_BOOTSTRAP_TPCH_SCHEMA DuckLake schema for the seed            (default tpch1)
#
# Usage:
#   ./scripts/run-jar.sh                                   # latest release
#   QUACK_VERSION=0.1.0 ./scripts/run-jar.sh               # pinned release
#   QUACK_VERSION=latest-snapshot ./scripts/run-jar.sh     # latest snapshot
#   BUILD=1 ./scripts/run-jar.sh                           # local source build
#   SL_QUACK_BOOTSTRAP_LOAD_TPCH=true ./scripts/run-jar.sh

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISTRIB_DIR="$REPO_DIR/distrib"

# Anchor CWD at the repo root so the JVM's child processes (spawn-quack-node.sh
# invoked via `./scripts/spawn-quack-node.sh`) resolve correctly no matter where
# the user called this script from.
cd "$REPO_DIR"

BUILD="${BUILD:-0}"
GROUP_PATH="ai/starlake"
ARTIFACT="quack-on-demand_3"
JAR_CACHE_DIR="${JAR_CACHE_DIR:-$HOME/.cache/quack-on-demand}"

# ---- Resolve jar ----
if [[ "$BUILD" == "1" ]]; then
  echo "BUILD=1: running 'sbt assembly'..."
  sbt assembly
  JAR="$(ls -t "$DISTRIB_DIR"/quack-on-demand-assembly-*.jar 2>/dev/null | head -n1 || true)"
  [[ -n "$JAR" ]] || { echo "ERROR: sbt assembly did not produce a jar in $DISTRIB_DIR" >&2; exit 1; }
else
  mkdir -p "$JAR_CACHE_DIR"

  resolve_latest_release() {
    curl -fsSL "https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT}/maven-metadata.xml" \
      | grep -oE '<release>[^<]+</release>' | sed 's/<[^>]*>//g' | head -1
  }
  resolve_latest_snapshot() {
    curl -fsSL "https://central.sonatype.com/repository/maven-snapshots/${GROUP_PATH}/${ARTIFACT}/maven-metadata.xml" \
      | grep -oE '<latest>[^<]+</latest>' | sed 's/<[^>]*>//g' | head -1
  }

  version="${QUACK_VERSION:-latest}"
  case "$version" in
    latest)
      version="$(resolve_latest_release)"
      [[ -n "$version" ]] || { echo "ERROR: failed to resolve latest release from Maven Central." >&2; exit 1; }
      echo "resolved latest release: $version"
      ;;
    latest-snapshot)
      version="$(resolve_latest_snapshot)"
      [[ -n "$version" ]] || { echo "ERROR: failed to resolve latest snapshot from Central snapshots repo." >&2; exit 1; }
      echo "resolved latest snapshot: $version"
      ;;
  esac

  if [[ "$version" == *-SNAPSHOT ]]; then
    base_url="https://central.sonatype.com/repository/maven-snapshots/${GROUP_PATH}/${ARTIFACT}/${version}"
    JAR="$JAR_CACHE_DIR/${ARTIFACT}-${version}.jar"
    # Snapshots: always re-download (same version label can ship new bits).
    echo "downloading snapshot $version (always refreshed)..."
    curl -fsSL "$base_url/${ARTIFACT}-${version}.jar" -o "$JAR"
  else
    base_url="https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT}/${version}"
    JAR="$JAR_CACHE_DIR/${ARTIFACT}-${version}.jar"
    if [[ ! -f "$JAR" ]]; then
      echo "downloading $version from Maven Central..."
      curl -fsSL "$base_url/${ARTIFACT}-${version}.jar" -o "$JAR"
    else
      echo "using cached jar: $JAR"
    fi
  fi
fi

echo "jar: $JAR"

# ---- Resolve java ----
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "${JAVA_BIN:-java}" >/dev/null 2>&1 || {
  echo "ERROR: java not found. Install JDK 17+ or set JAVA_HOME." >&2
  exit 1
}
echo "java: $("${JAVA_BIN:-java}" -version 2>&1 | head -1)"

# ---- Sanity: Postgres reachable when stateStorage=postgres (the default) ----
state_mode="${SL_QUACK_STATE_STORAGE:-postgres}"
pg_host="${SL_QUACK_PG_HOST:-localhost}"
pg_port="${SL_QUACK_PG_PORT:-5432}"
pg_user="${SL_QUACK_PG_USER:-postgres}"
pg_pass="${SL_QUACK_PG_PASSWORD:-azizam}"
pg_admin_db="${SL_QUACK_PG_ADMIN_DB:-postgres}"
pg_dbname="${SL_QUACK_PG_DBNAME:-tpch}"

pg_reachable=0
if [[ "$state_mode" == "postgres" ]] && command -v psql >/dev/null 2>&1; then
  if PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
       -tAc 'SELECT 1' >/dev/null 2>&1; then
    echo "postgres: OK ($pg_user@$pg_host:$pg_port)  [state storage]"
    pg_reachable=1
  else
    echo "WARN: cannot reach Postgres at $pg_user@$pg_host:$pg_port; manager will fail at startup if it cannot persist state." >&2
    echo "      Either start Postgres, set SL_QUACK_STATE_STORAGE=file, or override SL_QUACK_PG_*." >&2
  fi
fi

# ---- Bootstrap catalog DB (idempotent) ----
# Without this a brand-new install fails at the first Hikari connection
# with "database does not exist". Skipped when Postgres isn't reachable or
# psql isn't installed.
if [[ "$state_mode" == "postgres" ]] && [[ "$pg_reachable" == "1" ]] && [[ -n "$pg_dbname" ]]; then
  if PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
       -tAc "SELECT 1 FROM pg_database WHERE datname='$pg_dbname'" 2>/dev/null | grep -q 1; then
    echo "catalog db: '$pg_dbname' already exists"
  else
    echo "catalog db: creating '$pg_dbname' in $pg_admin_db..."
    PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
       -tAc "CREATE DATABASE \"$pg_dbname\"" >/dev/null
  fi
fi

# ---- Optional: TPC-H seed (delegates to load-tpch-dbgen.sh) ----
if [[ "${SL_QUACK_BOOTSTRAP_LOAD_TPCH:-false}" == "true" ]]; then
  tpch_schema="${SL_QUACK_BOOTSTRAP_TPCH_SCHEMA:-tpch1}"
  tpch_sf="${SL_QUACK_BOOTSTRAP_TPCH_SF:-1}"
  if ! command -v duckdb >/dev/null 2>&1; then
    echo "WARN: SL_QUACK_BOOTSTRAP_LOAD_TPCH=true but duckdb CLI not on PATH; skipping TPC-H seed." >&2
  elif [[ "$pg_reachable" != "1" ]]; then
    echo "WARN: SL_QUACK_BOOTSTRAP_LOAD_TPCH=true but Postgres unreachable; skipping TPC-H seed." >&2
  else
    DATA_PATH="${SL_QUACK_DUCKLAKE_DATA_PATH:-$REPO_DIR/ducklake/$pg_dbname}" \
    PG_HOST="$pg_host" PG_PORT="$pg_port" PG_USER="$pg_user" PG_PASS="$pg_pass" \
    DB_NAME="$pg_dbname" SCHEMA_NAME="$tpch_schema" SF="$tpch_sf" \
      "$REPO_DIR/scripts/load-tpch-dbgen.sh"
  fi
fi

# ---- Effective settings ----
echo "REST + UI:  http://${SL_QUACK_ON_DEMAND_HOST:-0.0.0.0}:${SL_QUACK_ON_DEMAND_PORT:-20900}/ui/"
echo "FlightSQL:  ${PROXY_HOST:-0.0.0.0}:${PROXY_PORT:-31338}  (TLS=${PROXY_TLS_ENABLED:-true})"
echo "State:      $state_mode"
echo "Runtime:    ${SL_QUACK_RUNTIME_TYPE:-local}"
echo ""

# ---- Run ----
# The assembly jar carries `Add-Opens` in its manifest (JEP 261) so Arrow's
# unsafe allocator works on Java 17+ without extra flags. The system
# property pins Arrow's allocator — without it Arrow picks netty and
# crashes with NoSuchFieldError: chunkSize.
exec "${JAVA_BIN:-java}" \
  -Darrow.allocation.manager.type=Unsafe \
  ${JAVA_OPTS:-} \
  -jar "$JAR"