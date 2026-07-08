<#
.SYNOPSIS
  Seed a DuckLake tenant-db with the SSB (Star Schema Benchmark) derived from
  DuckDB's TPC-H dbgen() (PowerShell twin of scripts/load-ssb-dbgen.sh).

.DESCRIPTION
  1. ATTACH the DuckLake catalog (Postgres metadata + local/remote data files)
  2. CREATE SCHEMA $SCHEMA_NAME if missing
  3. INSTALL/LOAD tpch, CALL dbgen(sf=$SF) into the in-memory catalog
  4. Derive the 5 SSB tables (lineorder, customer, supplier, part, dwdate) into
     $DB_NAME.$SCHEMA_NAME

  The star schema is served alongside the TPC-H tables from the same acme
  tenant-db (default DB_NAME acme_tpch, SCHEMA_NAME ssb1). See
  scripts/load-tpch-dbgen.ps1 for the path-matching warning and env-var contract.

.EXAMPLE
  .\scripts\load-ssb-dbgen.ps1                   # SF=1 into acme_tpch.ssb1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_load-common.ps1')

$cfg = Resolve-LoadConfig -DefaultDbName 'acme_tpch' -DefaultSchema 'ssb1'
Assert-LoadSanity $cfg
Initialize-PgDatabase $cfg
$storage = Resolve-Storage $cfg
$cfg.DataPath = $storage.DataPath

Write-LoadBanner $cfg "approx $([int]$cfg.Sf * 6)M lineorder rows"

if (Test-AlreadyLoaded -cfg $cfg -StorageSql $storage.Sql -ProbeTable 'lineorder' -ProbeColumn 'lo_orderkey') {
  exit 0
}

$attach = Get-AttachSql $cfg
$preamble = Get-PreambleSql $cfg
$db = $cfg.DbName
$sc = $cfg.Schema

# dbgen() only writes to native DuckDB catalogs, not DuckLake. Generate TPC-H
# into the in-memory database, then derive each SSB table into the DuckLake
# schema. DROP first so a previous partial run is overwritten cleanly.
$sql = @"
$preamble
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL tpch;     LOAD tpch;
$($storage.Sql)

$attach

CREATE SCHEMA IF NOT EXISTS $db.$sc;

.print ''
.print '== Generating TPC-H SF=$($cfg.Sf) (in-memory) as the SSB source =='
.print ''
CALL dbgen(sf = $($cfg.Sf));

.print ''
.print '== Deriving SSB star schema into DuckLake $db.$sc =='

-- customer: TPC-H customer joined to nation/region; c_city is the SSB 10-char
-- city (9-char nation prefix + digit, key mod 10 as the digit).
DROP TABLE IF EXISTS $db.$sc.customer;
CREATE TABLE $db.$sc.customer AS
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
DROP TABLE IF EXISTS $db.$sc.supplier;
CREATE TABLE $db.$sc.supplier AS
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

-- part: TPC-H p_brand is 'Brand#XY'. SSB rolls up brand1 (MFGR#XYZZ) ->
-- category (MFGR#XY) -> mfgr (MFGR#X); p_color is the first token of p_name.
DROP TABLE IF EXISTS $db.$sc.part;
CREATE TABLE $db.$sc.part AS
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
DROP TABLE IF EXISTS $db.$sc.dwdate;
CREATE TABLE $db.$sc.dwdate AS
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
-- lo_tax become integer percentages, lo_supplycost comes from partsupp.
DROP TABLE IF EXISTS $db.$sc.lineorder;
CREATE TABLE $db.$sc.lineorder AS
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

USE $db.$sc;

.print ''
.print '== Final state =='
SHOW TABLES;
SELECT 'lineorder' AS tbl, count(*) AS rows FROM lineorder
UNION ALL SELECT 'customer', count(*) FROM customer
UNION ALL SELECT 'supplier', count(*) FROM supplier
UNION ALL SELECT 'part',     count(*) FROM part
UNION ALL SELECT 'dwdate',   count(*) FROM dwdate;
"@

$r = Invoke-DuckDb -Sql $sql
exit $r.ExitCode
