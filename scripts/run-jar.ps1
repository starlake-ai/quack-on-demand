<#
.SYNOPSIS
  Run the quack-on-demand manager from the assembly uber-jar on Windows.

.DESCRIPTION
  PowerShell port of the essential path in scripts/run-jar.sh. It:
    1. Anchors the working directory at the repo root.
    2. Self-installs the DuckDB CLI + libduckdb (windows-amd64) into
       .duckdb\<version>\{bin,lib} and prepends both to $env:PATH. On Windows
       the dynamic loader resolves dependent DLLs (quackwire.dll -> duckdb.dll)
       via PATH, so this replaces LD_LIBRARY_PATH / DYLD_LIBRARY_PATH.
    3. Resolves the assembly jar (newest under distrib\, or `sbt assembly`
       when BUILD=1 / none present).
    4. Probes Postgres when a psql client is available.
    5. Runs `java -Darrow.allocation.manager.type=Unsafe -jar <jar>`.

  All quack-on-demand settings come from QOD_* / PROXY_* env vars, same as the
  bash path. The Add-Opens JVM flags ship in the jar manifest (JEP 261).

  Env knobs:
    BUILD=1                run `sbt assembly` first (requires sbt + npm)
    DUCKDB_VERSION         pin a DuckDB release (default: derived from build.sbt)
    DUCKDB_CACHE_DIR       relocate the duckdb cache (default: <repo>\.duckdb)
    QOD_NATIVE_CLIENT      false = embedded DuckDB-JDBC path (no quackwire.dll)
    JAVA_HOME              uses `java` on PATH if unset
    JAVA_OPTS              extra JVM flags (e.g. -Xmx2g)
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$RepoDir    = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$DistribDir = Join-Path $RepoDir 'distrib'
Set-Location $RepoDir

# Anchor the DuckLake data path to the repo unless the caller set it.
if ([string]::IsNullOrEmpty($env:QOD_DUCKLAKE_DATA_PATH)) {
  $env:QOD_DUCKLAKE_DATA_PATH = Join-Path $RepoDir 'ducklake\data'
}

# ---- DuckDB CLI + libduckdb self-install (windows-amd64) ------------------
function Resolve-DuckDbVersion {
  if (-not [string]::IsNullOrEmpty($env:DUCKDB_VERSION)) { return $env:DUCKDB_VERSION }
  $buildSbt = Join-Path $RepoDir 'build.sbt'
  if (Test-Path $buildSbt) {
    $line = Select-String -Path $buildSbt -Pattern '^val libquackwireVersion' | Select-Object -First 1
    if ($line -and $line.Line -match '"([0-9]+\.[0-9]+\.[0-9]+)') { return $Matches[1] }
  }
  return '1.5.4'
}

function Ensure-DuckDb {
  $version = Resolve-DuckDbVersion
  $cacheRoot = if ([string]::IsNullOrEmpty($env:DUCKDB_CACHE_DIR)) { Join-Path $RepoDir '.duckdb' } else { $env:DUCKDB_CACHE_DIR }
  $cache  = Join-Path $cacheRoot $version
  $binDir = Join-Path $cache 'bin'
  $libDir = Join-Path $cache 'lib'

  # Always expose the cache to PATH (Windows resolves dependent DLLs via PATH).
  $env:PATH = "$binDir;$libDir;$env:PATH"

  $duckExe = Join-Path $binDir 'duckdb.exe'
  if (Test-Path $duckExe) {
    $ver = & $duckExe -version 2>$null | Select-Object -First 1
    if ($ver -match "v$([regex]::Escape($version))") {
      Write-Host "duckdb: cached v$version -> $duckExe"
      return
    }
  }

  Write-Host "duckdb: installing v$version (windows-amd64) into $cache"
  New-Item -ItemType Directory -Force -Path $binDir, $libDir | Out-Null
  $base = "https://github.com/duckdb/duckdb/releases/download/v$version"
  $tmp  = Join-Path ([System.IO.Path]::GetTempPath()) ("qod-duckdb-" + [System.Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  try {
    Write-Host "  fetching CLI from $base/duckdb_cli-windows-amd64.zip"
    Invoke-WebRequest -Uri "$base/duckdb_cli-windows-amd64.zip" -OutFile "$tmp\cli.zip"
    Expand-Archive -Path "$tmp\cli.zip" -DestinationPath $binDir -Force

    Write-Host "  fetching libduckdb from $base/libduckdb-windows-amd64.zip"
    Invoke-WebRequest -Uri "$base/libduckdb-windows-amd64.zip" -OutFile "$tmp\lib.zip"
    Expand-Archive -Path "$tmp\lib.zip" -DestinationPath $libDir -Force
    # duckdb.dll must sit next to the CLI too so `duckdb.exe` starts standalone.
    $srcDll = Join-Path $libDir 'duckdb.dll'
    if (Test-Path $srcDll) { Copy-Item $srcDll (Join-Path $binDir 'duckdb.dll') -Force }
  } finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  }
  Write-Host "duckdb: ready -> $duckExe (v$version)"
}
Ensure-DuckDb

# ---- Resolve jar ----
function Get-LocalJar {
  if (-not (Test-Path $DistribDir)) { return $null }
  Get-ChildItem $DistribDir -Filter 'quack-on-demand-assembly-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}

function Invoke-SbtAssembly {
  $sbt = Get-Command sbt -ErrorAction SilentlyContinue
  if ($null -eq $sbt) {
    Write-Error "sbt not on PATH and no jar in $DistribDir. Install sbt + npm, or drop a prebuilt assembly jar into distrib\."
    exit 1
  }
  Write-Host "running 'sbt assembly' (local build)..."
  & $sbt.Source assembly
  if ($LASTEXITCODE -ne 0) { Write-Error "sbt assembly failed"; exit 1 }
}

$Jar = $null
if ($env:BUILD -eq '1') {
  Write-Host "BUILD=1: local build"
  Invoke-SbtAssembly
  $Jar = Get-LocalJar
} else {
  $Jar = Get-LocalJar
  if ($null -eq $Jar) {
    Write-Host "no jar in distrib\; building from source..."
    Invoke-SbtAssembly
    $Jar = Get-LocalJar
  } else {
    Write-Host "using existing local jar: $Jar"
    Write-Host "  (set BUILD=1 to force a fresh build)"
  }
}
if ($null -eq $Jar) { Write-Error "ERROR: no assembly jar found in $DistribDir"; exit 1 }
Write-Host "jar: $Jar"

# ---- Resolve java ----
$JavaBin = if (-not [string]::IsNullOrEmpty($env:JAVA_HOME)) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (-not (Get-Command $JavaBin -ErrorAction SilentlyContinue)) {
  Write-Error "ERROR: java not found. Install JDK 21+ or set JAVA_HOME."; exit 1
}
Write-Host ("java: " + ((& $JavaBin -version 2>&1 | Select-Object -First 1)))

# ---- Optional Postgres probe (only when a psql client is present) ----
$pgHost   = if ($env:QOD_PG_HOST) { $env:QOD_PG_HOST } else { 'localhost' }
$pgPort   = if ($env:QOD_PG_PORT) { $env:QOD_PG_PORT } else { '5432' }
$pgUser   = if ($env:QOD_PG_USER) { $env:QOD_PG_USER } else { 'postgres' }
$pgAdmin  = if ($env:QOD_PG_ADMIN_DB) { $env:QOD_PG_ADMIN_DB } else { 'postgres' }
$pgPass   = if ($env:QOD_PG_PASSWORD) { $env:QOD_PG_PASSWORD } else { 'azizam' }
$psql = Get-Command psql -ErrorAction SilentlyContinue
if ($psql) {
  $env:PGPASSWORD = $pgPass
  & psql -h $pgHost -p $pgPort -U $pgUser -d $pgAdmin -tAc 'SELECT 1' *> $null
  if ($LASTEXITCODE -eq 0) {
    Write-Host "postgres: OK ($pgUser@${pgHost}:$pgPort)"
  } else {
    Write-Warning "cannot reach Postgres at $pgUser@${pgHost}:$pgPort; the manager will fail at startup if it cannot persist state."
  }
} else {
  Write-Host "postgres: psql not on PATH; skipping reachability probe (manager still needs a reachable PG 16+)."
}

# ---- Effective settings ----
$restHost = if ($env:QOD_ON_DEMAND_HOST) { $env:QOD_ON_DEMAND_HOST } else { '0.0.0.0' }
$restPort = if ($env:QOD_ON_DEMAND_PORT) { $env:QOD_ON_DEMAND_PORT } else { '20900' }
$flightPort = if ($env:PROXY_PORT) { $env:PROXY_PORT } else { '31338' }
Write-Host "REST + UI:  http://${restHost}:$restPort/ui/"
Write-Host "FlightSQL:  ${flightPort}  (TLS=$(if ($env:PROXY_TLS_ENABLED) { $env:PROXY_TLS_ENABLED } else { 'true' }))"
Write-Host "Runtime:    $(if ($env:QOD_RUNTIME_TYPE) { $env:QOD_RUNTIME_TYPE } else { 'local' })"
Write-Host ""

# ---- Run ----
$javaArgs = @('-Darrow.allocation.manager.type=Unsafe')
if (-not [string]::IsNullOrEmpty($env:JAVA_OPTS)) { $javaArgs += ($env:JAVA_OPTS -split '\s+') }
$javaArgs += @('-jar', $Jar)
& $JavaBin @javaArgs
exit $LASTEXITCODE
