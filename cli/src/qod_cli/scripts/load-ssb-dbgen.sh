#!/usr/bin/env bash
#
# Seed the DuckLake metastore with an SSB (Star Schema Benchmark) dataset.
# DuckDB has no ssb extension, so we generate TPC-H in-memory with the
# built-in `dbgen()` and derive the 5 SSB tables from it using the O'Neil
# SSB spec's TPC-H mapping - no external CSV/JSON files required, no
# datasets/ directory to ship. Works for any of the three deployment
# paths (native, Docker single container, Docker compose).
#
# The star schema lands NEXT TO the TPC-H demo data: same tenant-db
# (acme_tpch by default) in its own schema (ssb1 by default), so the
# existing acme pools serve it with no extra bootstrap entities. Query it
# as `ssb1.lineorder` etc. from any acme session (the demo tenant_admin's
# `*.*.* ALL` grant covers it).
#
# Tables produced:
#   lineorder  fact:      lineitem x orders x partsupp   (~SF*6M rows)
#   customer   dimension: customer x nation x region
#   supplier   dimension: supplier x nation x region
#   part       dimension: part with SSB's mfgr/category/brand1 rollup
#   dwdate     dimension: 2,557 calendar days 1992-01-01..1998-12-31
#              (named dwdate, not the spec's `date`, so the 13 canonical
#              SSB queries run without quoting a reserved word - same
#              rename the Redshift SSB tutorial uses)
#
# Derivation notes: c_city/s_city take the nation prefix + key mod 10,
# p_brand1 spreads p_partkey mod 40 under the TPC-H brand's category, and
# lo_supplycost joins partsupp. d_sellingseason and the d_*fl flags are
# approximated - none of the 13 canonical SSB queries read them. Supplier
# keeps TPC-H's SF*10k rows (spec says SF*2k); lineorder joins are
# unaffected.
#
# *** PATH-MATCHING WARNING ***
# DuckLake stores `DATA_PATH` as an ABSOLUTE path inside the Postgres
# catalog; loader and manager must see the same string. See the header of
# load-tpch-dbgen.sh for the full explanation - the same rules apply here.
#
# Overrides via env vars (with defaults):
#   PG_HOST       Postgres host                       (default localhost)
#   PG_PORT       Postgres port                       (default 5432)
#   PG_USER       Postgres user                       (default postgres)
#   PG_PASS       Postgres password                   (default azizam)
#   DB_NAME       Postgres DB + DuckLake catalog name (default acme_tpch)
#   SCHEMA_NAME   DuckLake schema (must differ from DB_NAME)  (default ssb1)
#   DATA_PATH     DuckLake data dir                   (default ducklake/$DB_NAME)
#   SF            scale factor - controls row counts  (default 1)
#                 SF=1  -> ~6M lineorder rows
#                 SF=10 -> ~60M lineorder rows (much heavier)
#
# Usage:
#   ./scripts/load-ssb-dbgen.sh                        # SF=1 into acme_tpch.ssb1
#   SF=10 ./scripts/load-ssb-dbgen.sh                  # larger workload
#   PG_HOST=db.internal SCHEMA_NAME=ssb10 SF=10 ./scripts/load-ssb-dbgen.sh

set -euo pipefail

# ---- Config / sanity / storage / idempotency (shared) ----
# Dataset knobs consumed by scripts/_load-common.sh, which owns the full env
# contract (PG_*, DB_NAME, SCHEMA_NAME, DATA_PATH, TEMP_DIR, MEMORY_LIMIT, SF)
# plus the Postgres provisioning, remote-storage detection, and the two-step
# idempotency probe. The PowerShell twin sources _load-common.ps1 the same way.
DATASET="ssb"
DATASET_LABEL="SSB"
DB_NAME="${DB_NAME:-acme_tpch}"
SCHEMA_NAME="${SCHEMA_NAME:-ssb1}"
SF="${SF:-1}"
SENTINEL_TABLE="lineorder"
SENTINEL_COLUMN="lo_orderkey"
SCALE_NOTE="approx $((SF * 6))M lineorder rows"

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
INIT_SQL="$(mktemp -t load-ssb-dbgen.XXXXXX.sql)"
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
.print '== Generating TPC-H SF=$SF (in-memory) as the SSB source =='
.print 'SF=1 is fast (~10s); SF=10 takes a few minutes; SF=100+ is slow.'
.print ''
-- dbgen() only writes to native DuckDB catalogs, not DuckLake. Generate
-- into the default in-memory database, then derive each SSB table into
-- the DuckLake schema. We DROP first so a previous partial run is
-- overwritten cleanly.
CALL dbgen(sf = $SF);

.print ''
.print '== Deriving SSB star schema into DuckLake $DB_NAME.$SCHEMA_NAME =='

-- customer: TPC-H customer joined to nation/region; c_city is the SSB
-- 10-char city (9-char nation prefix + digit, key mod 10 as the digit).
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.customer;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.customer AS
SELECT
  c.c_custkey,
  c.c_name,
  c.c_address,
  substr(rpad(n.n_name, 9, ' '), 1, 9) || CAST(c.c_custkey % 10 AS VARCHAR) AS c_city,
  n.n_name AS c_nation,
  r.r_name AS c_region,
  c.c_phone,
  c.c_mktsegment
FROM memory.main.customer c
JOIN memory.main.nation n ON c.c_nationkey = n.n_nationkey
JOIN memory.main.region r ON n.n_regionkey = r.r_regionkey;

-- supplier: same shape as customer.
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.supplier;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.supplier AS
SELECT
  s.s_suppkey,
  s.s_name,
  s.s_address,
  substr(rpad(n.n_name, 9, ' '), 1, 9) || CAST(s.s_suppkey % 10 AS VARCHAR) AS s_city,
  n.n_name AS s_nation,
  r.r_name AS s_region,
  s.s_phone
FROM memory.main.supplier s
JOIN memory.main.nation n ON s.s_nationkey = n.n_nationkey
JOIN memory.main.region r ON n.n_regionkey = r.r_regionkey;

-- part: TPC-H p_brand is 'Brand#XY' with X = manufacturer 1-5, Y = 1-5.
-- SSB rolls up brand1 (MFGR#XYZZ, ZZ in 1-40) -> category (MFGR#XY)
-- -> mfgr (MFGR#X); p_color is the first token of the TPC-H p_name.
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.part;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.part AS
SELECT
  p_partkey,
  p_name,
  'MFGR#' || substr(p_brand, 7, 1) AS p_mfgr,
  'MFGR#' || substr(p_brand, 7, 2) AS p_category,
  'MFGR#' || substr(p_brand, 7, 2) || CAST(p_partkey % 40 + 1 AS VARCHAR) AS p_brand1,
  split_part(p_name, ' ', 1) AS p_color,
  p_type,
  p_size,
  p_container
FROM memory.main.part;

-- dwdate: one row per calendar day over TPC-H's order date range.
-- d_daynuminweek is 1(Sunday)..7(Saturday) per the SSB spec.
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.dwdate;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.dwdate AS
SELECT
  CAST(strftime(d, '%Y%m%d') AS INTEGER)               AS d_datekey,
  strftime(d, '%B %d, %Y')                             AS d_date,
  dayname(d)                                           AS d_dayofweek,
  monthname(d)                                         AS d_month,
  CAST(year(d) AS INTEGER)                             AS d_year,
  CAST(year(d) * 100 + month(d) AS INTEGER)            AS d_yearmonthnum,
  strftime(d, '%b%Y')                                  AS d_yearmonth,
  CAST(dayofweek(d) + 1 AS INTEGER)                    AS d_daynuminweek,
  CAST(day(d) AS INTEGER)                              AS d_daynuminmonth,
  CAST(dayofyear(d) AS INTEGER)                        AS d_daynuminyear,
  CAST(month(d) AS INTEGER)                            AS d_monthnuminyear,
  CAST(weekofyear(d) AS INTEGER)                       AS d_weeknuminyear,
  CASE
    WHEN month(d) = 12 OR (month(d) = 11 AND day(d) >= 15) THEN 'Christmas'
    WHEN month(d) IN (6, 7, 8)                             THEN 'Summer'
    WHEN month(d) IN (3, 4, 5)                             THEN 'Spring'
    WHEN month(d) IN (9, 10, 11)                           THEN 'Fall'
    ELSE 'Winter'
  END                                                  AS d_sellingseason,
  CASE WHEN dayofweek(d) = 6 THEN 1 ELSE 0 END         AS d_lastdayinweekfl,
  CASE WHEN d = last_day(d) THEN 1 ELSE 0 END          AS d_lastdayinmonthfl,
  CASE WHEN (month(d) = 12 AND day(d) = 25)
         OR (month(d) = 1  AND day(d) = 1)
         OR (month(d) = 7  AND day(d) = 4) THEN 1 ELSE 0 END AS d_holidayfl,
  CASE WHEN dayofweek(d) IN (0, 6) THEN 0 ELSE 1 END   AS d_weekdayfl
FROM (
  SELECT CAST(gs.d AS DATE) AS d
  FROM generate_series(DATE '1992-01-01', DATE '1998-12-31', INTERVAL 1 DAY) AS gs(d)
);

-- lineorder: lineitem denormalized with its order header; lo_discount and
-- lo_tax become 0-10 / 0-8 integer percentages (SSB queries filter
-- lo_discount BETWEEN 1 AND 3), lo_supplycost comes from partsupp.
DROP TABLE IF EXISTS $DB_NAME.$SCHEMA_NAME.lineorder;
CREATE TABLE $DB_NAME.$SCHEMA_NAME.lineorder AS
SELECT
  l.l_orderkey                                         AS lo_orderkey,
  l.l_linenumber                                       AS lo_linenumber,
  o.o_custkey                                          AS lo_custkey,
  l.l_partkey                                          AS lo_partkey,
  l.l_suppkey                                          AS lo_suppkey,
  CAST(strftime(o.o_orderdate, '%Y%m%d') AS INTEGER)   AS lo_orderdate,
  o.o_orderpriority                                    AS lo_orderpriority,
  o.o_shippriority                                     AS lo_shippriority,
  CAST(l.l_quantity AS INTEGER)                        AS lo_quantity,
  l.l_extendedprice                                    AS lo_extendedprice,
  o.o_totalprice                                       AS lo_ordtotalprice,
  CAST(round(l.l_discount * 100) AS INTEGER)           AS lo_discount,
  round(l.l_extendedprice * (1 - l.l_discount), 2)     AS lo_revenue,
  ps.ps_supplycost                                     AS lo_supplycost,
  CAST(round(l.l_tax * 100) AS INTEGER)                AS lo_tax,
  CAST(strftime(l.l_commitdate, '%Y%m%d') AS INTEGER)  AS lo_commitdate,
  l.l_shipmode                                         AS lo_shipmode
FROM memory.main.lineitem l
JOIN memory.main.orders o
  ON l.l_orderkey = o.o_orderkey
JOIN memory.main.partsupp ps
  ON l.l_partkey = ps.ps_partkey AND l.l_suppkey = ps.ps_suppkey;

USE $DB_NAME.$SCHEMA_NAME;

.print ''
.print '== Final state =='
SHOW TABLES;
SELECT 'lineorder' AS tbl, count(*) AS rows FROM lineorder
UNION ALL SELECT 'customer', count(*) FROM customer
UNION ALL SELECT 'supplier', count(*) FROM supplier
UNION ALL SELECT 'part',     count(*) FROM part
UNION ALL SELECT 'dwdate',   count(*) FROM dwdate;
SQL

exec duckdb < "$INIT_SQL"