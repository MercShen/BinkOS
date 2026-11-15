# 测试 playbook（agent 用）

这份文档记录「跑一次 UI 验证」该怎么做，避免每次都现场摸索设置页里哪个开关在哪。
配套：[automation-entry.md](automation-entry.md)（所有 dest）、`tools/notifprobe/`（通知探针）。

约定：调试包 `app.binkos.debug`，正式包 `app.binkos`。命令里按当前默认桌面替换。

---

## 0. 通用技巧（这轮踩过的坑）

| 目的 | 做法 |
|---|---|
| 找某个设置行的坐标 | `adb shell uiautomator dump /sdcard/u.xml && adb pull /sdcard/u.xml`，看 `bounds`，不要再靠截图猜 |
| 设置页里的行在屏幕外 | 用**小步**上滑（`input swipe 400 1300 400 500 200`），大距离下滑会触发返回/关闭 |
| sheet 打开后想换一级 | 点「返回」文本，不要按 BACK（BACK 会把整个 sheet 关掉，多选 sheet 关闭即提交） |
| 设置项没有自动化入口时 | 先用本文的 state dest 设置；确实没有再加 dest，不要写死坐标 |
| 截图验证 | `adb exec-out screencap -p > x.png`，配合字节大小/哈希对比判断是否有变化 |
| 默认桌面 | `tools/home.ps1 debug`，测试完保持最近测试的包 |
| 主页翻页截图 | 用 `dest home_next` / `dest home_prev`；横滑会触发**长滑快捷方式**，不要用滑 |
| 主页页码看起来没变 | 先看 logcat 里 `home_next before/after` 的页码；主屏「Home Page Reset」开启时，每次回到主屏都会回第 1 页 |

---

## 1. 测试状态一键设置（不用再翻设置页）

```bash
ADB="adb -s $SERIAL"
APP="app.binkos.debug"
MAIN="com.github.gezimos.inkos.MainActivity"

# 主页页码指示器
$ADB shell am start -n $APP/$MAIN --es dest pager_on
$ADB shell am start -n $APP/$MAIN --es dest pager_off

# 底部小部件类型
$ADB shell am start -n $APP/$MAIN --es dest bottom_android
$ADB shell am start -n $APP/$MAIN --es dest bottom_shortcuts
$ADB shell am start -n $APP/$MAIN --es dest bottom_quote
$ADB shell am start -n $APP/$MAIN --es dest bottom_events
$ADB shell am start -n $APP/$MAIN --es dest bottom_usage
$ADB shell am start -n $APP/$MAIN --es dest bottom_pagedots
$ADB shell am start -n $APP/$MAIN --es dest bottom_off
# 通用写法：--es dest bottomwidget --es type android

# 白名单清空（多选列表/通知过滤测试前先清）
$ADB shell am start -n $APP/$MAIN --es dest allowlist_clear
$ADB shell am start -n $APP/$MAIN --es dest allowlist_tray_clear
$ADB shell am start -n $APP/$MAIN --es dest allowlist_hub_clear
```

---

## 2. 通知：托盘 / Hub

探针工程在 `tools/notifprobe/`（三个包，5/4/3 条通知）：

```bash
cd tools/notifprobe
# 首次需要 local.properties: sdk.dir=<SDK 路径>
./gradlew :app:assembleAlphaDebug :app:assembleBetaDebug :app:assembleGammaDebug
adb install -r app/build/outputs/apk/alpha/debug/app-alpha-debug.apk
adb install -r app/build/outputs/apk/beta/debug/app-beta-debug.apk
adb install -r app/build/outputs/apk/gamma/debug/app-gamma-debug.apk
adb shell pm grant app.notifprobe.alpha android.permission.POST_NOTIFICATIONS
adb shell pm grant app.notifprobe.beta  android.permission.POST_NOTIFICATIONS
adb shell pm grant app.notifprobe.gamma android.permission.POST_NOTIFICATIONS
# 每次启动都会 cancelAll 自己的旧通知再发
for p in alpha beta gamma; do adb shell am start -n app.notifprobe.$p/app.notifprobe.MainActivity; done
```

然后：

```bash
$ADB shell am start -n $APP/$MAIN --es dest tray   # 托盘：整页上下滑翻页
$ADB shell am start -n $APP/$MAIN --es dest hub    # Hub：分类 Tab，同样整页翻页
```

观察点：
- 每页条数按可用高度算，不出现半张卡片
- 页码点：消息框侧边、屏幕垂直居中、多页才显示、当前页放大
- 托盘/Hub 白名单互相独立（`SIMPLE TRAY` / `HUB` 两个入口）

清场：托盘底部 `Clear All`，或长按单条。

---

## 3. 小组件选择

```bash
$ADB shell am start -n $APP/$MAIN --es dest bottom_android
$ADB shell am start -n $APP/$MAIN --es dest settings        # 进入设置根（Home 页）
# 再用 uiautomator 找「Choose Widget」行点进去；有已绑定组件时会先落在该 App 的组件列表
# 点左上 Back 才是应用列表（带搜索框 + 翻页点）
```

---

## 4. 页码指示器清单（当前都应「当前页 12dp、其他 10dp」）

| 位置 | 打开方式 |
|---|---|
| 主页 | `dest pager_on` 后 `dest home` |
| 设置子页头部 | `dest lookfeel` / `dest fonts` / `dest gestures` 等 |
| 托盘 | `dest tray`（需要 >1 页消息） |
| Hub | `dest hub` |
| 角标样式选择 | `dest badgeindicator` |
| 多选列表 | `dest notifsettings` → 白名单行 |
| 小组件应用列表 | `dest bottom_android` → 设置 Home → Choose Widget |
| 图标样式选择 | `dest lookfeel` → Homepage 图标行 |

---

## 5. 收尾检查

```bash
tools/home.ps1 debug      # 确认默认桌面仍是刚测的包
git status                # 确认没有遗漏改动
```
## 6. 编辑模式（人机交互 + 自动化入口）

人类路径：

1. **捏合**主屏 → 弹出 `QUICK MENU`（Bink OS Settings / Edit Mode / Edit Favorites / Look & Feel / About）
2. 点 `Edit Mode` → 进入编辑模式（虚线安全框 + 拖拽手柄）
3. 编辑模式下**长按空白处** → 打开背景设置面板（顶部四个图标标签：主题 / 对齐 / 岛屿与圆角 / 手势）
4. `对齐` 标签里的 `Margin/Offset` 行 → 打开滑块面板（Top/Bottom widget offset、Home apps X/Y、Page Indicators Margin）

自动化对应：

```bash
# 快捷菜单（等价捏合）
$ADB shell am start -n $APP/$MAIN --es dest quick_menu
# 背景设置面板，直达标签
$ADB shell am start -n $APP/$MAIN --es dest edit_bg_align      # 对齐
$ADB shell am start -n $APP/$MAIN --es dest edit_bg_theme
$ADB shell am start -n $APP/$MAIN --es dest edit_bg_islands
$ADB shell am start -n $APP/$MAIN --es dest edit_bg_gestures
# 旧的通用入口
$ADB shell am start -n $APP/$MAIN --es dest editmode_on
```

面板里 `Margin/Offset` 的滑块面板仍需点那一行打开（没有单独 dest）。
### 编辑模式下各区域打开的面板

| 点哪里 | 面板 | 标签页 | dest |
|---|---|---|---|
| 空白处 | 背景/主题设置 | 主题 / 对齐 / 岛屿与圆角 / 手势 | `edit_bg --ei tab N`（或 `edit_bg_theme` 等） |
| 时钟 | STYLE | 样式（Clock Size/Font/Style/Typography）/ 功能 / 手势 | `edit_clock --ei tab N` |
| 日期 | STYLE | 样式（Date Size/Font/Typography）/ 功能 / 手势 | `edit_date --ei tab N` |
| 主页应用列表 | STYLE | 字体 / 大小与间距 / 名称类型 / 排版 | `edit_apps --ei tab N` |
### 底部小组件（编辑模式）

| 操作 | 结果 |
|---|---|
| 点小组件**本体** | 打开小组件设置面板（WIDGET / FUNCTION / STYLE / GESTURES） |
| 面板里 `Choose App`（显示当前 App 名） | 打开 CHOOSE APP 应用列表 |
| 面板里 `Choose Widget`（显示当前组件名） | 打开**当前 App** 的组件列表 |
| 选择器里 `CHOOSE APP` 左侧的返回箭头 | 回到小组件设置面板 |
