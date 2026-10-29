<#
.SYNOPSIS
  Windows smoke of qod-node.exe through the real spawn script (CI, qod-node.yml).

.DESCRIPTION
  Starts scripts/spawn-quack-node.ps1 under Windows PowerShell (what LocalQuackBackend runs) with
  -NodeBin as QOD_NODE_BIN, then checks that:
    - qod-node ran the init SQL the script handed it, extraSetupSql included: its second statement
      reads a table that does not exist, and qod-node's init failure line must name it;
    - over quack, through the release DuckDB CLI (-DuckdbCli), a statement without a session handle
      is refused by the parse screen and a valid prelude (minted by -HandleTool) installs;
    - qod-node removed the secrets directory once it read its token and key;
    - qod-node exits by itself once its wrapper is killed (its parent watch).
  Exits non-zero, with the node's output, on the first failure.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)] [string] $NodeBin,
  [Parameter(Mandatory = $true)] [string] $DuckdbCli,
  [Parameter(Mandatory = $true)] [string] $SpawnScript,
  [Parameter(Mandatory = $true)] [string] $HandleTool,
  [int] $Port = 23457
)
$ErrorActionPreference = 'Stop'

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("qod-node-smoke-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $work | Out-Null
$secrets = Join-Path $work 'secrets'
New-Item -ItemType Directory -Path $secrets | Out-Null
$token = 'tok-windows-smoke'
$nodeKey = '4242424242424242424242424242424242424242424242424242424242424242'
$nodeId = 'quack-windows-smoke-1'
[System.IO.File]::WriteAllText((Join-Path $secrets 'QOD_NODE_TOKEN'), $token)
[System.IO.File]::WriteAllText((Join-Path $secrets 'QOD_NODE_AUTHZ_KEY'), $nodeKey)
[System.IO.File]::WriteAllText((Join-Path $secrets 'QOD_INIT_NONCE'), '0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f')
# The second statement fails on purpose, naming a table nothing else creates: the spawn script's own
# statements satisfy the init count alone, so only this failure shows extraSetupSql reached qod-node.
$extraMarker = 'qod_smoke_extra_setup_marker'
[System.IO.File]::WriteAllText((Join-Path $secrets 'extraSetupSql'),
  "CREATE TABLE fed AS SELECT 'fed-ok' AS v;`nSELECT * FROM $extraMarker;")
$start = Join-Path $work 'start'
New-Item -ItemType Directory -Path $start | Out-Null

$env:kind = 'memory'
$env:dbName = 'm'
$env:schemaName = 'main'
$env:QOD_NODE_BIN = $NodeBin
$env:QOD_NODE_BIND = '127.0.0.1'
$env:QOD_NODE_ID = $nodeId
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
# One quack_query is one client connection with one PREPARE.
function Invoke-Quack([string]$sql) {
  $query = @"
INSTALL quack;
LOAD quack;
FROM quack_query('quack:127.0.0.1:$Port', '$sql', token := '$token');
"@
  $env:DUCKDB_AGENT_MODE = '0'
  return ($query | & $DuckdbCli -noheader -list 2>&1 | Out-String)
}

# qod-node logs to the stderr the wrapper hands it.
$deadline = [DateTime]::UtcNow.AddSeconds(90)
while (-not ((Test-Path -LiteralPath $err) -and ((Get-Content -LiteralPath $err -Raw) -match '\[qod-node\] ready'))) {
  if ($wrapper.HasExited) { Fail "the spawn wrapper exited (status $($wrapper.ExitCode)) before the node was ready" }
  if ([DateTime]::UtcNow -gt $deadline) { Fail 'the node never became ready' }
  Start-Sleep -Milliseconds 500
}
Write-Host 'node ready'

$nodeErr = Get-Content -LiteralPath $err -Raw
if ($nodeErr -notmatch '\[qod-node\] init: [1-9]\d* of \d+ statement\(s\) ran') {
  Fail 'qod-node reported no init SQL run'
}
if ($nodeErr -notmatch "\[qod-node\] init: statement \d+ of \d+ failed: [^\r\n]*$extraMarker") {
  Fail "qod-node never ran extraSetupSql: no init failure names $extraMarker"
}
Write-Host 'init SQL ran, extraSetupSql included'

# A table that does not exist: only the parse screen refuses before binding (quack words it
# "Authorization callback failed"); the post_bind backstop refuses after it, and then a missing table
# is a catalog error.
$answer = Invoke-Quack 'SELECT v FROM memory.main.no_such_table'
if ($answer -notmatch 'Authorization callback failed: .*no verified principal') {
  Fail "a statement without a session handle was not refused by the parse screen: $answer"
}
Write-Host 'handle-less statement refused'

$handle = (& $HandleTool $nodeKey $nodeId 'u-smoke' 60000 | Out-String).Trim()
$answer = Invoke-Quack "CALL system.main.qod_session(''$handle'')"
if ($answer -notmatch '(?m)^true\s*$') { Fail "a valid prelude did not install: $answer" }
Write-Host 'prelude installed'

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
