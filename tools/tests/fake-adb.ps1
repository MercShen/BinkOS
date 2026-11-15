#Requires -Version 5.1
<# fake-adb.ps1 - 测试用 adb 替身，只被 tools/tests/run-tests.ps1 调用，绝不连接真机。
   场景由 FAKE_* 环境变量描述；调用记录写到 $env:FAKE_ADB_STATE 目录。 #>
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$arguments = @($args)
$joined = $arguments -join ' '

if ($env:FAKE_FAIL_MATCH -and $joined -match $env:FAKE_FAIL_MATCH) {
    [Console]::Error.WriteLine("mock adb failure: $joined")
    exit 1
}

$stateDir = $env:FAKE_ADB_STATE
if (-not $stateDir) { $stateDir = Join-Path $env:TEMP 'fake-adb-state' }
if (-not (Test-Path -LiteralPath $stateDir)) { New-Item -ItemType Directory -Path $stateDir -Force | Out-Null }

function Write-State { param([string]$Name, [string]$Value)
    Set-Content -LiteralPath (Join-Path $stateDir $Name) -Value $Value -Encoding UTF8 }
function Write-Lines { param([string]$Text)
    if ($Text) { foreach ($line in ($Text -split "`r?`n")) { Write-Output $line } } }

if ($arguments.Count -eq 1 -and $arguments[0] -eq 'version') { Write-Output 'Android Debug Bridge version 1.0.41'; exit 0 }

if ($arguments.Count -ge 1 -and $arguments[0] -eq 'devices') {
    Write-Output 'List of devices attached'
    Write-Lines $env:FAKE_ADB_DEVICES
    exit 0
}

if ($arguments.Count -lt 3 -or $arguments[0] -ne '-s') { Write-Output "mock-adb: $joined"; exit 0 }

$serial = $arguments[1]
$rest = @($arguments[2..($arguments.Count - 1)])
Write-State 'last_serial.txt' $serial
if ($rest.Count -eq 0) { exit 0 }

switch ($rest[0]) {
    'shell' {
        $cmd = @()
        if ($rest.Count -gt 1) { $cmd = @($rest[1..($rest.Count - 1)]) }
        if ($cmd.Count -eq 0) { exit 0 }

        if ($cmd.Count -ge 3 -and $cmd[0] -eq 'cmd' -and $cmd[1] -eq 'package' -and $cmd[2] -eq 'resolve-activity') {
            $switched = Join-Path $stateDir 'home_applied.txt'
            if ($env:FAKE_HOME_SWITCH -eq '1' -and (Test-Path -LiteralPath $switched)) {
                $component = (Get-Content -LiteralPath $switched -Raw).Trim()
                Write-Output 'priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true'
                Write-Output "  packageName=$($component.Split('/')[0])"
                exit 0
            }
            Write-Lines $env:FAKE_HOME_RESOLVE
            exit 0
        }
        if ($cmd.Count -ge 3 -and $cmd[0] -eq 'cmd' -and $cmd[1] -eq 'role' -and $cmd[2] -eq 'get-role-holders') {
            $switched = Join-Path $stateDir 'home_applied.txt'
            if ($env:FAKE_HOME_SWITCH -eq '1' -and (Test-Path -LiteralPath $switched)) {
                Write-Output ((Get-Content -LiteralPath $switched -Raw).Trim().Split('/')[0])
            } else { Write-Lines $env:FAKE_ROLE }
            exit 0
        }
        if ($cmd.Count -ge 4 -and $cmd[0] -eq 'cmd' -and $cmd[1] -eq 'role' -and $cmd[2] -eq 'add-role-holders') {
            Write-Output 'Success'; exit 0
        }
        if ($cmd.Count -ge 3 -and $cmd[0] -eq 'cmd' -and $cmd[1] -eq 'package' -and $cmd[2] -eq 'set-home-activity') {
            if ($env:FAKE_HOME_SWITCH -eq '1' -and $cmd.Count -ge 4) { Write-State 'home_applied.txt' $cmd[3] }
            Write-Output 'Success'; exit 0
        }
        if ($cmd.Count -ge 3 -and $cmd[0] -eq 'pm' -and $cmd[1] -eq 'list' -and $cmd[2] -eq 'packages') {
            Write-Lines $env:FAKE_PACKAGES; exit 0
        }
        if ($cmd[0] -eq 'pidof') { if ($env:FAKE_PID) { Write-Output $env:FAKE_PID }; exit 0 }
        if ($cmd.Count -ge 2 -and $cmd[0] -eq 'am' -and $cmd[1] -eq 'start') {
            Write-State 'am_start.txt' $joined
            $idx = [array]::IndexOf($cmd, 'request_id')
            if ($idx -ge 0 -and $cmd.Count -gt ($idx + 1)) { Write-State 'request_id.txt' $cmd[$idx + 1] }
            Write-Output 'Starting: Intent { act=android.intent.action.MAIN }'
            exit 0
        }
        if ($cmd[0] -eq 'logcat') {
            if (-not $env:FAKE_STATUS -or $env:FAKE_STATUS -eq 'none') { exit 0 }
            $requestFile = Join-Path $stateDir 'request_id.txt'
            if (-not (Test-Path -LiteralPath $requestFile)) { exit 0 }
            $requestId = (Get-Content -LiteralPath $requestFile -Raw).Trim()
            $message = ''; if ($env:FAKE_MESSAGE) { $message = $env:FAKE_MESSAGE }
            $payload = [ordered]@{ requestId = $requestId; dest = $env:FAKE_DEST; status = $env:FAKE_STATUS; message = $message }
            $json = ($payload | ConvertTo-Json -Compress)
            Write-Output "11-15 18:00:00.000  4242  4242 I MainActivity: automation_result: $json"
            exit 0
        }
        if ($cmd[0] -eq 'uiautomator' -and $cmd.Count -ge 2 -and $cmd[1] -eq 'dump') {
            if ($env:FAKE_SLOW_DUMP -eq '1') { Start-Sleep -Seconds 10 }
            Write-State 'dump_path.txt' $cmd[2]
            Write-Output "UI hierchary dumped to: $($cmd[2])"; exit 0
        }
        if ($cmd[0] -eq 'rm') { exit 0 }
        Write-Output "mock-shell: $($cmd -join ' ')"; exit 0
    }
    'exec-out' {
        if ($rest.Count -ge 2 -and $rest[1] -eq 'cat') { if ($env:FAKE_UI_XML) { Write-Lines $env:FAKE_UI_XML }; exit 0 }
        Write-Output 'mock-exec-out'; exit 0
    }
    default { Write-Output "mock-adb: $joined"; exit 0 }
}