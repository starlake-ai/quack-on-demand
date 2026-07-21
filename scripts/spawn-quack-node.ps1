<#
.SYNOPSIS
  Spawn one Quack node on Windows - invoked by LocalQuackBackend.

.DESCRIPTION
  PowerShell mirror of scripts/spawn-quack-node.sh. LocalQuackBackend wraps
  this as `powershell.exe -File spawn-quack-node.ps1 <port> <token>`.

  The metastore connection details arrive as environment variables that
  LocalQuackBackend.start() sets from CreatePoolRequest.metastore. The
  env-var keys match the UI form field names verbatim:

    pgHost pgPort pgUser pgPassword   (Postgres for DuckLake catalog)
    dbName                             (Postgres DB + DuckDB catalog)
    schemaName                         (DuckLake schema under $dbName; default `main`.
                                        MUST differ from $dbName or 2-part identifiers
                                        like "$dbName"."customer" resolve as ambiguous.)
    dataPath                           (DuckLake data files directory)
    kind                               (ducklake | duckdb-file | memory)
    dbInitSql extraSetupSql            (optional per-db / per-pool init SQL)
    lockdownSql                        (optional engine lockdown SQL, NodeLockdown.scala; runs
                                        before quack_serve)

  Unlike the bash version there is no FIFO: DuckDB is fed its init SQL over a
  redirected stdin that this script leaves OPEN, so the background quack
  thread keeps serving until the process tree is terminated (LocalQuackBackend
  uses `taskkill /T` from stop()/cleanup()).

  The Postgres `CREATE DATABASE` step the bash script performs as a fallback is
  intentionally omitted here: the manager JVM already provisions the tenant-db
  (DbAdmin.createDatabase via PoolSupervisor.createTenantDb + DuckLakeInitializer).
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true, Position = 0)] [int]    $Port,
  [Parameter(Mandatory = $true, Position = 1)] [string] $Token
)

$ErrorActionPreference = 'Stop'

function Env-Or([string]$name, [string]$default) {
  $v = [Environment]::GetEnvironmentVariable($name)
  if ([string]::IsNullOrEmpty($v)) { return $default } else { return $v }
}

$pgHost     = Env-Or 'pgHost'     'localhost'
$pgPort     = Env-Or 'pgPort'     '5432'
$pgUser     = Env-Or 'pgUser'     'postgres'
$pgPassword = Env-Or 'pgPassword' 'azizam'
$dbName     = Env-Or 'dbName'     'db1'
$schemaName = Env-Or 'schemaName' 'main'
$dataPath   = Env-Or 'dataPath'   (Join-Path (Get-Location) "ducklake\$dbName")

$kind = [Environment]::GetEnvironmentVariable('kind')
if ([string]::IsNullOrEmpty($kind)) {
  Write-Error "kind env var is required (ducklake | duckdb-file | memory)"; exit 92
}
if ($kind -notin @('ducklake', 'duckdb-file', 'memory')) {
  Write-Error "fatal: unknown kind='$kind' (expected: ducklake | duckdb-file | memory)"; exit 92
}

if ($schemaName -eq $dbName) {
  Write-Error @"
ERROR: schemaName ($schemaName) must differ from dbName ($dbName).
       DuckDB rejects 2-part identifiers like "$dbName".<table> as
       ambiguous when a catalog and a schema share a name.
"@
  exit 1
}

# Detect whether dataPath points at a remote object store. DuckLake accepts
# s3:// (AWS S3, SeaweedFS, MinIO, R2, GCS via the S3-interop endpoint) and
# azure:// / abfss:// when the matching DuckDB extension is loaded. For remote
# schemes we skip the local mkdir and emit the SQL needed to install
# httpfs/azure + a SECRET so the ATTACH below can read/write parquet.
$isRemote   = $false
$storageSql = ''
switch -Regex ($dataPath) {
  '^(s3|s3a|gs|r2)://' {
    $isRemote = $true
    $storageSql = "INSTALL httpfs; LOAD httpfs;"
    $s3Key = [Environment]::GetEnvironmentVariable('QOD_S3_ACCESS_KEY_ID')
    $s3Sec = [Environment]::GetEnvironmentVariable('QOD_S3_SECRET_ACCESS_KEY')
    if (-not [string]::IsNullOrEmpty($s3Key) -and -not [string]::IsNullOrEmpty($s3Sec)) {
      $ep = Env-Or 'QOD_S3_ENDPOINT' ''
      $ep = $ep -replace '^https?://', '' -replace '/$', ''
      $s3Region   = Env-Or 'QOD_S3_REGION'    'us-east-1'
      $s3UrlStyle = Env-Or 'QOD_S3_URL_STYLE' 'path'
      $s3UseSsl   = Env-Or 'QOD_S3_USE_SSL'   'true'
      $storageSql += @"

CREATE OR REPLACE SECRET quack_s3 (
  TYPE s3,
  KEY_ID '$s3Key',
  SECRET '$s3Sec',
  REGION '$s3Region',
  ENDPOINT '$ep',
  URL_STYLE '$s3UrlStyle',
  USE_SSL $s3UseSsl
);
"@
    }
    break
  }
  '^(az|azure|abfss)://' {
    $isRemote = $true
    $storageSql = "INSTALL azure; LOAD azure;"
    $azConn = [Environment]::GetEnvironmentVariable('QOD_AZURE_CONNECTION_STRING')
    if (-not [string]::IsNullOrEmpty($azConn)) {
      $storageSql += @"

CREATE OR REPLACE SECRET quack_azure (
  TYPE azure,
  CONNECTION_STRING '$azConn'
);
"@
    }
    break
  }
}

if ($kind -ne 'memory' -and -not $isRemote) {
  New-Item -ItemType Directory -Force -Path $dataPath | Out-Null
}

# Resolve the DuckDB CLI. $env:DUCKDB_BIN wins; otherwise the first `duckdb`
# on PATH (run-jar.ps1 prepends the self-installed .duckdb\<ver>\bin).
$duckdbBin = [Environment]::GetEnvironmentVariable('DUCKDB_BIN')
if ([string]::IsNullOrEmpty($duckdbBin)) {
  $cmd = Get-Command duckdb.exe -ErrorAction SilentlyContinue
  if ($null -eq $cmd) { $cmd = Get-Command duckdb -ErrorAction SilentlyContinue }
  if ($null -eq $cmd) { Write-Error "ERROR: duckdb not on PATH"; exit 1 }
  $duckdbBin = $cmd.Source
}

# DuckDB does NOT honour HTTP_PROXY/HTTPS_PROXY for `INSTALL <extension>`
# downloads - those go through libcurl via the SQL `SET http_proxy=...`
# setting only. Emit the SET line only when a proxy env var is present.
$proxyUrl = $null
foreach ($n in 'HTTP_PROXY', 'http_proxy', 'HTTPS_PROXY', 'https_proxy') {
  $v = [Environment]::GetEnvironmentVariable($n)
  if (-not [string]::IsNullOrEmpty($v)) { $proxyUrl = $v; break }
}
$proxySql = ''
if (-not [string]::IsNullOrEmpty($proxyUrl)) {
  $hostPort = $proxyUrl -replace '^https?://', '' -replace '/$', ''
  $proxySql = "SET http_proxy = '$hostPort';"
}

$dbInitSql    = [Environment]::GetEnvironmentVariable('dbInitSql')
$extraSetupSql = [Environment]::GetEnvironmentVariable('extraSetupSql')
$lockdownSql  = [Environment]::GetEnvironmentVariable('lockdownSql')

# Build init SQL piecemeal based on $kind so memory / duckdb-file skip the
# DuckLake-specific setup entirely. Mirrors spawn-quack-node.sh.
$sb = [System.Text.StringBuilder]::new()
if (-not [string]::IsNullOrEmpty($proxySql))  { [void]$sb.AppendLine($proxySql) }
if (-not [string]::IsNullOrEmpty($dbInitSql)) { [void]$sb.AppendLine($dbInitSql) }
[void]$sb.AppendLine("INSTALL quack;    LOAD quack;")

switch ($kind) {
  'ducklake' {
    [void]$sb.AppendLine("INSTALL ducklake; LOAD ducklake;")
    [void]$sb.AppendLine("INSTALL postgres; LOAD postgres;")
    if (-not [string]::IsNullOrEmpty($storageSql)) { [void]$sb.AppendLine($storageSql) }
    [void]$sb.AppendLine("ATTACH 'host=$pgHost port=$pgPort dbname=$dbName user=$pgUser password=$pgPassword' AS qod_init_pg (TYPE postgres);")
    [void]$sb.AppendLine("SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_lock(hashtext(''qod-ducklake-init:$dbName''))');")
    [void]$sb.AppendLine("ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=$dbName user=$pgUser password=$pgPassword' AS ""$dbName""")
    [void]$sb.AppendLine("  (DATA_PATH '$dataPath');")
    [void]$sb.AppendLine("SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_unlock(hashtext(''qod-ducklake-init:$dbName''))');")
    [void]$sb.AppendLine("DETACH qod_init_pg;")
    [void]$sb.AppendLine("USE ""$dbName"";")
    [void]$sb.AppendLine("CREATE SCHEMA IF NOT EXISTS ""$schemaName"";")
    [void]$sb.AppendLine("USE ""$dbName"".""$schemaName"";")
  }
  'duckdb-file' {
    [void]$sb.AppendLine("ATTACH '$dataPath' AS ""$dbName"";")
    [void]$sb.AppendLine("USE ""$dbName"";")
    [void]$sb.AppendLine("CREATE SCHEMA IF NOT EXISTS ""$schemaName"";")
    [void]$sb.AppendLine("USE ""$dbName"".""$schemaName"";")
  }
  'memory' {
    # nothing; DuckDB's built-in 'memory' catalog is the default
  }
}

if (-not [string]::IsNullOrEmpty($extraSetupSql)) { [void]$sb.AppendLine($extraSetupSql) }

# Deployment lockdown (lockdownSql env var, authored by NodeLockdown.scala).
# MUST run before quack_serve so the restrictions are in effect before the
# node serves any tenant statement.
if (-not [string]::IsNullOrEmpty($lockdownSql)) { [void]$sb.AppendLine($lockdownSql) }

[void]$sb.AppendLine("CALL quack_serve('quack:0.0.0.0:$Port', token := '$Token', allow_other_hostname := true);")

$initSql = $sb.ToString()

# Start duckdb with a redirected stdin we keep OPEN (the Windows analogue of
# the bash FIFO). stdout/stderr are inherited so node logs flow to the
# manager's console (LocalQuackBackend uses inheritIO()).
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName               = $duckdbBin
$psi.RedirectStandardInput  = $true
$psi.RedirectStandardOutput = $false
$psi.RedirectStandardError  = $false
$psi.UseShellExecute        = $false

$proc = [System.Diagnostics.Process]::Start($psi)
$proc.StandardInput.Write($initSql)
$proc.StandardInput.Flush()
# Do NOT close StandardInput: duckdb's main thread blocks reading it, which
# keeps the process (and the background quack_serve thread) alive until the
# manager terminates the tree.
$proc.WaitForExit()
exit $proc.ExitCode
