# notifprobe — 通知测试探针

三个极小的 APK，用来在真机/模拟器上生成「来自不同 App」的通知，验证 BinkOS 的
Simple Tray / Hub 分页、白名单过滤与页码指示器。

| flavor | applicationId | 每次启动发送 |
|---|---|---|
| alpha | app.notifprobe.alpha | 5 条 |
| beta | app.notifprobe.beta | 4 条 |
| gamma | app.notifprobe.gamma | 3 条 |

每次启动会先 `cancelAll()` 清掉自己的旧通知，再重新发一批，方便反复测试。

## 构建

```bash
# 需要 local.properties：sdk.dir=<Android SDK 路径>
./gradlew :app:assembleAlphaDebug :app:assembleBetaDebug :app:assembleGammaDebug
```

产物：`app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk`

## 安装 + 发通知

```bash
for f in alpha beta gamma; do
  adb install -r app/build/outputs/apk/$f/debug/app-$f-debug.apk
  adb shell pm grant app.notifprobe.$f android.permission.POST_NOTIFICATIONS
  adb shell am start -n app.notifprobe.$f/app.notifprobe.MainActivity
done
```

通知文案为「Alpha 消息 N / 来自 Alpha 的测试通知 #N」，应用名为 `Probe Alpha` 等。