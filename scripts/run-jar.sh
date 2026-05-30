#!/usr/bin/env bash
#
# Run the quack-on-demand manager from the assembly uber-jar.
#
# Two modes, picked via BUILD:
#   BUILD=0 (default) - download the jar from Maven Central
#                       (ai.starlake:quack-on-demand_3:<QUACK_VERSION>),
#                       cache it under $JAR_CACHE_DIR, run `java -jar`.
#   BUILD=1           - run `sbt assembly` from this checkout and use the
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
#   NUKE=1                        wipe local state (Postgres DB, ducklake/,
#                                 state/, certs/) before booting. Irreversible.
#                                 Mirrors run-docker-compose.sh's NUKE flag for
#                                 the native-jar path.                  (default 0)
#   SF=N                          shortcut for the TPC-H seed: implies
#                                 SL_QUACK_BOOTSTRAP_LOAD_TPCH=true and
#                                 SL_QUACK_BOOTSTRAP_TPCH_SF=N. Works in either
#                                 BUILD=0 or BUILD=1.                   (default unset)
#
# Usage:
#   ./scripts/run-jar.sh                                   # latest release
#   QUACK_VERSION=0.1.0 ./scripts/run-jar.sh               # pinned release
#   QUACK_VERSION=latest-snapshot ./scripts/run-jar.sh     # latest snapshot
#   BUILD=1 ./scripts/run-jar.sh                           # local source build
#   SL_QUACK_BOOTSTRAP_LOAD_TPCH=true ./scripts/run-jar.sh
#   NUKE=1 SF=1 ./scripts/run-jar.sh                       # wipe + fresh boot + TPC-H SF=1

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISTRIB_DIR="$REPO_DIR/distrib"

# Anchor CWD at the repo root so the JVM's child processes (spawn-quack-node.sh
# invoked via `./scripts/spawn-quack-node.sh`) resolve correctly no matter where
# the user called this script from.
cd "$REPO_DIR"

BUILD="${BUILD:-0}"
NUKE="${NUKE:-0}"
GROUP_PATH="ai/starlake"
ARTIFACT="quack-on-demand_3"
JAR_CACHE_DIR="${JAR_CACHE_DIR:-$HOME/.cache/quack-on-demand}"

# SF=N is a shortcut for the TPC-H seed: implies SL_QUACK_BOOTSTRAP_LOAD_TPCH=true
# and SL_QUACK_BOOTSTRAP_TPCH_SF=N. Explicit env vars still win if both are set.
if [[ -n "${SF:-}" ]]; then
  export SL_QUACK_BOOTSTRAP_LOAD_TPCH="${SL_QUACK_BOOTSTRAP_LOAD_TPCH:-true}"
  export SL_QUACK_BOOTSTRAP_TPCH_SF="${SL_QUACK_BOOTSTRAP_TPCH_SF:-$SF}"
fi

# ---- Resolve jar ----
# BUILD=1 always builds locally. BUILD=0 (default) tries Maven Central
# first and falls back to `sbt assembly` if the artifact hasn't been
# published yet (pre-release / dev), so a fresh clone of the source
# tree works with the documented invocation regardless of release state.

# Ensure `sbt` is callable. If it isn't on PATH, fetch the official
# distribution into `.sbt-bootstrap/` under the repo root so a fresh
# clone can build with zero prior setup. The actual sbt version used by
# the project comes from `project/build.properties`; we just need any
# modern launcher.
SBT_BOOTSTRAP_VERSION="${SBT_BOOTSTRAP_VERSION:-1.12.11}"
SBT_BOOTSTRAP_ROOT="$REPO_DIR/.sbt-bootstrap"
SBT_CMD="sbt"

ensure_sbt() {
  if command -v sbt >/dev/null 2>&1; then
    SBT_CMD="sbt"
    return
  fi
  local sbt_dir="$SBT_BOOTSTRAP_ROOT/sbt-$SBT_BOOTSTRAP_VERSION"
  SBT_CMD="$sbt_dir/bin/sbt"
  if [[ -x "$SBT_CMD" ]]; then
    echo "using bootstrapped sbt: $SBT_CMD"
    return
  fi
  echo "sbt not on PATH; downloading sbt-$SBT_BOOTSTRAP_VERSION into $SBT_BOOTSTRAP_ROOT/ ..."
  mkdir -p "$SBT_BOOTSTRAP_ROOT"
  local tarball="$SBT_BOOTSTRAP_ROOT/sbt-$SBT_BOOTSTRAP_VERSION.tgz"
  if ! curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_BOOTSTRAP_VERSION}/sbt-${SBT_BOOTSTRAP_VERSION}.tgz" -o "$tarball"; then
    echo "ERROR: failed to download sbt-$SBT_BOOTSTRAP_VERSION from GitHub releases." >&2
    rm -f "$tarball"
    exit 1
  fi
  tar xzf "$tarball" -C "$SBT_BOOTSTRAP_ROOT"
  # The tarball expands to ./sbt/{bin,conf,lib}; pin it by renaming with the version.
  if [[ -d "$SBT_BOOTSTRAP_ROOT/sbt" ]]; then
    mv "$SBT_BOOTSTRAP_ROOT/sbt" "$sbt_dir"
  fi
  rm -f "$tarball"
  [[ -x "$SBT_CMD" ]] || { echo "ERROR: bootstrapped sbt did not produce $SBT_CMD" >&2; exit 1; }
  echo "bootstrapped sbt -> $SBT_CMD"
}

build_locally() {
  ensure_sbt
  echo "running '$SBT_CMD assembly' (local build)..."
  "$SBT_CMD" assembly
  JAR="$(ls -t "$DISTRIB_DIR"/quack-on-demand-assembly-*.jar 2>/dev/null | head -n1 || true)"
  [[ -n "$JAR" ]] || { echo "ERROR: sbt assembly did not produce a jar in $DISTRIB_DIR" >&2; exit 1; }
}

# Used in the Maven-Central-fallback path only. Reuses an existing assembly
# jar if one is sitting in distrib/, saving ~30-45s of sbt assembly. `BUILD=1`
# bypasses this and always rebuilds.
use_local_jar_or_build() {
  JAR="$(ls -t "$DISTRIB_DIR"/quack-on-demand-assembly-*.jar 2>/dev/null | head -n1 || true)"
  if [[ -n "$JAR" ]]; then
    echo "using existing local jar: $JAR"
    echo "  (BUILD=1 ./scripts/run-jar.sh to force a fresh build)"
  else
    build_locally
  fi
}

if [[ "$BUILD" == "1" ]]; then
  echo "BUILD=1: local build"
  build_locally
else
  mkdir -p "$JAR_CACHE_DIR"

  # Resolve the latest published version. The trailing `|| true` keeps
  # the function exit code at 0 even when curl 404s or grep finds nothing
  # (otherwise `set -e` + `pipefail` would kill the script before the
  # empty-result fallback can fire). `2>/dev/null` silences curl's error
  # messages so the "no published release" path stays clean.
  resolve_latest_release() {
    { curl -fsSL "https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT}/maven-metadata.xml" 2>/dev/null \
        | grep -oE '<release>[^<]+</release>' | sed 's/<[^>]*>//g' | head -1; } || true
  }
  resolve_latest_snapshot() {
    { curl -fsSL "https://central.sonatype.com/repository/maven-snapshots/${GROUP_PATH}/${ARTIFACT}/maven-metadata.xml" 2>/dev/null \
        | grep -oE '<latest>[^<]+</latest>' | sed 's/<[^>]*>//g' | head -1; } || true
  }

  # Belt-and-suspenders: also wrap the substitution with `|| true`, so an
  # accidental non-zero from the function never aborts the script under
  # `set -e`. The fall-back branch below picks up the empty result.
  version="${QUACK_VERSION:-latest}"
  case "$version" in
    latest)
      version="$(resolve_latest_release || true)"
      if [[ -n "$version" ]]; then
        echo "resolved latest release: $version"
      else
        echo "WARN: no release found on Maven Central for ${GROUP_PATH}/${ARTIFACT}; falling back to local build." >&2
      fi
      ;;
    latest-snapshot)
      version="$(resolve_latest_snapshot || true)"
      if [[ -n "$version" ]]; then
        echo "resolved latest snapshot: $version"
      else
        echo "WARN: no snapshot found in Central snapshots repo; falling back to local build." >&2
      fi
      ;;
  esac

  if [[ -z "$version" ]]; then
    # Resolution failed -> reuse local jar if present, else build.
    use_local_jar_or_build
  elif [[ "$version" == *-SNAPSHOT ]]; then
    base_url="https://central.sonatype.com/repository/maven-snapshots/${GROUP_PATH}/${ARTIFACT}/${version}"
    JAR="$JAR_CACHE_DIR/${ARTIFACT}-${version}.jar"
    # Snapshots: always re-download (same version label can ship new bits).
    echo "downloading snapshot $version (always refreshed)..."
    if ! curl -fsSL "$base_url/${ARTIFACT}-${version}.jar" -o "$JAR" 2>/dev/null; then
      echo "WARN: snapshot download failed; falling back to local jar / build." >&2
      use_local_jar_or_build
    fi
  else
    base_url="https://repo1.maven.org/maven2/${GROUP_PATH}/${ARTIFACT}/${version}"
    JAR="$JAR_CACHE_DIR/${ARTIFACT}-${version}.jar"
    if [[ ! -f "$JAR" ]]; then
      echo "downloading $version from Maven Central..."
      if ! curl -fsSL "$base_url/${ARTIFACT}-${version}.jar" -o "$JAR" 2>/dev/null; then
        echo "WARN: release download failed; falling back to local jar / build." >&2
        use_local_jar_or_build
      fi
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

# ---- Optional nuke: tear down + wipe before starting ----
# Mirrors run-docker-compose.sh's NUKE flag for the native-jar path. Stops a
# running manager (if any), drops the Postgres catalog DB, and wipes local
# state directories (ducklake/, state/, certs/). Idempotent: missing pieces
# are skipped silently.
if [[ "$NUKE" == "1" ]]; then
  echo "NUKE=1: tearing down state..."
  # Stop any running manager via the project's own stop script.
  if curl -sS --max-time 2 "http://${SL_QUACK_ON_DEMAND_HOST:-localhost}:${SL_QUACK_ON_DEMAND_PORT:-20900}/health" >/dev/null 2>&1; then
    echo "  stopping running manager"
    "$REPO_DIR/scripts/stop-jar.sh" >/dev/null 2>&1 || true
  fi
  # Drop the Postgres catalog DB (will be recreated below). Requires
  # Postgres to be reachable; otherwise warn and continue with the local
  # wipes — they're still useful and the Postgres side is harmless when
  # the DB is later recreated by the bootstrap step.
  if [[ "$state_mode" == "postgres" ]] && [[ "$pg_reachable" == "1" ]] && [[ -n "$pg_dbname" ]]; then
    echo "  dropping Postgres database: $pg_dbname"
    PGPASSWORD="$pg_pass" psql -h "$pg_host" -p "$pg_port" -U "$pg_user" -d "$pg_admin_db" \
       -tAc "DROP DATABASE IF EXISTS \"$pg_dbname\"" >/dev/null
  elif [[ "$state_mode" == "postgres" ]]; then
    echo "  WARN: Postgres unreachable; skipping DB drop (local wipes still proceed)" >&2
  fi
  # Wipe local state directories that the JVM and child quack nodes write to.
  for d in "$REPO_DIR/ducklake" "$REPO_DIR/state" "$REPO_DIR/certs"; do
    if [[ -d "$d" ]]; then
      echo "  wiping $d"
      rm -rf "$d"
    fi
  done
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
# property pins Arrow's allocator - without it Arrow picks netty and
# crashes with NoSuchFieldError: chunkSize.
exec "${JAVA_BIN:-java}" \
  -Darrow.allocation.manager.type=Unsafe \
  ${JAVA_OPTS:-} \
  -jar "$JAR"