# 滚动 UI vs 翻页 UI（清单 + 截图）

墨水屏上「跟手滚动」会让内容反复局部刷新，容易留下残影；整页切换（分页）刷新区域更可控。
这份清单把当前所有可滚动界面列出来，标注实现位置、截图，以及建议方向。

截图目录：`docs/images/scroll-audit/`。所有截图都用文末的自动化 `dest` 直接打开后截取（B651，824×1648）。

## 一、仍是自由滚动

| 界面 | 实现 | 截图 | 备注 |
|---|---|---|---|
| 组件列表（选具体小组件，第二级） | `LazyColumn` 固定 400dp，`EditModeHelper.kt` `WidgetListContent` | [widget-list-scroll.png](images/scroll-audit/widget-list-scroll.png) | 与上一级应用列表（已分页）不一致，优先级最高 |
| 快捷方式图标选择 | `LazyRow` 横向滚动，`ComposeBottomSheet.kt` `ShortcutIconPickerSheet` | [shortcut-icon-picker.png](images/scroll-audit/shortcut-icon-picker.png) | 原仅编辑模式可达，已加 `dest shortcuticon` |
| 角标样式选择 | `LazyRow` 横向滚动，`ComposeBottomSheet.kt` `BadgeIndicatorPickerSheet` | [badge-indicator-picker.png](images/scroll-audit/badge-indicator-picker.png) | 同上，已加 `dest badgeindicator` |
| 单选列表（语言等） | `LazyColumn`，`SingleChoiceSheet` | [single-choice-language.png](images/scroll-audit/single-choice-language.png) | 项数少，滚动压力低 |
| ~~多选列表（标注通知应用等）~~ | 已改为按可用高度分页 + 搜索（`MultiChoiceSheet`，v0.2.6） | [multi-choice.png](images/scroll-audit/multi-choice.png) | 见下方「已翻页」 |
| 分组多选 / 分组快捷方式 | `LazyColumn`，`GroupedMultiChoiceSheet` / `TabbedShortcutsSheet` | — | 分组头 + 子项，暂未单独截图 |
| 字体选择（分 Tab） | `LazyColumn`，`TabbedFontPickerSheet` | [font-picker.png](images/scroll-audit/font-picker.png) | 字体预览行较高，滚动观感尚可 |
| 手势动作选择 | 3 段 `LazyColumn`，`GestureActionPickerSheet` | [gesture-action-picker.png](images/scroll-audit/gesture-action-picker.png) | 分区列表，项目多 |
| 隐私政策 | `LazyColumn`，`PrivacyPolicySheet` | [privacy-policy.png](images/scroll-audit/privacy-policy.png) | 长文本，适合连续滚动 |
| 开放字体许可 | `LazyColumn`，`FontLicenseSheet` | [font-license.png](images/scroll-audit/font-license.png) | 同上 |
| 颜色编辑器整页 | `verticalScroll`，`ColorEditorUI.kt` | [color-editor.png](images/scroll-audit/color-editor.png) | 长表单页面 |
| 通知展开详情 | `verticalScroll`，`NotificationsUI.kt` | — | Hub / 托盘里展开后的长内容 |
| 设置各页容器 | `verticalScroll(enabled = false)`，`SettingsFragment.kt` | [settings-fonts.png](images/scroll-audit/settings-fonts.png) · [settings-gestures.png](images/scroll-audit/settings-gestures.png) · [settings-notifications.png](images/scroll-audit/settings-notifications.png) · [settings-drawer.png](images/scroll-audit/settings-drawer.png) | 只是布局，用户不能滚 |

## 二、已经是翻页 / 不可手滚

| 界面 | 机制 |
|---|---|
| 主屏应用、App 抽屉、最近任务 | 分页状态 + 滑动切页（`userScrollEnabled = false` 的 `LazyColumn` 只负责每页布局） |
| 来信 `LettersFragment` | `VerticalPager`，一屏一条 |
| 简易托盘 | `SimplePagerState` 分页（每页 = Notifications Per Page） |
| 通知中心 Hub | 分类 Tab + 单页滚动列表（**不是分页**；`*3` 只用于键盘/索引换算） |
| 壁纸画廊 | 按页切换 |
| Onboarding | 分页 |
| 小组件选择·应用列表（第一级） | 按可用高度分页（`chunked(rowsPerPage)` + 左右滑） | [paged-app-list.png](images/scroll-audit/paged-app-list.png) |
| 图标样式选择 sheet | `BoxWithConstraints` 计算每页卡片数 |
| 多选列表（标注通知应用） | 按可用高度分页 + 搜索框（`MultiChoiceSheet`） |
| 角标样式选择 | 按宽度分页 + 页码圆点（`BadgeIndicatorPickerSheet`） |

## 三、建议顺序

1. 组件列表（第二级）改分页 —— 和第一级割裂最明显。
2. 两个 `LazyRow`（快捷方式图标、角标样式）改整页左右切换 —— 横向半行卡住最难受。
3. 多选/分组列表 —— 条目多，可考虑「每页 N 项 + 页码」。
4. 长文本 sheet（隐私政策、字体许可）、颜色编辑器、通知详情 —— 保留滚动更自然。

## 四、复现截图的自动化入口

```bash
# 字体页 / 手势页 / 通知页 / 抽屉页 / 高级
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest fonts
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest gestures
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest notifsettings
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest drawersettings
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest advanced

# 弹窗/选择器
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest shortcuticon
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest badgeindicator
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest privacy
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest fontlicense
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest colors

# 小组件选择（先确保设置里底部小部件为 Android 小部件）
adb shell am start -n app.binkos.debug/com.github.gezimos.inkos.MainActivity --es dest settings
# 再进「主屏 → 底部小部件 → 选择组件」
```

完整 dest 列表见 [automation-entry.md](automation-entry.md)。
