<#
.SYNOPSIS
  Stop the quack-on-demand manager and its child Quack nodes on Windows.

.DESCRIPTION
  PowerShell mirror of scripts/stop-jar.sh. Locates the manager JVM by its REST
  / FlightSQL listen ports and ITS child Quack nodes by their spawn-quack-node.ps1
  wrapper, whose command line carries the manager's marker
  `<rest-port>-<boot id>` (LocalQuackBackend.managerMarker), plus any orphaned
  qod-node.exe (or an earlier release's duckdb.exe) of that manager. Another manager's nodes (a different REST port) and
  unmarked nodes from an older manager are left alone.

  The manager is asked to stop first, alone (`taskkill` without /T): when it
  accepts, the script waits for it to exit, up to QOD_DRAIN_TIMEOUT_SEC plus a
  margin, since it lets running statements finish and then stops its own
  nodes (#221). Then it terminates the process trees of the wrappers left with
  `taskkill /T` (graceful), escalating to `/T /F` (force) on everything after
  FORCE_AFTER seconds. A console JVM usually refuses a graceful taskkill; the
  script then says so and goes on without the wait.

  Env overrides:
    QOD_ON_DEMAND_PORT     (default 20900 - manager REST + UI)
    PROXY_PORT             (default 31338 - FlightSQL edge)
    QOD_DRAIN_TIMEOUT_SEC  (default 60 - the manager's drain, as it reads it)
    FORCE_AFTER            (default 10 - seconds before force kill)
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'SilentlyContinue'

$MgrPort    = if ($env:QOD_ON_DEMAND_PORT) { [int]$env:QOD_ON_DEMAND_PORT } else { 20900 }
$EdgePort   = if ($env:PROXY_PORT)          { [int]$env:PROXY_PORT }          else { 31338 }
$ForceAfter = if ($env:FORCE_AFTER)         { [int]$env:FORCE_AFTER }         else { 10 }
$DrainTimeout = 60
if ($env:QOD_DRAIN_TIMEOUT_SEC -match '^\d+$') { $DrainTimeout = [int]$env:QOD_DRAIN_TIMEOUT_SEC }
# How much longer than its drain the manager may take to exit: its JVM shutdown hook waits the
# drain plus 15 s for the graceful chain before it forces its own teardown, which stops the nodes.
$DrainMargin = 20

function Get-ListenerPid([int]$port) {
  (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty OwningProcess)
}

# This manager's wrappers: `... spawn-quack-node.ps1 <node-port> <rest-port>-<boot id>` (the script
# path may be quoted).
$SpawnRe = "spawn-quack-node\.ps1`"?\s+\d+\s+$MgrPort-[0-9a-f]+"
function Get-SpawnPids {
  Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'pwsh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match $SpawnRe } |
    Select-Object -ExpandProperty ProcessId
}

$mgrPid    = Get-ListenerPid $MgrPort
$edgePid   = Get-ListenerPid $EdgePort
$spawnPids = @(Get-SpawnPids)

$pids = @($mgrPid, $edgePid) + $spawnPids | Where-Object { $_ } | Select-Object -Unique
if ($pids.Count -eq 0) {
  Write-Host "nothing running on ports $MgrPort / $EdgePort and no spawn-quack-node processes."
  exit 0
}

if ($mgrPid)          { Write-Host "manager:  pid=$mgrPid (port $MgrPort)" }
if ($edgePid)         { Write-Host "edge:     pid=$edgePid (port $EdgePort)" }
if ($spawnPids.Count) { Write-Host ("quack:    pids=" + ($spawnPids -join ' ')) }

$mgrPids = @($mgrPid, $edgePid) | Where-Object { $_ } | Select-Object -Unique
function Test-ManagerAlive {
  foreach ($p in $mgrPids) { if (Get-Process -Id $p -ErrorAction SilentlyContinue) { return $true } }
  return $false
}

# ---- Graceful: the manager first, alone (no /T: its tree holds the nodes) ----
$waited = 0
if ($mgrPids.Count) {
  $accepted = $false
  foreach ($p in $mgrPids) {
    & taskkill /PID $p *> $null
    if ($LASTEXITCODE -eq 0) { $accepted = $true }
  }
  if ($accepted) {
    $budget = $DrainTimeout + $DrainMargin
    Write-Host ("stopping the manager first: it lets running statements finish for up to ${DrainTimeout}s " +
      "(QOD_DRAIN_TIMEOUT_SEC), then stops its nodes; waiting up to ${budget}s for it to exit.")
    while ($waited -lt $budget -and (Test-ManagerAlive)) {
      Start-Sleep -Seconds 1
      $waited++
    }
    if (Test-ManagerAlive) { Write-Host "the manager is still running after ${budget}s; stopping its nodes." }
  } else {
    Write-Host "the manager refused a graceful stop (a console JVM only takes a forced one); not waiting for a drain."
  }
}

# ---- Then the nodes it left: taskkill /T ----
$leftSpawn = @(Get-SpawnPids)
foreach ($p in $leftSpawn) { & taskkill /PID $p /T *> $null }
$pids = @($pids) + $leftSpawn | Where-Object { $_ } | Select-Object -Unique

# ---- Wait for ports to clear ----
for ($i = 0; $i -le $ForceAfter; $i++) {
  $stillMgr  = Get-ListenerPid $MgrPort
  $stillEdge = Get-ListenerPid $EdgePort
  $stillSpawn = @(Get-SpawnPids)
  if (-not (Test-ManagerAlive) -and -not $stillMgr -and -not $stillEdge -and $stillSpawn.Count -eq 0) {
    Write-Host "stopped cleanly after $($waited + $i)s."
    exit 0
  }
  if ($i -lt $ForceAfter) { Start-Sleep -Seconds 1 }
}

# ---- Force: taskkill /T /F ----
Write-Host "still running after ${ForceAfter}s; force-killing."
foreach ($p in $pids) { & taskkill /PID $p /T /F *> $null }
# Sweep any orphaned node of THIS manager left behind: a qod-node.exe whose command line names
# `--secret-directory <...>\quack-node.<rest-port>-<boot id>.<id>\secrets\...` (an earlier release's
# `duckdb.exe ... -init <...>\quack-node.<rest-port>-<boot id>.<id>\init`), not an unrelated process
# nor another manager's node.
Get-CimInstance Win32_Process -Filter "Name = 'qod-node.exe' OR Name = 'duckdb.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match "quack-node\.$MgrPort-[0-9a-f]+\." } |
  ForEach-Object { & taskkill /PID $_.ProcessId /T /F *> $null }

Start-Sleep -Seconds 2
if ((Get-ListenerPid $MgrPort) -or (Get-ListenerPid $EdgePort)) {
  Write-Warning "a port is still listening. Investigate with: Get-NetTCPConnection -LocalPort $MgrPort,$EdgePort -State Listen"
  exit 1
}
Write-Host "stopped (forced)."
