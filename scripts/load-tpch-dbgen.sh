#!/usr/bin/env bash
#
# Seed the DuckLake metastore with a TPC-H dataset using DuckDB's built-in
# `dbgen()` function - no external CSV/JSON files required, no datasets/
# directory to ship. Works for any of the three deployment paths (native,
# Docker single container, Docker compose).
#
# *** PATH-MATCHING WARNING ***
# DuckLake stores `DATA_PATH` as an ABSOLUTE path inside the Postgres
# catalog. Every Quack node later reads files from that exact string. So
# the loader and the manager MUST see the same path:
#
#   - Native loader  + native manager  -> default works
#     (both see e.g. /Users/you/quack-on-demand/ducklake/tpch)
#
#   - Docker manager + native loader   -> BROKEN
#     The manager mounts the host dir at /app/ducklake; the catalog was
#     written with the host's absolute path, which doesn't exist in the
#     container. Quack nodes fail to read the data files.
#
#   - Docker manager + Docker loader   -> works, paths match by construction
#     Use `LOAD_TPC=1 ./scripts/run-docker-compose.sh` (or
#     `docker compose exec quack /app/scripts/load-tpch-dbgen.sh` against
#     a running stack) so the loader runs inside the same /app/ducklake
#     mount as the manager.
#
# What it does:
#   1. ATTACH the DuckLake catalog (Postgres metadata + local data files)
#   2. CREATE SCHEMA $SCHEMA_NAME if missing
#   3. INSTALL/LOAD tpch
#   4. CALL dbgen(sf = $SF)  -> creates the 8 TPC-H tables in the schema
#
# Overrides via env vars (with defaults):
#   PG_HOST       Postgres host                       (default localhost)
#   PG_PORT       Postgres port                       (default 5432)
#   PG_USER       Postgres user                       (default postgres)
#   PG_PASS       Postgres password                   (default azizam)
#   DB_NAME       Postgres DB + DuckLake catalog name (default acme_tpch)
#   SCHEMA_NAME   DuckLake schema (must differ from DB_NAME)  (default tpch1)
#   DATA_PATH     DuckLake data dir                   (default ducklake/$DB_NAME)
#   SF            scale factor - controls row counts  (default 1)
#                 SF=1  -> ~6M lineitem rows
#                 SF=10 -> ~60M lineitem rows (much heavier)
#
# Usage:
#   ./scripts/load-tpch-dbgen.sh                       # SF=1 into acme_tpch.tpch1
#   SF=10 ./scripts/load-tpch-dbgen.sh                 # larger workload
#   PG_HOST=db.internal SCHEMA_NAME=tpch10 SF=10 ./scripts/load-tpch-dbgen.sh

set -euo pipefail

# ---- Config ----
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASS="${PG_PASS:-azizam}"

DB_NAME="${DB_NAME:-acme_tpch}"
SCHEMA_NAME="${SCHEMA_NAME:-tpch1}"
SF="${SF:-1}"

# DuckDB's default temp_directory is the cwd-relative `./.tmp/`. At SF>=10
# dbgen() exceeds the default memory budget and DuckDB spills to disk; if
# `.tmp/` doesn't exist the entire load aborts with
#   IO Error: Cannot open file ".tmp/duckdb_temp_storage_DEFAULT-1.tmp"
# and every subsequent `CREATE TABLE ... AS SELECT * FROM memory.main.<t>`
# fails because the source tables were never populated. Anchor the temp
# directory to an absolute path we control, mkdir it up-front, and let
# DuckDB spill into it. Override via TEMP_DIR when running under a
# read-only / quota'd filesystem.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMP_DIR="${TEMP_DIR:-$REPO_DIR/.tmp/duckdb-tpch-load}"
mkdir -p "$TEMP_DIR"

# DuckDB's default memory budget is ~80% of *host* RAM and ignores the cgroup
# limit, so inside a memory-capped container (e.g. a 4Gi pod, where this loader
# also shares the cgroup with the manager JVM) dbgen() at SF>=10 overruns the
# limit and the kernel OOM-kills the process (`command terminated with exit code
# 137`). Bound it instead, so DuckDB spills to TEMP_DIR rather than overcommit.
# Default = 40% of the cgroup limit (leaving the rest for the JVM); override with
# MEMORY_LIMIT (e.g. MEMORY_LIMIT=3GiB), or MEMORY_LIMIT= to keep DuckDB's default.
cgroup_memory_bytes() {
  local v=""
  if [[ -r /sys/fs/cgroup/memory.max ]]; then                       # cgroup v2
    v="$(cat /sys/fs/cgroup/memory.max 2>/dev/null)"
  elif [[ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]]; then   # cgroup v1
    v="$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null)"
  fi
  [[ "$v" =~ ^[0-9]+$ ]] || return 0                                # "max"/empty -> unset
  # Skip the v1 "unlimited" sentinel (~2^63); treat only real caps as limits.
  (( v > 0 && v < 9000000000000000000 )) && echo "$v"
}
default_memory_limit() {
  local bytes mib
  bytes="$(cgroup_memory_bytes)"
  [[ -n "$bytes" ]] || return 0
  mib=$(( bytes / 1024 / 1024 * 40 / 100 ))
  (( mib < 1024 )) && mib=1024
  echo "${mib}MiB"
}
MEMORY_LIMIT="${MEMORY_LIMIT-$(default_memory_limit)}"
MEMORY_LIMIT_SQL=""
[[ -n "$MEMORY_LIMIT" ]] && MEMORY_LIMIT_SQL="SET memory_limit='$MEMORY_LIMIT';"

# Default DATA_PATH matches `scripts/run-docker.sh` (CWD-anchored, not
# repo-anchored) so a native loader and a same-CWD `docker run` agree on
# the same absolute string. Override DATA_PATH to point at any location
# that BOTH the loader and the manager will see (see warning above).
DATA_PATH="${DATA_PATH:-$PWD/ducklake/$DB_NAME}"

# ---- Sanity ----
command -v duckdb >/dev/null 2>&1 || {
  echo "ERROR: duckdb CLI not on PATH." >&2
  echo "       Install via 'brew install duckdb' or https://duckdb.org/docs/installation/" >&2
  exit 1
}

if [[ "$SCHEMA_NAME" == "$DB_NAME" ]]; then
  echo "ERROR: SCHEMA_NAME ($SCHEMA_NAME) must differ from DB_NAME ($DB_NAME)." >&2
  echo "       DuckDB rejects 2-part identifiers like \"$DB_NAME\".<table> as ambiguous" >&2
  echo "       when a catalog and a schema share a name." >&2
  exit 1
fi

# Provision the target Postgres database if it doesn't exist. With per-
# tenant-db storage the loader's target is the bootstrap tenant-db (e.g.
# `tpch_tpch1`) which the manager only creates at boot inside
# PoolSupervisor.createTenantDb. Doing it here too lets the loader run
# BEFORE the manager (the order run-jar.sh uses). Mirrors the same idiom
# in spawn-quack-node.sh. Skipped silently when psql is absent -- the
# ATTACH below will then surface the missing-database error itself.
if command -v psql >/dev/null 2>&1; then
  PG_ADMIN_DB="${PG_ADMIN_DB:-postgres}"
  EXISTS=$(PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
    -d "$PG_ADMIN_DB" -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" 2>/dev/null || true)
  if [[ "$EXISTS" != "1" ]]; then
    echo "load-tpch: creating Postgres database $DB_NAME"
    PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
      -d "$PG_ADMIN_DB" -tAc "CREATE DATABASE \"$DB_NAME\"" >/dev/null || {
        echo "WARN: CREATE DATABASE $DB_NAME failed; ATTACH below may fail" >&2
      }
  fi
fi

# Detect remote DATA_PATH (s3:// / SeaweedFS / MinIO / R2, gs://, azure://) and
# emit the matching DuckDB extension + SECRET so the ATTACH below can read/write
# parquet against the bucket. Skips mkdir + canonicalize for remote schemes.
IS_REMOTE=0
STORAGE_SQL=""
case "$DATA_PATH" in
  s3://*|s3a://*|gs://*|r2://*)
    IS_REMOTE=1
    STORAGE_SQL="INSTALL httpfs; LOAD httpfs;"
    if [[ -n "${QOD_S3_ACCESS_KEY_ID:-}" && -n "${QOD_S3_SECRET_ACCESS_KEY:-}" ]]; then
      ep="${QOD_S3_ENDPOINT:-}"
      ep="${ep#http://}"; ep="${ep#https://}"; ep="${ep%/}"
      STORAGE_SQL="$STORAGE_SQL
CREATE OR REPLACE SECRET quack_s3 (
  TYPE s3,
  KEY_ID '${QOD_S3_ACCESS_KEY_ID}',
  SECRET '${QOD_S3_SECRET_ACCESS_KEY}',
  REGION '${QOD_S3_REGION:-us-east-1}',
  ENDPOINT '${ep}',
  URL_STYLE '${QOD_S3_URL_STYLE:-path}',
  USE_SSL ${QOD_S3_USE_SSL:-true}
);"
    fi
    ;;
  az://*|azure://*|abfss://*)
    IS_REMOTE=1
    STORAGE_SQL="INSTALL azure; LOAD azure;"
    if [[ -n "${QOD_AZURE_CONNECTION_STRING:-}" ]]; then
      STORAGE_SQL="$STORAGE_SQL
CREATE OR REPLACE SECRET quack_azure (
  TYPE azure,
  CONNECTION_STRING '${QOD_AZURE_CONNECTION_STRING}'
);"
    fi
    ;;
esac

if [[ "$IS_REMOTE" == "0" ]]; then
  mkdir -p "$DATA_PATH"
  # Canonicalize - DuckLake persists this exact string in the catalog.
  DATA_PATH="$(cd "$DATA_PATH" && pwd)"

  # Detect "I'm probably running on a host that will later run Docker" and
  # emit a heads-up. Heuristic: DATA_PATH doesn't start with /app/, but the
  # manager's Dockerfile defaults QOD_DUCKLAKE_DATA_PATH=/app/ducklake.
  if [[ "$DATA_PATH" != /app/ducklake/* ]] && [[ -e /.dockerenv || "${IN_DOCKER:-}" == "1" ]]; then
    : # we're inside Docker but DATA_PATH isn't /app/* - caller probably knows
  elif [[ "$DATA_PATH" != /app/ducklake/* ]]; then
    echo "Heads up: DATA_PATH='$DATA_PATH'" >&2
    echo "If you plan to run the manager in Docker, the container will look for the" >&2
    echo "data files at /app/ducklake/$DB_NAME (its bind-mount target), NOT at" >&2
    echo "'$DATA_PATH'. Use 'docker compose exec quack /app/scripts/load-tpch-dbgen.sh'" >&2
    echo "to load TPC-H from inside the container (paths match by construction)." >&2
    echo "" >&2
  fi
fi

echo "postgres:    $PG_USER@$PG_HOST:$PG_PORT/$DB_NAME"
echo "catalog:     $DB_NAME.$SCHEMA_NAME"
echo "data path:   $DATA_PATH"
echo "scale:       SF=$SF (approx $((SF * 6))M lineitem rows)"
echo "memory:      ${MEMORY_LIMIT:-DuckDB default} (spill dir: $TEMP_DIR)"
echo ""

# ---- Idempotency probe ----
# Two-step check:
#   1. SELECT count(*) - hits DuckLake's metadata, decides if the schema
#      claims any rows. Cheap; uses the `__ducklake_*` tables in Postgres
#      and never touches parquet.
#   2. SELECT LIMIT 1 - if step 1 says "yes, rows exist", this verifies
#      the parquet files those rows reference are actually on disk.
#      DuckLake stores absolute paths in its catalog; if `./ducklake/`
#      (or the S3 prefix) was wiped while pgdata was kept, count(*) lies
#      and every client query fails with "Cannot open file ...". Catching
#      that here turns a silent post-boot failure into an actionable
#      pre-boot one.
#
# stderr is silenced for the count (expected "table does not exist" path
# on first boot is quiet) and surfaced for the file-probe (we want the
# Cannot-open-file diagnostic visible).
PROBE_SQL="$(mktemp -t load-tpch-probe.XXXXXX.sql)"
cat > "$PROBE_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
$STORAGE_SQL
ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');
.mode csv
.headers off
SELECT count(*) FROM $DB_NAME.$SCHEMA_NAME.lineitem;
SQL
existing_rows="$(duckdb < "$PROBE_SQL" 2>/dev/null | tr -d '\r\n ' || true)"
rm -f "$PROBE_SQL"
if [[ "$existing_rows" =~ ^[0-9]+$ ]] && (( existing_rows > 0 )); then
  # Catalog says there's data. Verify the parquet files are reachable -
  # LIMIT 1 forces an actual file open, which the metadata short-circuit
  # in count(*) bypasses.
  FILE_PROBE_SQL="$(mktemp -t load-tpch-file-probe.XXXXXX.sql)"
  cat > "$FILE_PROBE_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
$STORAGE_SQL
ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');
.mode csv
.headers off
SELECT l_orderkey FROM $DB_NAME.$SCHEMA_NAME.lineitem LIMIT 1;
SQL
  file_probe_err="$(duckdb < "$FILE_PROBE_SQL" 2>&1 1>/dev/null || true)"
  rm -f "$FILE_PROBE_SQL"
  if [[ -n "$file_probe_err" && "$file_probe_err" == *"Cannot open file"* ]]; then
    echo "ERROR: catalog claims $existing_rows rows in $DB_NAME.$SCHEMA_NAME.lineitem" >&2
    echo "       but the parquet files it references are missing on disk:" >&2
    echo "$file_probe_err" | sed -n 's/.*Cannot open file "\([^"]*\)".*/         \1/p' \
      | head -1 >&2
    echo "" >&2
    echo "Catalog metadata in Postgres got out of sync with the data files. Most" >&2
    echo "likely cause: ./ducklake/ (or the S3 prefix) was wiped while ./pgdata/" >&2
    echo "was kept. Either re-create both from scratch (NUKE=1) or manually" >&2
    echo "drop the stale schema:" >&2
    echo "  DROP SCHEMA $DB_NAME.$SCHEMA_NAME CASCADE;" >&2
    exit 1
  fi
  echo "already loaded: $DB_NAME.$SCHEMA_NAME.lineitem has $existing_rows rows; skipping."
  echo "(delete the schema or override SCHEMA_NAME to force a reload)"
  exit 0
fi

# ---- Generate + run ----
INIT_SQL="$(mktemp -t load-tpch-dbgen.XXXXXX.sql)"
trap 'rm -f "$INIT_SQL"' EXIT

cat > "$INIT_SQL" <<SQL
-- Anchor temp_directory FIRST so any subsequent INSTALL / LOAD / CALL that
-- needs to spill (dbgen at SF>=10 will) writes into a directory we know
-- exists, rather than DuckDB's cwd-relative default ./.tmp/.
SET temp_directory='$TEMP_DIR';
-- Bound DuckDB's memory so dbgen() spills to temp_directory instead of
-- overrunning the container cgroup limit and getting OOM-killed (exit 137).
$MEMORY_LIMIT_SQL

INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL tpch;     LOAD tpch;
$STORAGE_SQL

ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');

CREATE SCHEMA IF NOT EXISTS $DB_NAME.$SCHEMA_NAME;

.print ''
.print '== Generating TPC-H SF=$SF (in-memory) =='
.print 'Creates 8 tables: region, nation, customer, supplier, part, partsupp, orders, lineitem'
.print 'SF=1 is fast (~10s); SF=10 takes a few minutes; SF=100+ is slow.'
.print ''
-- dbgen() only writes to native DuckDB catalogs, not DuckLake. Generate
-- into the default in-memory database, then CTAS each table into the
-- DuckLake schema. We DROP first so a previous partial run is overwritten
-- cleanly without depending on CREATE-OR-REPLACE semantics.
CALL dbgen(sf = $SF);

.print ''
.print '== Copying into DuckLake $DB_NAME.$SCHEMA_NAME =='
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.region;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.region   AS SELECT * FROM memory.main.region;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.nation;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.nation   AS SELECT * FROM memory.main.nation;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.customer;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.customer AS SELECT * FROM memory.main.customer;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.supplier;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.supplier AS SELECT * FROM memory.main.supplier;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.part;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.part     AS SELECT * FROM memory.main.part;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.partsupp;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.partsupp AS SELECT * FROM memory.main.partsupp;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.orders;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.orders   AS SELECT * FROM memory.main.orders;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.lineitem;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.lineitem AS SELECT * FROM memory.main.lineitem;

USE $DB_NAME.$SCHEMA_NAME;

.print ''
.print '== Final state =='
SHOW TABLES;
SELECT 'lineitem' AS tbl, count(*) AS rows FROM lineitem
UNION ALL SELECT 'orders',   count(*) FROM orders
UNION ALL SELECT 'customer', count(*) FROM customer
UNION ALL SELECT 'partsupp', count(*) FROM partsupp
UNION ALL SELECT 'part',     count(*) FROM part
UNION ALL SELECT 'supplier', count(*) FROM supplier
UNION ALL SELECT 'nation',   count(*) FROM nation
UNION ALL SELECT 'region',   count(*) FROM region;
SQL

exec duckdb < "$INIT_SQL"
