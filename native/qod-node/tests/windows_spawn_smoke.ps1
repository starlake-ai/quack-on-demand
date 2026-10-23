<#
.SYNOPSIS
  Windows smoke of qod-node.exe through the real spawn script (CI, qod-node.yml).

.DESCRIPTION
  Starts scripts/spawn-quack-node.ps1 under Windows PowerShell (what LocalQuackBackend runs) with
  -NodeBin as QOD_NODE_BIN, then checks that:
    - the node serves its init table over quack to the release DuckDB CLI (-DuckdbCli);
    - qod-node removed the secrets directory once it read its token;
    - qod-node exits by itself once its wrapper is killed (its parent watch).
  Exits non-zero, with the node's output, on the first failure.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)] [string] $NodeBin,
  [Parameter(Mandatory = $true)] [string] $DuckdbCli,
  [Parameter(Mandatory = $true)] [string] $SpawnScript,
  [int] $Port = 23457
)
$ErrorActionPreference = 'Stop'

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("qod-node-smoke-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $work | Out-Null
$secrets = Join-Path $work 'secrets'
New-Item -ItemType Directory -Path $secrets | Out-Null
$token = 'tok-windows-smoke'
[System.IO.File]::WriteAllText((Join-Path $secrets 'QOD_NODE_TOKEN'), $token)
[System.IO.File]::WriteAllText((Join-Path $secrets 'extraSetupSql'), "CREATE TABLE fed AS SELECT 'fed-ok' AS v;")
$start = Join-Path $work 'start'
New-Item -ItemType Directory -Path $start | Out-Null

$env:kind = 'memory'
$env:dbName = 'm'
$env:schemaName = 'main'
$env:QOD_NODE_BIN = $NodeBin
$env:QOD_NODE_BIND = '127.0.0.1'
$env:QOD_SECRETS_DIR = $secrets
$env:QOD_SECRETS_DIR_EPHEMERAL = '1'

$out = Join-Path $work 'node.out'
$err = Join-Path $work 'node.err'
$wrapper = Start-Process -FilePath 'powershell.exe' -PassThru -NoNewWindow -WorkingDirectory $start `
  -RedirectStandardOutput $out -RedirectStandardError $err `
  -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $SpawnScript, "$Port")

function Show-NodeOutput {
  foreach ($f in @($out, $err)) {
    if (Test-Path -LiteralPath $f) { Write-Host "---- $f"; Get-Content -LiteralPath $f | Write-Host }
  }
}
function Fail([string]$why) {
  Write-Host "FAIL: $why"
  Show-NodeOutput
  & taskkill /PID $wrapper.Id /T /F 2>&1 | Out-Null
  exit 1
}
function Get-NodeProcess {
  Get-CimInstance Win32_Process -Filter "Name = 'qod-node.exe'" |
    Where-Object { $_.CommandLine -match "--port $Port" }
}

# qod-node logs to the stderr the wrapper hands it.
$deadline = [DateTime]::UtcNow.AddSeconds(90)
while (-not ((Test-Path -LiteralPath $err) -and ((Get-Content -LiteralPath $err -Raw) -match '\[qod-node\] ready'))) {
  if ($wrapper.HasExited) { Fail "the spawn wrapper exited (status $($wrapper.ExitCode)) before the node was ready" }
  if ([DateTime]::UtcNow -gt $deadline) { Fail 'the node never became ready' }
  Start-Sleep -Milliseconds 500
}
Write-Host 'node ready'

$query = @"
INSTALL quack;
LOAD quack;
ATTACH 'quack:127.0.0.1:$Port' AS q (TYPE quack, TOKEN '$token');
FROM quack_query_by_name('q', 'SELECT v FROM memory.main.fed');
"@
$env:DUCKDB_AGENT_MODE = '0'
$answer = ($query | & $DuckdbCli -noheader -list 2>&1 | Out-String)
if ($answer -notmatch 'fed-ok') { Fail "the node did not serve its init table over quack: $answer" }
Write-Host 'quack query OK'

if (Test-Path -LiteralPath $secrets) { Fail 'the secrets directory is still there once the node serves' }
Write-Host 'secrets directory removed'

$node = @(Get-NodeProcess)
if ($node.Count -ne 1) { Fail "expected one qod-node.exe on port $Port, found $($node.Count)" }
# The wrapper alone, not its tree: qod-node must notice its parent is gone and exit.
Stop-Process -Id $wrapper.Id -Force
$deadline = [DateTime]::UtcNow.AddSeconds(20)
while (@(Get-NodeProcess).Count -gt 0) {
  if ([DateTime]::UtcNow -gt $deadline) { Fail 'qod-node.exe outlived its wrapper' }
  Start-Sleep -Milliseconds 500
}
Write-Host 'qod-node exited with its wrapper'
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
Write-Host 'windows spawn smoke: all passed'
exit 0
