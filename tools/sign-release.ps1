# sign-release.ps1 — 用仓库外的 keystore 构建签名 release 包
#
# 为什么有这个脚本：gradle 只认环境变量 KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS /
# KEY_PASSWORD（见 app/build.gradle.kts 与 README），而密钥材料放在仓库外的
# `.secrets/binkos/keystore.properties`。以前靠人手 export，于是 properties 里那个
# 绝对路径在换机器后悄悄失效、没人发现（2026-11-28 就是因此构建失败）。
# 这个脚本是唯一的消费者：它负责找 properties、把相对路径解析成绝对路径、
# 只在子进程里注入环境变量、构建并校验产物。
#
# 用法：
#   pwsh -NoProfile -File .\tools\sign-release.ps1
#   pwsh -NoProfile -File .\tools\sign-release.ps1 -PropsPath D:\somewhere\keystore.properties
#
# 安全约定：任何情况下都不打印密码值；缺键时只报键名。

param(
    [string]$PropsPath = '',
    [switch]$SkipBuild      # 只解析并打印诊断（不含密码），不跑 gradle
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$repoRoot = Split-Path -Parent $PSScriptRoot

# ---- 1) 定位 properties ----
$candidates = @()
if ($PropsPath) { $candidates += $PropsPath }
if ($env:BINKOS_KEYSTORE_PROPS) { $candidates += $env:BINKOS_KEYSTORE_PROPS }
$candidates += Join-Path $repoRoot '.secrets\binkos\keystore.properties'
$candidates += Join-Path (Split-Path -Parent $repoRoot) '.secrets\binkos\keystore.properties'
$propsFile = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $propsFile) {
    throw "找不到 keystore.properties，试过：`n  " + ($candidates -join "`n  ")
}
$propsDir = Split-Path -Parent $propsFile
Write-Host "properties: $propsFile"

# ---- 2) 读取（只取键值，不打印值）----
$props = @{}
Get-Content $propsFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*([^=#]+?)\s*=\s*(.+?)\s*$') { $props[$Matches[1]] = $Matches[2] }
}
$need = @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')
$missing = @($need | Where-Object { -not $props.ContainsKey($_) -or [string]::IsNullOrEmpty($props[$_]) })
if ($missing.Count) { throw "keystore.properties 缺键: $($missing -join ', ')" }

# ---- 3) storeFile 相对路径解析（这次要修的核心）----
$store = $props['storeFile']
if (-not [System.IO.Path]::IsPathRooted($store)) {
    $store = Join-Path $propsDir $store
}
if (-not (Test-Path $store)) {
    throw "storeFile 指向不存在的文件: $store （properties 里写的是 '$($props['storeFile'])'；相对路径按 properties 所在目录解析）"
}
Write-Host "keystore  : $store"

if ($SkipBuild) {
    Write-Host "诊断模式：键齐全、keystore 存在，未构建。"
    return
}

# ---- 4) 只在子进程注入环境变量 ----
# gradlew 必须在仓库根目录调用，调用方的 CWD 可能是任何地方
Set-Location $repoRoot
$env:KEYSTORE_FILE = $store
$env:KEYSTORE_PASSWORD = $props['storePassword']
$env:KEY_ALIAS = $props['keyAlias']
$env:KEY_PASSWORD = $props['keyPassword']
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = 'C:\AndroidSDK\sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

$log = Join-Path $env:TEMP 'binkos-sign-release.log'
cmd /c "gradlew.bat :app:assembleRelease --console=plain > `"$log`" 2>&1"
$code = $LASTEXITCODE
if ($code -ne 0) {
    Get-Content $log -ErrorAction SilentlyContinue | Select-String -Pattern '^e: |FAILED|FAILURE' | Select-Object -First 8
    throw "assembleRelease 失败 (exit=$code)，完整日志: $log"
}

# ---- 5) 校验产物与签名 ----
$apk = Get-ChildItem (Join-Path $repoRoot 'app\build\outputs\apk\release') -Filter '*-Signed.apk' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "构建成功但没找到 *-Signed.apk" }
Write-Host "产物: $($apk.FullName)  ($([int]($apk.Length / 1MB))MB, $($apk.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')))"

$bt = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$signer = Join-Path $bt.FullName 'apksigner.bat'
if (Test-Path $signer) {
    $fp = & $signer verify --print-certs $apk.FullName 2>&1 | Select-String -Pattern 'SHA-256' | Select-Object -First 1
    if ($fp) { Write-Host ("签名: " + $fp.Line.Trim()) }
}
Write-Host "完成。"
