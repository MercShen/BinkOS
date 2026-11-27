# Bink OS 自动化入口 — AI agent 接口契约

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
# 安装产物按「最新的 debug APK」取，不要把版本号写死（写死会在每次 bump 后卡住复制粘贴）
$APK = (Get-ChildItem '.\app\build\outputs\apk\debug\*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
& $ADB -s $SERIAL install -r -t "$APK"
if ($LASTEXITCODE -ne 0) { throw 'install failed' }
& $ADB -s $SERIAL shell am force-stop $PKG
pwsh -NoProfile -File ./tools/home.ps1 debug -Serial $SERIAL -AdbPath $ADB
if ($LASTEXITCODE -ne 0) { throw 'HOME verification failed' }
pwsh -NoProfile -File ./tools/automate.ps1 -Dest status -Serial $SERIAL -AdbPath $ADB
& $ADB -s $SERIAL shell pidof $PKG
& $ADB -s $SERIAL shell dumpsys package $PKG | Select-String 'versionName=|lastUpdateTime='
```

本机路径/序列号只是示例，脚本支持 `-AdbPath` 或 PATH/SDK 环境变量，不再依赖仓库外的 home 脚本。`tools/home.ps1` 设置/检查的是默认 HOME，不保证页面已渲染。

**装包后统一验收（不要手拼等价命令）**：`tools/post-install.ps1 -App debug|release` 一次输出版本 / lastUpdateTime / 默认 HOME（内部走 `home.ps1`）/ PID 四行；任一项不过退出非零。`cmd package resolve-activity` 单读一次**不算**验收，也没有把包设为默认 HOME。

- 设备测试产物统一放 `/sdcard/BinkOS-Test/v<versionName>/`，按 `screenshots/`、`ui/`、`tmp/`、`evidence/` 分类；截图/XML 用 `<目的>-<yyyyMMdd-HHmmss>` 命名，禁止散落 `/sdcard` 根目录。详见 [testing-playbook.md](<testing-playbook.md#11-设备测试产物管理>)。

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
| `-Pkg` | 仅与 `-Dest external` 搭配，映射为 `--es pkg`；值须形如 `com.android.settings` |
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
| `back` | 先关最上层可见/待展示面板；没有面板则返回上一级（`mainFragment` 时不动作）；applied，message 的 currentDestination 是结果 |
| `external` | 需要 `--es pkg com.example.app`；按启动意图拉起该 App 并离开 BinkOS（没有可启动 Activity 则 rejected）；回到 BinkOS 用 `dest=home` |

无“打开任意页面”的保证：WIDGET、小部件二级选择、字体等子 sheet 暂无独立 dest，按真实产品入口操作；应用内语言改用状态命令 `dest=language`（见第 6 节）。重复导航会改变返回栈；用 home 清理测试路径，不无限叠加同一页。

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
| `eink_anim_on/off` | 无 | 保存翻页动画开关（`EINK_PAGE_TURN_ANIMATION`） |
| `eink_style_directional` / `eink_style_ripple` | 无 | 保存动画样式，**不会自动打开动画开关**；要出效果需先 `eink_anim_on` |
| `eink_fast_on/off` | 无 | 保存「智能刷新模式」开关（`EINK_INTERACTION_FAST_MODE`） |
| `volume_keys_on/off` | 无 | 保存「音量键翻页」开关（`USE_VOLUME_KEYS_FOR_PAGES`）；键盘取证要反复开合它，别再点设置页或 `run-as` 灌 prefs |
| `vendor_switches_on/off`（别名 `force_vendor_on/off`） | 无 | **调试后门**：无厂商固件（模拟器）时也强制显示「系统全刷 / 刷新动画 / 智能刷新模式」三项专属开关及厂商引导页；只改 UI 可见性，不伪造厂商接口。写在 `DEBUG_FORCE_VENDOR_SWITCHES`，重进 App 仍生效；release 构建忽略 |
| `language` | --es language **必填**：`system` / `en` / `zh-CN`（另有 english、chinese、zh_cn、default 等别名） | 写应用内语言偏好并重建界面，与设置页「语言」选择器同一路径；applied message 是 `{"language":"en"}`（`system` 表示跟随系统）。 |
| `backup` | 无 | 生成完整备份 ZIP，写到 `filesDir/backups/backup-<yyyyMMdd-HHmmss>.zip`；结果 JSON 回 `file/bytes/sha256` |
| `restore` | path **必填** | 先清空当前数据再导入该 ZIP；path 相对 `filesDir`（如 `backups/x.zip`）或位于 `filesDir`/`cacheDir` 的绝对路径；**额外要求 `--ez confirm_clear true`**，恢复后需重启应用生效 |

上表都须 `--ez confirm_write true`。脚本未加 `-AllowStateChanges` 会本地退出2且不发请求；App端缺确认则 rejected/脚本退出3。type合法输入：`android/android_widget`、`shortcuts/search`、`quote`、`events`、`usage/total_usage`、`pagedots/page_dots`、`off/disabled`。

e-ink 四个命令用来替代「点设置页 / 用 run-as 灌 prefs」，典型组合：

```powershell
# Ripple + 动画开
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest eink_anim_on --ez confirm_write true
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest eink_style_ripple --ez confirm_write true
# 交互极速开关
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest eink_fast_on --ez confirm_write true
# 模拟器上强开厂商专属开关（只改可见性；关掉重进仍生效）
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest vendor_switches_on --ez confirm_write true
# 应用内语言（写偏好 + 重建界面；system = 跟随系统）
& $ADB -s $SERIAL shell am start -n $PKG/com.github.gezimos.inkos.MainActivity `
  --es dest language --es language en --ez confirm_write true
```

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
- 类型严格：整数用--ei、布尔--ez、字符串--es。tab只允许通用edit_bg/clock/date/apps；value只允许pager_margin；type只允许bottomwidget；language只允许dest=language且取值限system/en/zh-CN；onboarding_page只允许onboarding且为0..7；guide只允许firsttime且必须是表中名称。显式错类型、缺必要参数、越界、不适用参数、未知dest一律拒绝，不静默截断或恢复默认。
- dest值不区分大小写，extra名称区分；request_id用短可打印串(≤128字符)，推荐UUID。脚本对dest/type还做安全字符校验。

## 6.5 备份与恢复（dest backup / restore）

两道确认、两份留档——与 `device-backup/`、`/sdcard/BinkOS-Test/` 的设备数据管理约定一致：

| 层 | 位置 | 谁产生 |
|---|---|---|
| 应用内工作副本 | `filesDir/backups/backup-<yyyyMMdd-HHmmss>.zip`（私有） | `dest backup` 直接写 |
| 设备留档 | `/sdcard/BinkOS-Test/<版本>/evidence/backup-<目的>-<时间>.zip` | 验收脚本 `adb push` |
| 工作区留档 | `ws3/device-backup/binkos-backups/backup-<目的>-<时间>.zip` | `adb exec-out run-as … cat` 拉取 |

流程（debug 包；路径与时间戳按当次替换）：

```powershell
# 1) 备份：写 filesDir/backups，automation_result 回 {"file":"backups/…","bytes":N,"sha256":"…"}
.\tools\automate.ps1 -Dest backup -AllowStateChanges

# 2) 拉回工作区留档（debug 可 run-as；release 包没有这条路）
cmd /c "adb exec-out run-as app.binkos.debug cat files/backups/backup-20261118-220000.zip > ws3\device-backup\binkos-backups\backup-<目的>-20261118-220000.zip"

# 3) 设备留档
adb push ws3\device-backup\binkos-backups\backup-<目的>-<时间戳>.zip /sdcard/BinkOS-Test/v<versionName>/evidence/

# 4) 恢复：先把 ZIP 送进 filesDir，再发 restore
cmd /c "type ws3\device-backup\binkos-backups\backup-<目的>-20261118-220000.zip | adb shell run-as app.binkos.debug sh -c 'cat > files/backups/restore-in.zip'"
.\tools\automate.ps1 -Dest restore -Path backups/restore-in.zip -AllowStateChanges -AllowClear
```

- `path` 契约校验：必填、非空、不含 `..`、无控制字符、≤256；App 侧再要求解析后的真实路径落在 `filesDir` 或 `cacheDir` 内。
- `restore` 会先清空 prefs 与 filesDir（与 UI 导入一致），所以 `confirm_write` 与 `confirm_clear` 缺一不可；只带前者本地退出2/App 端 rejected。
- 结果 `{"restored":true,"restart":"required"}`：自动化**不自动重启**（避免 `automation_result` 来不及写），调用方自己 `am force-stop` + 启动后再验 UI。
- `backup` 不覆盖旧文件（时间戳命名）；体积随字体/壁纸增长。
- 正式包没有这两个 dest（automation 只在 debug 可用），正式包仍走设置 → 高级 → 备份与恢复（SAF）。

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
| language | 应用内语言：`system` 或 tag（`en` / `zh-CN`）；读的是应用自管偏好，不是系统 LocaleManager |
| sheetOpen | Boolean：是否还有底部面板显示或在排队显示。导航 dest 只回 `dispatched`，**上一层 sheet 还盖着时按键会打在 sheet 上**，这个字段用来判定目标画面是否真的可见 |
| zoneEditor | Boolean：短滑区域编辑器是否打开（它是挂在 decorView 上的覆盖层，`currentDestination` 看不出来） |
| onboarding | Boolean：当前是否在首启引导页。为 true 时 `onKeyDown` 会吞掉**所有**按键（BACK/ESC/音量都到不了系统），别把「按键没反应」当成键位缺失 |
| windowFocus | Boolean：本 Activity 是否持有窗口焦点。为 false 说明按键被系统弹窗/其它 App 吃掉，此时任何按键结论都无效 |
| volumeKeys | Boolean：「音量键翻页」偏好的实际值（与 `volume_keys_on/off` 配对使用） |
| pageUpKeys / pageDownKeys | **keycode 数组**：自定义翻页键的当前绑定（默认 `[92]` / `[93]`，即 PAGE_UP/PAGE_DOWN）。脚本要验「某颗键是否已绑上/是否真的翻页」，读这两个字段比猜界面可靠 |
| notifListener | Boolean：通知使用权是否已授予本包。为 false 时 Hub/Tray 必然「无通知」，那是环境没铺对，不是 App 缺陷 |

`type=android/shortcuts/usage/pagedots/off` 分别存为android_widget/search/total_usage/page_dots/disabled。status可随时读，不取消等待动作；不允许附带skip_onboarding或refresh_eink=true。状态命令仍会启动/前置Activity，因此“只读”指不改偏好，不是无任何UI副作用。

## 7.5 键盘 / 无触摸取证（注入按键）

墨水屏 T9 键盘机的可达性只能靠注入按键取证。以下是 2026-11-26 一轮实测踩出来的硬规则，**照做再下结论**：

### 手工按键：虚拟 T9 面板

脚本之外要人手试键盘手感，用 `ws3\emulator\t9pad.ps1`：独立窗口，按钮直接发
`adb shell input keyevent`，所以设备收到的是真 KeyEvent（与物理键盘同一条分发路径），
支持 `--longpress` 与连发次数；`-Probe` 可不开窗口自检通路。它只解决「按键逻辑」，
**不解决 T9 数字→字母**（客机没有 T9 IME）。

### 先自证画面，再按按键

```powershell
.\tools\automate.ps1 -Dest hub -ExpectedText 'All (' -Serial $SERIAL -AdbPath $ADB   # 导航必须带锚点
.\tools\automate.ps1 -Dest status -Serial $SERIAL -AdbPath $ADB                       # 再读 sheetOpen / windowFocus / onboarding
```

- **不要用裸 `am start --es dest X` 当导航**。它回 `dispatched` 只代表已分发；实测里 `dest hub`、`dest fonts` 多次「分发成功但画面仍是主页」，此时所有按键结论都是假的。要么走 `automate.ps1 -ExpectedText`，要么按完后用 `status` 的 `sheetOpen/currentDestination` + uiautomator 文本锚点自证。
- 按键前后各取一次判据，**判据优先级**：① `status` 的 `page/pageCount` 等结构化字段 ② uiautomator 文本/结构指纹 ③ 像素差分。整帧 md5/像素差会被状态栏时钟污染（每分钟必变），单独用它判「有没有反应」必然误报。
- **uiautomator 的 `focused` 对 Compose 虚拟节点不可靠**（实测大量 `focused=false` 而焦点其实在移动），`clickable` 同样会漏报 `pointerInput` 行。焦点位置只能靠截图高亮或行为结果反推。
- 每次按键前先确认 `windowFocus=true` 且焦点窗口是本 App：`adb shell dumpsys window | grep mCurrentFocus`。系统弹窗（权限页、模拟器虚拟 SD 卡提示等）会静默吃掉按键并跳到别的 App，取证结果整段作废。

### keycode 速查（`adb shell input keyevent`）

| 注入值 | 实际含义 | 备注 |
|---|---|---|
| `19/20/21/22/23` | DPAD_UP/DOWN/LEFT/RIGHT/CENTER | 应用内 zone 模型的主键 |
| `66` / `23` | ENTER / DPAD_CENTER | 激活 |
| `4` / `111` | BACK / ESC | BACK 走 `OnBackPressedDispatcher`；ESC 走 `MainActivity.onKeyDown` 回主页 |
| `82` | MENU | 开应用抽屉（仅 mainFragment） |
| `3` | **HOME**，不是数字 3 | 数字键要写 `KEYCODE_` 名或用正确码位 |
| `1` | **CALL**，不是数字 1 | 同上 |
| `7`…`16` | KEYCODE_0 … KEYCODE_9 | 数字 `1`=`8`、`3`=`10`；本 App 里 `2/6/7/8` 与长按 `9`、`1`、`3` 有命令语义 |
| `92` / `93` | PAGE_UP / PAGE_DOWN | 上游 #125 场景（侧键映射成 Page 键的机型） |
| `24` / `25` | VOLUME_UP / DOWN | 受 `volumeKeys` 偏好门控；Hub/Tray 目前不生效 |
| `67` | DEL（退格） | 通知删除语义；未消费时会被转成「打字即搜索」并进抽屉 |
| `--longpress <code>` | 长按同一键 | 用于验 `NavHelper` 的 700ms 长按语义（换 App / 展开 / 删除） |

### 铺通知（Hub / Tray 取证前置）

```powershell
# 探针包名是 app.notifprobe.<flavor>，不是 app.<flavor>.notifprobe
adb shell pm grant app.notifprobe.alpha android.permission.POST_NOTIFICATIONS   # API 33+ 必须，否则静默丢弃
adb shell am start -n app.notifprobe.alpha/app.notifprobe.MainActivity          # 每次启动先 cancelAll 再发 5 条
adb shell settings put secure enabled_notification_listeners app.binkos.debug/com.github.gezimos.inkos.services.NotificationService
```

监听组件是 **`.services.NotificationService`**（写成 `NotificationManager` 会静默无效，Hub 显示「无通知」，容易被误判成缺陷）。改完用 `status.notifListener=true` 自证。

### 自定义翻页键怎么被验

绑定动作在 `MainActivity.dispatchKeyEvent` **入口**完成：`PageKeyRouter.rewriteTarget` 判定这颗键该不该被改写成 `PAGE_UP/PAGE_DOWN`，改写后的事件才继续分发。所以全 App 只需要认 Page 键，不存在「某屏支持自定义键、某屏不支持」。

脚本验证顺序（不要用 UI 猜）：

```powershell
# 1) 绑一颗键（例：KEYCODE_BOOKMARK=174 绑到下一页）
adb shell input keyevent 174          # 前提是捕获面板已打开
# 2) 读回绑定表，确认写进去了
.\tools\automate.ps1 -Dest status     # message.pageDownKeys 应包含 174
# 3) 到真的分页界面上按它，看内容换页
.\tools\automate.ps1 -Dest hub -ExpectedText 'All ('
adb shell input keyevent 174
```

三条规则必须在 JVM 测试里钉住（`PageKeyRouterTest`），不要靠真机试：

| 规则 | 为什么 |
|---|---|
| `PAGE_UP/PAGE_DOWN` 无条件翻页 | 这两颗键没有别的消费者，不该藏在开关后面（上游 #125 的原始诉求） |
| 音量键仍看 `useVolumeKeysForPages`，且播放中不改写 | 音量键同时是系统音量，抢不得 |
| `CENTER/ENTER/BACK/HOME/ESCAPE/MENU/DEL` 是黑名单 | 绑成翻页等于把激活键或返回键扔掉，界面会按不动 |

方向键与音量键属于**软警告**名单：允许绑定，但第一次按下只给警告，同一颗键再按一次才确认——因为它们在 `Home`/`Letters` 里本来就有职责。

### 已知取证盲区

- **焦点在哪，uiautomator 和 status 都看不见**：设置页页头与正文是两个 ComposeView，页头进页即抢焦点，正文的按键处理器那时收不到键（PAGE 键已在页头也挂了一份）。验按键时**先按目标键再看结果**，不要先按方向键"热身"，否则会把只有焦点在正文才生效的路径误判成全局可用。
- 模拟器没有 T9 数字键盘 IME（仓内无 multi-tap，`SearchHelper` 只认 `a..z`）、没有厂商物理侧键、没有墨水屏波形。数字→字母、侧键真实 keycode、刷新观感只能真机验。
- 图标包相关的分页（图标样式 sheet 第 2 页）在干净模拟器上造不出来：没有第三方图标包时选项不足一页。

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
- 功能依赖：通知访问权、POST_NOTIFICATIONS、系统相机/日历/蓝牙权限、已装组件、锁屏认证会改变结果。脚本不自动授予Bink OS权限或关闭设置锁。

自动检查：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug --console=plain
pwsh -NoProfile -File ./tools/tests/run-tests.ps1
```

契约JVM测试和fake-adb脚本测试不等于真实UI测试。构建成功不等于Lint通过；NO-SOURCE表示没有测试，必须检查报告。实机记录命令、requestId、结果、UI树、截图、状态差异；收尾关sheet/退出编辑模式，恢复获准修改的状态，检查debug默认HOME并保留人工验收环境。
