<#
.SYNOPSIS
  Seed a DuckLake tenant-db with TPC-DS using DuckDB's built-in dsdgen()
  (PowerShell twin of scripts/load-tpcds-dbgen.sh).

.DESCRIPTION
  1. ATTACH the DuckLake catalog (Postgres metadata + local/remote data files)
  2. CREATE SCHEMA $SCHEMA_NAME if missing
  3. INSTALL/LOAD tpcds, CALL dsdgen(sf=$SF) into the in-memory catalog
  4. CTAS the 24 TPC-DS tables into $DB_NAME.$SCHEMA_NAME

  See scripts/load-tpch-dbgen.ps1 for the path-matching warning and the shared
  env-var contract. Defaults here: DB_NAME(globex_tpcds) SCHEMA_NAME(tpcds1).

.EXAMPLE
  .\scripts\load-tpcds-dbgen.ps1                 # SF=1 into globex_tpcds.tpcds1
.EXAMPLE
  $env:SF=10; .\scripts\load-tpcds-dbgen.ps1     # larger workload
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_load-common.ps1')

$cfg = Resolve-LoadConfig -DefaultDbName 'globex_tpcds' -DefaultSchema 'tpcds1'
Assert-LoadSanity $cfg
Initialize-PgDatabase $cfg
$storage = Resolve-Storage $cfg
$cfg.DataPath = $storage.DataPath

Write-LoadBanner $cfg "approx $([int]$cfg.Sf * 3)M store_sales rows"

if (Test-AlreadyLoaded -cfg $cfg -StorageSql $storage.Sql -ProbeTable 'store_sales' -ProbeColumn 'ss_sold_date_sk') {
  exit 0
}

$attach = Get-AttachSql $cfg
$preamble = Get-PreambleSql $cfg

# dsdgen() only writes to native DuckDB catalogs, not DuckLake. Generate into the
# in-memory database, then CTAS each of the 24 tables into the DuckLake schema.
$tables = @(
  'call_center','catalog_page','catalog_returns','catalog_sales','customer',
  'customer_address','customer_demographics','date_dim','household_demographics',
  'income_band','inventory','item','promotion','reason','ship_mode','store',
  'store_returns','store_sales','time_dim','warehouse','web_page','web_returns',
  'web_sales','web_site'
)
$ctas = ($tables | ForEach-Object {
  "DROP TABLE IF EXISTS $($cfg.DbName).$($cfg.Schema).$_;`n" +
  "CREATE TABLE $($cfg.DbName).$($cfg.Schema).$_ AS SELECT * FROM memory.main.$_;"
}) -join "`n"

$sql = @"
$preamble
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL tpcds;    LOAD tpcds;
$($storage.Sql)

$attach

CREATE SCHEMA IF NOT EXISTS $($cfg.DbName).$($cfg.Schema);

.print ''
.print '== Generating TPC-DS SF=$($cfg.Sf) (in-memory) =='
.print 'Creates 24 tables including store_sales, catalog_sales, web_sales, inventory, ...'
.print ''
CALL dsdgen(sf = $($cfg.Sf));

.print ''
.print '== Copying into DuckLake $($cfg.DbName).$($cfg.Schema) =='
$ctas

USE $($cfg.DbName).$($cfg.Schema);

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
"@

$r = Invoke-DuckDb -Sql $sql
exit $r.ExitCode
