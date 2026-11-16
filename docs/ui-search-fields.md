# 搜索输入框清单与统一约定

只有两种形态，**不要互相统一**：

- **App 抽屉顶部搜索行**：保持上游 gezimos/inkOS 的原实现（`AppsUI.SearchField` 里的
  `BasicTextField`：无边框、`>`/`<` 前缀、`_` 空态占位、按对齐方式三种布局）。
  这块**不要**改成药丸，上游怎么长就怎么长。
- **设置类弹窗里的搜索框**：用 `InkSearchField`（药丸）。

## 药丸搜索框（`ui/compose/InkSearchField.kt`）

```
┌────────────────────────────────────────┐
│                 搜索                    │   ← 空态：半透明"搜索"居中
└────────────────────────────────────────┘
┌────────────────────────────────────────┐
│ you                                   ✕ │   ← 有内容：左对齐文本 + 右侧清除
└────────────────────────────────────────┘
```

- 尺寸：与列表行同宽（吃满父级）、高 48dp（× screenScale）、圆角 50%（药丸）、1.5dp 文字色描边；
  上间距 14dp、下间距 10dp（别贴住标题/返回箭头）。
- 空态占位是**居中、45% 透明**的「搜索」两个字（`R.string.search_placeholder`），
  用户键入任何内容即消失 —— 不是聚焦就消失。
- 有内容时右侧出现 32dp 点击区的小叉（`Icons.Rounded.Close`），点击清空并保持聚焦（键盘不收起）。
- 不再有 `>` 前缀和 `_` 下划线：那是抽屉的记号，药丸里不摆。
- 交互约定（踩过的坑）：
  1. **整颗药丸可点**，内边距区域也算命中。
  2. 聚焦后 `delay(40)` 再 `keyboardController.show()`；同一帧 `requestFocus(); show()` 会丢。
  3. `FocusRequester` 必须在函数体里 `remember`，不能放默认参数（实例不稳定 → `requestFocus()` 抛异常被 catch → "点空白没反应"）。
  4. 光标闪烁关闭（`LocalCursorBlinkEnabled = false`）。

## 唯一实现

`ui/compose/InkSearchField.kt`

- `InkSearchField(value: TextFieldValue, …)` —— 抽屉用，需要跟它的 `FocusRequester` 和 `<`/`>` 方向联动。
- `InkSearchField(value: String, …)` —— 其他界面用，内部包一层 `TextFieldValue`。
- `pill = true` 时的规格：`fillMaxWidth(0.66f)`、`RoundedCornerShape(50%)`、1.5dp 文字色描边、`heightIn(min = 44dp)`、内边距 18/10dp、外边距 上14/下10dp。
- 参数要点：`prefix`（右侧对齐传 `<` 且 `prefixAtEnd = true`）、`hint`（空态提示，画在 `_` 后面，40% 透明）、`fontSize`/`fontFamily` 跟随调用方排版、`imeAction = Search`。
- 行为约定（都是踩过坑的）：
  1. **整行可点**：`clickable` 挂在行/药丸上，不是只压在 `BasicTextField` 的内容宽度上。旧实现里边框内边距、行尾空白都点不到。
  2. **聚焦后延迟再要键盘**：`LaunchedEffect(isFocused) { delay(40); keyboard.show() }`。同一帧 `requestFocus(); show()` 时输入会话还没建立，`show()` 会被丢掉。
  3. **`FocusRequester` 必须在函数体里 `remember`**，不能写成 `focusRequester: FocusRequester = remember { FocusRequester() }`。默认参数里的实例不稳定，绑在 modifier 上是旧实例，`requestFocus()` 抛 `IllegalStateException` 被 catch 吞掉 —— 现象就是「点输入框里的字能聚焦，点右边空白没反应」。
  4. 关闭光标闪烁（`LocalCursorBlinkEnabled = false`），墨水屏上闪烁只会变残影。

## 入口清单

| 界面 | 入口 | 实现 | 形态 |
|---|---|---|---|
| App 抽屉搜索行 | 手势（默认「向左滑」= 应用抽屉）→ 抽屉第一行 | `AppsUI.SearchField`（上游原实现，不接 `InkSearchField`） | 极简行 |
| 通知 → 主屏通知白名单（标注通知应用） | 设置 → 通知 | `showAppAllowlistDialog` → `MultiChoiceSheet` → `InkSearchField` | 药丸 |
| 通知 → Letters 白名单 | 设置 → 通知 | 同上 | 药丸 |
| 通知 → Simple Tray 白名单 | 设置 → 通知 | 同上 | 药丸 |
| 通知 → Hub 白名单 | 设置 → 通知 | 同上 | 药丸 |
| 选择联系人来源（联系人账户多选） | 应用抽屉设置 → 搜索 → 联系人 | `MultiChoiceSheet` | 药丸 |
| 选择应用（底部/Android 小部件） | 设置 → 主屏 → 底部小部件 → 选择小部件 → 应用列表 | `WidgetPickerContent.WidgetAppListContent` → `InkSearchField` | 药丸 |

新增「能打字过滤」的**设置类弹窗**时，接 `InkSearchField`，不要再手写 `BasicTextField`；
抽屉那条搜索行保持上游实现，别动。

## 还没搜的（已知不一致）

`TabbedShortcutsSheet` / `GroupedMultiChoiceSheet`（应用快捷方式选择、快捷方式分组多选）没有搜索框。
列表长时只能翻页找，后续按同一约定补。

## 验收方式

坐标不要靠截图估。用 `uiautomator dump` 取真实 bounds，先看目标行有没有
`clickable=true`，点完再确认 `EditText focused=true`：

```powershell
& $ADB -s $SERIAL shell uiautomator dump /sdcard/ui.xml
& $ADB -s $SERIAL pull /sdcard/ui.xml .\ui.xml
# 在 XML 里找 EditText 节点的 bounds / focused，再 input tap
```

导航本身走 [automation-entry.md](automation-entry.md) 的 `dest`（debug 包才有），
子面板没有 dest 才退回 `uiautomator` + `input tap`。
