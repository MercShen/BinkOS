# Bink OS AI agent 测试 Playbook

接口、全部 dest、参数闸门及四种结果见 [automation-entry.md](<automation-entry.md>)。本文只讲执行和验收，不再复制接口表。所有例子从仓库根运行，以 debug 包为被测对象。

## 1. 每轮准备

1. 先读用户要求，记录基线 `git status`/版本；不要把既有问题当本轮回归，也不要以“已知问题”跳过验收。
2. `adb devices` 选目标；多设备显式 Serial。命令默认不要碰正式包、其它应用或其它设备。
3. 装新的 debug 后：**先记录当前默认 HOME**（`resolve-activity` + `role get-role-holders`）→ install 成功 → force-stop → `tools/home.ps1 debug` 设置并校验默认桌面 → 启动 → 核对版本、lastUpdateTime、PID。`install -r` 可能把 HOME 解析重置成 `android` resolver，`roleHolders` 仍显示 debug 也不代表正常；必须以 `isDefault=true` / `resolve-activity` 指向 debug 为准。
4. 请求 `status` 保存 page/pageCount/editMode/bottomWidget/pagerVisible/pagerMargin/homeReset/currentDestination。需修改白名单等未暴露字段时，另外安全保存原值；不要顺手清空。
5. 不跑用户数据恢复/pm clear。必须做破坏测试时先获得具体授权、保存且验证prefs+filesDir完整快照，在隔离测试数据上进行。

```powershell
$ADB = 'C:\AndroidSDK\sdk\platform-tools\adb.exe'  # 改为实际路径
$SERIAL = 'B651D2W2GAPA006000108'                   # 改为实际目标
$PKG = 'app.binkos.debug'
$MAIN = 'com.github.gezimos.inkos.MainActivity'
& $ADB devices
# 先记录安装前默认桌面，测试后要恢复时用
& $ADB -s $SERIAL shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
& $ADB -s $SERIAL shell cmd role get-role-holders android.app.role.HOME
& $ADB -s $SERIAL install -r -t '.\app\build\outputs\apk\debug\app.binkos_v0.2.7-Debug.apk'
if ($LASTEXITCODE -ne 0) { throw 'install failed' }
& $ADB -s $SERIAL shell am force-stop $PKG
pwsh -NoProfile -File ./tools/home.ps1 debug -Serial $SERIAL -AdbPath $ADB
if ($LASTEXITCODE -ne 0) { throw 'default HOME failed' }
pwsh -NoProfile -File ./tools/automate.ps1 -Dest status -Serial $SERIAL -AdbPath $ADB
& $ADB -s $SERIAL shell pidof $PKG
& $ADB -s $SERIAL shell dumpsys package $PKG | Select-String 'versionName=|lastUpdateTime='
```

脚本支持自动唯一设备/adb发现，仓库外脚本不再是依赖。版本字符串未变不意味着运行了新代码，还要核对install与进程/更新时间。


### 1.1 设备测试产物管理

自动化/调试只要会在设备上生成文件，就必须统一收纳，不得散落在 `/sdcard` 根目录。

- 主目录：`/sdcard/BinkOS-Test/`
- 版本目录：`/sdcard/BinkOS-Test/v<versionName>/`（例如 `v0.2.7`；版本未知用 `unversioned`）
- 子目录：
  - `screenshots/`：截图
  - `ui/`：`uiautomator dump` XML
  - `tmp/`：本轮临时文件，结束删除
  - `evidence/`：需要复用的证据/基准
- 文件名：`<简短目的>-<yyyyMMdd-HHmmss>.<ext>`，例如 `onboarding-refresh-demo-20261117-032500.png`。
- 不用 `/sdcard/*.png`、`/sdcard/*.xml` 这种根目录落盘；脚本必须 `mkdir -p` 目标目录。
- 设备产物需要带回仓库时，落到工作区的版本化目录（如 `emulator/artifacts/<version>/`），不要把设备根目录文件直接拷散。

```powershell
$ROOT = '/sdcard/BinkOS-Test/v0.2.7'
& $ADB -s $SERIAL shell mkdir -p "$ROOT/screenshots" "$ROOT/ui" "$ROOT/tmp" "$ROOT/evidence"
& $ADB -s $SERIAL shell screencap -p "$ROOT/screenshots/onboarding-refresh-demo-20261117-032500.png"
& $ADB -s $SERIAL shell uiautomator dump "$ROOT/ui/settings-extras-20261117-032500.xml"
```

历史遗留的 `/sdcard` 根文件不自动清理；由设备所有者决定是否删除。新调试一律按上述路径。

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

### 小部件：两处产品入口

| 入口 | 进入路径 | 小部件列表箭头目标 |
|---|---|---|
| 编辑模式 | 已有 Android 小部件本体→WIDGET→Choose App/Choose Widget | WIDGET面板 |
| 设置 | SETTINGS根→Home（Widgets分组）→Choose Widget | CHOOSE APP应用列表 |

没有直达 WIDGET 或小部件选择器 dest，不杜撰。bottom_android只改类型，不绑定小部件、不直接打开选择器。已有小部件时设置入口优先显示该 App 小部件；没有绑定时可能先显示App列表。需要授权/配置 Activity 的小部件可能跳到系统UI，不能只验证列表点击。

点击 App 列表与小部件列表的下滑、横向翻页、搜索、空结果、末页、大字号，检查底部不露主页/截翻页条。编辑模式App列表内部下滑不应关闭；小部件预览仍是可滚列表，不能无条件吞其下滑回滚（**第二级小部件列表的滚动是设计选择，不是缺陷**，见 [ui-scroll-vs-paged.md](ui-scroll-vs-paged.md)「有意保留滚动」，不要把它当分页改造的 TODO 提）。字体等已有单向滚问题另记。

**必测边界：应用列表只剩一页。** 在应用列表搜索框里过滤到只剩 1 个应用（如 `Maps`）、或设备上带小部件的应用本来就少，列表 `pageCount == 1`，`PagedNavRow` 不发射节点；此处曾崩在 `WidgetPickerContent.kt` 的 `subcompose("footer").single()`。验收以“搜索到单页仍能选中应用”为准，不只验收多页翻页。Bink OS issue #2 即此场景。

### 卡片/页码

- shortcuticon/badgeindicator的dest是**预览**：预选当前配置、空onSelect回调不保存；点击只改选择器局部选中态。
- 持久化验证走真正设置入口，重新打开并验证保存值。
- 自适应每页卡片数，不硬断言固定四个；首/中/末页不留半卡，手势与箭头可翻页。
- 页码点当前12dp、其它10dp，大小随screenScale；圆角半径=size/3，固定槽同心。检查形状、圆心与文字岛设置，不仅看数量。
- 主页翻页用home_next/home_prev，读取page；页边界/回主页重置选项可能导致不变；其它区域按其真实分页方向测试。

## 5. 通知探针

完整构建/安装/权限命令见 [探针说明](<../tools/notifprobe/README.md>)，路径是仓库内tools/notifprobe。Android13+探针需要POST_NOTIFICATIONS；Bink OS需要通知访问权，脚本不静默帮用户授予。

- alpha/beta/gamma每次发5/4/3条；发前cancelAll自己的旧通知，再用1000+i固定ID发送。重复启动应保持每包条数，不残留旧ID。
- 先数探针自己的消息ID 1001..1005，再打开tray/hub检验分类/过滤/分页。Android可能额外生成ranker_group汇总，计数应排除summary。用户同时新增通知会影响全局总数，不断言“系统总通知恰好12”。
- 脚本不得用Clear All清所有用户通知。清场只清探针自己的通知，且获得必要授权。
- 托盘和Hub白名单独立；媒体、summary通知过滤不同，数据源可能不同，按产品契约比较而非硬要求屏幕计数相等。
- Hub 分类 tab 支持整页任意位置左右短滑切换（48dp；「≥32dp 且够快」的 flick 也切），与竖向翻页共用一套「总位移主导轴」判定（`ui/compose/AxisSwipeGestures.kt`）。回归点：斜向 30–40° 的左右短滑必须切 tab、不能变成翻页（横竖两套探测器抢 slop 时就是这个症状，末页上表现为“划了没反应”）；竖向快短滑（<48dp）不翻页。单条消息删除用行右侧小叉，行上横滑也切 tab，不再触发消除。旧 commit `cdd943c` 曾随反向长滑一起删除，2026-11-16 恢复。
- Hub 的反向长滑关闭同样依赖 `OpenHub` 已绑定打开方向；当前绑定下滑打开，所以上滑关闭。未绑定时不关闭，也不播动画。
- Hub 侧边页码点验收：点在屏幕边缘与消息框边框之间的水平中点；左右两种 `homePagerAlign` 都要量一下两段间距是否一致。
- Hub 清除全部按钮固定在“满页消息框底边与屏幕底边”的中点，不随每页消息数量上下移动；点击后清空并自动回主页。锚点行高只测一次并持久化（`HUB_CLEAR_ANCHOR_ROW_HEIGHT`，换字号/缩放/图标/密度才重测）：通知行高受字体度量影响（同列表 CJK 行 140px、纯拉丁/系统行 136px），用“当前页首行”实测值会让末页按钮上移 14px。回归验收：翻到最后一页、把通知清到只剩 1 页、`force-stop` 后重开，三次截图里按钮文字的 y 必须一致；改通知行内部布局时把 HubScreen 锚点 key 的 `v1` 前缀加一。
- Hub 行右侧小叉只对可清除通知显示；`FLAG_NO_CLEAR` / `FLAG_ONGOING_EVENT` / `isOngoing` 的系统通知不显示小叉（USB 调试通知是最方便的回归样本）。
- Hub / Tray / 主页通知计数可整体隐藏系统通知：设置 → 通知 → 底部「高级」板块 → 「隐藏系统通知」（默认关；英文 `Advanced` / `Hide System Notification`）。只隐藏「`isOngoing` 或 `NO_CLEAR`/`ONGOING`」且发件包是系统应用（`ApplicationInfo.FLAG_SYSTEM`）的通知；电话类（`CATEGORY_CALL` / `CATEGORY_MISSED_CALL`）永不隐藏，第三方常驻通知（下载/录音/导航）保留。只过滤 Bink OS 自己的视图与计数（Hub、Tray、主页计数、通知组件、Letters 入会话），系统通知栏不受影响（`NotificationFilter.shouldHideFromViews`）。开关打开时还会清掉已记进 Letters 的同类会话。
- 回归样本（模拟器 824×1648）：① 包 `android` 的 `Serial console enabled`（ongoing）——开→Hub 全部 4→3、主页计数 4→3，关→恢复；② 去电（`am start -a android.intent.action.CALL -d tel:10086`，拨号器通知 `flags=0x206a`、`CATEGORY_CALL`）——**Hub 不显示**、**Tray 仍显示**、Tray 顶部数字与主页计数都不含它、不进 Letters；挂断后消失；③ `cmd notification post` 发的可清除通知始终显示；④ 未接来电（可清除）→ Hub「电话」tab 显示、计入计数（模拟器造不出来，真机回归）。模拟器**没有** NFC 服务（`NfcAdapter=null`）、也不产生 USB 调试/USB 连接通知（`svc usb setFunctions mtp`、`setprop sys.usb.config adb` 均无通知），这两类要真机回归；来电/未接来电需要模拟器 console（本机 console 无响应），也放真机。
- 通话状态行的本地化（`CallStatusText`）：正文是拨号器写进 `android.text` 的，Bink OS 只在「文案为空」或「等于已知英文短语（Ongoing call / Calling… / Missed call）」时替换成本地化串 `call_status_ongoing/calling/missed`，其余原样显示，避免吞掉联系人/时长。模拟器两种环境都验过：英文系统 + Bink OS 中文界面 → 显示「通话中」（替换生效）；系统切到 zh-CN 重启后，拨号器自己写「正在通话」，原样显示。
- 通知没有正文时的分类兜底文案统一走 `NotificationFallbackText`（`notif_fallback_*` 字符串资源），跟随 App 语言；`CATEGORY_CALL` 不在兜底表里，由 `CallStatusText` 区分「通话中 / 未接来电」。回归：切语言后兜底文案要跟着变（英文 UI 显示 New message，中文 UI 显示 新消息）。
- Letters 没有独立「启用」总开关：`OpenLettersScreen` 动作与 Letters 快捷方式始终可用，没绑手势自然没有入口；`Letters` 文案里不再带「窗口」。
- Hub 分类 tab 的映射：**消息** = `CATEGORY_MESSAGE`/`CATEGORY_SOCIAL` 或包名在硬编码 `messagingPackages` 名单里（兜住不发 category 的 IM）；**电话** = `CATEGORY_CALL`/`CATEGORY_MISSED_CALL`，或发件包是默认拨号器（`TelecomManager.defaultDialerPackage` + 常见包名集合；部分 ROM 的通话/未接来电通知不打 category）；**邮件/日程** = `CATEGORY_EMAIL`/`CATEGORY_EVENT`；**其他** = 不属于消息、也不属于电话且 category 不在已知集合的补集。回归：去电时电话 tab 计数为 0（通话中不进 Hub）、未接来电进电话 tab。
- Simple Tray 行右侧同样有小叉（仅可清除通知）；点击删除单条，长按仍保留媒体停止/删除行为。
- Extra → Page Turn Animation Style 只在翻页动画开关启用时出现：**两种样式都只走 `setEinkAnimationTimeout` 一个临时接口**——`Directional` 用方向值 1–4；`Ripple` 打开下发 10、关闭下发 9、窗口 800ms，到期厂商服务自己恢复。**不要用 `setEinkAnimationForPackage`/`appEinkAnimation`**：那是 e-ink center 里「每应用动画」的持久字段，写它等于替用户常开（开屏、时钟、角标都会播），还会覆盖用户自己选的值。**Ripple 的规则是「只有主页翻页留 Directional，其它一律 Ripple」**：靠 `navController.previousBackStackEntry` 判断前进/返回（进入下一级 10、返回上一级/回主页 9），与长滑绑定无关，所以壁纸/指南这类没绑手势的页面也会播；Ripple 下发失败只记日志，**不再回退 Directional**（回退会表现为「返回突然变成方向式擦除」）。从其它 App 回主屏（没有导航变化）时**两种样式都不补动画**：那次整屏刷新是厂商在顶层应用切换时做的，补 Ripple 9 会让基础全刷带上波纹（2026-11-24 起移除该补偿）。
- 主屏「主屏页重置 / HOME 键回第一页」是自动归位，`setCurrentPage(0, animate = false)` 不播翻页动画：否则 Ripple 样式下返回主屏会看到一次方向式翻页（B651 实测：开了页重置 + 停在非首页时必现）。只有用户手动翻页才播 Directional。
- 方向动画的保持窗口是 **280ms**（`BigmeEink.DURATION_MS`；2026-11-18 曾压到 100ms，2026-11-24 因「关闭抽屉返回主屏」首帧可能晚于窗口调回 280ms），连击节流 **300ms**（`THROTTLE_MS`，比窗口多 20ms 余量）。回归看 `logcat -s BigmeEink DisplayPolicyService`：`eink animation close dir=... type=.. duration=280ms` + `setEinkAnimationTimeout: type=?, duration=280ms`，280ms 后 `eink animation timeout, restoring`；被节流时会看到 `eink animation throttled: ...`。
- Settings → Extras 的「智能刷新模式」（默认关）：**编辑模式、短滑区域编辑器**打开时把本包波形切到大我的 **Extreme** `179`，关闭后回到 Regal `180`。底部面板不参与——面板多是点击型分页列表，且每次切波形都会触发一次策略 apply（观感是一次全刷），「开快捷菜单 → 进设置」这种流程会白闪。回归看 `logcat -s EinkInteractionMode BigmeEink`：进入 `enter fast mode token=edit_mode` + `refreshMode=179`，退出 `leave fast mode … -> Regal` + `refreshMode=180`；大我 Refresh mode 菜单对应：HD 256 = `0x800000B1`、**Regal = 180**、Fast = 178、**Extreme = 179**、Hyper = 183（2026-11-18 截图核对）。嵌套（编辑模式里开面板）不能卡在 179；打开面板本身不应出现任何 `refreshMode=` 日志。
- 厂商增强开关（系统全刷 / 刷新动画 / 智能刷新模式）的显示只看 `EinkCapabilities` 的能力位，不直接判断 `BigmeEink.isAvailable`；换品牌时实现后端 + 声明能力位即可，界面不改。回归：非大我设备（x86_64 模拟器）装 debug 包，设置 →「更多功能」只剩「自动墨水屏刷新 / 刷新延迟 / 音量键翻页」，onboarding 指示器是 **1/7**（没有厂商页）；B651 上三项仍在，指示器 1/8。 调试后门（仅 debug）：`--es dest vendor_switches_on --ez confirm_write true` 可在模拟器上强行显示三项专属开关（只改可见性，不伪造接口），`vendor_switches_off` 还原；关掉 App 重进仍生效（写在 `DEBUG_FORCE_VENDOR_SWITCHES`）。
- 设置页允许从图标/齿轮直接点入：`OpenSettings` 未绑定长滑时也会用默认 R2L 打开、L2R 关闭；但反向长滑仍然不关闭（仍需显式绑定）。
- Simple Tray 的反向长滑关闭与 Hub 同规则：`OpenSimpleTray` 绑定时（当前下滑开→上滑关）才关闭；未绑定不关闭，也不播动画。
- 媒体通知（Spotify/网易云/Audible 等 `CATEGORY_TRANSPORT`）不计入主页全局通知数量，也不计入 Tray 顶部数字；Tray 是否显示媒体卡由 Simple Tray 白名单决定；Letters 永不显示 transport/媒体持续通知。

### Regal/HD256 刷新动画引导

Debug 构建可直接用后门打开，不需要清数据/改开关（见 [automation-entry.md](<automation-entry.md#firsttime首次引导后门debug>)）：

```powershell
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest firsttime --es guide refresh_animation
# 关闭弹窗，不写任何已读状态：
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest firsttime --es guide close
```

需要验证真实页面点击路径时再用下面的清数据流程（会清掉本包设置；仅在测试机/获得授权时用）：

```powershell
& $ADB -s $SERIAL shell pm clear $PKG
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest onboarding --ei onboarding_page 7 --ez confirm_write true
if ($LASTEXITCODE -ne 0) { throw 'onboarding page 7 failed' }
# 5/8 页显示「刷新动画」开关；开启后底部出现「刷新动画样式」（默认方向式）；
# 首次开启会弹出标题「刷新动画」的预览：
# 只有在刷新模式设为 Regal 或 HD256 时才能看到刷新动画。
```

- `onboarding_page 7` 的契约见 [automation-entry.md](<automation-entry.md>)；也可用 `firsttime refresh_animation` 直接调出预览。
- 开关默认 false；点击开关才写 `EINK_PAGE_TURN_ANIMATION`，点击样式行只切 `EINK_PAGE_TURN_ANIMATION_STYLE`（默认 0=方向式）。
- 演示只在**首次开启开关**时弹出，onboarding 和 Settings 共用 `tooltip_refresh_animation_demo_shown`；onboarding 跳过 Extra 后，首次在 Settings 打开仍会弹。
- Settings → More/More Features 的开关与样式交互与 onboarding 相同；动效是手动 60ms 帧计时器，不依赖 `animator_duration_scale`。

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

已修复：字体/长文双向滚动、ⓘ许可页跳转、小部件后台单飞/图片加载、自适应分页、通知合并原子写、托盘对称注销与手电筒真值、时钟分钟对齐、恢复校验与普通异常回滚。
仍需验证边界：相机/蓝牙权限未授权设备的开关链、旧系统API、现代PinItemRequest OEM确认流程、大字号/键盘布局、恢复中进程强杀/断电。首次自定义字体解析仍可能同步；没有Profiler量化的耗时/内存数字不能写成改善百分比。

收尾：退出编辑模式→home关sheet→恢复获准写的旧状态→记录版本/PID/测试证据→保留用户可人工验收的环境。

**默认桌面必须收尾**（`adb install -r` debug 后经常出现 role 仍在、HOME 解析落到 `android` 的半坏状态）：

- 继续用 debug：`tools/home.ps1 ensure debug`；
- 回到正式包：`tools/home.ps1 release`；
- 回到第三方桌面：`cmd package set-home-activity <pkg>/<activity>`，必要时先 `cmd role add-role-holders android.app.role.HOME <pkg>`；
- 卸载 debug 前先切到目标桌面，卸载后再校验一次；
- 只认 `resolve-activity` 解析到目标且 `isDefault=true`；`role get-role-holders` 单独显示目标包不算正常。
- 自动化测试结束的汇报必须明确写出当前默认 launcher 是否是 debug 包（给出 `package=... isDefault=... roleHolders=...`）；不要只写“已校验”。

```powershell
# 安装前记录的原桌面；按需选择下面一种恢复
pwsh -NoProfile -File ./tools/home.ps1 ensure debug -Serial $SERIAL -AdbPath $ADB
pwsh -NoProfile -File ./tools/home.ps1 release -Serial $SERIAL -AdbPath $ADB
# 第三方桌面示例（把 pkg/activity 换成实际值）：
# & $ADB -s $SERIAL shell cmd role add-role-holders android.app.role.HOME com.example.launcher
# & $ADB -s $SERIAL shell cmd package set-home-activity com.example.launcher/.MainActivity
# 最后必须重新 resolve + role 校验
```

```powershell
pwsh -NoProfile -File ./tools/automate.ps1 -Dest editmode_off -Serial $SERIAL -AdbPath $ADB
pwsh -NoProfile -File ./tools/automate.ps1 -Dest home -Serial $SERIAL -AdbPath $ADB
pwsh -NoProfile -File ./tools/home.ps1 ensure debug -Serial $SERIAL -AdbPath $ADB
git status --short
```

本项目当前发布准备：版本 **0.2.7 / versionCode 10**；开发/测试完提交 push，Release/tag/资产发布仍需用户明确确认。
