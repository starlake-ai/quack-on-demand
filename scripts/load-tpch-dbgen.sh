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
#     Use `LOAD_TPCH=true ./scripts/run-docker-compose.sh` (or
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
#   DB_NAME       Postgres DB + DuckLake catalog name (default tpch)
#   SCHEMA_NAME   DuckLake schema (must differ from DB_NAME)  (default tpch1)
#   DATA_PATH     DuckLake data dir                   (default ducklake/$DB_NAME)
#   SF            scale factor - controls row counts  (default 1)
#                 SF=1  -> ~6M lineitem rows
#                 SF=10 -> ~60M lineitem rows (much heavier)
#
# Usage:
#   ./scripts/load-tpch-dbgen.sh                       # SF=1 into tpch.tpch1
#   SF=10 ./scripts/load-tpch-dbgen.sh                 # larger workload
#   PG_HOST=db.internal SCHEMA_NAME=tpch10 SF=10 ./scripts/load-tpch-dbgen.sh

set -euo pipefail

# ---- Config ----
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASS="${PG_PASS:-azizam}"

DB_NAME="${DB_NAME:-tpch}"
SCHEMA_NAME="${SCHEMA_NAME:-tpch1}"
SF="${SF:-1}"

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

mkdir -p "$DATA_PATH"
# Canonicalize - DuckLake persists this exact string in the catalog.
DATA_PATH="$(cd "$DATA_PATH" && pwd)"

# Detect "I'm probably running on a host that will later run Docker" and
# emit a heads-up. Heuristic: DATA_PATH doesn't start with /app/, but the
# manager's Dockerfile defaults SL_QUACK_DUCKLAKE_DATA_PATH=/app/ducklake.
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

echo "postgres:    $PG_USER@$PG_HOST:$PG_PORT/$DB_NAME"
echo "catalog:     $DB_NAME.$SCHEMA_NAME"
echo "data path:   $DATA_PATH"
echo "scale:       SF=$SF (approx $((SF * 6))M lineitem rows)"
echo ""

# ---- Idempotency probe ----
# Check whether $DB_NAME.$SCHEMA_NAME.lineitem already exists with rows. Use
# DuckDB itself (not psql against information_schema) because DuckLake tables
# are not visible to Postgres's catalog - their metadata lives in
# `__ducklake_*` tables and their data in parquet on disk. Running the probe
# through DuckDB matches what any client will actually see. stderr is
# silenced so the expected "table does not exist" path is quiet on first
# boot.
PROBE_SQL="$(mktemp -t load-tpch-probe.XXXXXX.sql)"
cat > "$PROBE_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');
.mode csv
.headers off
SELECT count(*) FROM $DB_NAME.$SCHEMA_NAME.lineitem;
SQL
existing_rows="$(duckdb < "$PROBE_SQL" 2>/dev/null | tr -d '\r\n ' || true)"
rm -f "$PROBE_SQL"
if [[ "$existing_rows" =~ ^[0-9]+$ ]] && (( existing_rows > 0 )); then
  echo "already loaded: $DB_NAME.$SCHEMA_NAME.lineitem has $existing_rows rows; skipping."
  echo "(delete the schema or override SCHEMA_NAME to force a reload)"
  exit 0
fi

# ---- Generate + run ----
INIT_SQL="$(mktemp -t load-tpch-dbgen.XXXXXX.sql)"
trap 'rm -f "$INIT_SQL"' EXIT

cat > "$INIT_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL tpch;     LOAD tpch;

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
