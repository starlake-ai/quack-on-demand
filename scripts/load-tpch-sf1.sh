#!/usr/bin/env bash
#
# Load the TPCH SF1 dataset into the DuckLake catalog $DB_NAME.$SCHEMA_NAME.
#
# Uses the DuckDB `tpch` extension's `dbgen()` table function to materialize
# the canonical TPCH dataset at the requested scale factor — no input files,
# no datasets/ directory, fully reproducible from the TPC-H spec.
#
# `dbgen` writes tables into the active in-memory DuckDB catalog (`memory.main`).
# We then CREATE OR REPLACE TABLE ... AS SELECT each one into the DuckLake-backed
# schema, which routes the data through DuckLake's Parquet writer and Postgres
# catalog the same way a normal load would.
#
# Re-running the script replaces each table. Set KEEP_LINEITEM=1 to skip the
# largest copy when iterating; the rest still get refreshed.
#
# Overrides via env vars:
#   PG_HOST   PG_PORT   PG_USER   PG_PASS
#   DB_NAME           (Postgres DB + DuckDB catalog; must already exist —
#                      use scripts/start-quack-ducklake.sh first to bootstrap it)
#   SCHEMA_NAME       (DuckLake schema under $DB_NAME; created if missing.
#                      MUST differ from DB_NAME or DuckDB rejects 2-part
#                      identifiers like "$DB_NAME"."customer" as ambiguous.)
#   DATA_PATH         (DuckLake data files dir; defaults under ducklake/$DB_NAME
#                      at the repo root)
#   SF                (TPCH scale factor; default 1 → ~6M lineitem rows)
#   KEEP_LINEITEM     (1 = skip the lineitem copy; default 0)

set -euo pipefail

# ---- Config ----
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASS="${PG_PASS:-azizam}"

DB_NAME="${DB_NAME:-tpch}"
SCHEMA_NAME="${SCHEMA_NAME:-tpch1}"
DATA_PATH="${DATA_PATH:-$REPO_DIR/ducklake/$DB_NAME}"
SF="${SF:-1}"
KEEP_LINEITEM="${KEEP_LINEITEM:-0}"

if [[ "$SCHEMA_NAME" == "$DB_NAME" ]]; then
  echo "ERROR: SCHEMA_NAME ($SCHEMA_NAME) must differ from DB_NAME ($DB_NAME)." >&2
  echo "       DuckDB rejects 2-part identifiers like \"$DB_NAME\".<table> as ambiguous" >&2
  echo "       when a catalog and a schema share a name." >&2
  exit 1
fi

# ---- Sanity ----
command -v duckdb >/dev/null 2>&1 || {
  echo "ERROR: duckdb not found on PATH." >&2
  exit 1
}

echo "scale factor:   SF=$SF"
echo "target catalog: $DB_NAME.$SCHEMA_NAME"
echo "data path:      $DATA_PATH"
echo "lineitem:       $([[ "$KEEP_LINEITEM" == "1" ]] && echo SKIPPED || echo "will load (this is the slow one at SF=$SF)")"

# ---- Generate the load SQL ----
INIT_SQL="$(mktemp -t load-tpch.XXXXXX.sql)"
trap 'rm -f "$INIT_SQL"' EXIT

cat > "$INIT_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL tpch;     LOAD tpch;

ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');

CREATE SCHEMA IF NOT EXISTS $DB_NAME.$SCHEMA_NAME;

.print ''
.print '-- generating TPCH dataset in memory (SF=$SF) --'
-- Leave `memory` as the active catalog: dbgen() only writes into DuckDB-file
-- catalogs, so switching to the DuckLake-backed $DB_NAME first would fail with
-- "dbgen is only supported for DuckDB database files".
CALL dbgen(sf=$SF);

.print ''
.print '-- copying generated tables into $DB_NAME.$SCHEMA_NAME via DuckLake --'
CREATE OR REPLACE TABLE $DB_NAME.$SCHEMA_NAME.region   AS SELECT * FROM memory.main.region;
SELECT 'region'   AS tbl, count(*) AS rows FROM $DB_NAME.$SCHEMA_NAME.region;
CREATE OR REPLACE TABLE $DB_NAME.$SCHEMA_NAME.nation   AS SELECT * FROM memory.main.nation;
SELECT 'nation'   AS tbl, count(*) AS rows FROM $DB_NAME.$SCHEMA_NAME.nation;
CREATE OR REPLACE TABLE $DB_NAME.$SCHEMA_NAME.customer AS SELECT * FROM memory.main.customer;
SELECT 'customer' AS tbl, count(*) AS rows FROM $DB_NAME.$SCHEMA_NAME.customer;
CREATE OR REPLACE TABLE $DB_NAME.$SCHEMA_NAME.supplier AS SELECT * FROM memory.main.supplier;
SELECT 'supplier' AS tbl, count(*) AS rows FROM $DB_NAME.$SCHEMA_NAME.supplier;
CREATE OR REPLACE TABLE $DB_NAME.$SCHEMA_NAME.part     AS SELECT * FROM memory.main.part;
SELECT 'part'     AS tbl, count(*) AS rows FROM $DB_NAME.$SCHEMA_NAME.part;
CREATE OR REPLACE TABLE $DB_NAME.$SCHEMA_NAME.partsupp AS SELECT * FROM memory.main.partsupp;
SELECT 'partsupp' AS tbl, count(*) AS rows FROM $DB_NAME.$SCHEMA_NAME.partsupp;
CREATE OR REPLACE TABLE $DB_NAME.$SCHEMA_NAME.orders   AS SELECT * FROM memory.main.orders;
SELECT 'orders'   AS tbl, count(*) AS rows FROM $DB_NAME.$SCHEMA_NAME.orders;
SQL

if [[ "$KEEP_LINEITEM" != "1" ]]; then
  cat >> "$INIT_SQL" <<SQL

.print ''
.print '-- lineitem (the slow one) --'
CREATE OR REPLACE TABLE $DB_NAME.$SCHEMA_NAME.lineitem AS SELECT * FROM memory.main.lineitem;
SELECT 'lineitem' AS tbl, count(*) AS rows FROM $DB_NAME.$SCHEMA_NAME.lineitem;
SQL
fi

cat >> "$INIT_SQL" <<SQL

.print ''
.print '== Final state =='
USE $DB_NAME.$SCHEMA_NAME;
SHOW TABLES;
SQL

# ---- Run ----
echo ""
duckdb < "$INIT_SQL"
