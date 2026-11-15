# BinkOS AI agent 测试 Playbook

接口、全部 dest、参数闸门及四种结果见 [automation-entry.md](<automation-entry.md>)。本文只讲执行和验收，不再复制接口表。所有例子从仓库根运行，以 debug 包为被测对象。

## 1. 每轮准备

1. 先读用户要求，记录基线 `git status`/版本；不要把既有问题当本轮回归，也不要以“已知问题”跳过验收。
2. `adb devices` 选目标；多设备显式 Serial。命令默认不要碰正式包、其它应用或其它设备。
3. 装新的 debug 后：检查 install 成功 → force-stop → `tools/home.ps1 debug` 设置并校验默认桌面 → 启动 → 核对版本、lastUpdateTime、PID。
4. 请求 `status` 保存 page/pageCount/editMode/bottomWidget/pagerVisible/pagerMargin/homeReset/currentDestination。需修改白名单等未暴露字段时，另外安全保存原值；不要顺手清空。
5. 不跑用户数据恢复/pm clear。必须做破坏测试时先获得具体授权、保存且验证prefs+filesDir完整快照，在隔离测试数据上进行。

```powershell
$ADB = 'C:\AndroidSDK\sdk\platform-tools\adb.exe'  # 改为实际路径
$SERIAL = 'B651D2W2GAPA006000108'                   # 改为实际目标
$PKG = 'app.binkos.debug'
$MAIN = 'com.github.gezimos.inkos.MainActivity'
& $ADB devices
& $ADB -s $SERIAL install -r -t '.\app\build\outputs\apk\debug\app.binkos_v0.2.6-Debug.apk'
if ($LASTEXITCODE -ne 0) { throw 'install failed' }
& $ADB -s $SERIAL shell am force-stop $PKG
pwsh -NoProfile -File ./tools/home.ps1 debug -Serial $SERIAL -AdbPath $ADB
if ($LASTEXITCODE -ne 0) { throw 'default HOME failed' }
pwsh -NoProfile -File ./tools/automate.ps1 -Dest status -Serial $SERIAL -AdbPath $ADB
& $ADB -s $SERIAL shell pidof $PKG
& $ADB -s $SERIAL shell dumpsys package $PKG | Select-String 'versionName=|lastUpdateTime='
```

脚本支持自动唯一设备/adb发现，仓库外脚本不再是依赖。版本字符串未变不意味着运行了新代码，还要核对install与进程/更新时间。

## 2. 每个 UI 场景的黄金流程

**发请求 → 看该requestId结果 → 等目标UI锚点 → 读UI树/看截图 → 核对状态与副作用。**

```powershell
pwsh -NoProfile -File ./tools/automate.ps1 -Dest home -Serial $SERIAL -AdbPath $ADB
pwsh -NoProfile -File ./tools/automate.ps1 -Dest settings -ExpectedText 'SETTINGS' -Serial $SERIAL -AdbPath $ADB
if ($LASTEXITCODE -ne 0) { throw 'settings target not verified' }
pwsh -NoProfile -File ./tools/automate.ps1 -Dest edit_apps -Tab 2 -ExpectedText 'Apps per page' -Serial $SERIAL -AdbPath $ADB
if ($LASTEXITCODE -ne 0) { throw 'function tab not verified' }
pwsh -NoProfile -File ./tools/automate.ps1 -Dest status -Serial $SERIAL -AdbPath $ADB
```

- UI请求只有一次 `dispatched`，**不会再发 applied**；需要ExpectedText/UI树证明目标，标题匹配不能证明整个布局正常。
- applied用于status/写状态/home/编辑模式/翻页；message二次JSON解析。home applied只代表程序化回主页完成，帧可能未画完。
- 当前导航字段只有Fragment entry name，不含设置内部route或sheet；不能用`currentDestination=mainFragment`证明打开了哪个编辑面板。
- rejected/cancelled/超时都不是成功；不能为了绕过闸门反复发写操作。
- UI锚点使用当前语言和真正页面里的文本；比如edit_apps Tab2“Apps per page”、Tab3“Badges and labels”，不能使用旧tab映射。
- UI树bounds与package仅初筛。截图还要看底部遮罩是否到底、边框是否连续、手柄是否居中、最后一行/翻页条是否裁切。像素抽样必须定位**被测面板**，不能拿背景框或安全线当目标边框。
- 不把截图大小/哈希作为正确性证明。哈希相同只说明没有像素变化；分钟跳变/通知新增也能改变哈希而与目标无关。
- 不清logcat；保存requestId、当前PID、目标tag和本次结果。没有明确完成日志的调试探针不能只按am start退出码判定。
- Windows后台读取adb的XML/log必须显式UTF-8；系统代码页可能把◆/中文变成乱码并破坏XML引号。脚本已在父/子进程设置UTF-8，若锚点超时但手工UI树正确，检查编码而不是盲增sleep。

截图（PowerShell7.4+支持原生字节重定向；旧环境走cmd）：

```powershell
$SHOT = Join-Path $PWD 'test-output.png'
cmd /c "`"$ADB`" -s $SERIAL exec-out screencap -p > `"$SHOT`""
& $ADB -s $SERIAL shell uiautomator dump /sdcard/binkos-inspect.xml
& $ADB -s $SERIAL pull /sdcard/binkos-inspect.xml './test-output.xml'
```

必须打开PNG核验；UI树可能含通知等隐私文本，报告只摘必要节点，别全量贴出。

## 3. 状态修改：最小化且可恢复

`pager_*`、`home_reset_*`、`bottom*`、`allowlist*` 会写设置，必须具体获授权；脚本不加AllowStateChanges本地拒绝，App无confirm_write也拒绝。不能把清白名单作为必跑准备。

获准后记录旧值，改目标、status/UI验证，再恢复旧值。输入type别名与存储字符串不同：android→android_widget，shortcuts→search，usage→total_usage，pagedots→page_dots，off→disabled。

```powershell
# 仅在用户允许修改页码留白时运行，原值先从status记录
pwsh -NoProfile -File ./tools/automate.ps1 -Dest pager_margin -Value 4 -AllowStateChanges -Serial $SERIAL -AdbPath $ADB
pwsh -NoProfile -File ./tools/automate.ps1 -Dest status -Serial $SERIAL -AdbPath $ADB
# 验收完成后，用同一命令 -Value <原值> 恢复
```

空通知白名单表示不过滤应用，不代表“零通知”，也不取消系统通知。状态读取不会取消挂起的editmode_on；有效非status请求/HOME/离开前台才取消。不要以中途false判失败。

## 4. 场景回归

### 编辑面板

- editmode_on/off是幂等置位，editmode是取反，自动测试优先on/off；on等待至少600ms。
- edit_bg四Tab：主题/对齐/岛屿圆角/手势；clock/date：样式/功能/手势；apps：样式/图标/功能/通知。
- HOME依赖入口会先回主屏并等待；“设置→edit_apps”不能静默无效，应打开正确Tab或报告rejected。
- 返回、HOME、快速两请求、离开Activity分别回归；不以随意post延迟证明旧窗口销毁。
- 子页面返回可能保留或重置Tab，按实际契约记录，不泛化所有sheet。

### 小组件：两处产品入口

| 入口 | 进入路径 | 组件列表箭头目标 |
|---|---|---|
| 编辑模式 | 已有Android组件本体→WIDGET→Choose App/Choose Widget | WIDGET面板 |
| 设置 | SETTINGS根→Home（Widgets分组）→Choose Widget | CHOOSE APP应用列表 |

没有直达WIDGET或组件选择器dest，不杜撰。bottom_android只改类型，不绑定组件、不直接打开选择器。已有组件时设置入口优先显示该App组件；没有绑定时可能先显示App列表。需要授权/配置Activity的组件可能跳到系统UI，不能只验证列表点击。

点击App列表与组件列表的下滑、横向翻页、搜索、空结果、末页、大字号，检查底部不露主页/截翻页条。编辑模式App列表内部下滑不应关闭；组件预览仍是可滚列表，不能无条件吞其下滑回滚。字体等已有单向滚问题另记。

### 卡片/页码

- shortcuticon/badgeindicator的dest是**预览**：预选当前配置、空onSelect回调不保存；点击只改选择器局部选中态。
- 持久化验证走真正设置入口，重新打开并验证保存值。
- 自适应每页卡片数，不硬断言固定四个；首/中/末页不留半卡，手势与箭头可翻页。
- 页码点当前12dp、其它10dp，大小随screenScale；圆角半径=size/3，固定槽同心。检查形状、圆心与文字岛设置，不仅看数量。
- 主页翻页用home_next/home_prev，读取page；页边界/回主页重置选项可能导致不变；其它区域按其真实分页方向测试。

## 5. 通知探针

完整构建/安装/权限命令见 [探针说明](<../tools/notifprobe/README.md>)，路径是仓库内tools/notifprobe。Android13+探针需要POST_NOTIFICATIONS；BinkOS需要通知访问权，脚本不静默帮用户授予。

- alpha/beta/gamma每次发5/4/3条；发前cancelAll自己的旧通知，再用1000+i固定ID发送。重复启动应保持每包条数，不残留旧ID。
- 先数探针自己的消息ID 1001..1005，再打开tray/hub检验分类/过滤/分页。Android可能额外生成ranker_group汇总，计数应排除summary。用户同时新增通知会影响全局总数，不断言“系统总通知恰好12”。
- 脚本不得用Clear All清所有用户通知。清场只清探针自己的通知，且获得必要授权。
- 托盘和Hub白名单独立；媒体、summary通知过滤不同，数据源可能不同，按产品契约比较而非硬要求屏幕计数相等。

## 6. 构建与离线回归

```powershell
$env:JAVA_HOME = 'C:\Users\ms120\AppData\Local\Programs\jdk-17' # 实际JDK17路径
$env:ANDROID_HOME = 'C:\AndroidSDK\sdk'                       # 实际SDK
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:testReleaseUnitTest --console=plain
if ($LASTEXITCODE -ne 0) { throw 'build/test failed' }
pwsh -NoProfile -File ./tools/tests/run-tests.ps1
if ($LASTEXITCODE -ne 0) { throw 'script tests failed' }
```

- JVM测试覆盖参数/别名/debug限制/确认闸门，fake-adb覆盖脚本结果/设备选择/锚点；不能替代真正的生命周期/渲染测试。
- 检查测试报告里的tests/failures/errors；NO-SOURCE不是测试通过。
- 如跑lint，查看报告分类：目前abortOnError=false，任务退出0也可含Error；不能宣称全项目lint已清。
- 相关改动审查diff，不git add -A夹带其它任务；不要顺便发布、改tag或升级依赖。

## 7. 已知风险与收尾

仍跟踪：字体/长文下滑回滚被全局pre-scroll拦截、字体ⓘ可能丢许可页、备份恢复非事务、冷组件缓存/UI预览主线程耗时。本轮自动化契约/脚本修复不表示这些都解决了。

收尾：退出编辑模式→home关sheet→恢复获准写的旧状态→`tools/home.ps1 ensure debug`校验→记录版本/PID/测试证据→保留用户可人工验收的环境。

```powershell
pwsh -NoProfile -File ./tools/automate.ps1 -Dest editmode_off -Serial $SERIAL -AdbPath $ADB
pwsh -NoProfile -File ./tools/automate.ps1 -Dest home -Serial $SERIAL -AdbPath $ADB
pwsh -NoProfile -File ./tools/home.ps1 ensure debug -Serial $SERIAL -AdbPath $ADB
git status --short
```

本项目当前交付规则：改动累计0.2.6，开发/测试完提交push，**不发版**；具体测试破坏性授权仍单独确认。
