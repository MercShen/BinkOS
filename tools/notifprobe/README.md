# notifprobe — 通知测试探针

三个极小的 APK，用来在真机/模拟器上生成「来自不同 App」的通知，验证 BinkOS 的
Simple Tray / Hub 分页、白名单过滤与页码指示器。

| flavor | applicationId | 每次启动发送 |
|---|---|---|
| alpha | app.notifprobe.alpha | 5 条 |
| beta | app.notifprobe.beta | 4 条 |
| gamma | app.notifprobe.gamma | 3 条 |

每次启动先 `NotificationManager.cancelAll()` 清掉自己的旧通知，再用**同一批固定 ID**
（`1000 + i`）重新发一批：反复启动不会叠加，也不会残留旧条目。
系统可能另生成 `ranker_group` 汇总通知（例如 id=2147483647）；验收5/4/3时数本包的消息ID 1001..1005，排除summary，不以NotificationRecord总数直接判断重复发消息。

## 前置条件

- Android 13（API 33）及以上：`POST_NOTIFICATIONS` 是运行时权限，安装后必须显式授予，
  否则 `notify()` 会被系统静默丢弃（探针会有 Toast，但通知不会出现在 InkOS 里）：
  `adb shell pm grant app.notifprobe.<flavor> android.permission.POST_NOTIFICATIONS`
- Android 12L 及以下：没有 `POST_NOTIFICATIONS` 运行时权限，不要执行该 `pm grant`；通知仍可能被用户的应用/频道设置关闭。
- InkOS 侧需要「通知使用权」已授权（Settings → 通知 → 通知使用权），否则 Simple Tray / Hub 收不到。

## 构建

从本目录运行。设置 `ANDROID_HOME` 或 `local.properties`（`sdk.dir=<Android SDK 路径>`）；若同时有 `ANDROID_SDK_ROOT`，必须与 `ANDROID_HOME` 指向同一 SDK，避免构建因冲突失败。Windows 属性文件路径用 `/` 或转义 `\\`。

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleAlphaDebug :app:assembleBetaDebug :app:assembleGammaDebug
```

Bash:

```bash
./gradlew :app:assembleAlphaDebug :app:assembleBetaDebug :app:assembleGammaDebug
```

产物：`app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk`

## 安装 + 发通知

Windows PowerShell:

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$serial = "<device-serial>"
foreach ($f in @('alpha', 'beta', 'gamma')) {
    & $adb -s $serial install -r "app/build/outputs/apk/$f/debug/app-$f-debug.apk"
    & $adb -s $serial shell pm grant "app.notifprobe.$f" android.permission.POST_NOTIFICATIONS
    & $adb -s $serial shell am start -n "app.notifprobe.$f/app.notifprobe.MainActivity"
}
```

Bash:

```bash
ADB=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb
SERIAL=<device-serial>
for f in alpha beta gamma; do
  "$ADB" -s "$SERIAL" install -r "app/build/outputs/apk/$f/debug/app-$f-debug.apk"
  "$ADB" -s "$SERIAL" shell pm grant "app.notifprobe.$f" android.permission.POST_NOTIFICATIONS
  "$ADB" -s "$SERIAL" shell am start -n "app.notifprobe.$f/app.notifprobe.MainActivity"
done
```

通知文案为「Alpha 消息 N / 来自 Alpha 的测试通知 #N」，应用名为 `Probe Alpha` 等。
