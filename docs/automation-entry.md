# BinkOS 自动化入口 — AI agent 接口契约

本文对应当前源码的 **debug 自动化接口**；正式包拒绝这些请求。主界面/产品入口、预览选择器、持久化设置命令和调试探针必须分开使用。源码、结构化日志和设备验证共同作为证据；`am start` 成功不是功能验收。

配套：[测试流程](<testing-playbook.md>)、[工具说明](<../tools/README.md>)、[通知探针](<../tools/notifprobe/README.md>)。

## 1. 安全与环境

- 仓库根运行命令。选择 **debug 包 `app.binkos.debug`**，Activity 类名仍为 `com.github.gezimos.inkos.MainActivity`；release 包 `app.binkos` 不接受 automation。
- 每条命令显式指定设备，或使用脚本的“唯一在线设备”选择；多设备且无 `-Serial` 时拒绝。不要假定连接的设备就是 B651。
- 安装新 debug APK 后：`install -r -t` 成功 → force-stop → 设置并校验 debug 为默认 HOME → 启动 → 核对版本、lastUpdateTime、PID。`install -r` 可能把 HOME 解析重置为 `android` resolver；只看 `roleHolders` 仍是 debug 不算正常，必须确认 `resolve-activity` 解析到 debug（`tools/home.ps1 debug/ensure` 会校验）。测试期间保留 debug 为默认；测试结束后若设备继续用于 debug 验收则保留，否则按 [测试 Playbook 的收尾步骤](<testing-playbook.md#7-已知风险与收尾>) 恢复安装前的桌面。
- 不自动清白名单、执行 `pm clear`、导入/恢复或改变用户配置。写操作先获授权并记录旧值；破坏性测试先保存完整设置和 filesDir 快照，且验证快照可用。
- `confirm_write`/`confirm_clear` 是防误操作闸门，不是身份认证。debug 构建只用于受控设备，不对第三方应用提供安全边界。
- 不全局清 logcat。只取本进程/本 request_id 的结果，避免打印不相关通知内容。

PowerShell（Windows 首选）：

```powershell
$ADB = 'C:\AndroidSDK\sdk\platform-tools\adb.exe'  # 按实际 SDK 修改
$SERIAL = 'B651D2W2GAPA006000108'                   # 按 adb devices 选择
$PKG = 'app.binkos.debug'
$MAIN = 'com.github.gezimos.inkos.MainActivity'
& $ADB devices
& $ADB -s $SERIAL install -r -t '.\app\build\outputs\apk\debug\app.binkos_v0.2.7-Debug.apk'
if ($LASTEXITCODE -ne 0) { throw 'install failed' }
& $ADB -s $SERIAL shell am force-stop $PKG
pwsh -NoProfile -File ./tools/home.ps1 debug -Serial $SERIAL -AdbPath $ADB
if ($LASTEXITCODE -ne 0) { throw 'HOME verification failed' }
pwsh -NoProfile -File ./tools/automate.ps1 -Dest status -Serial $SERIAL -AdbPath $ADB
& $ADB -s $SERIAL shell pidof $PKG
& $ADB -s $SERIAL shell dumpsys package $PKG | Select-String 'versionName=|lastUpdateTime='
```

本机路径/序列号只是示例，脚本支持 `-AdbPath` 或 PATH/SDK 环境变量，不再依赖仓库外的 home 脚本。`tools/home.ps1` 设置/检查的是默认 HOME，不保证页面已渲染。

## 2. 推荐调用与结果协议

```powershell
pwsh -NoProfile -File ./tools/automate.ps1 -Dest settings -ExpectedText 'SETTINGS' -Serial $SERIAL -AdbPath $ADB
pwsh -NoProfile -File ./tools/automate.ps1 -Dest edit_apps -Tab 2 -ExpectedText 'Apps per page' -Serial $SERIAL -AdbPath $ADB
pwsh -NoProfile -File ./tools/automate.ps1 -Dest status -Serial $SERIAL -AdbPath $ADB
```

脚本为每次请求生成 UUID，传入 `--es request_id`。App 用 `MainActivity` 标签输出：

```text
automation_result: {"requestId":"<UUID>","dest":"<规范名称>","status":"<状态>","message":"<说明或嵌套状态JSON>"}
```

| status | 含义 | 断言方式 |
|---|---|---|
| `dispatched` | 导航/面板/预览/区域编辑器已分发；这是该请求的终态，不会再出 `applied` | 等目标 UI 锚点，再看截图；认证、异步加载仍可能未完成 |
| `applied` | `status` 已读取；持久化状态命令或 home/编辑模式/翻页动作已经执行 | 解析 message，核对字段；程序化导航完成不等于帧已画完 |
| `rejected` | debug 限制、参数/确认闸门错误、分发异常或等待就绪超时 | 修正请求；不得把命令退出码 0 当成功 |
| `cancelled` | 有效非 status 请求覆盖，或 Activity 离开前台取消尚未执行的动作 | 以最新有效请求为准；不重复运行写操作 |

导航请求即使附带获准的 `skip_onboarding`/`refresh_eink`，仍归 `dispatched`，不能据写入副作用声称 UI 完成。脚本匹配精确 requestId；无 request_id 时 App 会生成一个，但调用方难以关联，建议总是提供。

`-ExpectedText` 在目标包 UI 树的 text/content-desc 中做不区分大小写的子串匹配，检查非零 bounds 且位于根窗口内。**这仍不能证明未被遮挡/样式正确**，关键 UI 验收要查看截图。按应用语言选锚点；默认英文例不适用于所有语言。

参数：

| 脚本参数 | 作用 |
|---|---|
| `-Dest` | 必填，规范名或别名 |
| `-Tab` / `-Value` / `-Type` | 显式传入才映射为 `--ei tab` / `--ei value` / `--es type` |
| `-Serial` / `-AdbPath` | 指定设备/adb；省略 Serial 必须唯一在线设备 |
| `-ExpectedText` | UI 验收必须提供；参数本身可省略，缺失仍会发请求，但仅 `dispatched` 时脚本退出 6，不算验收成功 |
| `-TimeoutSeconds` | 3..120，默认 15；日志与 UI 分阶段预算，不是全命令硬性总时限 |
| `-AllowStateChanges` | 明确获授权后启用，传 `--ez confirm_write true`；脚本默认本地拒绝 state dest |
| `-App debug\|release` | 默认 debug；release 用于拒绝路径验证，不用于正常测试 |

退出码：0 验证通过；2 脚本参数/本地写闸门拒绝；3 App rejected；4 cancelled；5 未收到结果；6 dispatched 无锚点；7 adb/设备失败；8 锚点超时。PowerShell 参数绑定错误也会非零退出，不要依赖它一定是 2。

Bash 原生调用（用可执行文件变量，不把 `adb -s SERIAL` 塞成一个字符串）：

```bash
ADB=adb
SERIAL='实际设备序列号'
PKG=app.binkos.debug
MAIN=com.github.gezimos.inkos.MainActivity
RID=$(uuidgen 2>/dev/null || python -c 'import uuid; print(uuid.uuid4())')
"$ADB" -s "$SERIAL" shell am start -n "$PKG/$MAIN" --es dest settings --es request_id "$RID"
# 这是一次取证示例；需要轮询到超时。UI命令只会dispatched，不要等第二次applied。
PID=$("$ADB" -s "$SERIAL" shell pidof "$PKG" | tr -d '\r')
"$ADB" -s "$SERIAL" logcat -d --pid="$PID" -s MainActivity:I | grep -F "$RID"
"$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/binkos-ui.xml
"$ADB" -s "$SERIAL" exec-out cat /sdcard/binkos-ui.xml
# 先确认目标节点与bounds，再截图；日志ACK不代表UI已渲染。
"$ADB" -s "$SERIAL" exec-out screencap -p > settings.png
```

GUID 生成依赖 uuidgen 或 Python。Windows 不使用 Bash 的 `\` 续行；旧 PowerShell 二进制重定向可能损坏 PNG，使用 `cmd /c` 或支持原生字节重定向的 PowerShell 7.4+，随后实际打开图像验证。

## 3. 导航 dest

| dest（别名） | 目标/锚点示例 |
|---|---|
| `home` | 主屏，关闭所有可见/待展示 sheet；applied message 的 currentDestination=mainFragment |
| `drawer` / `apps` / `applist` | 应用抽屉；内容依赖安装应用与搜索设置 |
| `recents` | 最近任务；空列表不一定是失败 |
| `tray` / `simpletray` | Simple Tray |
| `hub` | 通知中心 |
| `letters` / `notifications` | Letters |
| `settings` | 设置根；SETTINGS |
| `lookfeel` | 设置→Look & Feel |
| `fonts` / `typography` | 设置→Typography |
| `gestures` / `gesture` | 设置→Gestures |
| `notifsettings` / `notification_settings` | 设置→Notifications |
| `advanced` | 设置→Advanced |
| `drawersettings` / `drawer_settings` | 设置→App Drawer |
| `about` / `support` | 设置→Support/About |
| `wallpaper` | 壁纸画廊 |
| `colors` / `coloreditor` | 颜色编辑器 |
| `themes` / `themepresets` | 主题预设 |
| `guide` | 使用引导 |
| `onboarding` | 可选 `--ei onboarding_page 0..7`；0 欢迎、1 隐私、2 通知、3 默认桌面、7 厂商 e-ink 页、4 设置、5 主题、6 收藏 | 直接导航首启页，不要求/不永久修改 firstOpen；带 `onboarding_page` 时写 `onboardingPage`，需 `confirm_write` |
| `firsttime` / `first_time` / `preview_guide` | `--es guide NAME` 必填 | Debug 首次引导后门：直接展示，不改任何偏好/已读状态；无需 confirm_write；`guide=close` 关闭当前提示；见下方 guide 列表 |
| `editfavorites` / `edit_favorites` | 编辑收藏，选择操作可能修改配置；进入本身不等于修改 |

无“打开任意页面”的保证：WIDGET、小部件二级选择、语言/字体等子 sheet 暂无独立 dest，按真实产品入口操作。重复导航会改变返回栈；用 home 清理测试路径，不无限叠加同一页。

## 4. 编辑模式与面板

以下会先回主页、关闭 sheet，最多等待约 3 秒直到 resumed Activity 和 Home 可用，再执行；就绪失败有 rejected，而不是静默 nullable no-op：`quick_menu`、全部 edit_bg 名称、`edit_clock/date/apps`、`editmode/editmode_on`、`shortswipe`、`home_next/prev`。

| dest（别名） | 参数 | 行为 |
|---|---|---|
| `editmode` / `edit_mode` | 无 | toggle，重复执行取反；applied |
| `editmode_on` / `edit_on` | 无 | 等待至少600ms置 true，applied；脚本优先使用幂等 on/off |
| `editmode_off` / `edit_off` | 无 | 置 false并关闭编辑覆盖层，applied |
| `quick_menu` | 无 | QUICK MENU；设置锁开启会先要求认证 |
| `edit_bg` / `edit_background` | tab 0..3，缺省0 | 0主题、1对齐、2岛屿/圆角、3手势 |
| `edit_bg_theme/align/islands/gestures` | 无 | 四个固定Tab入口，不接受tab参数 |
| `edit_clock` / `edit_date` | tab 0..2，缺省0 | 0样式、1功能、2手势 |
| `edit_apps` | tab 0..3，缺省0 | 0样式、1图标、2功能、3通知 |
| `shortswipe` / `short_swipe` / `zone` | 无 | 短滑区域编辑器；进入只预览，实际拖动可能保存区域设置 |
| `home_next` / `home_prev` | 无 | 翻页applied，page从0计数；首页重置或页边界可导致不变 |

`editmode_on` 等待期间：status只读，不取消；非法 rejected 请求不取消；**有效非status请求**、HOME或Activity离开前台会取消尚未执行动作。没有“post就是下一帧”的保证。

edit_apps 锚点：0页 Apps Font；1页图标卡片；2页 Nr of Apps/Pages 与 Apps per page 描述；3页 Notification Badge 与 Badges and labels 描述。面板内从初始Tab换到其它Tab后打开子弹窗，返回Tab可能受现有父页重开逻辑影响，不假定永远保持。

## 5. 预览型 sheet

| dest（别名） | 行为 |
|---|---|
| `shortcuticon` / `shortcut_icon` | 快捷方式图标预览，预选当前配置，点击只改本次选择器选中态，不保存 |
| `badgeindicator` / `badge_indicator` | 角标预览，同上 |
| `privacy` / `privacypolicy` | 隐私政策长文 |
| `fontlicense` / `font_license` | 字体许可长文 |

两个卡片选择器均按可用宽度分页、自适应约3–6列，整页横滑或点‹/›，多页时有页码点；不要要求固定4列。持久化验收走编辑面板或设置里的真实选择器，再重开/读偏好；不要在预览dest点击后期待主页配置改变。

## 6. 状态写入与参数校验

| dest（别名） | 参数 | 副作用 |
|---|---|---|
| `pager_on/off` | 无 | 保存主页页码显示开关 |
| `pager_margin` | value **必填整数0..12** | 保存页码留白；0是安全框边缘基准，不等于物理屏幕0px |
| `home_reset_on/off` | 无 | 保存回主页重置页码开关 |
| `bottomwidget` / `bottom_widget` | type必填 | 保存底部小部件类型，不自动导航/打开小部件选择器 |
| `bottom_android/shortcuts/quote/events/usage/pagedots/off` | 无 | 分别设置对应类型 |
| `allowlist_clear` | 无 | 清掉托盘+Hub用户白名单，不清通知；**空集合=不过滤应用** |
| `allowlist_tray_clear` / `allowlist_hub_clear` | 无 | 分别清掉一个白名单；不是“关闭全部通知” |

上表都须 `--ez confirm_write true`。脚本未加 `-AllowStateChanges` 会本地退出2且不发请求；App端缺确认则 rejected/脚本退出3。type合法输入：`android/android_widget`、`shortcuts/search`、`quote`、`events`、`usage/total_usage`、`pagedots/page_dots`、`off/disabled`。

附加extra：
- `skip_onboarding=true` 永久将firstOpen置false；`refresh_eink=true` 请求刷新。两者都要确认闸门，**必须搭配dest**；脚本暂不封装这两个extra。
- `onboarding_page=0..7` 只允许与 `dest onboarding` 搭配，写入 `onboardingPage` 后导航；需要 `--ei` + `--ez confirm_write true`。用于直接打开指定引导页，例如：

```powershell
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest onboarding --ei onboarding_page 7 --ez confirm_write true
```

页面索引：0 欢迎、1 隐私、2 通知、3 默认桌面、7 厂商 e-ink（Regal/HD256 说明在此页）、4 设置、5 主题、6 收藏。`onboarding_page` 与 `skip_onboarding` 可同时使用；前者只改引导页，后者才永久关闭首启。

### firsttime：首次引导后门（Debug）

用于不 reset 状态、不重装，直接复查首次点击才出现的提示/弹窗。仅 Debug 构建可用；不会写/清 tooltip flag，也不会改动画/引导已读状态；`editmode` 只临时进入编辑模式，`guide=close` 会恢复打开前的状态；打开后可再用 `guide=close` 关闭。

```powershell
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest firsttime --es guide recents
```

| guide | 直接打开/展示 |
|---|---|
| `home` / `home_pinch` / `home_swipe_pages` / `home_long_swipe` | 主页三连提示的指定步骤 |
| `recents` | Recents 首次提示 |
| `letters` | Letters 首次提示 |
| `editfavorites` | App Drawer → Edit Favorites 首次提示 |
| `hiddenapps` | App Drawer → Hidden Apps 首次提示 |
| `edit_hiddenapps` | App Drawer → Edit Hidden Apps 首次提示 |
| `editmode` | 主页编辑模式五步引导（从第 1 步开始） |
| `refresh_animation` | onboarding 厂商页 + Regal/HD256 刷新动画弹窗 |
| `close` | 关闭当前由 firsttime 打开的提示/弹窗 |

- 这些命令**不改**任何偏好或首次已读状态；不是 `pm clear`，也不是“清 flag 再触发”。关闭提示不会把已读状态写成 true。
- `home*` 和 `editmode` 会先回主页；其余按对应界面导航。
- `refresh_animation` 只临时跳到第 7 页并弹窗，不写 `onboardingPage`，不要求动画开关当前为 on。
- `editmode` 需要临时进入编辑模式才能显示五步引导；`guide=close` 会恢复到打开前的编辑模式状态。

首次开启 refresh animations 的 Regal/HD256 弹窗没有独立 extra，需在 onboarding 或 Settings 打开动画开关；完整验收步骤见 [testing-playbook.md](<testing-playbook.md#regalhd256-刷新动画引导>)。
- refresh先尝试系统全刷（仅einkSystemFullRefresh开启且厂商调用可用），否则100ms覆盖层闪屏；不保证物理GC16或任何波形实际效果。
- 类型严格：整数用--ei、布尔--ez、字符串--es。tab只允许通用edit_bg/clock/date/apps；value只允许pager_margin；type只允许bottomwidget；onboarding_page只允许onboarding且为0..7；guide只允许firsttime且必须是表中名称。显式错类型、缺必要参数、越界、不适用参数、未知dest一律拒绝，不静默截断或恢复默认。
- dest值不区分大小写，extra名称区分；request_id用短可打印串(≤128字符)，推荐UUID。脚本对dest/type还做安全字符校验。

## 7. status（纯只读）

```powershell
pwsh -NoProfile -File ./tools/automate.ps1 -Dest status -Serial $SERIAL -AdbPath $ADB
```

message是需二次解析的JSON字符串：

| 字段 | 类型/语义 |
|---|---|
| page / pageCount | 当前页0基/页数 |
| editMode | Boolean |
| bottomWidget | **String存储值**：disabled、quote、events、android_widget、search、total_usage、page_dots |
| pagerVisible / homeReset | Boolean |
| pagerMargin | 0..12整数 |
| currentDestination | 导航resource entry name，如mainFragment/settingsFragment；**不包含设置内子路由或当前sheet** |

`type=android/shortcuts/usage/pagedots/off` 分别存为android_widget/search/total_usage/page_dots/disabled。status可随时读，不取消等待动作；不允许附带skip_onboarding或refresh_eink=true。状态命令仍会启动/前置Activity，因此“只读”指不改偏好，不是无任何UI副作用。

## 8. Debug探针 — 不属于dest协议

组件全名为 `com.github.gezimos.inkos.debug.<Activity>`，仅debug Manifest注册。单独Activity的结果看对应日志，不会产MainActivity的automation_result。

| Activity | 参数/结果 | 风险 |
|---|---|---|
| EditModeTestActivity | 无参数，EditModeTest日志；toggle后finish | Activity切换/onPause会干扰编辑状态，不推荐；用dest on/off |
| BigmeTestActivity | mode整数，缺省HD=177；176 CLEAN、4 GC16；BigmeTest日志true/false | 厂商接口调用成功≠物理刷成功，受控测试才用 |
| HandleGalleryActivity | 无，样式画廊 | UI预览，返回HOME会关闭 |
| BackupCompatTestActivity | 下表；BackupTest日志 | 涉及私有文件/配置，默认不运行写操作 |

BackupCompatTestActivity 用 DebugProbeContract 校验：一次只能选一个op，无op/混选/未知参数/错类型拒绝。优先读取检查，不运行用户数据恢复：

| op | 参数 | 确认闸门/说明 |
|---|---|---|
| params / gestures / checkwall | --ez 对应参数 true | 无需确认；打印状态，checkwall可能解码壁纸 |
| alias | --es alias 包名 | confirm_write；将别名改成测试值 |
| dump / zipbackup | --ez 对应参数 true | confirm_write；写filesDir dump.json/export.zip，可能包含敏感配置 |
| wallpaper / font | --es 对应参数 设备内可读路径 | confirm_write；实际写文件和偏好 |
| file | --es file 设备内JSON路径 | confirm_write；合并导入，仍可能改变多项偏好 |
| file + clear=true | 上述加--ez clear true | **confirm_write + confirm_clear**；清prefs和filesDir后导入，可能丢数据 |
| ziprestore | --es ziprestore 设备内ZIP路径 | **confirm_write + confirm_clear**；恢复内部也会clear，不能省第二道确认 |

`clear`参数仅支持file，ziprestore及其他op显式带clear(包括false)也拒绝。confirm只代表调用方声称获授权，不代替快照/回滚。备份恢复已增加校验、快照和普通异常回滚，但不保证进程强杀/断电时跨偏好与文件的原子提交；确认保护仍不能代替独立备份。

安全读取例：

```powershell
& $ADB -s $SERIAL shell am start -n "$PKG/com.github.gezimos.inkos.debug.BackupCompatTestActivity" --ez params true
& $ADB -s $SERIAL logcat -d -s BackupTest:I
```

不提供清用户数据的复制即跑示例。设备路径不是主机路径；debug私有文件读取用run-as，正式包通常不允许。

## 9. 常规 ACTION_OPEN_* 与生命周期

常规action完整前缀 `com.github.gezimos.inkos.action.`，后缀为OPEN_APP_DRAWER/OPEN_NOTIFICATIONS/OPEN_SIMPLE_TRAY/OPEN_HUB/OPEN_RECENTS/OPEN_SETTINGS。现有源码只在onNewIntent分发，onCreate不是同一契约；默认HOME可能被系统再次唤起，看起来像热启动，不能以一次成功证明冷启动总可用。

自动化使用dest，不与action混发；automation先消费并移除extras，未知/拒绝请求也不再掉进其它action。App重建不重放请求。常规产品action与OPEN_SIMPLE_TRAY Boolean不提供requestId ACK，和debug限制互相独立。

## 10. 验收边界、已知问题

- 后续修复已恢复字体/长文双向滚动及ⓘ许可页；host采用最新请求代次，替换/HOME/STOP不触发旧父页回调。仍要回归认证、返回栈和生命周期，不能以Handler.post证明旧Dialog已销毁。
- ZIP恢复先受限解压/类型校验，再快照prefs和files、提交；普通异常回滚，回滚失败的恢复点保留在noBackupFilesDir。旧JSON/主题是合并，不清files。**尚不保证进程强杀/断电跨prefs+files的原子性**，恢复测试仍须隔离。
- 小部件枚举是后台单飞快照，标题/搜索区实测后计算分页，预览图片后台加载；首次字体文件加载/文件元数据读取等仍需关注，未给出全机型性能保证。
- WIDGET编辑面板里的Choose App/Choose Widget同sheet切状态，小部件列表箭头回WIDGET；设置→Home/Widgets的Choose Widget箭头回应用列表。需按入口区分。
- 想截图分页，不用主页横滑代替home_next/prev；通知区/卡片自身仍按其实现支持翻页。字节大小/哈希仅证明变化，不证明正确。
- 功能依赖：通知访问权、POST_NOTIFICATIONS、系统相机/日历/蓝牙权限、已装组件、锁屏认证会改变结果。脚本不自动授予BinkOS权限或关闭设置锁。

自动检查：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug --console=plain
pwsh -NoProfile -File ./tools/tests/run-tests.ps1
```

契约JVM测试和fake-adb脚本测试不等于真实UI测试。构建成功不等于Lint通过；NO-SOURCE表示没有测试，必须检查报告。实机记录命令、requestId、结果、UI树、截图、状态差异；收尾关sheet/退出编辑模式，恢复获准修改的状态，检查debug默认HOME并保留人工验收环境。
