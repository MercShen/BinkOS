#Requires -Version 5.1
<#
  home.ps1 - 切换/校验默认桌面（launcher），仓库内独立版本。

  与仓库外的 ../tools/home.ps1 行为一致，但：
    - 不写死 adb 路径：优先 -AdbPath，其次 PATH 里的 adb，再其次 $env:ANDROID_HOME、
      $env:ANDROID_SDK_ROOT 下的 platform-tools
    - 设备必须显式 -Serial 或“仅一台 device 状态设备”，否则拒绝执行（防串机）
    - debug / release / ensure 三种动作，设置后都做一次状态校验；校验失败退出码 9
    - 每一条 adb 命令都检查原生命令退出码，非 0 直接失败（退出码 7）

  注意：release 会把日常桌面切到 app.binkos；ensure 只校验/修复目标，不会顺带改其它设置。

  用法：
    .\home.ps1                          # 查看当前默认桌面 + 角色持有者
    .\home.ps1 debug                    # 设为 Bink OS Debug
    .\home.ps1 release                  # 设为 Bink OS
    .\home.ps1 ensure debug             # 校验，不满足则修复为 debug
    .\home.ps1 ensure release -Serial X # 指定设备

  退出码：0 成功 / 2 参数错误 / 7 adb 或设备错误 / 9 校验失败
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('', 'debug', 'release', 'ensure')]
    [string]$Target = '',

    [Parameter(Position = 1)]
    [ValidateSet('', 'debug', 'release')]
    [string]$Ensure = '',

    [string]$Serial = '',
    [string]$AdbPath = ''
)

$ErrorActionPreference = 'Stop'

$roleName = 'android.app.role.HOME'
$packages = @{
    debug   = 'app.binkos.debug/com.github.gezimos.inkos.MainActivity'
    release = 'app.binkos/com.github.gezimos.inkos.MainActivity'
}

function Resolve-AdbPath {
    param([string]$Explicit)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) {
            throw [System.ArgumentException]::new("adb not found at -AdbPath: $Explicit")
        }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }

    $cmd = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Source }

    $roots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $env:ANDROID_SDK_HOME) | Where-Object { $_ }
    foreach ($root in $roots) {
        foreach ($name in @('adb.exe', 'adb')) {
            $candidate = Join-Path (Join-Path $root 'platform-tools') $name
            if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
        }
    }

    throw [System.ArgumentException]::new('adb not found: pass -AdbPath or put adb on PATH / set ANDROID_HOME')
}

function Resolve-DeviceSerial {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [string]$Wanted
    )

    $out = & $Adb devices 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "adb devices failed (exit $code): $($out -join ' ')" }

    $online = @()
    foreach ($line in @($out)) {
        $text = [string]$line
        if ($text -match '^\s*(\S+)\s+device\s*$') { $online += $Matches[1] }
    }

    if ($Wanted) {
        if ($online -notcontains $Wanted) {
            throw "device '$Wanted' is not in 'device' state (online: $($online -join ', '))"
        }
        return $Wanted
    }

    if ($online.Count -eq 0) { throw 'no device in state "device"; connect one or pass -Serial' }
    if ($online.Count -gt 1) {
        throw "multiple devices online ($($online -join ', ')); pass -Serial to choose one"
    }
    return $online[0]
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial,
        [Parameter(Mandatory = $true)][string[]]$Command
    )

    $out = & $Adb -s $DeviceSerial @Command 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        throw "adb -s $DeviceSerial $($Command -join ' ') failed (exit $code): $($out -join ' ')"
    }
    return (@($out) -join "`n").Trim()
}

function Get-HomeState {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial
    )

    $resolve = Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Command @(
        'shell', 'cmd', 'package', 'resolve-activity', '-a', 'android.intent.action.MAIN',
        '-c', 'android.intent.category.HOME'
    )
    $role = Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Command @(
        'shell', 'cmd', 'role', 'get-role-holders', $roleName
    )

    $resolvedPackage = ''
    $isDefault = ''
    foreach ($line in ($resolve -split "`n")) {
        if (-not $resolvedPackage -and $line -match 'packageName=(\S+)') { $resolvedPackage = $Matches[1] }
        if (-not $isDefault -and $line -match 'isDefault=(\S+)') { $isDefault = $Matches[1] }
    }

    [pscustomobject]@{
        Package     = $resolvedPackage
        IsDefault   = $isDefault
        RoleHolders = ($role -replace '\s+', ',').Trim(',')
    }
}

function Show-HomeState {
    param(
        [Parameter(Mandatory = $true)]$State,
        [string]$Serial
    )
    "serial : $Serial"
    "package=$($State.Package)  isDefault=$($State.IsDefault)  roleHolders=$($State.RoleHolders)"
}

function Test-HomeApplied {
    param($State, [string]$Package)
    return ($State.Package -eq $Package -and $State.IsDefault -eq 'true' -and
        @($State.RoleHolders -split ',') -contains $Package)
}

function Assert-PackageInstalled {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial,
        [Parameter(Mandatory = $true)][string]$Package
    )
    $out = Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Command @('shell', 'pm', 'list', 'packages', $Package)
    # 精确整行匹配，避免 app.binkos 匹配到 app.binkos.debug
    if ($out -notmatch ('(?m)^\s*package:' + [regex]::Escape($Package) + '\s*$')) {
        throw "package '$Package' is not installed on $DeviceSerial"
    }
}

function Set-Home {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial
    )

    $component = $packages[$Name]
    $package = $component.Split('/')[0]
    Assert-PackageInstalled -Adb $Adb -DeviceSerial $DeviceSerial -Package $package

    Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Command @('shell', 'cmd', 'package', 'set-home-activity', $component) | Out-Null
    Start-Sleep -Milliseconds 800

    $state = Get-HomeState -Adb $Adb -DeviceSerial $DeviceSerial
    if (Test-HomeApplied -State $state -Package $package) {
        "target : $Name ($component)"
        Show-HomeState -State $state -Serial $DeviceSerial
        return
    }

    # 半坏状态修复：补一次 role 授权后重设，再校验
    Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Command @('shell', 'cmd', 'role', 'add-role-holders', $roleName, $package) | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Command @('shell', 'cmd', 'package', 'set-home-activity', $component) | Out-Null
    Start-Sleep -Milliseconds 800

    $state = Get-HomeState -Adb $Adb -DeviceSerial $DeviceSerial
    if (Test-HomeApplied -State $state -Package $package) {
        "target : $Name ($component)  [repaired]"
        Show-HomeState -State $state -Serial $DeviceSerial
        return
    }

    "target : $Name ($component)"
    Show-HomeState -State $state -Serial $DeviceSerial
    throw "home not applied: expected $package, got '$($state.Package)' (isDefault=$($state.IsDefault))"
}

if ($Target -eq '' -and $Ensure -ne '') { $Target = 'ensure' }
if ($Target -eq 'ensure' -and $Ensure -eq '') {
    Write-Host 'usage: home.ps1 ensure <debug|release>' -ForegroundColor Red
    exit 2
}

try {
    $adb = Resolve-AdbPath -Explicit $AdbPath
    $deviceSerial = Resolve-DeviceSerial -Adb $adb -Wanted $Serial
} catch {
    Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red
    exit 7
}

try {
    switch ($Target) {
        '' {
            $state = Get-HomeState -Adb $adb -DeviceSerial $deviceSerial
            Show-HomeState -State $state -Serial $deviceSerial
            exit 0
        }
        'ensure' {
            $state = Get-HomeState -Adb $adb -DeviceSerial $deviceSerial
            Show-HomeState -State $state -Serial $deviceSerial
            $wantPackage = $packages[$Ensure].Split('/')[0]
            if (Test-HomeApplied -State $state -Package $wantPackage) {
                "ensure : ok ($Ensure)"
                exit 0
            }
            try {
                Set-Home -Name $Ensure -Adb $adb -DeviceSerial $deviceSerial
                "ensure : repaired -> $Ensure"
                exit 0
            } catch {
                Write-Host "verify failed: $($_.Exception.Message)" -ForegroundColor Red
                exit 9
            }
        }
        default {
            try {
                Set-Home -Name $Target -Adb $adb -DeviceSerial $deviceSerial
                exit 0
            } catch {
                Write-Host "verify failed: $($_.Exception.Message)" -ForegroundColor Red
                exit 9
            }
        }
    }
} catch {
    Write-Host "adb error: $($_.Exception.Message)" -ForegroundColor Red
    exit 7
}