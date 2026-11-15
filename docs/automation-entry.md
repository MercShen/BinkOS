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
| `editfavorites` | 编辑收藏（应用列表 EditFavorites） |
| `shortswipe` / `zone` | 短滑生效区域编辑器（原编辑模式 → 手势 → Short swipe area） |
| `onboarding` | 首启引导 |
| `editmode` / `edit_mode` | 切换编辑模式 |
| `editmode_on` / `edit_on` | 进入编辑模式（延迟 600ms 等布局就绪） |
| `editmode_off` / `edit_off` | 退出编辑模式 |
| `fonts` / `typography` | 设置 → 字体排印 |
| `gestures` / `gesture` | 设置 → 手势 |
| `notifsettings` / `notification_settings` | 设置 → 通知 |
| `advanced` | 设置 → 高级 |
| `drawersettings` / `drawer_settings` | 设置 → 应用抽屉 |
| `shortcuticon` / `shortcut_icon` | 快捷方式图标选择（LazyRow，原仅编辑模式可达） |
| `badgeindicator` / `badge_indicator` | 角标样式选择（LazyRow，原仅编辑模式可达） |
| `privacy` / `privacypolicy` | 隐私政策弹窗 |
| `fontlicense` / `font_license` | 开放字体许可弹窗 |
| `pager_on` / `pager_off` | 主页页码指示器开关 |
| `pager_margin` | 页码指示器留白(0..12)，`--ei value N`；0 = 贴安全区虚线 |
| `home_reset_off` / `home_reset_on` | 主屏「Home Page Reset」开关（新装默认关） |
| `home_next` / `home_prev` | 主页翻到下一页 / 上一页（横滑会触发长滑快捷方式，截图验证页码用这个） |
| `bottomwidget` | 底部小部件类型，需 `--es type android|shortcuts|quote|events|usage|pagedots|off` |
| `bottom_android` `bottom_shortcuts` `bottom_quote` `bottom_events` `bottom_usage` `bottom_pagedots` `bottom_off` | 直接切到对应底部小部件 |
| `allowlist_clear` | 清空托盘与 Hub 白名单（测试通知过滤前用） |
| `allowlist_tray_clear` / `allowlist_hub_clear` | 分别清空托盘 / Hub 白名单 |

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

## 测试流程

跑一次 UI 验证的标准步骤、探针 App 与踩坑记录见 [testing-playbook.md](testing-playbook.md)。
