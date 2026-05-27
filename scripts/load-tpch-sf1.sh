#!/usr/bin/env bash
#
# Load the TPCH SF1 dataset into the DuckLake catalog $DB_NAME.$SCHEMA_NAME.
#
# Input files are picked up from $TPCH_SRC (defaults to docs/TPCH_SF1) and
# are a mix of CSV (8 tables), JSON (nation), and XML (region):
#
#   CUSTOMER-*.csv      ;-delimited, "-quoted
#   LINEITEM-*.csv      ;-delimited, "-quoted, ~6M rows / 962 MB
#   NATION-*.json       array of objects with string-typed numerics
#   ORDERS-*.csv        ;-delimited
#   PART-*.csv          ;-delimited
#   PARTSUPP-*.csv      ;-delimited
#   REGION-*.xml        5 rows, hardcoded inline (DuckDB has no built-in XML reader)
#   SUPPLIER-*.csv      ;-delimited
#
# Re-running the script DROPs and recreates each table. Set KEEP_LINEITEM=1
# to skip the largest load when iterating.
#
# Overrides via env vars:
#   PG_HOST   PG_PORT   PG_USER   PG_PASS
#   DB_NAME           (Postgres DB + DuckDB catalog; must already exist —
#                      use scripts/start-quack-ducklake.sh first to bootstrap it)
#   SCHEMA_NAME       (DuckLake schema under $DB_NAME; created if missing.
#                      MUST differ from DB_NAME or DuckDB rejects 2-part
#                      identifiers like "$DB_NAME"."customer" as ambiguous.)
#   DATA_PATH         (DuckLake data files dir; defaults under ducklake/$DB_NAME)
#   TPCH_SRC          (defaults to datasets/TPCH_SF1 under the repo)
#   KEEP_LINEITEM     (1 = skip the lineitem load; default 0)

set -euo pipefail

# ---- Config ----
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASS="${PG_PASS:-azizam}"

DB_NAME="${DB_NAME:-tpch}"
SCHEMA_NAME="${SCHEMA_NAME:-tpch1}"
DATA_PATH="${DATA_PATH:-/Users/hayssams/git/public/quack-on-demand/ducklake/$DB_NAME}"

if [[ "$SCHEMA_NAME" == "$DB_NAME" ]]; then
  echo "ERROR: SCHEMA_NAME ($SCHEMA_NAME) must differ from DB_NAME ($DB_NAME)." >&2
  echo "       DuckDB rejects 2-part identifiers like \"$DB_NAME\".<table> as ambiguous" >&2
  echo "       when a catalog and a schema share a name." >&2
  exit 1
fi
TPCH_SRC="${TPCH_SRC:-/Users/hayssams/git/public/quack-on-demand/datasets/TPCH_SF1}"
KEEP_LINEITEM="${KEEP_LINEITEM:-0}"

# ---- Sanity ----
command -v duckdb >/dev/null 2>&1 || {
  echo "ERROR: duckdb not found on PATH." >&2
  exit 1
}
test -d "$TPCH_SRC" || {
  echo "ERROR: TPCH source dir not found: $TPCH_SRC" >&2
  exit 1
}

# Pick the timestamped files (one per table)
shopt -s nullglob
CUSTOMER_CSV=( "$TPCH_SRC"/CUSTOMER-*.csv )
LINEITEM_CSV=( "$TPCH_SRC"/LINEITEM-*.csv )
ORDERS_CSV=(   "$TPCH_SRC"/ORDERS-*.csv )
PART_CSV=(     "$TPCH_SRC"/PART-*.csv )
PARTSUPP_CSV=( "$TPCH_SRC"/PARTSUPP-*.csv )
SUPPLIER_CSV=( "$TPCH_SRC"/SUPPLIER-*.csv )
NATION_JSON=(  "$TPCH_SRC"/NATION-*.json )
shopt -u nullglob

for var in CUSTOMER_CSV LINEITEM_CSV ORDERS_CSV PART_CSV PARTSUPP_CSV SUPPLIER_CSV NATION_JSON; do
  declare -n arr="$var"
  if [[ ${#arr[@]} -eq 0 ]]; then
    echo "ERROR: no file matched in $TPCH_SRC for $var" >&2
    exit 1
  fi
done

echo "source dir:     $TPCH_SRC"
echo "target catalog: $DB_NAME.$SCHEMA_NAME"
echo "data path:      $DATA_PATH"
echo "lineitem:       $([[ "$KEEP_LINEITEM" == "1" ]] && echo SKIPPED || echo "will load (~6M rows, this is the slow one)")"

# ---- Generate the load SQL ----
INIT_SQL="$(mktemp -t load-tpch.XXXXXX.sql)"
trap 'rm -f "$INIT_SQL"' EXIT

cat > "$INIT_SQL" <<SQL
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;

ATTACH 'ducklake:postgres:host=$PG_HOST port=$PG_PORT dbname=$DB_NAME user=$PG_USER password=$PG_PASS' AS $DB_NAME
  (DATA_PATH '$DATA_PATH');

USE $DB_NAME;
CREATE SCHEMA IF NOT EXISTS $SCHEMA_NAME;
USE $DB_NAME.$SCHEMA_NAME;

.print ''
.print '-- region (5 rows, hardcoded from REGION-*.xml) --'
DROP TABLE IF EXISTS region;
CREATE TABLE region (r_regionkey INTEGER, r_name VARCHAR, r_comment VARCHAR);
INSERT INTO region VALUES
  (0, 'AFRICA',      'lar deposits. blithely final packages cajole. regular waters are final requests. regular accounts are according to'),
  (1, 'AMERICA',     'hs use ironic, even requests. s'),
  (2, 'ASIA',        'ges. thinly even pinto beans ca'),
  (3, 'EUROPE',      'ly final courts cajole furiously final excuse'),
  (4, 'MIDDLE EAST', 'uickly special accounts cajole carefully blithely close requests. carefully final asymptotes haggle furiousl');
SELECT 'region' AS tbl, count(*) AS rows FROM region;

.print ''
.print '-- nation (JSON; cast string keys to ints) --'
DROP TABLE IF EXISTS nation;
CREATE TABLE nation AS
  SELECT
    CAST(N_NATIONKEY AS INTEGER) AS n_nationkey,
    N_NAME                        AS n_name,
    CAST(N_REGIONKEY AS INTEGER) AS n_regionkey,
    N_COMMENT                     AS n_comment
  FROM read_json('${NATION_JSON[0]}');
SELECT 'nation' AS tbl, count(*) AS rows FROM nation;

.print ''
.print '-- customer --'
DROP TABLE IF EXISTS customer;
CREATE TABLE customer AS
  SELECT * FROM read_csv('${CUSTOMER_CSV[0]}', delim=';', quote='"', header=true, auto_detect=true);
SELECT 'customer' AS tbl, count(*) AS rows FROM customer;

.print ''
.print '-- supplier --'
DROP TABLE IF EXISTS supplier;
CREATE TABLE supplier AS
  SELECT * FROM read_csv('${SUPPLIER_CSV[0]}', delim=';', quote='"', header=true, auto_detect=true);
SELECT 'supplier' AS tbl, count(*) AS rows FROM supplier;

.print ''
.print '-- part --'
DROP TABLE IF EXISTS part;
CREATE TABLE part AS
  SELECT * FROM read_csv('${PART_CSV[0]}', delim=';', quote='"', header=true, auto_detect=true);
SELECT 'part' AS tbl, count(*) AS rows FROM part;

.print ''
.print '-- partsupp --'
DROP TABLE IF EXISTS partsupp;
CREATE TABLE partsupp AS
  SELECT * FROM read_csv('${PARTSUPP_CSV[0]}', delim=';', quote='"', header=true, auto_detect=true);
SELECT 'partsupp' AS tbl, count(*) AS rows FROM partsupp;

.print ''
.print '-- orders --'
DROP TABLE IF EXISTS orders;
CREATE TABLE orders AS
  SELECT * FROM read_csv('${ORDERS_CSV[0]}', delim=';', quote='"', header=true, auto_detect=true);
SELECT 'orders' AS tbl, count(*) AS rows FROM orders;
SQL

if [[ "$KEEP_LINEITEM" != "1" ]]; then
  cat >> "$INIT_SQL" <<SQL

.print ''
.print '-- lineitem (~6M rows, the slow one) --'
DROP TABLE IF EXISTS lineitem;
CREATE TABLE lineitem AS
  SELECT * FROM read_csv('${LINEITEM_CSV[0]}', delim=';', quote='"', header=true, auto_detect=true);
SELECT 'lineitem' AS tbl, count(*) AS rows FROM lineitem;
SQL
fi

cat >> "$INIT_SQL" <<SQL

.print ''
.print '== Final state =='
SHOW TABLES;
SQL

# ---- Run ----
echo ""
duckdb < "$INIT_SQL"