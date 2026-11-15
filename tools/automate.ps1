#Requires -Version 5.1
<#
  automate.ps1 - 用 adb intent 驱动 BinkOS 的 automation dest，并按 request_id 判定结果。

  契约（与 MainActivity 的 automation_result 日志配合）：
    - 目标 App 默认 debug（app.binkos.debug），-App release 切到 app.binkos
    - -Dest 必填，本地只接受 ^[a-z_]+$（长度 <= 48），避免远程 sh 拆分/注入
    - -Tab / -Value / -Type 仅在显式传入时下发；显式 -Type '' 本地拒绝，避免 adb shell 参数歧义
    - 每次生成 request_id(UUID)，随 --es request_id 下发
    - 状态写操作（pager_* / home_reset_* / bottom* / allowlist*）默认本地拒绝；
      必须显式 -AllowStateChanges，才会附加 --ez confirm_write true
    - 结果以 MainActivity 的 automation_result: JSON 为准：
        applied    -> 成功（若给了 -ExpectedText，还会校验 UI 上确有该文字）
        dispatched -> 仅代表已分发；必须有 -ExpectedText 且 UI 校验通过才算成功，否则失败
        rejected   -> 失败（退出码 3，未知 dest/缺 confirm_write 都走这里）
        cancelled  -> 失败（退出码 4）
        超时未见结果 -> 失败（退出码 5）
    - -ExpectedText 用 uiautomator dump 的 XML 轮询匹配 text / content-desc（子串、不区分大小写，
      且要求 bounds 宽高 > 0）。dump 路径带 request_id 唯一化；单次 dump/cat 有独立超时预算，
      避免设备卡住时脚本无限等待；不打印整份 XML
    - 设备：-Serial 显式，或仅一台 device 状态设备，否则拒绝执行（防串机）
    - 不执行 logcat -c，不打印全量日志；只读取 MainActivity 标签下含 request_id 的行

  注意：
    - 只带 -Dest 的 UI 请求通常只会拿到 dispatched，脚本按契约退出码 6（不是成功）；
      要判定 UI 成功请给 -ExpectedText；dispatched 是终态，不会再返回 applied
    - -TimeoutSeconds 是“结果 + UI 锚点”的检查预算；单次 dump/cat 另有 1–4s 预算；
      adb 原生命令不保证硬性封顶，超预算会中止该次检查并以 5/8 退出
    - 当前 App 在 release 拒绝 automation；-App release 只用于验证拒绝路径
      （rejected/退出码 3），不用于日常包测试

  用法：
    .\automate.ps1 -Dest settings -ExpectedText Settings
    .\automate.ps1 -Dest edit_clock -Tab 1 -ExpectedText Clock
    .\automate.ps1 -Dest pager_off -AllowStateChanges
    .\automate.ps1 -Dest bottomwidget -Type quote -AllowStateChanges

  退出码：0 成功 / 2 参数或 dest 校验失败 / 3 rejected / 4 cancelled / 5 超时 / 6 dispatched 无锚点 /
          7 adb 或设备错误 / 8 ExpectedText 未确认
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Dest,

    [int]$Tab,
    [int]$Value,
    [string]$Type,

    [string]$Serial = '',
    [string]$AdbPath = '',

    [ValidateSet('debug', 'release')]
    [string]$App = 'debug',

    [switch]$AllowStateChanges,

    [string]$ExpectedText = '',

    [ValidateRange(3, 120)]
    [int]$TimeoutSeconds = 15
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

$exitUsage = 2
$exitRejected = 3
$exitCancelled = 4
$exitTimeout = 5
$exitDispatchedOnly = 6
$exitAdb = 7
$exitAnchorMissing = 8

$packages = @{
    debug   = 'app.binkos.debug'
    release = 'app.binkos'
}
$launcherComponent = 'com.github.gezimos.inkos.MainActivity'
$stateDestPatterns = @('^pager_', '^home_reset_', '^bottom', '^allowlist')
$destPattern = '^[A-Za-z_]+$'
$typePattern = '^[A-Za-z0-9_.\-]*$'
$component = "$($packages[$App])/$launcherComponent"

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
        [Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Command
    )

    $out = & $Adb -s $DeviceSerial @Command 2>&1
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        throw "adb -s $DeviceSerial $($Command -join ' ') failed (exit $code): $($out -join ' ')"
    }
    return (@($out) -join "`n").Trim()
}

# 有界 adb 调用：设备卡住（例如 uiautomator dump 不返回）时按预算终止，避免脚本无限等待。
function Invoke-AdbTimed {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Command,
        [Parameter(Mandatory = $true)][int]$TimeoutMs
    )

    $job = Start-Job -ArgumentList $Adb, $DeviceSerial, $Command -ScriptBlock {
        param([string]$AdbPath, [string]$Serial, [object[]]$Cmd)
        # Background jobs start with the Windows code page; adb emits UTF-8 XML/logs.
        # Set both encodings inside the child, or a Unicode glyph can swallow an XML quote.
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
        $OutputEncoding = [Console]::OutputEncoding
        $texts = @($Cmd | ForEach-Object { [string]$_ })
        $output = & $AdbPath -s $Serial @texts 2>&1
        $exit = $LASTEXITCODE
        Write-Output ("__EXIT__" + $exit)
        foreach ($line in @($output)) { Write-Output ([string]$line) }
    }

    $completed = Wait-Job -Job $job -Timeout ([Math]::Max(1, [int]($TimeoutMs / 1000)))
    if (-not $completed) {
        try { Stop-Job -Job $job -ErrorAction SilentlyContinue } catch { }
        try { Remove-Job -Job $job -Force -ErrorAction SilentlyContinue } catch { }
        throw "adb -s $DeviceSerial $($Command -join ' ') timed out after ${TimeoutMs}ms"
    }

    $lines = @(Receive-Job -Job $job)
    try { Remove-Job -Job $job -Force -ErrorAction SilentlyContinue } catch { }

    $exitLine = $lines | Where-Object { $_ -like '__EXIT__*' } | Select-Object -Last 1
    $exitCode = 1
    if ($exitLine) { $exitCode = [int]([string]$exitLine -replace '^__EXIT__', '') }
    $text = (@($lines | Where-Object { $_ -notlike '__EXIT__*' }) -join "`n").Trim()
    if ($exitCode -ne 0) {
        throw "adb -s $DeviceSerial $($Command -join ' ') failed (exit $exitCode): $text"
    }
    return $text
}

function Test-UiTextVisible {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial,
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$RequestId,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][datetime]$Deadline
    )

    # 唯一化远端路径，避免并行 request 互相覆盖
    $remote = "/sdcard/.binkos_automate_ui_$RequestId.xml"

    while ((Get-Date) -lt $Deadline) {
        $remainingMs = [int]((($Deadline - (Get-Date)).TotalMilliseconds))
        if ($remainingMs -le 500) { break }
        $budget = [Math]::Min(4000, [Math]::Max(1000, $remainingMs))

        $dumpOk = $false
        try {
            $dump = Invoke-AdbTimed -Adb $Adb -DeviceSerial $DeviceSerial -Command @('shell', 'uiautomator', 'dump', $remote) -TimeoutMs $budget
            if ($dump -match 'dumped to') { $dumpOk = $true }
        } catch {
            $dumpOk = $false
        }

        if ($dumpOk) {
            try {
                $xmlText = Invoke-AdbTimed -Adb $Adb -DeviceSerial $DeviceSerial -Command @('exec-out', 'cat', $remote) -TimeoutMs $budget
                if ($xmlText -and $xmlText -match '<') {
                    $doc = New-Object System.Xml.XmlDocument
                    $doc.LoadXml($xmlText)
                    $rootBounds = $null
                    $rootNode = $doc.SelectSingleNode('//node')
                    if ($rootNode -and $rootNode.Attributes['bounds']) {
                        if ($rootNode.Attributes['bounds'].Value -match '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$') {
                            $rootBounds = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
                        }
                    }
                    foreach ($node in $doc.SelectNodes('//node')) {
                        $candidates = @()
                        if ($node.Attributes['text']) { $candidates += $node.Attributes['text'].Value }
                        if ($node.Attributes['content-desc']) { $candidates += $node.Attributes['content-desc'].Value }
                        $hit = $false
                        foreach ($candidate in $candidates) {
                            if ($candidate -and $candidate.IndexOf($Text, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                                $hit = $true
                                break
                            }
                        }
                        if (-not $hit) { continue }

                        # 只认目标 App 的节点，避免背景应用/系统窗口误命中
                        $nodePackage = ''
                        if ($node.Attributes['package']) { $nodePackage = $node.Attributes['package'].Value }
                        if ($nodePackage -ne $PackageName) { continue }

                        $bounds = ''
                        if ($node.Attributes['bounds']) { $bounds = $node.Attributes['bounds'].Value }
                        if ($bounds -match '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$') {
                            $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
                            $insideRoot = $true
                            if ($rootBounds -ne $null) {
                                $insideRoot = ($x1 -ge $rootBounds[0]) -and ($y1 -ge $rootBounds[1]) -and ($x2 -le $rootBounds[2]) -and ($y2 -le $rootBounds[3])
                            }
                            if (($x2 - $x1) -gt 0 -and ($y2 - $y1) -gt 0 -and $insideRoot) {
                                try { Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Command @('shell', 'rm', '-f', $remote) | Out-Null } catch { }
                                return $true
                            }
                        }
                    }
                }
            } catch {
            }
        }

        Start-Sleep -Milliseconds 500
    }

    try { Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Command @('shell', 'rm', '-f', $remote) | Out-Null } catch { }
    return $false
}

function Get-AutomationResult {
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$DeviceSerial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string]$RequestId
    )

    $pidGuess = ''
    try {
        $pidText = Invoke-Adb -Adb $Adb -DeviceSerial $DeviceSerial -Command @('shell', 'pidof', $PackageName)
        $pidGuess = ($pidText -split '\s+')[0]
    } catch {
        $pidGuess = ''
    }

    $logArgs = @('shell', 'logcat', '-d')
    if ($pidGuess -match '^\d+$') { $logArgs += "--pid=$pidGuess" }
    $logArgs += @('-s', 'MainActivity:I')

    $log = ''
    try {
        $log = Invoke-AdbTimed -Adb $Adb -DeviceSerial $DeviceSerial -Command $logArgs -TimeoutMs 5000
    } catch {
        return $null
    }

    foreach ($line in ($log -split "`n")) {
        if ($line -notmatch 'automation_result:') { continue }
        if ($line.IndexOf($RequestId, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
        $jsonText = $line.Substring($line.IndexOf('automation_result:') + 'automation_result:'.Length).Trim()
        try {
            $obj = $jsonText | ConvertFrom-Json
        } catch {
            continue
        }
        if (-not $obj.requestId) { continue }
        if ([string]$obj.requestId -ne $RequestId) { continue }
        return $obj
    }

    return $null
}

$destValue = $Dest.Trim()
if ($destValue.Length -eq 0 -or $destValue.Length -gt 48 -or $destValue -cnotmatch $destPattern) {
    Write-Host "error: -Dest must match ^[A-Za-z_]+$ and be 1..48 chars (got '$destValue')" -ForegroundColor Red
    exit $exitUsage
}

if ($PSBoundParameters.ContainsKey('Type')) {
    $typeValue = [string]$Type
    if ($typeValue.Length -eq 0) {
        Write-Host 'error: explicit -Type must not be empty' -ForegroundColor Red
        exit $exitUsage
    }
    if ($typeValue.Length -gt 64 -or $typeValue -cnotmatch $typePattern) {
        Write-Host "error: -Type must match ^[A-Za-z0-9_.-]*$ and be <= 64 chars (got '$typeValue')" -ForegroundColor Red
        exit $exitUsage
    }
} else {
    $typeValue = ''
}

$isStateDest = $false
foreach ($pattern in $stateDestPatterns) {
    if ($destValue -match $pattern) { $isStateDest = $true; break }
}

if ($isStateDest -and -not $AllowStateChanges) {
    Write-Host "refused: '$destValue' is a state-changing dest." -ForegroundColor Red
    Write-Host '  re-run with -AllowStateChanges (adds --ez confirm_write true) if you really mean it.' -ForegroundColor Yellow
    exit $exitUsage
}

$requestId = [guid]::NewGuid().ToString()

try {
    $adb = Resolve-AdbPath -Explicit $AdbPath
    $deviceSerial = Resolve-DeviceSerial -Adb $adb -Wanted $Serial
} catch {
    Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red
    exit $exitAdb
}

$startArgs = @('shell', 'am', 'start', '-n', $component, '--es', 'dest', $destValue, '--es', 'request_id', $requestId)
if ($PSBoundParameters.ContainsKey('Tab')) { $startArgs += @('--ei', 'tab', [string]$Tab) }
if ($PSBoundParameters.ContainsKey('Value')) { $startArgs += @('--ei', 'value', [string]$Value) }
if ($PSBoundParameters.ContainsKey('Type')) { $startArgs += @('--es', 'type', $typeValue) }
if ($AllowStateChanges) { $startArgs += @('--ez', 'confirm_write', 'true') }

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

try {
    $startOutput = Invoke-Adb -Adb $adb -DeviceSerial $deviceSerial -Command $startArgs
} catch {
    Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red
    exit $exitAdb
}

if ($startOutput -match '(?m)^\s*Error:') {
    Write-Host "error: am start reported: $($startOutput -replace "`n", ' ')" -ForegroundColor Red
    exit $exitAdb
}

$result = $null
while ((Get-Date) -lt $deadline) {
    $result = Get-AutomationResult -Adb $adb -DeviceSerial $deviceSerial -PackageName $packages[$App] -RequestId $requestId
    if ($result) { break }
    Start-Sleep -Milliseconds 500
}

if (-not $result) {
    Write-Host "timeout: no automation_result for request_id=$requestId within ${TimeoutSeconds}s" -ForegroundColor Red
    exit $exitTimeout
}

$status = [string]$result.status
$message = ''
if ($result.message) { $message = [string]$result.message }
$summary = "request_id=$requestId dest=$destValue status=$status"
if ($message) { $summary += " message=$message" }

switch ($status) {
    'rejected' {
        Write-Host "refused by app: $summary" -ForegroundColor Red
        exit $exitRejected
    }
    'cancelled' {
        Write-Host "cancelled: $summary" -ForegroundColor Red
        exit $exitCancelled
    }
    'applied' {
        if ($ExpectedText) {
            $anchorDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
            if (-not (Test-UiTextVisible -Adb $adb -DeviceSerial $deviceSerial -Text $ExpectedText -RequestId $requestId -PackageName $packages[$App] -Deadline $anchorDeadline)) {
                Write-Host "anchor missing: $summary (expected text '$ExpectedText' not visible within ${TimeoutSeconds}s)" -ForegroundColor Red
                exit $exitAnchorMissing
            }
            Write-Host "$summary anchor='$ExpectedText'"
        } else {
            Write-Host $summary
        }
        exit 0
    }
    'dispatched' {
        if (-not $ExpectedText) {
            Write-Host "dispatched only: $summary (pass -ExpectedText to confirm the target UI)" -ForegroundColor Red
            exit $exitDispatchedOnly
        }
        $anchorDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
        if (-not (Test-UiTextVisible -Adb $adb -DeviceSerial $deviceSerial -Text $ExpectedText -RequestId $requestId -PackageName $packages[$App] -Deadline $anchorDeadline)) {
            Write-Host "anchor missing: $summary (expected text '$ExpectedText' not visible within ${TimeoutSeconds}s)" -ForegroundColor Red
            exit $exitAnchorMissing
        }
        Write-Host "$summary anchor='$ExpectedText'"
        exit 0
    }
    default {
        Write-Host "unknown status: $summary" -ForegroundColor Red
        exit $exitTimeout
    }
}
