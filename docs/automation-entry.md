# 无手势入口（自动化调试）

BinkOS 的主要界面大多只挂在手势上（捏合开快捷菜单、长滑开各面板）。做 UI 开发或自动化测试时，
手势既不好复现（模拟器/scrcpy 需要 Ctrl+拖动模拟捏合），也不好脚本化。

所以 `MainActivity` 提供了一个 intent 入口：**用 adb 直接打开任意界面**，不需要任何手势。

## 用法

```bash
adb shell am start -n app.binkos/com.github.gezimos.inkos.MainActivity \
    --es dest settings
```

调试包（applicationIdSuffix `.debug`）把包名换成 `app.binkos.debug`。

## 支持的 dest

| dest | 打开 |
|---|---|
| `home` | 回主屏 |
| `drawer` / `apps` / `applist` | 应用抽屉 |
| `recents` | 最近任务 |
| `tray` / `simpletray` | 简易通知托盘 |
| `hub` | 通知中心（Hub） |
| `letters` / `notifications` | 来信列表 |
| `settings` | 设置首页 |
| `lookfeel` | 设置 → Look & Feel |
| `about` / `support` | 设置 → About |
| `wallpaper` | 壁纸 |
| `colors` / `coloreditor` | 颜色编辑器 |
| `themes` / `themepresets` | 主题预设 |
| `guide` | 使用引导 |
| `editmode` | 切换编辑模式（等同快捷菜单里的 Edit mode） |
| `editfavorites` | 编辑收藏（应用列表 EditFavorites） |
| `shortswipe` / `zone` | 短滑生效区域编辑器（原编辑模式 → 手势 → Short swipe area） |
| `onboarding` | 首启引导 |

## 附加开关

| extra | 作用 |
|---|---|
| `--ez skip_onboarding true` | 清掉首启标记，之后不再进入引导 |
| `--ez refresh_eink true` | 打开界面后强制一次 GC16 全刷（验证刷新观感用） |

## 实现位置

- `MainActivity.handleAutomationIntent(intent)`：dest 分发
- `onCreate` 与 `onNewIntent` 都会调用，`singleTask` 下重复 `am start` 也能直接切界面
- 常量：`EXTRA_AUTOMATION_DEST` / `EXTRA_AUTOMATION_SKIP_ONBOARDING` / `EXTRA_AUTOMATION_REFRESH`
- 日志：`adb logcat -s MainActivity` 里能看到 `automation: dest=...`

## 常用组合

```bash
# 进设置并立刻截一张
adb shell am start -n app.binkos/com.github.gezimos.inkos.MainActivity --es dest settings
adb exec-out screencap -p > settings.png

# 跳过引导后回到主屏
adb shell am start -n app.binkos/com.github.gezimos.inkos.MainActivity --es dest home --ez skip_onboarding true
```

## 与既有 action 的关系

`MainActivity` 原本就处理 `Constants.ACTION_OPEN_*`（小部件/快捷方式用），
`dest` 是它的超集：新增了 `lookfeel` / `about` / `wallpaper` / `colors` / `themes` / `guide`，
并且不需要预先在主屏。
