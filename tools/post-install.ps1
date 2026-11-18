#Requires -Version 5.1
<#
  post-install.ps1 - 装完 APK 后的统一验收清单（版本 / 默认桌面 / 进程）。

  为什么单独成脚本：
    文档要求「装包后确认版本、lastUpdateTime、PID，并把被测包设为默认 HOME 再校验」，
    这一步以前靠人记；漏掉就会出现「用 resolve-activity 读了一下就当验过」的情况。
    这里把它固化成一条命令，输出固定四行，汇报直接粘贴。

  用法：
    pwsh -NoProfile -File .\tools\post-install.ps1 -App debug
    pwsh -NoProfile -File .\tools\post-install.ps1 -App release -Serial <序列号>

  退出码：0 全部通过 / 2 参数或设备错误 / 3 版本或 HOME 校验失败
#>
[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$App = 'debug',

    [string]$Serial = '',
    [string]$AdbPath = ''
)

$ErrorActionPreference = 'Stop'
$exitUsage = 2
$exitCheck = 3

$packages = @{ debug = 'app.binkos.debug'; release = 'app.binkos' }
$launcherComponent = 'com.github.gezimos.inkos.MainActivity'
$pkg = $packages[$App]

function Resolve-AdbPath {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    elseif ($env:ADB) { $candidates += $env:ADB }
    $androidSdk = $env:ANDROID_SDK_ROOT
    if (-not $androidSdk) { $androidSdk = $env:ANDROID_HOME }
    if ($androidSdk) { $candidates += (Join-Path $androidSdk 'platform-tools/adb.exe') }
    $candidates += (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe')
    $candidates += 'adb'
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        if (Test-Path $candidate) { return $candidate }
        $cmd = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    throw 'adb not found; pass -AdbPath'
}

function Resolve-DeviceSerial {
    param([string]$Adb, [string]$Wanted)
    $lines = & $Adb devices | Select-Object -Skip 1
    $devices = @($lines | Where-Object { $_ -match '\sdevice$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($Wanted) {
        if ($Wanted -notin $devices) { throw "device $Wanted not online" }
        return $Wanted
    }
    if ($devices.Count -ne 1) { throw "expected exactly one device, found $($devices.Count); pass -Serial" }
    return $devices[0]
}

function Invoke-Adb {
    param([string]$Adb, [string]$DeviceSerial, [string[]]$Command)
    $output = & $Adb -s $DeviceSerial @Command
    return ($output -join "`n")
}

try {
    $adb = Resolve-AdbPath -Explicit $AdbPath
    $deviceSerial = Resolve-DeviceSerial -Adb $adb -Wanted $Serial
} catch {
    Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red
    exit $exitUsage
}

$failures = New-Object System.Collections.Generic.List[string]

# 1. 已安装 + 版本信息
$installPath = Invoke-Adb -Adb $adb -DeviceSerial $deviceSerial -Command @('shell', 'pm', 'path', $pkg)
if ($installPath -notmatch 'package:') {
    $failures.Add("$pkg is not installed")
    $versionName = '-'
    $versionCode = '-'
    $lastUpdate = '-'
} else {
    $dump = Invoke-Adb -Adb $adb -DeviceSerial $deviceSerial -Command @('shell', 'dumpsys', 'package', $pkg)
    $versionName = if ($dump -match 'versionName=(\S+)') { $Matches[1] } else { '-' }
    $versionCode = if ($dump -match 'versionCode=(\S+)') { $Matches[1] } else { '-' }
    $lastUpdate = if ($dump -match 'lastUpdateTime=([^\r\n]+)') { $Matches[1].Trim() } else { '-' }
    if ($versionName -eq '-') { $failures.Add('versionName not readable') }
}

# 2. 默认 HOME：统一走 home.ps1（它自己会补授权并校验 resolve-activity）
$homeScript = Join-Path $PSScriptRoot 'home.ps1'
$homeLine = 'home: (home.ps1 missing)'
if (Test-Path $homeScript) {
    $homeOutput = & pwsh -NoProfile -File $homeScript $App -Serial $deviceSerial -AdbPath $adb 2>&1
    $homeText = ($homeOutput | Out-String)
    $homeLine = (($homeText -split "`r?`n") | Where-Object { $_ -match '^\s*package=' } | Select-Object -First 1)
    if (-not $homeLine) { $homeLine = 'home: (no package= line)' }
    if ($homeText -notmatch 'isDefault=true') { $failures.Add('default HOME is not the tested package') }
} else {
    $failures.Add('tools/home.ps1 missing; cannot verify default HOME')
}

# 3. 进程（launcher 常驻；debug 首次启动前可能为 0，用 status 前先启动）
$pidValue = (Invoke-Adb -Adb $adb -DeviceSerial $deviceSerial -Command @('shell', 'pidof', $pkg)).Trim()
if (-not $pidValue) { $pidValue = '(not running)' }

Write-Host "package=$pkg versionName=$versionName versionCode=$versionCode lastUpdateTime=$lastUpdate"
Write-Host $homeLine
Write-Host "component=$pkg/$launcherComponent"
Write-Host "pid=$pidValue serial=$deviceSerial app=$App"

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) { Write-Host "FAIL: $failure" -ForegroundColor Red }
    exit $exitCheck
}
Write-Host 'OK: version + default HOME verified' -ForegroundColor Green
exit 0
