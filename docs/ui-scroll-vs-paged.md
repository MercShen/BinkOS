# 滚动 UI vs 翻页 UI（清单 + 截图）

墨水屏上「跟手滚动」会让内容反复局部刷新，容易留下残影；整页切换（分页）刷新区域更可控。
这份清单把当前所有可滚动界面列出来，标注实现位置、截图，以及建议方向。

截图目录：`docs/images/scroll-audit/`。所有截图都用文末的自动化 `dest` 直接打开后截取（B651，824×1648）。

## 一、仍是自由滚动

| 界面 | 实现 | 截图 | 备注 |
|---|---|---|---|
| 组件列表（选具体小组件，第二级） | `LazyColumn` 固定 400dp，`EditModeHelper.kt` `WidgetListContent` | [widget-list-scroll.png](images/scroll-audit/widget-list-scroll.png) | 与上一级应用列表（已分页）不一致，优先级最高 |


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
| 简易托盘 | `SimplePagerState` 分页，每页条数按可用高度动态计算（原「每页数量」设置已移除） |
| 通知中心 Hub | 分类 Tab + 每页条数按可用高度动态分页；侧边页码点跟随文字岛与主页对齐 |
| 壁纸画廊 | 按页切换 |
| Onboarding | 分页 |
| 小组件选择·应用列表（第一级） | 按可用高度分页（`chunked(rowsPerPage)` + 左右滑） | [paged-app-list.png](images/scroll-audit/paged-app-list.png) |
| 图标样式选择 sheet | `BoxWithConstraints` 计算每页卡片数 |
| 多选列表（标注通知应用） | 按可用高度分页 + 搜索框（`MultiChoiceSheet`） |
| 角标样式选择 | 按宽度分页 + 页码圆点（`BadgeIndicatorPickerSheet`） |
| 快捷方式图标选择 | 按宽度分页 + 页码圆点（`ShortcutIconPickerSheet`） |

## 三、分页指示器约定（2026-11 增补）

弹窗里点搜索框弹出键盘后，可视高度变小 → 每页行数变少 → 页数暴涨（实测同一列表从 2 页变 4 页），
把每一页都画成一个点会把列表挤没。统一用 `SettingsComposable.PagedNavRow`：

- **页码点最多 8 个**，当前页始终在窗口内，窗口随当前页滑动（滑动窗口算法在
  `ui/compose/PagedDots.kt` 的 `pagedDotWindowStart`，有 JVM 单测覆盖 >8 页的情况）。
- **箭头只在真的有对应方向的页面时出现**：第一页没有 `‹`，最后一页没有 `›`。
  用"箭头在不在"表达边界，不要靠把箭头画灰（墨水屏上灰箭头仍然像可点）。
- **左右箭头各占一个固定槽位**（28dp，无箭头时留空），页码点因此始终固定居中，不会因为
  翻到第二页时左侧突然多出 `‹` 而整体位移。
- 已接入：多选白名单（`MultiChoiceSheet`）、选择应用（`WidgetAppListContent`）。
  其余分页 sheet 仍是旧样式，改动时沿用这个组件。

## 四、建议顺序

1. 组件列表（第二级）改分页 —— 和第一级割裂最明显。
2. ~~两个 `LazyRow`（快捷方式图标、角标样式）改整页左右切换~~ —— 均已完成。
3. 多选/分组列表 —— 条目多，可考虑「每页 N 项 + 页码」。
4. 长文本 sheet（隐私政策、字体许可）、颜色编辑器、通知详情 —— 保留滚动更自然。

## 五、复现截图的自动化入口

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
