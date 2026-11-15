#Requires -Version 5.1
<#
  run-tests.ps1 - tools/ 脚本的离线测试（不连真机、不构建、不操作 B651）。

  覆盖：语法安全（Parser 0 错误）、参数契约、automate 行为（state dest 本地拒绝、
  confirm_write/request_id 透传、applied/dispatched/rejected/cancelled/timeout、
  ExpectedText 可见 bounds、设备唯一性、adb 原生失败）、home 行为（状态/ensure/修复/校验失败/包缺失）。

  所有 adb 调用都指向 tools/tests/fake-adb.cmd，场景由 FAKE_* 环境变量注入。
  用法：pwsh -NoProfile -File tools/tests/run-tests.ps1
  退出码：0 全部通过 / 1 有失败
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$testsRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$toolsRoot = Split-Path -Parent $testsRoot
$automate = Join-Path $toolsRoot 'automate.ps1'
$homeScript = Join-Path $toolsRoot 'home.ps1'
$fakeAdb = Join-Path $testsRoot 'fake-adb.cmd'
$pwshPath = (Get-Command pwsh -ErrorAction Stop).Source
$stateRoot = Join-Path $testsRoot 'state'
$expectedStateRoot = [System.IO.Path]::GetFullPath((Join-Path $testsRoot 'state'))
if ([System.IO.Path]::GetFullPath($stateRoot) -ne $expectedStateRoot -or (Split-Path -Leaf $stateRoot) -ne 'state') {
    throw 'Unexpected test cleanup target'
}
if (Test-Path -LiteralPath $stateRoot) { Remove-Item -Recurse -Force -LiteralPath $stateRoot }
New-Item -ItemType Directory -Force -Path $stateRoot | Out-Null

$script:passed = 0
$script:failed = 0
$script:failures = @()

function Write-CaseResult {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    if ($Ok) { $script:passed++; Write-Host ("  PASS  {0}" -f $Name) -ForegroundColor Green }
    else { $script:failed++; $script:failures += ("{0}: {1}" -f $Name, $Detail); Write-Host ("  FAIL  {0}  {1}" -f $Name, $Detail) -ForegroundColor Red }
}
function Assert-True { param([string]$Name, [bool]$Ok, [string]$Detail = '') Write-CaseResult -Name $Name -Ok $Ok -Detail $Detail }

function Invoke-Child {
    param([string]$ScriptPath, [string[]]$ScriptArgs = @(), [hashtable]$Env = @{}, [string]$StateDir)
    if (-not (Test-Path -LiteralPath $StateDir)) { New-Item -ItemType Directory -Force -Path $StateDir | Out-Null }
    $saved = @{}
    $effective = @{ FAKE_ADB_STATE = $StateDir }
    foreach ($key in $Env.Keys) { $effective[$key] = $Env[$key] }
    foreach ($key in $effective.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, [string]$effective[$key], 'Process')
    }
    $stdout = Join-Path $StateDir 'stdout.txt'
    $stderr = Join-Path $StateDir 'stderr.txt'
    $argList = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) + @($ScriptArgs)
    try {
        $proc = Start-Process -FilePath $pwshPath -ArgumentList $argList -NoNewWindow -Wait -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $code = $proc.ExitCode
    } finally {
        foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
    $outText = ''
    if (Test-Path -LiteralPath $stdout) { $outText = Get-Content -LiteralPath $stdout -Raw }
    $errText = ''
    if (Test-Path -LiteralPath $stderr) { $errText = Get-Content -LiteralPath $stderr -Raw }
    if (-not $outText) { $outText = '' }
    if (-not $errText) { $errText = '' }
    [pscustomobject]@{ Code = $code; Out = $outText; Err = $errText; StateDir = $StateDir }
}

function Invoke-InProcess {
    param([string]$ScriptPath, [string[]]$ScriptArgs = @(), [hashtable]$Env = @{}, [string]$StateDir)
    if (-not (Test-Path -LiteralPath $StateDir)) { New-Item -ItemType Directory -Force -Path $StateDir | Out-Null }
    $saved = @{}
    $effective = @{ FAKE_ADB_STATE = $StateDir }
    foreach ($key in $Env.Keys) { $effective[$key] = $Env[$key] }
    foreach ($key in $effective.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, [string]$effective[$key], 'Process')
    }
    try {
        $output = (& $ScriptPath @ScriptArgs 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } finally {
        foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
    [pscustomobject]@{ Code = $code; Out = $output; Err = ''; StateDir = $StateDir }
}


function Invoke-AutomateInProcess {
    param([string]$Dest, [string]$Type, [string]$Serial, [string]$AdbPath, [hashtable]$Env = @{}, [string]$StateDir)
    if (-not (Test-Path -LiteralPath $StateDir)) { New-Item -ItemType Directory -Force -Path $StateDir | Out-Null }
    $saved = @{}
    $effective = @{ FAKE_ADB_STATE = $StateDir }
    foreach ($key in $Env.Keys) { $effective[$key] = $Env[$key] }
    foreach ($key in $effective.Keys) {
        $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, [string]$effective[$key], 'Process')
    }
    try {
        $output = (& $automate -Dest $Dest -Type $Type -Serial $Serial -AdbPath $AdbPath 2>&1 | Out-String)
        $code = $LASTEXITCODE
    } finally {
        foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
    [pscustomobject]@{ Code = $code; Out = $output; Err = ''; StateDir = $StateDir }
}

function Read-StateFile { param([string]$StateDir, [string]$Name)
    $path = Join-Path $StateDir $Name
    if (Test-Path -LiteralPath $path) { return (Get-Content -LiteralPath $path -Raw) }
    return '' }

$uuidPattern = '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
$uiXmlVisible = '<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0"><node index="0" text="" content-desc="" package="app.binkos.debug" bounds="[0,0][1080,2400]"><node index="1" text="Clock Face" content-desc="" package="app.binkos.debug" bounds="[100,200][300,260]" /><node index="2" text="" content-desc="PAGE DOTS" package="app.binkos.debug" bounds="[10,900][20,960]" /></node></hierarchy>'
$uiXmlZeroSize = '<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0"><node index="0" text="Clock Face" content-desc="" package="app.binkos.debug" bounds="[100,200][100,200]" /></hierarchy>'
$uiXmlForeign = '<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0"><node index="0" text="" content-desc="" package="app.binkos.debug" bounds="[0,0][1080,2400]"><node index="1" text="Clock Face" content-desc="" package="com.other.app" bounds="[100,200][300,260]" /></node></hierarchy>'
$uiXmlOutsideRoot = '<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0"><node index="0" text="" content-desc="" package="app.binkos.debug" bounds="[0,0][500,500]"><node index="1" text="Clock Face" content-desc="" package="app.binkos.debug" bounds="[600,600][700,700]" /></node></hierarchy>'
$homeResolveDebug = "priority=0 preferredOrder=0 specificIndex=-1 isDefault=true`n  packageName=app.binkos.debug"

Write-Host 'syntax checks' -ForegroundColor Cyan
foreach ($path in @($automate, $homeScript, (Join-Path $testsRoot 'fake-adb.ps1'))) {
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$parseErrors) | Out-Null
    $count = @($parseErrors).Count
    Assert-True -Name ("parse {0}" -f (Split-Path -Leaf $path)) -Ok ($count -eq 0) -Detail ("errors={0}" -f $count)
}

Write-Host 'contract checks' -ForegroundColor Cyan
$autoParams = (Get-Command -Name $automate).Parameters
Assert-True -Name 'automate has mandatory -Dest' -Ok ($autoParams.ContainsKey('Dest') -and @($autoParams['Dest'].Attributes | Where-Object { $_.Mandatory }).Count -gt 0)
Assert-True -Name 'automate has -AllowStateChanges switch' -Ok ($autoParams.ContainsKey('AllowStateChanges') -and $autoParams['AllowStateChanges'].SwitchParameter)
foreach ($name in @('Tab', 'Value', 'Type', 'Serial', 'AdbPath', 'ExpectedText', 'TimeoutSeconds', 'App')) {
    Assert-True -Name ("automate has -{0}" -f $name) -Ok $autoParams.ContainsKey($name)
}
$homeParams = (Get-Command -Name $homeScript).Parameters
foreach ($name in @('Target', 'Ensure', 'Serial', 'AdbPath')) {
    Assert-True -Name ("home has -{0}" -f $name) -Ok $homeParams.ContainsKey($name)
}

$baseEnv = @{
    FAKE_ADB_DEVICES  = "S1`tdevice"
    FAKE_PACKAGES     = 'package:app.binkos.debug'
    FAKE_PID          = '4242'
    FAKE_HOME_RESOLVE = $homeResolveDebug
    FAKE_ROLE         = 'app.binkos.debug'
}
function New-Env { param([hashtable]$Overrides)
    $result = @{}
    $baseEnv.Keys | ForEach-Object { $result[$_] = $baseEnv[$_] }
    $Overrides.Keys | ForEach-Object { $result[$_] = $Overrides[$_] }
    return $result }

Write-Host 'automate.ps1 behaviour' -ForegroundColor Cyan

$case = Join-Path $stateRoot 'missing-dest'
$r = Invoke-Child -ScriptPath $automate -Env $baseEnv -StateDir $case
Assert-True -Name 'missing -Dest fails' -Ok ($r.Code -ne 0 -and (($r.Err + $r.Out) -match 'Dest'))

$case = Join-Path $stateRoot 'state-denied'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'pager_off', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env $baseEnv -StateDir $case
Assert-True -Name 'state dest refused without -AllowStateChanges' -Ok ($r.Code -eq 2 -and (($r.Err + $r.Out) -match 'refused'))
Assert-True -Name 'state dest refusal sends no am start' -Ok ((Read-StateFile $case 'am_start.txt').Trim() -eq '')

$case = Join-Path $stateRoot 'state-allowed'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'pager_off', '-AllowStateChanges', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'applied'; FAKE_DEST = 'pager_off' }) -StateDir $case
$startLog = Read-StateFile $case 'am_start.txt'
Assert-True -Name 'allow-state applied exits 0' -Ok ($r.Code -eq 0) -Detail $r.Err
Assert-True -Name 'allow-state sends confirm_write true' -Ok ($startLog -match '--ez confirm_write true')
$requestId = (Read-StateFile $case 'request_id.txt').Trim()
Assert-True -Name 'request_id is uuid' -Ok ($requestId -match $uuidPattern) -Detail $requestId
Assert-True -Name 'request_id forwarded in am start' -Ok ($startLog -match [regex]::Escape($requestId))
Assert-True -Name 'serial forwarded to adb' -Ok ((Read-StateFile $case 'last_serial.txt').Trim() -eq 'S1')

foreach ($statusCase in @(@('rejected', 3), @('cancelled', 4))) {
    $status = $statusCase[0]; $wantCode = [int]$statusCase[1]
    $case = Join-Path $stateRoot ("status-" + $status)
    $r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = $status; FAKE_MESSAGE = ('mock ' + $status) }) -StateDir $case
    Assert-True -Name ("status {0} exits {1}" -f $status, $wantCode) -Ok ($r.Code -eq $wantCode) -Detail ("code={0} err={1}" -f $r.Code, $r.Err)
}

$case = Join-Path $stateRoot 'timeout'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-TimeoutSeconds', '3', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'none' }) -StateDir $case
Assert-True -Name 'timeout exits 5' -Ok ($r.Code -eq 5 -and (($r.Err + $r.Out) -match 'timeout')) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'dispatched-no-anchor'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'dispatched' }) -StateDir $case
Assert-True -Name 'dispatched without anchor exits 6' -Ok ($r.Code -eq 6 -and (($r.Err + $r.Out) -match 'dispatched only')) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'anchor-ok'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-ExpectedText', 'Clock', '-TimeoutSeconds', '5', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'dispatched'; FAKE_UI_XML = $uiXmlVisible }) -StateDir $case
Assert-True -Name 'dispatched + ExpectedText visible exits 0' -Ok ($r.Code -eq 0) -Detail ("code={0} err={1}" -f $r.Code, $r.Err)

$case = Join-Path $stateRoot 'anchor-content-desc'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-ExpectedText', 'PAGE', '-TimeoutSeconds', '5', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'dispatched'; FAKE_UI_XML = $uiXmlVisible }) -StateDir $case
Assert-True -Name 'content-desc anchor matched (case-insensitive)' -Ok ($r.Code -eq 0) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'anchor-zero-bounds'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-ExpectedText', 'Clock', '-TimeoutSeconds', '3', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'applied'; FAKE_UI_XML = $uiXmlZeroSize }) -StateDir $case
Assert-True -Name 'zero-size bounds not visible (exit 8)' -Ok ($r.Code -eq 8) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'no-devices'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_ADB_DEVICES = '' }) -StateDir $case
Assert-True -Name 'no device exits 7' -Ok ($r.Code -eq 7 -and (($r.Err + $r.Out) -match 'no device')) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'multi-devices'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_ADB_DEVICES = "S1`tdevice`nS2`tdevice" }) -StateDir $case
Assert-True -Name 'multiple devices refuse without -Serial' -Ok ($r.Code -eq 7 -and (($r.Err + $r.Out) -match 'multiple devices')) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'serial-offline'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-Serial', 'S9', '-AdbPath', $fakeAdb) -Env $baseEnv -StateDir $case
Assert-True -Name 'explicit offline serial refused' -Ok ($r.Code -eq 7 -and (($r.Err + $r.Out) -match "not in 'device' state"))

$case = Join-Path $stateRoot 'am-start-fails'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_FAIL_MATCH = 'am start' }) -StateDir $case
Assert-True -Name 'native am start failure exits 7' -Ok ($r.Code -eq 7) -Detail ("code={0} err={1}" -f $r.Code, $r.Err)

$case = Join-Path $stateRoot 'bad-app'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-App', 'bogus', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env $baseEnv -StateDir $case
Assert-True -Name 'invalid -App rejected by ValidateSet' -Ok ($r.Code -ne 0)

Write-Host 'home.ps1 behaviour' -ForegroundColor Cyan

$homeEnv = @{
    FAKE_ADB_DEVICES  = "S1`tdevice"
    FAKE_PACKAGES     = "package:app.binkos.debug`npackage:app.binkos"
    FAKE_HOME_RESOLVE = $homeResolveDebug
    FAKE_ROLE         = 'app.binkos.debug'
}

$case = Join-Path $stateRoot 'home-status'
$r = Invoke-Child -ScriptPath $homeScript -ScriptArgs @('-Serial', 'S1', '-AdbPath', $fakeAdb) -Env $homeEnv -StateDir $case
Assert-True -Name 'home status exits 0' -Ok ($r.Code -eq 0 -and $r.Out -match 'package=app\.binkos\.debug' -and $r.Out -match 'isDefault=true') -Detail ("code={0} out={1}" -f $r.Code, $r.Out)

$case = Join-Path $stateRoot 'home-ensure-ok'
$r = Invoke-Child -ScriptPath $homeScript -ScriptArgs @('ensure', 'debug', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env $homeEnv -StateDir $case
Assert-True -Name 'home ensure already-satisfied exits 0' -Ok ($r.Code -eq 0 -and $r.Out -match 'ensure : ok') -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'home-set-release'
$repairEnv = @{}
$homeEnv.Keys | ForEach-Object { $repairEnv[$_] = $homeEnv[$_] }
$repairEnv['FAKE_HOME_SWITCH'] = '1'
$r = Invoke-Child -ScriptPath $homeScript -ScriptArgs @('release', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env $repairEnv -StateDir $case
Assert-True -Name 'home set release verifies applied' -Ok ($r.Code -eq 0 -and $r.Out -match 'target : release') -Detail ("code={0} err={1}" -f $r.Code, $r.Err)

$case = Join-Path $stateRoot 'home-verify-fails'
$r = Invoke-Child -ScriptPath $homeScript -ScriptArgs @('release', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env $homeEnv -StateDir $case
Assert-True -Name 'home verification failure exits 9' -Ok ($r.Code -eq 9 -and (($r.Err + $r.Out) -match 'verify failed')) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'home-package-missing'
$r = Invoke-Child -ScriptPath $homeScript -ScriptArgs @('release', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_PACKAGES = "package:app.binkos.debug" }) -StateDir $case
Assert-True -Name 'home package missing exits 9' -Ok ($r.Code -eq 9 -and (($r.Err + $r.Out) -match 'not installed')) -Detail ("code={0}" -f $r.Code)


Write-Host 'contract hardening cases' -ForegroundColor Cyan

$case = Join-Path $stateRoot 'unknown-dest-rejected'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'no_such_dest', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'rejected'; FAKE_MESSAGE = 'unknown dest' }) -StateDir $case
Assert-True -Name 'unknown dest -> app rejected -> exit 3' -Ok ($r.Code -eq 3) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'dest-uppercase-forwarded'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'Settings', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'applied' }) -StateDir $case
Assert-True -Name 'uppercase dest allowed by ^[A-Za-z_]+$ and forwarded' -Ok ($r.Code -eq 0 -and (Read-StateFile $case 'am_start.txt') -match '--es dest Settings') -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'dest-space-denied'
# 含空格参数不能用 Start-Process 的 ArgumentList 拼串（会被拆词），走进程内调用
$r = Invoke-AutomateInProcess -Dest 'settings x' -Type '' -Serial 'S1' -AdbPath $fakeAdb -Env $baseEnv -StateDir $case
Assert-True -Name 'dest with whitespace refused locally (exit 2)' -Ok ($r.Code -eq 2) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'dest-injection-denied'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings;id', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env $baseEnv -StateDir $case
Assert-True -Name 'dest with shell metachar refused locally (exit 2)' -Ok ($r.Code -eq 2) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'dest-too-long'
$longDest = ('a' * 49)
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', $longDest, '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env $baseEnv -StateDir $case
Assert-True -Name 'dest longer than 48 refused locally (exit 2)' -Ok ($r.Code -eq 2) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'type-injection-denied'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'bottomwidget', '-Type', 'quote;id', '-AllowStateChanges', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'applied' }) -StateDir $case
Assert-True -Name 'type with shell metachar refused locally (exit 2)' -Ok ($r.Code -eq 2) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'type-empty-forwarded'
$r = Invoke-AutomateInProcess -Dest 'settings' -Type '' -Serial 'S1' -AdbPath (Join-Path $testsRoot 'fake-adb.ps1') -Env (New-Env @{ FAKE_STATUS = 'applied' }) -StateDir $case
$typeLog = Read-StateFile $case 'am_start.txt'
Assert-True -Name 'explicit empty -Type sends no request' -Ok ($typeLog.Trim() -eq '') -Detail $typeLog
Assert-True -Name 'explicit empty -Type refused locally' -Ok ($r.Code -eq 2) -Detail ("code={0} out={1}" -f $r.Code, $r.Out)

$case = Join-Path $stateRoot 'slow-dump-bounded'
$slowStart = Get-Date
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-ExpectedText', 'Clock', '-TimeoutSeconds', '3', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'dispatched'; FAKE_UI_XML = $uiXmlVisible; FAKE_SLOW_DUMP = '1' }) -StateDir $case
$slowElapsed = ((Get-Date) - $slowStart).TotalSeconds
Assert-True -Name 'hung uiautomator dump does not hang the script' -Ok ($slowElapsed -lt 20) -Detail ("elapsed={0:n1}s" -f $slowElapsed)
Assert-True -Name 'hung dump ends as anchor missing (exit 8)' -Ok ($r.Code -eq 8) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'request-id-unique-1'
$r1 = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'applied' }) -StateDir $case
$id1 = (Read-StateFile $case 'request_id.txt').Trim()
$case2 = Join-Path $stateRoot 'request-id-unique-2'
$r2 = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'applied' }) -StateDir $case2
$id2 = (Read-StateFile $case2 'request_id.txt').Trim()
Assert-True -Name 'request_id differs between runs' -Ok ($id1 -ne $id2 -and $id1 -match $uuidPattern -and $id2 -match $uuidPattern) -Detail ("{0} vs {1}" -f $id1, $id2)

$case = Join-Path $stateRoot 'dump-path-unique'
$null = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-ExpectedText', 'Clock', '-TimeoutSeconds', '6', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'dispatched'; FAKE_UI_XML = $uiXmlVisible }) -StateDir $case
$dumpPath = (Read-StateFile $case 'dump_path.txt').Trim()
$rid = (Read-StateFile $case 'request_id.txt').Trim()
Assert-True -Name 'ui dump path contains request_id (parallel-safe)' -Ok ($dumpPath -match [regex]::Escape($rid) -and $dumpPath -match '^/sdcard/') -Detail $dumpPath


$case = Join-Path $stateRoot 'anchor-foreign-package'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-ExpectedText', 'Clock', '-TimeoutSeconds', '3', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'dispatched'; FAKE_UI_XML = $uiXmlForeign }) -StateDir $case
Assert-True -Name 'foreign package text does not count as anchor (exit 8)' -Ok ($r.Code -eq 8) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'anchor-outside-root'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-ExpectedText', 'Clock', '-TimeoutSeconds', '3', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'dispatched'; FAKE_UI_XML = $uiXmlOutsideRoot }) -StateDir $case
Assert-True -Name 'node outside root bounds does not count as anchor (exit 8)' -Ok ($r.Code -eq 8) -Detail ("code={0}" -f $r.Code)

$case = Join-Path $stateRoot 'unicode-xml-native'
$unicodeGlyph = [string][char]0x25c6
$unicodeXml = '<hierarchy><node package="app.binkos.debug" bounds="[0,0][1080,2400]"><node package="app.binkos.debug" text="' + $unicodeGlyph + '" bounds="[10,10][30,40]" /><node package="app.binkos.debug" text="Clock Face" bounds="[50,50][200,100]" /></node></hierarchy>'
$r = Invoke-Child -ScriptPath $automate -ScriptArgs @('-Dest', 'settings', '-ExpectedText', 'Clock', '-TimeoutSeconds', '8', '-Serial', 'S1', '-AdbPath', $fakeAdb) -Env (New-Env @{ FAKE_STATUS = 'dispatched'; FAKE_UI_XML = $unicodeXml }) -StateDir $case
Assert-True -Name 'UTF-8 glyph before anchor keeps native job XML valid' -Ok ($r.Code -eq 0) -Detail ("code={0} err={1}" -f $r.Code, $r.Err)

Write-Host ''
if ($script:failed -eq 0) { Write-Host ("ALL PASS ({0})" -f $script:passed) -ForegroundColor Green; exit 0 }
Write-Host ("FAILED {0} / passed {1}" -f $script:failed, $script:passed) -ForegroundColor Red
foreach ($failure in $script:failures) { Write-Host " - $failure" -ForegroundColor Red }
exit 1
