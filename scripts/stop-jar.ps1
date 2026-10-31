<#
.SYNOPSIS
  Stop the quack-on-demand manager and its child Quack nodes on Windows.

.DESCRIPTION
  PowerShell mirror of scripts/stop-jar.sh. Locates the manager JVM by its REST
  / FlightSQL listen ports and ITS child Quack nodes by their spawn-quack-node.ps1
  wrapper, whose command line carries the manager's marker
  `<rest-port>-<boot id>` (LocalQuackBackend.managerMarker), plus any orphaned
  qod-node.exe (or an earlier release's duckdb.exe) of that manager. Another manager's nodes (a different REST port) and
  unmarked nodes from an older manager are left alone. Terminates each process
  tree with `taskkill /T` (graceful) escalating to `/T /F` (force) after
  FORCE_AFTER seconds.

  Env overrides:
    QOD_ON_DEMAND_PORT   (default 20900 - manager REST + UI)
    PROXY_PORT           (default 31338 - FlightSQL edge)
    FORCE_AFTER          (default 10 - seconds before force kill)
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'SilentlyContinue'

$MgrPort    = if ($env:QOD_ON_DEMAND_PORT) { [int]$env:QOD_ON_DEMAND_PORT } else { 20900 }
$EdgePort   = if ($env:PROXY_PORT)          { [int]$env:PROXY_PORT }          else { 31338 }
$ForceAfter = if ($env:FORCE_AFTER)         { [int]$env:FORCE_AFTER }         else { 10 }

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

# ---- Graceful: taskkill /T ----
foreach ($p in $pids) { & taskkill /PID $p /T *> $null }

# ---- Wait for ports to clear ----
for ($i = 0; $i -lt $ForceAfter; $i++) {
  Start-Sleep -Seconds 1
  $stillMgr  = Get-ListenerPid $MgrPort
  $stillEdge = Get-ListenerPid $EdgePort
  $stillSpawn = @(Get-SpawnPids)
  if (-not $stillMgr -and -not $stillEdge -and $stillSpawn.Count -eq 0) {
    Write-Host "stopped cleanly after ${i}s."
    exit 0
  }
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
