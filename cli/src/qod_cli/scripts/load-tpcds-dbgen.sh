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

# ---- Config / sanity / storage / idempotency (shared) ----
# Dataset knobs consumed by scripts/_load-common.sh, which owns the full env
# contract (PG_*, DB_NAME, SCHEMA_NAME, DATA_PATH, TEMP_DIR, MEMORY_LIMIT, SF)
# plus the Postgres provisioning, remote-storage detection, and the two-step
# idempotency probe. The PowerShell twin sources _load-common.ps1 the same way.
DATASET="tpcds"
DATASET_LABEL="TPC-DS"
DB_NAME="${DB_NAME:-globex_tpcds}"
SCHEMA_NAME="${SCHEMA_NAME:-tpcds1}"
SF="${SF:-1}"
SENTINEL_TABLE="store_sales"
SENTINEL_COLUMN="ss_sold_date_sk"
SCALE_NOTE="approx $((SF * 3))M store_sales rows"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_load-common.sh
source "$SCRIPT_DIR/_load-common.sh"

load_config
load_sanity
load_ensure_pg_database
load_resolve_storage
load_print_banner
load_exit_if_already_loaded

# ---- Generate + run ----
INIT_SQL="$(mktemp -t load-tpcds-dsdgen.XXXXXX.sql)"
trap 'rm -f "$INIT_SQL"' EXIT

cat > "$INIT_SQL" <<SQL
-- Anchor temp_directory FIRST so any subsequent INSTALL / LOAD / CALL that
-- needs to spill (dsdgen at SF>=10 will) writes into a directory we know
-- exists, rather than DuckDB's cwd-relative default ./.tmp/.
SET temp_directory='$TEMP_DIR';
-- Bound DuckDB's memory so dsdgen() spills to temp_directory instead of
-- overrunning the container cgroup limit and getting OOM-killed (exit 137).
$MEMORY_LIMIT_SQL

$(load_preamble_sql "INSTALL tpcds;    LOAD tpcds;")

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
