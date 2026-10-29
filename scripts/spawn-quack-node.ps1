<#
.SYNOPSIS
  Spawn one Quack node on Windows - invoked by LocalQuackBackend.

.DESCRIPTION
  PowerShell mirror of scripts/spawn-quack-node.sh. LocalQuackBackend wraps
  this as `powershell.exe -File spawn-quack-node.ps1 <port> <manager-marker>`. The marker
  (LocalQuackBackend.managerMarker: REST port plus a per-boot id) names the node directory
  `quack-node.<marker>.<id>`, so stop-jar.ps1 stops only its own manager's nodes.

  The non-sensitive metastore connection details arrive as environment
  variables that LocalQuackBackend.start() sets from CreatePoolRequest.metastore.
  The env-var keys match the UI form field names verbatim:

    pgHost pgPort pgUser               (Postgres for DuckLake catalog)
    dbName                             (Postgres DB; also the DuckDB catalog alias unless
                                        catalogAlias is set)
    catalogAlias                       (optional DuckDB catalog alias the database is ATTACHed
                                        under; defaults to $dbName. Branch catalogs set it to
                                        their parent's alias, see TenantDb.catalogAlias)
    schemaName                         (DuckLake schema under the alias; default `main`.
                                        MUST differ from the alias or 2-part identifiers
                                        like "$dbName"."customer" resolve as ambiguous.)
    dataPath                           (DuckLake data files directory)
    encrypted                          (`true` for an encrypted database, stamped by the manager)

  These metastore keys are TenantDb.NodeEnvKeys: every launcher passes them and no other metastore
  key (a metastore is tenant-admin input), so a metastore key this script starts reading must be
  added there too (NodeMetastoreSpec checks this script reads each of them). The launcher sets the
  rest itself:

    kind                               (ducklake | duckdb-file | memory)
    lockdownSql                        (optional engine lockdown SQL, NodeLockdown.scala; runs
                                        last, before qod-node serves)

  The node process is qod-node (native/qod-node), not the DuckDB CLI: it links an official DuckDB
  release library, runs the init SQL this script assembles, and serves over quack.
  $env:QOD_NODE_BIN names it (an absolute path the launcher sets); otherwise the first `qod-node`
  on PATH.

  Sensitive values (pgPassword, encryptionKey, extraSetupSql, objectStoreSql, the node token, the
  node key, the init marker nonce, the tenant-db's and the pool's initSql (tenantDbInitSql and
  tenantPoolInitSql, or their ...Trusted twins for a superuser's or the manager's text) and the
  object-store credentials, see $SecretKeys below) NEVER arrive through the
  environment or the command line: another process of the same user can read both. They arrive as
  one file per key in the directory $env:QOD_SECRETS_DIR, which LocalQuackBackend creates with an
  ACL granting only the current user (QOD_SECRETS_DIR_EPHEMERAL=1). This script reads the keys it
  assembles into the init SQL and deletes each file right after; qod-node reads its own keys (the
  node token and the node key) and removes the directory, which this script also removes when it
  exits. A key still present in the environment is ignored with a warning.

  The init SQL reaches qod-node on its stdin (--init -), UTF-8 bytes down a pipe it reads once to
  the end: never on disk, and nothing else reaches the node's own connection through it. qod-node
  runs it statement by statement and carries on past a failing one, as a node always has (an
  unreachable federated source, an offline INSTALL). What must not fail silently it does itself,
  strictly, around that SQL: the secret directory first, then loading quack and quack_serve with the
  node token. It ends when this script ends (--parent-pid: it watches this process from its start)
  or on a console stop, at once while still starting. A normal stop terminates the whole process
  tree (LocalQuackBackend uses `taskkill /T` from stop()/cleanup()).

  The Postgres `CREATE DATABASE` step the bash script performs as a fallback is
  intentionally omitted here: the manager JVM already provisions the tenant-db
  (DbAdmin.createDatabase via PoolSupervisor.createTenantDb + DuckLakeInitializer).

  qod-node runs in an empty directory private to the current user, created here per node: DuckDB
  2.0 replacement-scans an unquoted dotted name as a file (`SELECT * FROM x.csv` reads .\x.csv),
  and an unquoted name cannot hold a path separator, so the working directory is the one place that
  form reaches. Every relative path the node relies on (dataPath, the qod-node binary, the secrets
  and spill directories) is made absolute against the directory this script started in. The node
  directory is removed when qod-node exits; `taskkill /T /F` gives this script no chance to, so
  each spawn also removes the node directories whose owning script is gone. Mirrors
  spawn-quack-node.sh.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true, Position = 0)] [int]    $Port,
  [Parameter(Mandatory = $false, Position = 1)] [string] $Marker = ''
)

$ErrorActionPreference = 'Stop'

# The directory relative paths resolve against (the manager's working directory, where the node
# itself used to run).
$startDir = (Get-Location).ProviderPath

# Keep in sync with NodeSecretFiles.Keys (Scala), SECRET_KEYS in spawn-quack-node.sh and
# _SECRET_KEYS in cli/src/qod_cli/fleet_join.py; NodeSecretFilesSpec pins all four.
$SecretKeys = @('pgPassword', 'encryptionKey', 'extraSetupSql', 'objectStoreSql',
  'QOD_NODE_TOKEN', 'QOD_NODE_AUTHZ_KEY', 'tenantDbInitSql', 'tenantDbInitSqlTrusted',
  'tenantPoolInitSql', 'tenantPoolInitSqlTrusted', 'QOD_INIT_NONCE',
  'QOD_S3_ACCESS_KEY_ID', 'QOD_S3_SECRET_ACCESS_KEY', 'QOD_AZURE_CONNECTION_STRING',
  'QOD_GCS_KEY_ID', 'QOD_GCS_SECRET')
# The keys qod-node reads from $QOD_SECRETS_DIR itself (the node token, the node key and the init
# marker nonce); this script reads none of them but the nonce, which it never deletes (below).
$QodNodeKeys = @('QOD_NODE_TOKEN', 'QOD_NODE_AUTHZ_KEY', 'QOD_INIT_NONCE')
# Deletes a variable from this process's environment, so the qod-node child never inherits it.
# [Environment]::SetEnvironmentVariable($name, $null) would not: PowerShell passes $null to a string
# parameter as an empty string, which pwsh keeps as an empty variable.
function Remove-EnvironmentVariable([string]$name) {
  Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
}
$Secrets = @{}
foreach ($k in $SecretKeys) {
  if ($null -ne [Environment]::GetEnvironmentVariable($k)) {
    [Console]::Error.WriteLine("WARN: ignoring $k from the environment; secrets are read from `$QOD_SECRETS_DIR only")
  }
  Remove-EnvironmentVariable $k
}
$secretsDir = [Environment]::GetEnvironmentVariable('QOD_SECRETS_DIR')
$secretsEphemeral = ([Environment]::GetEnvironmentVariable('QOD_SECRETS_DIR_EPHEMERAL') -eq '1')
Remove-EnvironmentVariable 'QOD_SECRETS_DIR'
Remove-EnvironmentVariable 'QOD_SECRETS_DIR_EPHEMERAL'
# Absolute, since qod-node starts in its own directory.
if (-not [string]::IsNullOrEmpty($secretsDir) -and -not [System.IO.Path]::IsPathRooted($secretsDir)) {
  $secretsDir = [System.IO.Path]::GetFullPath((Join-Path $startDir $secretsDir))
}

# qod-node removes an ephemeral secrets directory once it has read its keys. This script removes it
# too, on every exit (Exit-Spawn, the trap, the final cleanup), so no exit before the launch (a failed
# check, a dry run) leaves the node token on disk; by the time it exits, its qod-node has read the
# directory or never will. `taskkill /T /F` skips all of it; the launcher removes the directory then.
function Remove-SecretsDirectory {
  if ($secretsEphemeral -and -not [string]::IsNullOrEmpty($secretsDir)) {
    Remove-Item -LiteralPath $secretsDir -Recurse -Force -ErrorAction SilentlyContinue
  }
}
function Exit-Spawn([int]$code) {
  Remove-SecretsDirectory
  exit $code
}
trap { Remove-SecretsDirectory; break }

$tokenFile = $null
if (-not [string]::IsNullOrEmpty($secretsDir)) { $tokenFile = Join-Path $secretsDir 'QOD_NODE_TOKEN' }
if ($null -eq $tokenFile -or -not (Test-Path -LiteralPath $tokenFile -PathType Leaf) -or
    (Get-Item -LiteralPath $tokenFile).Length -eq 0) {
  [Console]::Error.WriteLine("ERROR: no node token: `$QOD_SECRETS_DIR\QOD_NODE_TOKEN is missing or empty")
  Exit-Spawn 1
}
foreach ($k in $SecretKeys) {
  if ($QodNodeKeys -contains $k) { continue }
  $f = Join-Path $secretsDir $k
  if (Test-Path -LiteralPath $f -PathType Leaf) {
    $Secrets[$k] = [System.IO.File]::ReadAllText($f)
    if ($secretsEphemeral) { Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue }
  }
}

# This spawn's init marker nonce (fresh per start, from the launcher), mirror of INIT_NONCE in
# spawn-quack-node.sh. Left in place for qod-node.
$initNonce = ''
$nonceFile = Join-Path $secretsDir 'QOD_INIT_NONCE'
if (Test-Path -LiteralPath $nonceFile -PathType Leaf) {
  $initNonce = ([System.IO.File]::ReadAllText($nonceFile)).Trim()
}

function Secret-Or([string]$name, [string]$default) {
  $v = $Secrets[$name]
  if ([string]::IsNullOrEmpty($v)) { return $default } else { return $v }
}

$tenantDbInitSql          = Secret-Or 'tenantDbInitSql' ''
$tenantDbInitSqlTrusted   = Secret-Or 'tenantDbInitSqlTrusted' ''
$tenantPoolInitSql        = Secret-Or 'tenantPoolInitSql' ''
$tenantPoolInitSqlTrusted = Secret-Or 'tenantPoolInitSqlTrusted' ''
$extraSetupSql  = Secret-Or 'extraSetupSql' ''
$lockdownSql    = [Environment]::GetEnvironmentVariable('lockdownSql')
$objectStoreSql = Secret-Or 'objectStoreSql' ''

function Env-Or([string]$name, [string]$default) {
  $v = [Environment]::GetEnvironmentVariable($name)
  if ([string]::IsNullOrEmpty($v)) { return $default } else { return $v }
}

$pgHost     = Env-Or 'pgHost'     'localhost'
$pgPort     = Env-Or 'pgPort'     '5432'
$pgUser     = Env-Or 'pgUser'     'postgres'
$pgPassword = Secret-Or 'pgPassword' 'azizam'
$dbName     = Env-Or 'dbName'     'db1'
$catalogAlias = Env-Or 'catalogAlias' $dbName
$schemaName = Env-Or 'schemaName' 'main'
$dataPath   = Env-Or 'dataPath'   (Join-Path (Get-Location) "ducklake\$dbName")

$kind = [Environment]::GetEnvironmentVariable('kind')
if ([string]::IsNullOrEmpty($kind)) {
  [Console]::Error.WriteLine("fatal: kind env var is required (ducklake | duckdb-file | memory)")
  Exit-Spawn 92
}
if ($kind -notin @('ducklake', 'duckdb-file', 'memory')) {
  [Console]::Error.WriteLine("fatal: unknown kind='$kind' (expected: ducklake | duckdb-file | memory)")
  Exit-Spawn 92
}

if ($schemaName -eq $catalogAlias) {
  [Console]::Error.WriteLine(@"
ERROR: schemaName ($schemaName) must differ from the catalog alias ($catalogAlias).
       DuckDB rejects 2-part identifiers like "$catalogAlias".<table> as
       ambiguous when a catalog and a schema share a name.
"@)
  Exit-Spawn 1
}

# Detect whether dataPath points at a remote object store. DuckLake accepts
# s3:// (AWS S3, SeaweedFS, MinIO, R2, GCS via the S3-interop endpoint) and
# azure:// / abfss:// when the matching DuckDB extension is loaded. For remote
# schemes we skip the local mkdir and emit the SQL needed to install
# httpfs/azure + a SECRET so the ATTACH below can read/write parquet.
# The data path signs with credentials scoped to it alone (spec decision 23): the database's own
# objectStore (qod_db_store) or else qod_data_s3 below, on the manager's QOD_S3_* keys or, with
# neither, on the instance's AWS credential chain (the aws extension). No unscoped s3 secret exists
# on a node, and every key is named. Mirrors spawn-quack-node.sh, which explains each choice.
$isRemote   = $false
$storageSql = ''
function Sql-Quote([string]$v) { return $v.Replace("'", "''") }
switch -Regex ($dataPath) {
  '^(s3|s3a|gs|r2)://' {
    $isRemote = $true
    $storageSql = "INSTALL httpfs;`nLOAD httpfs;"
    if ([string]::IsNullOrEmpty($objectStoreSql)) {
      if ($kind -ceq 'duckdb-file') { $scopes = @($dataPath) }
      elseif ($dataPath -cmatch '^(.*)__br_[0-9a-f]{8}/?$') {
        $scopes = @(($Matches[1] + '/'), (($dataPath -replace '/$', '') + '/'))
      }
      else {
        $base = $dataPath -replace '/$', ''
        $scopes = @(($base + '/'), ($base + '__br_'))
      }
      $dataScopes = ($scopes | ForEach-Object { "'" + (Sql-Quote $_) + "'" }) -join ', '
      $ep = Env-Or 'QOD_S3_ENDPOINT' ''
      $ep = Sql-Quote ($ep -replace '^https?://', '' -replace '/$', '')
      # USE_SSL is spliced into SQL unquoted: true or false only (mirrors spawn-quack-node.sh).
      $s3UseSsl = (Env-Or 'QOD_S3_USE_SSL' 'true').ToLowerInvariant()
      if ($s3UseSsl -cne 'true' -and $s3UseSsl -cne 'false') {
        [Console]::Error.WriteLine("ERROR: QOD_S3_USE_SSL must be true or false.")
        Exit-Spawn 1
      }
      $s3Key = Secret-Or 'QOD_S3_ACCESS_KEY_ID' ''
      $s3Sec = Secret-Or 'QOD_S3_SECRET_ACCESS_KEY' ''
      if (-not [string]::IsNullOrEmpty($s3Key) -and -not [string]::IsNullOrEmpty($s3Sec)) {
        $s3KeySql   = Sql-Quote $s3Key
        $s3SecSql   = Sql-Quote $s3Sec
        $s3Region   = Sql-Quote (Env-Or 'QOD_S3_REGION' 'us-east-1')
        $s3UrlStyle = Sql-Quote (Env-Or 'QOD_S3_URL_STYLE' 'path')
        $storageSql += @"

CREATE OR REPLACE SECRET qod_data_s3 (
  TYPE s3,
  KEY_ID '$s3KeySql',
  SECRET '$s3SecSql',
  SESSION_TOKEN '',
  REGION '$s3Region',
  ENDPOINT '$ep',
  URL_STYLE '$s3UrlStyle',
  USE_SSL $s3UseSsl,
  KMS_KEY_ID '',
  URL_COMPATIBILITY_MODE false,
  REQUESTER_PAYS false,
  SCOPE [$dataScopes]
);
"@
      } else {
        $regionLine = ''
        $region = Env-Or 'QOD_S3_REGION' ''
        if (-not [string]::IsNullOrEmpty($region)) { $regionLine = "`n  REGION '" + (Sql-Quote $region) + "'," }
        $s3UrlStyle = Sql-Quote (Env-Or 'QOD_S3_URL_STYLE' 'vhost')
        $storageSql += @"

INSTALL aws;
LOAD aws;
CREATE OR REPLACE SECRET qod_data_s3 (
  TYPE s3,
  PROVIDER credential_chain,
  VALIDATION 'none',
  REFRESH 'auto',$regionLine
  ENDPOINT '$ep',
  URL_STYLE '$s3UrlStyle',
  USE_SSL $s3UseSsl,
  URL_COMPATIBILITY_MODE false,
  SCOPE [$dataScopes]
);
"@
      }
    }
    break
  }
  '^(az|azure|abfss)://' {
    $isRemote = $true
    $storageSql = "INSTALL azure;`nLOAD azure;"
    $azConn = Secret-Or 'QOD_AZURE_CONNECTION_STRING' ''
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

# A relative local dataPath used to resolve against the node's working directory; resolve it against
# the starting directory instead. DuckLake refuses a DATA_PATH that differs from the one recorded at
# catalog creation (still the relative spelling), so the ATTACH then passes OVERRIDE_DATA_PATH, which
# applies to this attach only and rewrites nothing in the catalog. Mirrors spawn-quack-node.sh.
$dataPathOverride = ''
if (-not $isRemote -and -not [string]::IsNullOrEmpty($dataPath) -and
    -not [System.IO.Path]::IsPathRooted($dataPath)) {
  $dataPath = [System.IO.Path]::GetFullPath((Join-Path $startDir $dataPath))
  $dataPathOverride = ', OVERRIDE_DATA_PATH true'
}
# The starting directory is not validated like the rest of the metastore: double any quote.
$dataPathSql = ([string]$dataPath).Replace("'", "''")

# New-Item behaviour differs by kind: for `ducklake`, dataPath is a parquet
# DIRECTORY, so creating the path itself is correct. For `duckdb-file`,
# dataPath is a .duckdb FILE path - creating a directory at that exact path
# would make the later `ATTACH '$dataPath'` fail ("Is a directory" / file
# already exists as a directory); only the parent directory needs to exist.
# For `memory`, there is no on-disk path at all. Mirrors spawn-quack-node.sh.
if (-not $isRemote) {
  switch ($kind) {
    'ducklake' {
      New-Item -ItemType Directory -Force -Path $dataPath | Out-Null
    }
    'duckdb-file' {
      if (-not [string]::IsNullOrEmpty($dataPath)) {
        $parent = Split-Path -Path $dataPath -Parent
        if (-not [string]::IsNullOrEmpty($parent)) {
          New-Item -ItemType Directory -Force -Path $parent | Out-Null
        }
      }
    }
    'memory' {
      # no on-disk dataPath to create
    }
  }
}

# Resolve qod-node. $env:QOD_NODE_BIN wins (an absolute path the launcher sets to the provisioned
# binary); otherwise the first `qod-node` on PATH. Absolute, since qod-node starts in its own
# directory. The presence check is skipped in dry-run mode: SpawnScriptEncryptionWindowsSpec only
# exercises init-SQL assembly, which is pure string building and needs no binary at all. Mirrors
# spawn-quack-node.sh.
$dryRun = ([Environment]::GetEnvironmentVariable('QOD_SPAWN_DRY_RUN') -eq '1')
$nodeBin = [Environment]::GetEnvironmentVariable('QOD_NODE_BIN')
if (-not $dryRun) {
  $notFound = "ERROR: qod-node not found (QOD_NODE_BIN='$nodeBin', 'qod-node' not on PATH)"
  if ([string]::IsNullOrEmpty($nodeBin)) {
    $cmd = Get-Command qod-node.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($null -eq $cmd) { $cmd = Get-Command qod-node -CommandType Application -ErrorAction SilentlyContinue }
    if ($null -eq $cmd) { [Console]::Error.WriteLine($notFound); Exit-Spawn 1 }
    $nodeBin = @($cmd)[0].Source
  } elseif (-not [System.IO.Path]::IsPathRooted($nodeBin)) {
    $cmd = Get-Command $nodeBin -CommandType Application -ErrorAction SilentlyContinue
    if ($null -ne $cmd -and -not [string]::IsNullOrEmpty(@($cmd)[0].Source)) { $nodeBin = @($cmd)[0].Source }
    else { $nodeBin = [System.IO.Path]::GetFullPath((Join-Path $startDir $nodeBin)) }
  }
  if (-not (Test-Path -LiteralPath $nodeBin -PathType Leaf)) { [Console]::Error.WriteLine($notFound); Exit-Spawn 1 }
}

$onWindows = ($env:OS -eq 'Windows_NT')

# Applies $acl to the directory $path through .NET rather than the Set-Acl cmdlet: Windows PowerShell
# started from PowerShell 7 (a manager launched from pwsh, a CI step) inherits a PSModulePath that
# lists PowerShell 7's own Microsoft.PowerShell.Security first, and then cannot load the module
# Set-Acl lives in. The instance method is .NET Framework's (Windows PowerShell); .NET's (PowerShell
# 7) is the FileSystemAclExtensions one.
function Set-DirectoryAcl([string]$path, $acl) {
  $dir = New-Object System.IO.DirectoryInfo($path)
  if ($dir.PSObject.Methods['SetAccessControl']) {
    $dir.SetAccessControl($acl)
  } else {
    [System.IO.FileSystemAclExtensions]::SetAccessControl($dir, $acl)
  }
}

# A directory only the current user can reach (the analogue of mktemp's 0700).
function New-PrivateDirectory([string]$path) {
  New-Item -ItemType Directory -Path $path -Force | Out-Null
  if ($onWindows) {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
      $me, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
    Set-DirectoryAcl $path $acl
  } else {
    & chmod 700 $path
  }
}

# The node's secret directory home (mirrors SECRET_DIR in spawn-quack-node.sh): a directory holding
# only the regular file `none`, which nobody can write to. DuckDB's secret directory is set BELOW
# that file, so it can never exist as a directory: CreateDirectoryW on an existing file fails for
# administrators too. On Windows a read-only attribute would not stop file creation in a directory,
# so the directory gets an ACL with no inherited entries that allows the current user only read,
# traverse, delete and delete-child (so Remove-NodeDirectory can remove the marker and the
# directory) and explicitly denies creating files or subdirectories. Elsewhere: 0400 / 0500.
function New-SecretDirectory([string]$path) {
  New-Item -ItemType Directory -Path $path -Force | Out-Null
  $marker = Join-Path $path 'none'
  [System.IO.File]::WriteAllText($marker, '')
  if ($onWindows) {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
      $me, 'ReadAndExecute, Delete, DeleteSubdirectoriesAndFiles', 'None', 'None', 'Allow')))
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
      $me, 'CreateFiles, CreateDirectories', 'None', 'None', 'Deny')))
    Set-DirectoryAcl $path $acl
  } else {
    & chmod 400 $marker
    & chmod 500 $path
  }
  return (Join-Path $marker 's')
}

# Whether $path exists as a real directory, not a symlink or junction (reparse point).
function Test-RealDirectory([string]$path) {
  $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
  return ($null -ne $item -and $item.PSIsContainer -and
    -not ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint))
}

# The spill directory a node directory records (spill.path), removed only when it is one this script
# makes: `qod-node.<id>` directly under `.tmp` of this script's starting directory, a real directory.
# Anything else (a planted path, the spill root of another starting directory) is left in place; the
# `tmp` fallback inside the node directory goes with that directory. Mirrors remove_stale_spill in
# spawn-quack-node.sh.
function Remove-SpillDirectory([string]$dir) {
  $spillFile = Join-Path $dir 'spill.path'
  if (-not (Test-Path -LiteralPath $spillFile -PathType Leaf)) { return }
  try {
    $spill = [System.IO.Path]::GetFullPath(([System.IO.File]::ReadAllText($spillFile)).Trim())
    $root  = [System.IO.Path]::GetFullPath((Join-Path $startDir '.tmp'))
  } catch { return }
  $cmp = if ($onWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
  $parent = (Split-Path -Parent $spill).TrimEnd('\', '/')
  if ((Split-Path -Leaf $spill) -match '^qod-node\.[0-9a-f]{12}$' -and
      [string]::Equals($parent, $root.TrimEnd('\', '/'), $cmp) -and (Test-RealDirectory $spill)) {
    Remove-Item -LiteralPath $spill -Recurse -Force -ErrorAction SilentlyContinue
  }
}

# Remove a node directory and the spill directory it records.
function Remove-NodeDirectory([string]$dir) {
  # The secret directory is read-only around its marker file (New-SecretDirectory): give the current
  # user full control of it again first, inherited by the marker (on Windows the marker's DACL is
  # empty once the directory's protected ACL propagated), or chmod 700 elsewhere. The owner may always
  # rewrite the DACL. Never through a symlink or junction, which would change whatever it points at.
  $secrets = Join-Path $dir 'secrets'
  if (Test-RealDirectory $secrets) {
    if ($onWindows) {
      try {
        $acl = New-Object System.Security.AccessControl.DirectorySecurity
        $acl.SetAccessRuleProtection($true, $false)
        $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
          $me, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
        Set-DirectoryAcl $secrets $acl
      } catch {
        [Console]::Error.WriteLine("WARN: could not restore access to $secrets before removing it: $($_.Exception.Message)")
      }
    } else {
      & chmod 700 $secrets 2>$null
    }
  }
  Remove-SpillDirectory $dir
  Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $dir) {
    [Console]::Error.WriteLine("WARN: could not remove the node directory $dir; remove it by hand")
  }
}

# Per-node private state: $nodeDir holds owner.pid (the owning script's PID and its start ticks),
# spill.path, $workDir, the empty directory qod-node runs in, and the read-only `secrets` directory
# below which DuckDB's secret directory points. Node directories of earlier spawns
# whose owning script is gone (taskkill /T /F skipped their cleanup) are removed first. The owner is
# alive only when a process of that PID exists AND started at the recorded time: Windows recycles
# PIDs, so the start time keeps the sweep from sparing (or killing) an unrelated process's dir. A
# dir with no owner.pid yet (still being created) or an unparseable one is left alone.
# Windows gives each user a private temp root; elsewhere (pwsh on Unix) it is often the shared,
# sticky /tmp, where any local user can create a `quack-node.*` entry, so only a real directory (no
# symlink or junction) qualifies, and off Windows only one owned by this user. A stray entry never
# aborts this spawn: anything unexpected skips it.
$tempRoot = [System.IO.Path]::GetTempPath()
$myUid = ''
if (-not $onWindows) { try { $myUid = (& id -u).Trim() } catch {} }
Get-ChildItem -LiteralPath $tempRoot -Directory -Filter 'quack-node.*' -ErrorAction SilentlyContinue |
  ForEach-Object {
    try {
      $dir = $_.FullName
      $mine = Test-RealDirectory $dir
      if ($mine -and -not $onWindows) {
        # `ls -ldn` prints the numeric owner uid as its third field.
        $mine = ($myUid -ne '' -and ((& ls -ldn -- $dir) -split '\s+')[2] -eq $myUid)
      }
      $ownerFile = Join-Path $dir 'owner.pid'
      if ($mine -and (Test-Path -LiteralPath $ownerFile -PathType Leaf)) {
        $parts = (([System.IO.File]::ReadAllText($ownerFile)).Trim() -split '\s+')
        $owner = 0
        if ($parts.Count -ge 1 -and [int]::TryParse($parts[0], [ref]$owner)) {
          $proc  = Get-Process -Id $owner -ErrorAction SilentlyContinue
          $alive = $false
          if ($null -ne $proc) {
            if ($parts.Count -ge 2) {
              try { $alive = ($proc.StartTime.Ticks.ToString() -eq $parts[1]) } catch { $alive = $true }
            } else {
              $alive = $true
            }
          }
          if (-not $alive) { Remove-NodeDirectory $dir }
        }
      }
    } catch {
      [Console]::Error.WriteLine("WARN: skipping stale node directory ${dir}: $($_.Exception.Message)")
    }
  }
$nodeId  = [Guid]::NewGuid().ToString('N').Substring(0, 12)
if (-not [string]::IsNullOrEmpty($Marker) -and $Marker -notmatch '^[0-9]+-[0-9a-f]+$') {
  [Console]::Error.WriteLine("ERROR: invalid manager marker '$Marker' (expected <rest-port>-<hex boot id>)")
  Exit-Spawn 1
}
$markerPart = ''
if (-not [string]::IsNullOrEmpty($Marker)) { $markerPart = "$Marker." }
$nodeDir = Join-Path $tempRoot "quack-node.$markerPart$nodeId"
New-PrivateDirectory $nodeDir
$workDir = Join-Path $nodeDir 'cwd'
New-PrivateDirectory $workDir
# DuckDB loads every persistent secret of its secret directory on first secret use, and the default
# is the OS user's ~/.duckdb/stored_secrets, which would put an operator's own cloud keys on every
# node. The node's secret directory is $secretPath instead, a path below a read-only marker file
# (New-SecretDirectory): nothing loads and CREATE PERSISTENT SECRET fails, while persistent secrets
# stay enabled (with allow_persistent_secrets = false, DuckDB 2.0 refuses every postgres ATTACH that
# names no SECRET, inline credentials included). qod-node sets it (--secret-directory) before
# anything else runs. Mirrors SECRET_PATH in spawn-quack-node.sh.
$secretPath = New-SecretDirectory (Join-Path $nodeDir 'secrets')
# DuckDB's spill directory defaults to `.tmp` under its working directory, which no longer persists
# across nodes. Give each node its own under `.tmp` of the starting directory (where it spilled
# before), per node so that under lockdown, where DuckDB admits its temp directory, no node can read
# another's spill files. Under $nodeDir when that is not writable.
try {
  $spillDir = Join-Path (Join-Path $startDir '.tmp') "qod-node.$nodeId"
  New-PrivateDirectory $spillDir
} catch {
  $spillDir = Join-Path $nodeDir 'tmp'
  New-PrivateDirectory $spillDir
}
[System.IO.File]::WriteAllText((Join-Path $nodeDir 'spill.path'), $spillDir)
$ownerTicks = ''
try { $ownerTicks = (Get-Process -Id $PID).StartTime.Ticks.ToString() } catch {}
[System.IO.File]::WriteAllText((Join-Path $nodeDir 'owner.pid'), "$PID $ownerTicks")

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

$encrypted    = [Environment]::GetEnvironmentVariable('encrypted')
$encryptionKey = Secret-Or 'encryptionKey' ''

# Build init SQL piecemeal based on $kind so memory / duckdb-file skip the DuckLake-specific setup
# entirely. qod-node runs it after setting the secret directory ($secretPath, see above: DuckDB then
# refuses to change it from any session) and before loading quack and serving. Mirrors
# spawn-quack-node.sh.
#
# A tenant's initSql as qod-node gets it, mirror of tenant_init_sql in spawn-quack-node.sh: a
# ...Trusted file (a superuser's or the manager's text) as written, any other between the lines
# `-- qod:untrusted-begin <nonce> <label>` and `-- qod:untrusted-end <nonce>`, a section qod-node runs
# under its tenant initSql allowlist; <nonce> is this spawn's, so no other line is a marker. Every
# line holding the marker text, in any case (-notmatch ignores case), is also dropped first.
# Empty: ''. An untrusted text without a well-formed nonce stops the spawn: it never runs unmarked.
function Format-TenantInitSql([string]$Label, [string]$Sql, [bool]$Trusted) {
  if ([string]::IsNullOrEmpty($Sql)) { return '' }
  $kept = @($Sql -split "`n" | Where-Object { $_ -notmatch 'qod:untrusted-' })
  $clean = ($kept -join "`n").TrimEnd("`r", "`n")
  if ([string]::IsNullOrEmpty($clean)) { return '' }
  if ($Trusted) { return $clean }
  if ($initNonce -cnotmatch '^[0-9a-f]{32,128}$') {
    [Console]::Error.WriteLine("ERROR: no well-formed init marker nonce (`$QOD_SECRETS_DIR\QOD_INIT_NONCE) for the $Label initSql")
    Exit-Spawn 1
  }
  return "-- qod:untrusted-begin $initNonce $Label`n$clean`n-- qod:untrusted-end $initNonce"
}

$sb = [System.Text.StringBuilder]::new()
if (-not [string]::IsNullOrEmpty($proxySql))  { [void]$sb.AppendLine($proxySql) }
# Before the tenant-db's initSql, so an operator's own SET still wins.
[void]$sb.AppendLine("SET temp_directory = '$($spillDir.Replace("'", "''"))';")
# qod-node opens the database with autoinstall_known_extensions off (decision 17): install, without
# loading, httpfs and avro. Mirrors spawn-quack-node.sh.
[void]$sb.AppendLine("INSTALL httpfs;")
[void]$sb.AppendLine("INSTALL avro;")
foreach ($section in @((Format-TenantInitSql 'database' $tenantDbInitSqlTrusted $true),
                       (Format-TenantInitSql 'database' $tenantDbInitSql $false))) {
  if (-not [string]::IsNullOrEmpty($section)) { [void]$sb.AppendLine($section) }
}

# Per-database object-store secret, emitted for EVERY kind: a `memory` or `duckdb-file`
# database may carry an objectStore (TenantDb.validate permits it), and a `memory` database
# of views over remote parquet reads nothing without the CREATE SECRET. Must precede ATTACH.
if (-not [string]::IsNullOrEmpty($objectStoreSql)) { [void]$sb.AppendLine($objectStoreSql) }
# The data path's storage secret and extensions, for every kind, before any ATTACH (mirrors
# spawn-quack-node.sh).
if (-not [string]::IsNullOrEmpty($storageSql)) { [void]$sb.AppendLine($storageSql) }

# Encryption needs OpenSSL, which the httpfs extension provides. Without it DuckDB falls back to
# mbedtls, where 1.4.1+ REFUSES WRITES to an encrypted database file, and the symptom is a silently
# read-only node rather than an error. storageSql loads httpfs for remote data paths only, so a
# local encrypted database would miss it. Mirrors ENCRYPTION_SQL in spawn-quack-node.sh.
# -ceq (case-sensitive) matches bash's `==`, which is also case-sensitive: this script's own
# code always emits the lowercase string "true", but staying case-sensitive keeps the two
# scripts behaving identically for any other caller of the env var.
if ($encrypted -ceq 'true') {
  [void]$sb.AppendLine("INSTALL httpfs;")
  [void]$sb.AppendLine("LOAD httpfs;")
}

switch ($kind) {
  'ducklake' {
    [void]$sb.AppendLine("INSTALL ducklake;")
    [void]$sb.AppendLine("LOAD ducklake;")
    [void]$sb.AppendLine("INSTALL postgres;")
    [void]$sb.AppendLine("LOAD postgres;")
    # The catalog password rides a TEMPORARY secret, never an ATTACH string: DuckDB prints an attach
    # path verbatim in duckdb_databases().path and in connection errors, readable by any SQL user
    # of the node. Both ATTACHes name the secret, and it is dropped right after them (before
    # federation SQL and before qod-node serves) so no session can attach the catalog again with it. Mirrors
    # spawn-quack-node.sh and CatalogPgSecret.scala. TenantDb.validateSafety already refuses a
    # quote in pgPassword; double one anyway.
    $pgPasswordSql = ([string]$pgPassword).Replace("'", "''")
    [void]$sb.AppendLine("CREATE OR REPLACE TEMPORARY SECRET qod_catalog_pg (TYPE postgres, PASSWORD '$pgPasswordSql');")
    [void]$sb.AppendLine("ATTACH 'host=$pgHost port=$pgPort dbname=$dbName user=$pgUser' AS qod_init_pg (TYPE postgres, SECRET qod_catalog_pg);")
    [void]$sb.AppendLine("SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_lock(hashtext(''qod-ducklake-init:$dbName''))');")
    [void]$sb.AppendLine("ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=$dbName user=$pgUser' AS ""$catalogAlias""")
    if ($encrypted -ceq 'true') {
      [void]$sb.AppendLine("  (DATA_PATH '$dataPathSql', ENCRYPTED$dataPathOverride, METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'});")
    } else {
      [void]$sb.AppendLine("  (DATA_PATH '$dataPathSql'$dataPathOverride, METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'});")
    }
    [void]$sb.AppendLine("SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_unlock(hashtext(''qod-ducklake-init:$dbName''))');")
    [void]$sb.AppendLine("DETACH qod_init_pg;")
    [void]$sb.AppendLine("DROP SECRET IF EXISTS qod_catalog_pg;")
    [void]$sb.AppendLine("USE ""$catalogAlias"";")
    [void]$sb.AppendLine("CREATE SCHEMA IF NOT EXISTS ""$schemaName"";")
    [void]$sb.AppendLine("USE ""$catalogAlias"".""$schemaName"";")
  }
  'duckdb-file' {
    if ($encrypted -ceq 'true') {
      [void]$sb.AppendLine("ATTACH '$dataPathSql' AS ""$catalogAlias"" (ENCRYPTION_KEY '$encryptionKey');")
    } else {
      [void]$sb.AppendLine("ATTACH '$dataPathSql' AS ""$catalogAlias"";")
    }
    [void]$sb.AppendLine("USE ""$catalogAlias"";")
    [void]$sb.AppendLine("CREATE SCHEMA IF NOT EXISTS ""$schemaName"";")
    [void]$sb.AppendLine("USE ""$catalogAlias"".""$schemaName"";")
  }
  'memory' {
    # nothing; DuckDB's built-in 'memory' catalog is the default
  }
}

# The pool's initSql, before the federation SQL (mirrors spawn-quack-node.sh).
foreach ($section in @((Format-TenantInitSql 'pool' $tenantPoolInitSqlTrusted $true),
                       (Format-TenantInitSql 'pool' $tenantPoolInitSql $false))) {
  if (-not [string]::IsNullOrEmpty($section)) { [void]$sb.AppendLine($section) }
}
if (-not [string]::IsNullOrEmpty($extraSetupSql)) { [void]$sb.AppendLine($extraSetupSql) }

# Deployment lockdown (lockdownSql env var, authored by NodeLockdown.scala). Runs last, before
# qod-node serves, so the restrictions are in effect before the node serves any tenant statement.
if (-not [string]::IsNullOrEmpty($lockdownSql)) { [void]$sb.AppendLine($lockdownSql) }

$initSql = $sb.ToString()

# Test seam, mirror of QOD_SPAWN_DRY_RUN in spawn-quack-node.sh: print the init SQL and exit without
# launching qod-node. Used by SpawnScriptEncryptionWindowsSpec and SpawnScriptWindowsInitSpec.
if ($dryRun) {
  [Console]::Out.Write($initSql)
  Remove-NodeDirectory $nodeDir
  Exit-Spawn 0
}

# One argument of ProcessStartInfo.Arguments, quoted the way the C runtime (and .NET off Windows)
# splits a command line: in double quotes when it holds a blank or a quote (or is empty), a quote
# escaped, and the backslashes before a quote or the closing quote doubled. Windows PowerShell 5.1
# has no ArgumentList.
function ConvertTo-CommandLineArgument([string]$arg) {
  if ($arg.Length -gt 0 -and $arg -notmatch '[\s"]') { return $arg }
  $sb = [System.Text.StringBuilder]::new()
  [void]$sb.Append('"')
  $backslashes = 0
  foreach ($c in $arg.ToCharArray()) {
    if ($c -eq [char]92) { $backslashes++; continue }
    if ($c -eq [char]34) {
      [void]$sb.Append([char]92, 2 * $backslashes + 1)
    } elseif ($backslashes -gt 0) {
      [void]$sb.Append([char]92, $backslashes)
    }
    [void]$sb.Append($c)
    $backslashes = 0
  }
  if ($backslashes -gt 0) { [void]$sb.Append([char]92, 2 * $backslashes) }
  [void]$sb.Append('"')
  return $sb.ToString()
}

# QOD_NODE_BIND: interface the node listens on (0.0.0.0 by default). Mirrors spawn-quack-node.sh.
$nodeBind = Env-Or 'QOD_NODE_BIND' '0.0.0.0'
# QOD_NODE_ID: the node's id, which every session handle qod-node accepts must name. Not secret.
$qodNodeId = [Environment]::GetEnvironmentVariable('QOD_NODE_ID')
if ([string]::IsNullOrEmpty($qodNodeId)) {
  [Console]::Error.WriteLine('ERROR: QOD_NODE_ID is not set (the launcher names the node it starts)')
  Remove-NodeDirectory $nodeDir
  Exit-Spawn 1
}
$nodeArgs = @('--port', "$Port", '--bind', $nodeBind, '--secrets-dir', $secretsDir,
  '--secret-directory', $secretPath, '--node-id', $qodNodeId, '--parent-pid', "$PID", '--init', '-')
if ($secretsEphemeral) { $nodeArgs += '--secrets-ephemeral' }
# Decision 17, mirror of spawn-quack-node.sh: each extension flag only for the exact string "true"
# (-ceq is case-sensitive like bash's ==).
if ([Environment]::GetEnvironmentVariable('QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS') -ceq 'true') {
  $nodeArgs += '--allow-community-extensions'
}
if ([Environment]::GetEnvironmentVariable('QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS') -ceq 'true') {
  $nodeArgs += '--allow-unsigned-extensions'
}
if ([Environment]::GetEnvironmentVariable('QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS') -ceq 'true') {
  $nodeArgs += '--autoinstall-known-extensions'
}

# stdout/stderr are inherited so node logs flow to the manager's console (LocalQuackBackend uses
# inheritIO()). stdin is the pipe the init SQL goes down, closed once written.
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName               = $nodeBin
$psi.Arguments              = ($nodeArgs | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join ' '
$psi.RedirectStandardInput  = $true
$psi.RedirectStandardOutput = $false
$psi.RedirectStandardError  = $false
$psi.UseShellExecute        = $false
$psi.WorkingDirectory       = $workDir

$exitCode = 1
try {
  $proc = [System.Diagnostics.Process]::Start($psi)
  # The init SQL as UTF-8 bytes straight down the pipe: the StandardInput writer of Windows
  # PowerShell would encode it in the console's code page. A qod-node that ended before reading it
  # says why on its own output.
  try {
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($initSql)
    $proc.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $proc.StandardInput.BaseStream.Flush()
  } catch {
    [Console]::Error.WriteLine("WARN: could not hand qod-node its init SQL: $($_.Exception.Message)")
  } finally {
    try { $proc.StandardInput.Close() } catch {}
  }
  $proc.WaitForExit()
  # A normal stop kills this wrapper with the node (taskkill /T), so reaching here means qod-node
  # ended on its own while the wrapper is alive: it only stops by itself when this wrapper is gone.
  # Say so and exit non-zero rather than vanishing; the manager then sees the node down.
  [Console]::Error.WriteLine("ERROR: the node's qod-node exited (status $($proc.ExitCode)) while its wrapper was still running; see the node output above")
} finally {
  Remove-NodeDirectory $nodeDir
  Remove-SecretsDirectory
}
exit $exitCode
