<#
.SYNOPSIS
  Seed a DuckLake tenant-db with TPC-H using DuckDB's built-in dbgen()
  (PowerShell twin of scripts/load-tpch-dbgen.sh).

.DESCRIPTION
  1. ATTACH the DuckLake catalog (Postgres metadata + local/remote data files)
  2. CREATE SCHEMA $SCHEMA_NAME if missing
  3. INSTALL/LOAD tpch, CALL dbgen(sf=$SF) into the in-memory catalog
  4. CTAS the 8 TPC-H tables into $DB_NAME.$SCHEMA_NAME

  *** PATH-MATCHING WARNING *** DuckLake stores DATA_PATH as an ABSOLUTE path in
  the Postgres catalog; every Quack node later reads files from that exact
  string. The loader and the manager MUST see the same path. On a single
  Windows host (native loader + native manager) the default works. See the bash
  twin's header for the Docker cross-path caveats.

  Overrides via env vars (defaults): PG_HOST(localhost) PG_PORT(5432)
  PG_USER(postgres) PG_PASS(azizam) DB_NAME(acme_tpch) SCHEMA_NAME(tpch1)
  DATA_PATH(<cwd>\ducklake\$DB_NAME) SF(1) DUCKDB_BIN TEMP_DIR MEMORY_LIMIT.

.EXAMPLE
  .\scripts\load-tpch-dbgen.ps1                  # SF=1 into acme_tpch.tpch1
.EXAMPLE
  $env:SF=10; .\scripts\load-tpch-dbgen.ps1      # larger workload
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_load-common.ps1')

$cfg = Resolve-LoadConfig -DefaultDbName 'acme_tpch' -DefaultSchema 'tpch1'
Assert-LoadSanity $cfg
Initialize-PgDatabase $cfg
$storage = Resolve-Storage $cfg
$cfg.DataPath = $storage.DataPath

Write-LoadBanner $cfg "approx $([int]$cfg.Sf * 6)M lineitem rows"

if (Test-AlreadyLoaded -cfg $cfg -StorageSql $storage.Sql -ProbeTable 'lineitem' -ProbeColumn 'l_orderkey') {
  exit 0
}

$attach = Get-AttachSql $cfg
$preamble = Get-PreambleSql $cfg

# dbgen() only writes to native DuckDB catalogs, not DuckLake. Generate into the
# default in-memory database, then CTAS each table into the DuckLake schema.
# DROP first so a previous partial run is overwritten cleanly.
$tables = @('region','nation','customer','supplier','part','partsupp','orders','lineitem')
$ctas = ($tables | ForEach-Object {
  "DROP TABLE IF EXISTS $($cfg.DbName).$($cfg.Schema).$_;`n" +
  "CREATE TABLE $($cfg.DbName).$($cfg.Schema).$_ AS SELECT * FROM memory.main.$_;"
}) -join "`n"

$sql = @"
$preamble
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
INSTALL tpch;     LOAD tpch;
$($storage.Sql)

$attach

CREATE SCHEMA IF NOT EXISTS $($cfg.DbName).$($cfg.Schema);

.print ''
.print '== Generating TPC-H SF=$($cfg.Sf) (in-memory) =='
.print 'Creates 8 tables: region, nation, customer, supplier, part, partsupp, orders, lineitem'
.print ''
CALL dbgen(sf = $($cfg.Sf));

.print ''
.print '== Copying into DuckLake $($cfg.DbName).$($cfg.Schema) =='
$ctas

USE $($cfg.DbName).$($cfg.Schema);

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
"@

$r = Invoke-DuckDb -Sql $sql
exit $r.ExitCode
