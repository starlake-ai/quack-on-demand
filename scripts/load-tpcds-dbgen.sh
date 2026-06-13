#!/usr/bin/env bash
#
# Seed the DuckLake metastore with a TPC-DS dataset using DuckDB's built-in
# `dsdgen()` function - no external CSV/JSON files required, no datasets/
# directory to ship. Works for any of the three deployment paths (native,
# Docker single container, Docker compose).
#
# Note: requires DuckDB's `tpcds` extension, which DuckDB downloads from
# its extension repo on first install. Offline environments need an
# extension cache or the `INSTALL tpcds` will fail.
#
# *** PATH-MATCHING WARNING ***
# DuckLake stores `DATA_PATH` as an ABSOLUTE path inside the Postgres
# catalog. Every Quack node later reads files from that exact string. So
# the loader and the manager MUST see the same path:
#
#   - Native loader  + native manager  -> default works
#     (both see e.g. /Users/you/quack-on-demand/ducklake/globex_tpcds)
#
#   - Docker manager + native loader   -> BROKEN
#     The manager mounts the host dir at /app/ducklake; the catalog was
#     written with the host's absolute path, which doesn't exist in the
#     container. Quack nodes fail to read the data files.
#
#   - Docker manager + Docker loader   -> works, paths match by construction
#     Use `LOAD_TPCDS=true ./scripts/run-docker-compose.sh` (or
#     `docker compose exec quack /app/scripts/load-tpcds-dbgen.sh` against
#     a running stack) so the loader runs inside the same /app/ducklake
#     mount as the manager.
#
# What it does:
#   1. ATTACH the DuckLake catalog (Postgres metadata + local data files)
#   2. CREATE SCHEMA $SCHEMA_NAME if missing
#   3. INSTALL/LOAD tpcds
#   4. CALL dsdgen(sf = $SF)  -> creates the 24 TPC-DS tables in the schema
#
# Overrides via env vars (with defaults):
#   PG_HOST       Postgres host                       (default localhost)
#   PG_PORT       Postgres port                       (default 5432)
#   PG_USER       Postgres user                       (default postgres)
#   PG_PASS       Postgres password                   (default azizam)
#   DB_NAME       Postgres DB + DuckLake catalog name (default globex_tpcds)
#   SCHEMA_NAME   DuckLake schema (must differ from DB_NAME)  (default tpcds1)
#   DATA_PATH     DuckLake data dir                   (default ducklake/$DB_NAME)
#   SF            scale factor - controls row counts  (default 1)
#                 SF=1  -> ~2.8M store_sales rows
#                 SF=10 -> ~28M store_sales rows (much heavier)
#
# Usage:
#   ./scripts/load-tpcds-dbgen.sh                       # SF=1 into globex_tpcds.tpcds1
#   SF=10 ./scripts/load-tpcds-dbgen.sh                 # larger workload
#   PG_HOST=db.internal SCHEMA_NAME=tpcds10 SF=10 ./scripts/load-tpcds-dbgen.sh

set -euo pipefail

# ---- Config ----
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASS="${PG_PASS:-azizam}"

DB_NAME="${DB_NAME:-globex_tpcds}"
SCHEMA_NAME="${SCHEMA_NAME:-tpcds1}"
SF="${SF:-1}"

# DuckDB's default temp_directory is the cwd-relative `./.tmp/`. At SF>=10
# dsdgen() exceeds the default memory budget and DuckDB spills to disk; if
# `.tmp/` doesn't exist the entire load aborts with
#   IO Error: Cannot open file ".tmp/duckdb_temp_storage_DEFAULT-1.tmp"
# and every subsequent `CREATE TABLE ... AS SELECT * FROM memory.main.<t>`
# fails because the source tables were never populated. Anchor the temp
# directory to an absolute path we control, mkdir it up-front, and let
# DuckDB spill into it. Override via TEMP_DIR when running under a
# read-only / quota'd filesystem.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMP_DIR="${TEMP_DIR:-$REPO_DIR/.tmp/duckdb-tpcds-load}"
mkdir -p "$TEMP_DIR"

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
# `globex_tpcds`) which the manager only creates at boot inside
# PoolSupervisor.createTenantDb. Doing it here too lets the loader run
# BEFORE the manager (the order run-jar.sh uses). Mirrors the same idiom
# in spawn-quack-node.sh. Skipped silently when psql is absent -- the
# ATTACH below will then surface the missing-database error itself.
if command -v psql >/dev/null 2>&1; then
  PG_ADMIN_DB="${PG_ADMIN_DB:-postgres}"
  EXISTS=$(PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
    -d "$PG_ADMIN_DB" -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" 2>/dev/null || true)
  if [[ "$EXISTS" != "1" ]]; then
    echo "load-tpcds: creating Postgres database $DB_NAME"
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
    echo "'$DATA_PATH'. Use 'docker compose exec quack /app/scripts/load-tpcds-dbgen.sh'" >&2
    echo "to load TPC-DS from inside the container (paths match by construction)." >&2
    echo "" >&2
  fi
fi

echo "postgres:    $PG_USER@$PG_HOST:$PG_PORT/$DB_NAME"
echo "catalog:     $DB_NAME.$SCHEMA_NAME"
echo "data path:   $DATA_PATH"
echo "scale:       SF=$SF (approx $((SF * 3))M store_sales rows)"
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
PROBE_SQL="$(mktemp -t load-tpcds-probe.XXXXXX.sql)"
FILE_PROBE_SQL=""
INIT_SQL=""
trap 'rm -f "$PROBE_SQL" "$FILE_PROBE_SQL" "$INIT_SQL"' EXIT
cat > "$PROBE_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
$STORAGE_SQL
ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');
.mode csv
.headers off
SELECT count(*) FROM $DB_NAME.$SCHEMA_NAME.store_sales;
SQL
existing_rows="$(duckdb < "$PROBE_SQL" 2>/dev/null | tr -d '\r\n ' || true)"
rm -f "$PROBE_SQL"
if [[ "$existing_rows" =~ ^[0-9]+$ ]] && (( existing_rows > 0 )); then
  # Catalog says there's data. Verify the parquet files are reachable -
  # LIMIT 1 forces an actual file open, which the metadata short-circuit
  # in count(*) bypasses.
  FILE_PROBE_SQL="$(mktemp -t load-tpcds-file-probe.XXXXXX.sql)"
  cat > "$FILE_PROBE_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
$STORAGE_SQL
ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');
.mode csv
.headers off
SELECT ss_sold_date_sk FROM $DB_NAME.$SCHEMA_NAME.store_sales LIMIT 1;
SQL
  file_probe_err="$(duckdb < "$FILE_PROBE_SQL" 2>&1 1>/dev/null || true)"
  rm -f "$FILE_PROBE_SQL"
  if [[ -n "$file_probe_err" && "$file_probe_err" == *"Cannot open file"* ]]; then
    echo "ERROR: catalog claims $existing_rows rows in $DB_NAME.$SCHEMA_NAME.store_sales" >&2
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
  echo "already loaded: $DB_NAME.$SCHEMA_NAME.store_sales has $existing_rows rows; skipping."
  echo "(delete the schema or override SCHEMA_NAME to force a reload)"
  exit 0
fi

# ---- Generate + run ----
INIT_SQL="$(mktemp -t load-tpcds-dsdgen.XXXXXX.sql)"

cat > "$INIT_SQL" <<SQL
-- Anchor temp_directory FIRST so any subsequent INSTALL / LOAD / CALL that
-- needs to spill (dsdgen at SF>=10 will) writes into a directory we know
-- exists, rather than DuckDB's cwd-relative default ./.tmp/.
SET temp_directory='$TEMP_DIR';

INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL tpcds;    LOAD tpcds;
$STORAGE_SQL

ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');

CREATE SCHEMA IF NOT EXISTS $DB_NAME.$SCHEMA_NAME;

.print ''
.print '== Generating TPC-DS SF=$SF (in-memory) =='
.print 'Creates 24 tables including store_sales, catalog_sales, web_sales, inventory, ...'
.print 'SF=1 is fast (~30s); SF=10 takes several minutes; SF=100+ is slow.'
.print ''
-- dsdgen() only writes to native DuckDB catalogs, not DuckLake. Generate
-- into the default in-memory database, then CTAS each table into the
-- DuckLake schema. We DROP first so a previous partial run is overwritten
-- cleanly without depending on CREATE-OR-REPLACE semantics.
CALL dsdgen(sf = $SF);

.print ''
.print '== Copying into DuckLake $DB_NAME.$SCHEMA_NAME =='
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.call_center;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.call_center        AS SELECT * FROM memory.main.call_center;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.catalog_page;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.catalog_page       AS SELECT * FROM memory.main.catalog_page;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.catalog_returns;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.catalog_returns    AS SELECT * FROM memory.main.catalog_returns;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.catalog_sales;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.catalog_sales      AS SELECT * FROM memory.main.catalog_sales;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.customer;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.customer           AS SELECT * FROM memory.main.customer;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.customer_address;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.customer_address   AS SELECT * FROM memory.main.customer_address;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.customer_demographics;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.customer_demographics AS SELECT * FROM memory.main.customer_demographics;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.date_dim;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.date_dim           AS SELECT * FROM memory.main.date_dim;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.household_demographics;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.household_demographics AS SELECT * FROM memory.main.household_demographics;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.income_band;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.income_band        AS SELECT * FROM memory.main.income_band;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.inventory;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.inventory          AS SELECT * FROM memory.main.inventory;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.item;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.item               AS SELECT * FROM memory.main.item;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.promotion;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.promotion          AS SELECT * FROM memory.main.promotion;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.reason;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.reason             AS SELECT * FROM memory.main.reason;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.ship_mode;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.ship_mode          AS SELECT * FROM memory.main.ship_mode;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.store;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.store              AS SELECT * FROM memory.main.store;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.store_returns;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.store_returns      AS SELECT * FROM memory.main.store_returns;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.store_sales;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.store_sales        AS SELECT * FROM memory.main.store_sales;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.time_dim;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.time_dim           AS SELECT * FROM memory.main.time_dim;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.warehouse;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.warehouse          AS SELECT * FROM memory.main.warehouse;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.web_page;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.web_page           AS SELECT * FROM memory.main.web_page;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.web_returns;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.web_returns        AS SELECT * FROM memory.main.web_returns;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.web_sales;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.web_sales          AS SELECT * FROM memory.main.web_sales;
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.web_site;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.web_site           AS SELECT * FROM memory.main.web_site;

USE $DB_NAME.$SCHEMA_NAME;

.print ''
.print '== Final state =='
SHOW TABLES;
SELECT 'store_sales'     AS tbl, count(*) AS rows FROM store_sales
UNION ALL SELECT 'catalog_sales',  count(*) FROM catalog_sales
UNION ALL SELECT 'web_sales',      count(*) FROM web_sales
UNION ALL SELECT 'store_returns',  count(*) FROM store_returns
UNION ALL SELECT 'catalog_returns',count(*) FROM catalog_returns
UNION ALL SELECT 'web_returns',    count(*) FROM web_returns
UNION ALL SELECT 'inventory',      count(*) FROM inventory
UNION ALL SELECT 'customer',       count(*) FROM customer
UNION ALL SELECT 'item',           count(*) FROM item
UNION ALL SELECT 'date_dim',       count(*) FROM date_dim;
SQL

exec duckdb < "$INIT_SQL"
