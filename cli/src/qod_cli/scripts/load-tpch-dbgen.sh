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

# ---- Config / sanity / storage / idempotency (shared) ----
# Dataset knobs consumed by scripts/_load-common.sh, which owns the full env
# contract (PG_*, DB_NAME, SCHEMA_NAME, DATA_PATH, TEMP_DIR, MEMORY_LIMIT, SF)
# plus the Postgres provisioning, remote-storage detection, and the two-step
# idempotency probe. The PowerShell twin sources _load-common.ps1 the same way.
DATASET="tpch"
DATASET_LABEL="TPC-H"
DB_NAME="${DB_NAME:-acme_tpch}"
SCHEMA_NAME="${SCHEMA_NAME:-tpch1}"
SF="${SF:-1}"
SENTINEL_TABLE="lineitem"
SENTINEL_COLUMN="l_orderkey"
SCALE_NOTE="approx $((SF * 6))M lineitem rows"

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

$(load_preamble_sql "INSTALL tpch;     LOAD tpch;")

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
