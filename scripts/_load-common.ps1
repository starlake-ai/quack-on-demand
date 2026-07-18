<#
.SYNOPSIS
  Shared helpers for the load-*-dbgen.ps1 demo seeders (PowerShell twins of the
  load-*-dbgen.sh scripts). Dot-sourced by each loader; defines functions only,
  no global side effects.

.DESCRIPTION
  Each loader (TPC-H / TPC-DS / SSB) resolves the same configuration, provisions
  the target Postgres database, detects a remote DATA_PATH, runs an idempotency
  probe, and finally streams a generated SQL script to the DuckDB CLI. Only the
  generation body (which dbgen/dsdgen call + which tables are created) differs.

  DuckDB invocation. The bash loaders use `duckdb < file.sql`, but Windows
  PowerShell 5.1 has no `<` input redirection, so we run the script with
  `duckdb -c ".read '<file>'"` instead - `.read` executes the file (including
  the .print / .mode dot-commands) in one session and exits.

  Memory limit. The bash loaders auto-derive a DuckDB memory_limit from the
  cgroup cap so dbgen at high SF spills instead of getting OOM-killed inside a
  container. On Windows the native path has no cgroup, so we only honour an
  explicit $env:MEMORY_LIMIT and otherwise leave DuckDB's default.
#>

Set-StrictMode -Version Latest

# Resolve the DuckDB CLI: $env:DUCKDB_BIN wins (matches spawn-quack-node.ps1),
# then `duckdb(.exe)` on PATH (run-jar.ps1 prepends its self-installed cache).
function Get-DuckDbExe {
  if ($env:DUCKDB_BIN -and (Test-Path -LiteralPath $env:DUCKDB_BIN)) { return $env:DUCKDB_BIN }
  $cmd = Get-Command duckdb.exe, duckdb -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($cmd) { return $cmd.Source }
  Write-Error @"
duckdb CLI not on PATH (and DUCKDB_BIN unset).
Install DuckDB (https://duckdb.org/docs/installation/) or run the loader through
scripts/run-jar.ps1, which self-installs the pinned duckdb.exe and puts it on PATH.
"@
  exit 1
}

# Env-or-default helper (mirrors bash `${VAR:-default}`). Treats empty string as
# unset so `$env:FOO=''` falls through to the default, like bash.
function Get-EnvOr([string]$Name, [string]$Default) {
  $v = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrEmpty($v)) { return $Default }
  return $v
}

# Resolve the shared config block. $DefaultDbName / $DefaultSchema are the
# per-loader defaults (acme_tpch/tpch1, globex_tpcds/tpcds1, acme_tpch/ssb1).
function Resolve-LoadConfig {
  param(
    [Parameter(Mandatory)] [string]$DefaultDbName,
    [Parameter(Mandatory)] [string]$DefaultSchema
  )
  $repoDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

  $cfg = [ordered]@{
    PgHost      = Get-EnvOr 'PG_HOST' 'localhost'
    PgPort      = Get-EnvOr 'PG_PORT' '5432'
    PgUser      = Get-EnvOr 'PG_USER' 'postgres'
    PgPass      = Get-EnvOr 'PG_PASS' 'azizam'
    PgAdminDb   = Get-EnvOr 'PG_ADMIN_DB' 'postgres'
    DbName      = Get-EnvOr 'DB_NAME' $DefaultDbName
    Schema      = Get-EnvOr 'SCHEMA_NAME' $DefaultSchema
    Sf          = Get-EnvOr 'SF' '1'
    # DuckDB spill dir. dbgen/dsdgen at high SF spills here rather than the
    # cwd-relative ./.tmp/ default; create it up-front so the load never aborts
    # mid-flight with "Cannot open file .tmp/...".
    TempDir     = Get-EnvOr 'TEMP_DIR' (Join-Path $repoDir '.tmp\duckdb-load')
    # No cgroup on native Windows: honour an explicit cap, else DuckDB default.
    MemoryLimit = [Environment]::GetEnvironmentVariable('MEMORY_LIMIT')
    # DATA_PATH default is CWD-anchored (matches run-docker.sh + the bash loader)
    # so a native loader and a same-CWD docker run agree on the absolute string
    # DuckLake persists in its catalog.
    DataPath    = Get-EnvOr 'DATA_PATH' (Join-Path (Get-Location).Path "ducklake\$(Get-EnvOr 'DB_NAME' $DefaultDbName)")
    RepoDir     = $repoDir
  }
  New-Item -ItemType Directory -Force -Path $cfg.TempDir | Out-Null
  return [pscustomobject]$cfg
}

# Sanity: DuckDB rejects a 2-part identifier "<db>".<table> as ambiguous when a
# catalog and schema share a name, so SCHEMA_NAME must differ from DB_NAME.
function Assert-LoadSanity($cfg) {
  if ($cfg.Schema -eq $cfg.DbName) {
    Write-Error "SCHEMA_NAME ($($cfg.Schema)) must differ from DB_NAME ($($cfg.DbName)); DuckDB treats ""$($cfg.DbName)"".<table> as ambiguous when a catalog and schema share a name."
    exit 1
  }
}

# Provision the target Postgres database if psql is present and it is missing.
# Lets the loader run BEFORE the manager (the order run-jar.ps1 uses). Skipped
# silently when psql is absent - the ATTACH will surface the error itself.
function Initialize-PgDatabase($cfg) {
  $psql = Get-Command psql -ErrorAction SilentlyContinue
  if (-not $psql) { return }
  $env:PGPASSWORD = $cfg.PgPass
  $exists = (& psql -h $cfg.PgHost -p $cfg.PgPort -U $cfg.PgUser -d $cfg.PgAdminDb `
      -tAc "SELECT 1 FROM pg_database WHERE datname = '$($cfg.DbName)'" 2>$null | Out-String).Trim()
  if ($exists -ne '1') {
    Write-Host "load: creating Postgres database $($cfg.DbName)"
    & psql -h $cfg.PgHost -p $cfg.PgPort -U $cfg.PgUser -d $cfg.PgAdminDb `
      -tAc "CREATE DATABASE ""$($cfg.DbName)""" *> $null
    if ($LASTEXITCODE -ne 0) {
      Write-Warning "CREATE DATABASE $($cfg.DbName) failed; ATTACH below may fail"
    }
  }
}

# Detect a remote DATA_PATH (s3/gs/r2/azure) and emit the matching DuckDB
# extension + SECRET SQL. For local paths, create the dir and canonicalize (the
# exact string DuckLake persists). Returns @{ Sql; DataPath }.
function Resolve-Storage($cfg) {
  $dp = $cfg.DataPath
  $storageSql = ''
  $isRemote = $false

  switch -Regex ($dp) {
    '^(s3|s3a|gs|r2)://' {
      $isRemote = $true
      $storageSql = "INSTALL httpfs; LOAD httpfs;"
      if ($env:QOD_S3_ACCESS_KEY_ID -and $env:QOD_S3_SECRET_ACCESS_KEY) {
        $ep = $env:QOD_S3_ENDPOINT
        if ($ep) { $ep = $ep -replace '^https?://','' -replace '/$','' }
        $storageSql += @"

CREATE OR REPLACE SECRET quack_s3 (
  TYPE s3,
  KEY_ID '$($env:QOD_S3_ACCESS_KEY_ID)',
  SECRET '$($env:QOD_S3_SECRET_ACCESS_KEY)',
  REGION '$(Get-EnvOr 'QOD_S3_REGION' 'us-east-1')',
  ENDPOINT '$ep',
  URL_STYLE '$(Get-EnvOr 'QOD_S3_URL_STYLE' 'path')',
  USE_SSL $(Get-EnvOr 'QOD_S3_USE_SSL' 'true')
);
"@
      }
      break
    }
    '^(az|azure|abfss)://' {
      $isRemote = $true
      $storageSql = "INSTALL azure; LOAD azure;"
      if ($env:QOD_AZURE_CONNECTION_STRING) {
        $storageSql += @"

CREATE OR REPLACE SECRET quack_azure (
  TYPE azure,
  CONNECTION_STRING '$($env:QOD_AZURE_CONNECTION_STRING)'
);
"@
      }
      break
    }
  }

  if (-not $isRemote) {
    New-Item -ItemType Directory -Force -Path $dp | Out-Null
    $dp = (Resolve-Path -LiteralPath $dp).Path
  }
  return [pscustomobject]@{ Sql = $storageSql; DataPath = $dp }
}

# The DuckLake ATTACH statement shared by the probe and the load script.
function Get-AttachSql($cfg) {
  "ATTACH 'ducklake:postgres:host=$($cfg.PgHost) port=$($cfg.PgPort) dbname=$($cfg.DbName) user=$($cfg.PgUser) password=$($cfg.PgPass)' AS $($cfg.DbName)`n  (DATA_PATH '$($cfg.DataPath)');"
}

# Stream SQL to the DuckDB CLI via `-c ".read '<tempfile>'"`. Writes the temp
# file as UTF-8 WITHOUT a BOM (a leading BOM would make DuckDB choke on the
# first statement). Returns @{ ExitCode; Output; Error }.
#
# Two modes, both avoiding the PowerShell 5.1 native-stderr trap (`2>$null` /
# `2>&1` on a native exe wraps each stderr line in a terminating
# NativeCommandError under ErrorActionPreference=Stop):
#   -Quiet  : run under System.Diagnostics.Process with redirected stdout/stderr
#             so an expected error (e.g. probe's "table does not exist") is
#             captured as plain text, never thrown. Output/Error are buffered.
#   default : call the exe directly with NO redirection so it inherits the
#             console and streams .print progress live; check $LASTEXITCODE.
function Invoke-DuckDb {
  param([Parameter(Mandatory)] [string]$Sql, [switch]$Quiet)
  $duck = Get-DuckDbExe
  $tmp = Join-Path ([IO.Path]::GetTempPath()) ("qod-load-" + [Guid]::NewGuid().ToString('N') + ".sql")
  [IO.File]::WriteAllText($tmp, $Sql, (New-Object System.Text.UTF8Encoding($false)))
  $argLine = '-c ".read ''{0}''"' -f ($tmp -replace '\\','/')
  try {
    if ($Quiet) {
      $psi = New-Object System.Diagnostics.ProcessStartInfo
      $psi.FileName = $duck
      $psi.Arguments = $argLine
      $psi.RedirectStandardOutput = $true
      $psi.RedirectStandardError  = $true
      $psi.UseShellExecute = $false
      $psi.CreateNoWindow  = $true
      $p = [System.Diagnostics.Process]::Start($psi)
      $out = $p.StandardOutput.ReadToEnd()
      $err = $p.StandardError.ReadToEnd()
      $p.WaitForExit()
      return @{ ExitCode = $p.ExitCode; Output = $out; Error = $err }
    } else {
      # Direct, unredirected: DuckDB streams straight to the console. Pipe to
      # Out-Host so those lines go to the console and do NOT leak into this
      # function's return value (which must be just the hashtable). Native
      # non-zero exit does NOT trip ErrorActionPreference, so check it by hand.
      & $duck -c ".read '$($tmp -replace '\\','/')'" | Out-Host
      return @{ ExitCode = $LASTEXITCODE; Output = ''; Error = '' }
    }
  } finally {
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
  }
}

# Idempotency probe. Two steps mirroring the bash loaders:
#   1. count(*) on $ProbeTable - hits DuckLake metadata only. >0 => "loaded".
#   2. LIMIT 1 - forces an actual parquet open; catches the case where the
#      catalog claims rows but ./ducklake was wiped (stale metadata).
# Returns $true when the schema is already populated and reachable (=> skip).
function Test-AlreadyLoaded {
  param($cfg, [string]$StorageSql, [Parameter(Mandatory)] [string]$ProbeTable, [string]$ProbeColumn = '*')
  $attach = Get-AttachSql $cfg
  $countSql = @"
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
$StorageSql
$attach
.mode csv
.headers off
SELECT count(*) FROM $($cfg.DbName).$($cfg.Schema).$ProbeTable;
"@
  $res = Invoke-DuckDb -Sql $countSql -Quiet
  $existing = ($res.Output -replace '[^0-9]','')
  if (-not ($existing -match '^\d+$') -or [int64]$existing -le 0) { return $false }

  # count(*) says rows exist; verify the parquet files are actually reachable.
  $fileSql = @"
INSTALL ducklake; LOAD ducklake;
INSTALL postgres; LOAD postgres;
$StorageSql
$attach
.mode csv
.headers off
SELECT $ProbeColumn FROM $($cfg.DbName).$($cfg.Schema).$ProbeTable LIMIT 1;
"@
  $probe = Invoke-DuckDb -Sql $fileSql -Quiet
  if ($probe.ExitCode -ne 0 -and $probe.Output -match 'Cannot open file') {
    Write-Error @"
catalog claims $existing rows in $($cfg.DbName).$($cfg.Schema).$ProbeTable but the
parquet files it references are missing on disk. The Postgres catalog got out of
sync with the data files (most likely ./ducklake was wiped while pgdata was kept).
Re-create both from scratch (NUKE=1) or drop the stale schema:
  DROP SCHEMA $($cfg.DbName).$($cfg.Schema) CASCADE;
"@
    exit 1
  }
  Write-Host "already loaded: $($cfg.DbName).$($cfg.Schema).$ProbeTable has $existing rows; skipping."
  Write-Host "(delete the schema or override SCHEMA_NAME to force a reload)"
  return $true
}

# Print the standard config banner + spill/memory line.
function Write-LoadBanner($cfg, [string]$ScaleNote) {
  Write-Host "postgres:    $($cfg.PgUser)@$($cfg.PgHost):$($cfg.PgPort)/$($cfg.DbName)"
  Write-Host "catalog:     $($cfg.DbName).$($cfg.Schema)"
  Write-Host "data path:   $($cfg.DataPath)"
  Write-Host "scale:       SF=$($cfg.Sf)$(if ($ScaleNote) { " ($ScaleNote)" })"
  Write-Host "memory:      $(if ($cfg.MemoryLimit) { $cfg.MemoryLimit } else { 'DuckDB default' }) (spill dir: $($cfg.TempDir))"
  Write-Host ""
}

# The SET temp_directory + optional memory_limit preamble every generation
# script opens with.
function Get-PreambleSql($cfg) {
  # Mirror of _load-common.sh: the seed loaders can share the console with the
  # manager, and duckdb's carriage-return progress bar overprints its log
  # lines; plain line output interleaves cleanly.
  $sql = "SET enable_progress_bar = false;`n"
  $sql += "SET temp_directory='$($cfg.TempDir -replace '\\','/')';`n"
  if ($cfg.MemoryLimit) { $sql += "SET memory_limit='$($cfg.MemoryLimit)';`n" }
  return $sql
}
