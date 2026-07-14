<#
.SYNOPSIS
  Run the quack-on-demand manager from the assembly uber-jar on Windows.
  PowerShell twin of scripts/run-jar.sh, at feature parity.

.DESCRIPTION
  Two modes, picked via $env:BUILD:
    BUILD=0 (default) - resolve the jar from Maven Central
                        (ai.starlake:quack-on-demand_3:<QOD_VERSION>), cache it
                        under $JAR_CACHE_DIR (sha1-checked), run `java -jar`.
                        Falls back to a local distrib\ jar / `sbt assembly` when
                        the artifact isn't published yet.
    BUILD=1           - run `sbt assembly` from this checkout (bootstrapping sbt
                        into .sbt-bootstrap\ if it isn't on PATH) and run the
                        freshly-built jar in distrib\.

  Boot extras (parity with run-jar.sh):
    - DuckDB CLI + libduckdb self-install (windows-amd64) onto PATH
    - Postgres reachability probe + PG16 version gate (when psql present)
    - NUKE=1 teardown (drop control-plane + demo tenant-db DBs, wipe local dirs)
    - idempotent CREATE DATABASE of the control-plane DB
    - optional TPC-H / TPC-DS / SSB demo seeds (LOAD_*), run in the background
      via the load-*-dbgen.ps1 loaders before the JVM starts
    - port preflight (REST/Flight in-use + orphan node-range detection)

  On Windows the dynamic loader resolves dependent DLLs (quackwire.dll ->
  duckdb.dll) via PATH, so prepending the duckdb cache bin\+lib\ replaces
  LD_LIBRARY_PATH / DYLD_LIBRARY_PATH. All quack-on-demand settings come from
  QOD_* / PROXY_* env vars; the Add-Opens JVM flags ship in the jar manifest.

  Env knobs (superset shared with run-jar.sh):
    BUILD=1                run `sbt assembly` first (requires sbt + npm; sbt is
                           auto-bootstrapped if missing)
    QOD_VERSION            artifact version to download (default = latest release;
                           `latest-snapshot` = newest Central snapshot; ignored
                           when BUILD=1)
    JAR_CACHE_DIR          download cache (default $HOME\.cache\quack-on-demand)
    NUKE=1                 wipe local state (control-plane DB, demo tenant-dbs,
                           ducklake\, state\, certs\) before booting. Irreversible.
    LOAD_TPCH=N            seed TPC-H SF=N into acme/acme_tpch (schema tpch1)
    LOAD_TPCDS=N           seed TPC-DS SF=N into globex/globex_tpcds (tpcds1)
    LOAD_SSB=N             seed SSB SF=N into acme/acme_tpch (schema ssb1)
    LOAD_TPC=N             legacy shortcut for LOAD_TPCH=LOAD_TPCDS=LOAD_SSB=N
    DEMO=full|minimal      which bundled demo manifest a LOAD_* boot imports
                           (minimal = acme only, one pool, single dual node)
    DUCKDB_VERSION         pin a DuckDB release (default: derived from build.sbt)
    DUCKDB_CACHE_DIR       relocate the duckdb cache (default: <repo>\.duckdb)
    QOD_NATIVE_CLIENT      false = embedded DuckDB-JDBC path (no quackwire.dll)
    JAVA_HOME              uses `java` on PATH if unset
    JAVA_OPTS              extra JVM flags (e.g. -Xmx2g)

.EXAMPLE
  .\scripts\run-jar.ps1                                  # latest release
.EXAMPLE
  $env:QOD_VERSION='latest-snapshot'; .\scripts\run-jar.ps1
.EXAMPLE
  $env:NUKE='1'; $env:LOAD_TPCH='1'; .\scripts\run-jar.ps1
.EXAMPLE
  $env:NUKE='1'; $env:DEMO='minimal'; $env:LOAD_TPCH='1'; .\scripts\run-jar.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$RepoDir    = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$DistribDir = Join-Path $RepoDir 'distrib'
Set-Location $RepoDir

$GroupPath   = 'ai/starlake'
$Artifact    = 'quack-on-demand_3'
$JarCacheDir = if ($env:JAR_CACHE_DIR) { $env:JAR_CACHE_DIR } else { Join-Path $HOME '.cache\quack-on-demand' }

# Anchor the DuckLake data path to the repo unless the caller set it. The LOAD_*
# blocks below derive per-tenant-db paths as <dir-of-this>\<tenant-db>.
if ([string]::IsNullOrEmpty($env:QOD_DUCKLAKE_DATA_PATH)) {
  $env:QOD_DUCKLAKE_DATA_PATH = Join-Path $RepoDir 'ducklake\data'
}

function Get-EnvOr([string]$Name, [string]$Default) {
  $v = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrEmpty($v)) { return $Default }
  return $v
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

function Install-DuckDb {
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
      # Expose to the loaders + JNI client explicitly.
      $env:DUCKDB_BIN = $duckExe
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
  $env:DUCKDB_BIN = $duckExe
}
Install-DuckDb

# ---- Demo seed controls (parity with run-jar.sh) -------------------------
$LoadTpch  = Get-EnvOr 'LOAD_TPCH'  (Get-EnvOr 'LOAD_TPC' '')
$LoadTpcds = Get-EnvOr 'LOAD_TPCDS' (Get-EnvOr 'LOAD_TPC' '')
$LoadSsb   = Get-EnvOr 'LOAD_SSB'   (Get-EnvOr 'LOAD_TPC' '')
foreach ($pair in @(@('LOAD_TPCH',$LoadTpch), @('LOAD_TPCDS',$LoadTpcds), @('LOAD_SSB',$LoadSsb))) {
  if ($pair[1] -and ($pair[1] -notmatch '^[0-9]+$' -or [int]$pair[1] -lt 1)) {
    Write-Error "$($pair[0]) must be a positive integer scale factor (got: '$($pair[1])')."
    exit 1
  }
}

$DemoExplicit = -not [string]::IsNullOrEmpty((Get-EnvOr 'DEMO' ''))
$Demo = Get-EnvOr 'DEMO' 'full'
if ($Demo -notin @('full', 'minimal')) {
  Write-Error "DEMO must be 'full' or 'minimal' (got: '$Demo')."
  exit 1
}
if ($Demo -eq 'minimal' -and $LoadTpcds) {
  Write-Warning "DEMO=minimal has no globex tenant; skipping the TPC-DS loader."
  $LoadTpcds = ''
}
# Checked AFTER the TPC-DS skip so a TPCDS-only minimal boot loudly announces
# that no demo seed remains and bootstrap will not run.
if ($DemoExplicit -and -not ($LoadTpch -or $LoadTpcds -or $LoadSsb)) {
  Write-Warning "DEMO is set but no LOAD_* flag is; bootstrap only runs with a demo seed."
}

# ---- sbt bootstrap (download into .sbt-bootstrap\ when not on PATH) --------
$SbtBootstrapVersion = Get-EnvOr 'SBT_BOOTSTRAP_VERSION' '1.12.11'
function Resolve-Sbt {
  $sbt = Get-Command sbt, sbt.bat -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($sbt) { return $sbt.Source }
  $root   = Join-Path $RepoDir '.sbt-bootstrap'
  $sbtDir = Join-Path $root "sbt-$SbtBootstrapVersion"
  $sbtBat = Join-Path $sbtDir 'sbt\bin\sbt.bat'
  if (Test-Path $sbtBat) { Write-Host "using bootstrapped sbt: $sbtBat"; return $sbtBat }
  Write-Host "sbt not on PATH; downloading sbt-$SbtBootstrapVersion into $root\ ..."
  New-Item -ItemType Directory -Force -Path $sbtDir | Out-Null
  $zip = Join-Path $root "sbt-$SbtBootstrapVersion.zip"
  Invoke-WebRequest -Uri "https://github.com/sbt/sbt/releases/download/v$SbtBootstrapVersion/sbt-$SbtBootstrapVersion.zip" -OutFile $zip
  Expand-Archive -Path $zip -DestinationPath $sbtDir -Force
  Remove-Item -Force $zip -ErrorAction SilentlyContinue
  if (-not (Test-Path $sbtBat)) { Write-Error "bootstrapped sbt did not produce $sbtBat"; exit 1 }
  Write-Host "bootstrapped sbt -> $sbtBat"
  return $sbtBat
}

# ---- Resolve jar ----
function Get-LocalJar {
  if (-not (Test-Path $DistribDir)) { return $null }
  Get-ChildItem $DistribDir -Filter 'quack-on-demand-assembly-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}

function Invoke-Build {
  $sbt = Resolve-Sbt
  Write-Host "running 'sbt assembly' (local build)..."
  # Note: unlike run-jar.sh, we don't rebuild the Windows quackwire.dll here (it
  # needs MSVC/CMake and is gated behind QOD_WITH_WINDOWS_NATIVE). sbt assembly
  # resolves the native classifiers already staged in the repo / Central. To
  # build the dll, see the "Run on Windows" section of the install guide.
  & $sbt assembly
  if ($LASTEXITCODE -ne 0) { Write-Error "sbt assembly failed"; exit 1 }
  $jar = Get-LocalJar
  if (-not $jar) { Write-Error "sbt assembly did not produce a jar in $DistribDir"; exit 1 }
  return $jar
}

function Use-LocalJarOrBuild {
  $jar = Get-LocalJar
  if ($jar) {
    Write-Host "using existing local jar: $jar"
    Write-Host "  (set BUILD=1 to force a fresh build)"
    return $jar
  }
  return (Invoke-Build)
}

function Get-LocalSnapshotVersion {
  $vf = Join-Path $RepoDir 'version.sbt'
  if (-not (Test-Path $vf)) { return $null }
  $line = Select-String -Path $vf -Pattern 'ThisBuild\s*/\s*version\s*:=' | Select-Object -First 1
  if ($line -and $line.Line -match '"([^"]+)"') { return $Matches[1] }
  return $null
}

function Get-MavenMetaValue([string]$Url, [string]$Tag) {
  try {
    $xml = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 15 -ErrorAction Stop
    if ($xml.Content -match "<$Tag>([^<]+)</$Tag>") { return $Matches[1] }
  } catch { }
  return $null
}

# Returns $true when the cached jar's sha1 matches the remote sidecar.
function Test-JarCurrent([string]$Jar, [string]$Sha1Url) {
  if (-not (Test-Path $Jar)) { return $false }
  try {
    $remote = (Invoke-WebRequest -Uri $Sha1Url -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop).Content
    $remote = ($remote -replace '\s','').Substring(0, 40).ToLower()
  } catch { return $false }
  $local = (Get-FileHash -Algorithm SHA1 -LiteralPath $Jar).Hash.ToLower()
  return ($local -eq $remote)
}

$Jar = $null
if ($env:BUILD -eq '1') {
  Write-Host "BUILD=1: local build"
  $Jar = Invoke-Build
} elseif ($env:LOCAL -eq '1') {
  Write-Host "LOCAL=1: newest distrib\ jar, no rebuild, no Central lookup"
  $Jar = Use-LocalJarOrBuild
} else {
  New-Item -ItemType Directory -Force -Path $JarCacheDir | Out-Null
  $version = Get-EnvOr 'QOD_VERSION' 'latest'
  switch ($version) {
    'latest' {
      $version = Get-MavenMetaValue "https://repo1.maven.org/maven2/$GroupPath/$Artifact/maven-metadata.xml" 'release'
      if ($version) {
        Write-Host "resolved latest release: $version"
      } else {
        $version = Get-LocalSnapshotVersion
        if ($version -and $version.EndsWith('-SNAPSHOT')) {
          Write-Host "no release on Maven Central; trying snapshot $version (from version.sbt)"
        } else {
          Write-Warning "no release on Maven Central and no snapshot detected; falling back to local jar / build."
          $version = ''
        }
      }
    }
    'latest-snapshot' {
      $version = Get-MavenMetaValue "https://central.sonatype.com/repository/maven-snapshots/$GroupPath/$Artifact/maven-metadata.xml" 'latest'
      if (-not $version) { $version = Get-LocalSnapshotVersion }
      if ($version) { Write-Host "resolved latest snapshot: $version" }
      else { Write-Warning "no snapshot found and no version.sbt detected; falling back to local jar / build." }
    }
  }

  if ([string]::IsNullOrEmpty($version)) {
    $Jar = Use-LocalJarOrBuild
  } elseif ($version.EndsWith('-SNAPSHOT')) {
    $baseUrl = "https://central.sonatype.com/repository/maven-snapshots/$GroupPath/$Artifact/$version"
    $Jar     = Join-Path $JarCacheDir "$Artifact-$version.jar"
    $jarUrl  = "$baseUrl/$Artifact-$version.jar"
    if (Test-JarCurrent $Jar "$jarUrl.sha1") {
      Write-Host "snapshot $version cached (sha1 matches Central); skipping download."
    } else {
      Write-Host "downloading snapshot $version..."
      try { Invoke-WebRequest -Uri $jarUrl -OutFile $Jar -TimeoutSec 600 -ErrorAction Stop }
      catch { Write-Warning "snapshot download failed; falling back to local jar / build."; $Jar = Use-LocalJarOrBuild }
    }
  } else {
    $baseUrl = "https://repo1.maven.org/maven2/$GroupPath/$Artifact/$version"
    $Jar     = Join-Path $JarCacheDir "$Artifact-$version.jar"
    $jarUrl  = "$baseUrl/$Artifact-$version.jar"
    if (Test-JarCurrent $Jar "$jarUrl.sha1") {
      Write-Host "release $version cached (sha1 matches Central); skipping download."
    } else {
      Write-Host "downloading $version from Maven Central..."
      try { Invoke-WebRequest -Uri $jarUrl -OutFile $Jar -TimeoutSec 600 -ErrorAction Stop }
      catch { Write-Warning "release download failed; falling back to local jar / build."; $Jar = Use-LocalJarOrBuild }
    }
  }
}
if (-not $Jar -or -not (Test-Path $Jar)) { Write-Error "no assembly jar available"; exit 1 }
Write-Host "jar: $Jar"

# ---- Resolve java ----
$JavaBin = if (-not [string]::IsNullOrEmpty($env:JAVA_HOME)) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (-not (Get-Command $JavaBin -ErrorAction SilentlyContinue)) {
  Write-Error "java not found. Install JDK 21+ or set JAVA_HOME."; exit 1
}
# `java -version` prints to stderr; the 2>&1 merge would wrap it in a terminating
# NativeCommandError under ErrorActionPreference=Stop, so drop to Continue for
# just this capture.
$javaVer = & { $ErrorActionPreference = 'Continue'; (& $JavaBin -version 2>&1 | Select-Object -First 1) }
Write-Host ("java: " + $javaVer)

# ---- Postgres probe + PG16 gate (only when psql present) ----
$stateMode = Get-EnvOr 'QOD_STATE_STORAGE' 'postgres'
$pgHost    = Get-EnvOr 'QOD_PG_HOST' 'localhost'
$pgPort    = Get-EnvOr 'QOD_PG_PORT' '5432'
$pgUser    = Get-EnvOr 'QOD_PG_USER' 'postgres'
$pgPass    = Get-EnvOr 'QOD_PG_PASSWORD' 'azizam'
$pgAdminDb = Get-EnvOr 'QOD_PG_ADMIN_DB' 'postgres'
$pgDbName  = Get-EnvOr 'QOD_PG_DBNAME' 'qod'
$pgReachable = $false

# Run a psql -tAc query; returns trimmed stdout ('' on any failure). Uses a
# Process object to dodge the PS 5.1 native-stderr-wrapping trap.
function Invoke-Psql([string]$Db, [string]$Sql) {
  $psql = Get-Command psql -ErrorAction SilentlyContinue
  if (-not $psql) { return $null }
  $env:PGPASSWORD = $pgPass
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName  = $psql.Source
  $psi.Arguments = "-h $pgHost -p $pgPort -U $pgUser -d $Db -tAc `"$Sql`""
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError  = $true
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow  = $true
  $p = [System.Diagnostics.Process]::Start($psi)
  $out = $p.StandardOutput.ReadToEnd()
  $null = $p.StandardError.ReadToEnd()
  $p.WaitForExit()
  if ($p.ExitCode -ne 0) { return $null }
  return $out.Trim()
}

if ($stateMode -eq 'postgres' -and (Get-Command psql -ErrorAction SilentlyContinue)) {
  if ((Invoke-Psql $pgAdminDb 'SELECT 1') -eq '1') {
    $pgMajor = Invoke-Psql $pgAdminDb 'SHOW server_version_num'
    if (-not $pgMajor -or [int]$pgMajor -lt 160000) {
      Write-Error "Postgres at $pgUser@${pgHost}:$pgPort reports server_version_num=$pgMajor (need >= 160000 / PG 16). This project requires Postgres 16+."
      exit 1
    }
    Write-Host "postgres: OK ($pgUser@${pgHost}:$pgPort, server_version_num=$pgMajor)  [state storage]"
    $pgReachable = $true
  } else {
    Write-Warning "cannot reach Postgres at $pgUser@${pgHost}:$pgPort; the manager will fail at startup if it cannot persist state."
  }
} elseif ($stateMode -eq 'postgres') {
  Write-Host "postgres: psql not on PATH; skipping reachability probe (manager still needs a reachable PG 16+)."
}

# ---- Optional nuke: tear down + wipe before starting ----
if ($env:NUKE -eq '1') {
  Write-Host "NUKE=1: tearing down state..."
  $restPort = Get-EnvOr 'QOD_ON_DEMAND_PORT' '20900'
  try {
    Invoke-WebRequest -Uri "http://localhost:$restPort/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop | Out-Null
    Write-Host "  stopping running manager"
    & (Join-Path $PSScriptRoot 'stop-jar.ps1') *> $null
  } catch { }
  if ($stateMode -eq 'postgres' -and $pgReachable -and $pgDbName) {
    Write-Host "  dropping Postgres database: $pgDbName (control plane)"
    Invoke-Psql $pgAdminDb "DROP DATABASE IF EXISTS ""$pgDbName"" WITH (FORCE)" | Out-Null
    foreach ($demo in @('acme_tpch','globex_tpcds')) {
      Write-Host "  dropping Postgres database: $demo (demo tenant-db)"
      Invoke-Psql $pgAdminDb "DROP DATABASE IF EXISTS ""$demo"" WITH (FORCE)" | Out-Null
    }
  } elseif ($stateMode -eq 'postgres') {
    Write-Warning "  Postgres unreachable; skipping DB drop (local wipes still proceed)"
  }
  foreach ($d in @((Join-Path $RepoDir 'ducklake'), (Join-Path $RepoDir 'state'), (Join-Path $RepoDir 'certs'))) {
    if (Test-Path $d) { Write-Host "  wiping $d"; Remove-Item -Recurse -Force $d }
  }
}

# ---- Bootstrap control-plane DB (idempotent) ----
if ($stateMode -eq 'postgres' -and $pgReachable -and $pgDbName) {
  if ((Invoke-Psql $pgAdminDb "SELECT 1 FROM pg_database WHERE datname='$pgDbName'") -eq '1') {
    Write-Host "catalog db: '$pgDbName' already exists"
  } else {
    Write-Host "catalog db: creating '$pgDbName' in $pgAdminDb..."
    Invoke-Psql $pgAdminDb "CREATE DATABASE ""$pgDbName""" | Out-Null
  }
}

# ---- Optional: TPC demo loaders (background, before the JVM starts) ----
if ($LoadTpch -or $LoadTpcds -or $LoadSsb) {
  if ([string]::IsNullOrEmpty($env:QOD_BOOTSTRAP_YAML)) {
    $demoManifest = if ($Demo -eq 'minimal') { 'bootstrap-demo-minimal.yaml' } else { 'bootstrap-demo.yaml' }
    $env:QOD_BOOTSTRAP_YAML = Join-Path $RepoDir "src\main\resources\$demoManifest"
  }
  Write-Host "load-tpc: profile=$Demo, TPC-H=$(if($LoadTpch){$LoadTpch}else{'skip'}), TPC-DS=$(if($LoadTpcds){$LoadTpcds}else{'skip'}), SSB=$(if($LoadSsb){$LoadSsb}else{'skip'}), bootstrap=$($env:QOD_BOOTSTRAP_YAML)"
  $ducklakeParent = Split-Path -Parent $env:QOD_DUCKLAKE_DATA_PATH

  # Launch one loader in the background. Sets the loader's env-var contract, then
  # Start-Process (which snapshots the env synchronously) so sequential launches
  # with different DB_NAME/SF don't race. PATH + DUCKDB_BIN are inherited.
  function Start-Loader([string]$Script, [string]$DbName, [string]$Schema, [string]$Sf, [string]$DataPath) {
    $env:PG_HOST=$pgHost; $env:PG_PORT=$pgPort; $env:PG_USER=$pgUser; $env:PG_PASS=$pgPass; $env:PG_ADMIN_DB=$pgAdminDb
    $env:DB_NAME=$DbName; $env:SCHEMA_NAME=$Schema; $env:SF=$Sf; $env:DATA_PATH=$DataPath
    Start-Process -FilePath 'powershell.exe' `
      -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot $Script)) `
      -NoNewWindow | Out-Null
  }

  if ($LoadTpch)  { Start-Loader 'load-tpch-dbgen.ps1'  'acme_tpch'    'tpch1'  $LoadTpch  (Join-Path $ducklakeParent 'acme_tpch') }
  if ($LoadTpcds) { Start-Loader 'load-tpcds-dbgen.ps1' 'globex_tpcds' 'tpcds1' $LoadTpcds (Join-Path $ducklakeParent 'globex_tpcds') }
  if ($LoadSsb)   { Start-Loader 'load-ssb-dbgen.ps1'   'acme_tpch'    'ssb1'   $LoadSsb   (Join-Path $ducklakeParent 'acme_tpch') }
  # Clear the loader-scoped vars so they don't leak into the JVM's environment.
  foreach ($n in 'DB_NAME','SCHEMA_NAME','SF','DATA_PATH','PG_HOST','PG_PORT','PG_USER','PG_PASS','PG_ADMIN_DB') {
    Remove-Item "Env:$n" -ErrorAction SilentlyContinue
  }
}

# ---- Effective settings ----
$restHost   = Get-EnvOr 'QOD_ON_DEMAND_HOST' '0.0.0.0'
$restPort   = Get-EnvOr 'QOD_ON_DEMAND_PORT' '20900'
$flightHost = Get-EnvOr 'PROXY_HOST' '0.0.0.0'
$flightPort = Get-EnvOr 'PROXY_PORT' '31338'
Write-Host "REST + UI:  http://${restHost}:$restPort/ui/"
Write-Host "FlightSQL:  ${flightHost}:$flightPort  (TLS=$(Get-EnvOr 'PROXY_TLS_ENABLED' 'true'))"
Write-Host "State:      $stateMode"
Write-Host "Runtime:    $(Get-EnvOr 'QOD_RUNTIME_TYPE' 'local')"
Write-Host ""

# ---- Port preflight ----
# Abort if the REST/Flight ports are held; WARN on orphan listeners inside the
# node port range (stale Quack nodes there cause silent 'Authentication failed'
# at query time because the port allocator does not probe the OS).
function Get-PortListeners([int]$Port) {
  try { return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop) }
  catch { return @() }
}
if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
  foreach ($p in @([int]$restPort, [int]$flightPort)) {
    # Wrap in @() at the call site: PowerShell unrolls a function's 1-element
    # array return, so without this $held would be a bare CimInstance (no .Count).
    $held = @(Get-PortListeners $p)
    if ($held.Count -gt 0) {
      $pids = ($held | Select-Object -ExpandProperty OwningProcess -Unique) -join ', '
      Write-Error "port $p already in use (pid(s): $pids). Stop the holder (.\scripts\stop-jar.ps1) and retry."
      exit 1
    }
  }
  $minPort = [int](Get-EnvOr 'QOD_MIN_PORT' '21900')
  $maxPort = [int](Get-EnvOr 'QOD_MAX_PORT' '22500')
  try {
    $orphans = @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
      Where-Object { $_.LocalPort -ge $minPort -and $_.LocalPort -le $maxPort })
  } catch { $orphans = @() }
  if ($orphans.Count -gt 0) {
    $desc = ($orphans | ForEach-Object { "  :$($_.LocalPort) pid=$($_.OwningProcess)" } | Sort-Object -Unique) -join "`n"
    Write-Error @"
orphan listener(s) inside the configured node port range [$minPort, $maxPort]:
$desc
These are almost certainly stale Quack node processes from a prior run. The
manager's port allocator does not probe the OS - re-leasing one of these ports
leads to silent 'Authentication failed' errors at query time. Kill them and
retry (.\scripts\stop-jar.ps1).
"@
    exit 1
  }
} else {
  Write-Warning "Get-NetTCPConnection unavailable; skipping port preflight."
}

# ---- Run ----
# The assembly jar carries Add-Opens in its manifest (JEP 261) so Arrow's unsafe
# allocator works on Java 17+ without extra flags. The system property pins the
# allocator - without it Arrow picks netty and crashes (NoSuchFieldError:
# chunkSize).
$javaArgs = @('-Darrow.allocation.manager.type=Unsafe')
if (-not [string]::IsNullOrEmpty($env:JAVA_OPTS)) { $javaArgs += ($env:JAVA_OPTS -split '\s+') }
$javaArgs += @('-jar', $Jar)
& $JavaBin @javaArgs
exit $LASTEXITCODE
