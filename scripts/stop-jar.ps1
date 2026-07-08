<#
.SYNOPSIS
  Stop the quack-on-demand manager and all child Quack nodes on Windows.

.DESCRIPTION
  PowerShell mirror of scripts/stop-jar.sh. Locates the manager JVM by its REST
  / FlightSQL listen ports and the child Quack nodes by their spawn-quack-node.ps1
  wrapper (plus any stray duckdb.exe), then terminates each process tree with
  `taskkill /T` (graceful) escalating to `/T /F` (force) after FORCE_AFTER
  seconds.

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

function Get-SpawnPids {
  Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'pwsh.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*spawn-quack-node*' } |
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
# Sweep any orphaned duckdb.exe left behind.
Get-Process duckdb -ErrorAction SilentlyContinue | ForEach-Object { & taskkill /PID $_.Id /T /F *> $null }

Start-Sleep -Seconds 2
if ((Get-ListenerPid $MgrPort) -or (Get-ListenerPid $EdgePort)) {
  Write-Warning "a port is still listening. Investigate with: Get-NetTCPConnection -LocalPort $MgrPort,$EdgePort -State Listen"
  exit 1
}
Write-Host "stopped (forced)."
