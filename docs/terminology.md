# Bink OS — Terminology & Localization Guide

Audience: **locale translators** and **AI agents** editing Bink OS UI strings.

This file is the single source of truth for how the same concept is named in English and
Simplified Chinese. If a string and this table disagree, fix the string (or update this table
first, then the string) — do not invent a third name.

UI currently ships **English (`values/`) and Simplified Chinese (`values-zh/`)** at 1:1 key
parity. Keep it that way.

## Product names — never translate

Use these verbatim in **every** locale: **Bink OS, Hub, Letters, Simple Tray, Android, GitHub,
ZIP, e-ink**. Chinese may add a Chinese suffix after a space (`Letters 窗口`, `Hub 白名单`), but
the product name itself stays English. Do not use 信笺 / 中枢 / 简易托盘 in UI strings.

---

## 1. Canonical terms

| Concept | English (canonical) | 简体中文（canonical） | Notes |
|---|---|---|---|
| The home screen | Home | 主屏 | The surface, not the system role. |
| The launcher role | Launcher | 桌面 | `Set as Default Launcher` → 设为默认桌面. |
| Apps placed on Home | Favorites | 常用应用 | Avoid “Home apps”, “Homepage”, “主屏应用”. |
| A widget | Widget | 小部件 | Avoid 组件 / 小组件 for UI widgets. |
| The launcher bottom widget | Bottom widget | 底部小部件 | Edit-mode panel title also uses this. |
| A system app widget | Android widget | Android 小部件 | Distinguish from the bottom widget. |
| Quote widget / its text | Quote | 引言 | `Quote size` → 引言字号, not “Widget Size”. |
| Theme gallery | Theme presets | 主题预设 | Row title; avoid bare “Presets”/“预设”. |
| Import/export file | Theme file | 主题文件 | Not “Theme” alone. |
| Light/dark mode | Theme mode | 主题模式 | |
| Wallpaper / background color | Wallpaper / Background | 壁纸 / 背景 | Background = color layer; wallpaper = image. |
| Wallpaper visibility | Wallpaper visibility | 壁纸可见性 | Not “opacity” in visible copy. |
| Notification badge | Notification badge | 通知角标 | Avoid “Asterisk Notification”/“星号通知” for the toggle. |
| Label notifications | Label notifications | 标注通知 | Font picker title must match: `Label Notifications Font` / 标注通知字体. |
| Letters window | Letters | Letters | Product name; never 字母 or 信笺. |
| Hub | Hub | Hub | Product name; never 中枢. |
| Simple Tray | Simple Tray | Simple Tray | Product name; always with a space. |
| Page dots setting | Page indicators | 页码指示器 | Plural in English. |
| Settings typography page | Typography | 字体排印 | A single font choice is `Font` / 字体. |
| One font for all slots | Use one font everywhere | 全局使用同一字体 | The switch label; not “Universal Font”. |
| Backup / restore sheet | Backup/Restore | 备份/恢复 | |
| Full export | Back up all data | 备份全部数据 | |
| Full import | Restore data | 恢复数据 | |
| Wipe app data | Clear all data | 清除全部数据 | |
| Clear notification lists | Clear notifications | 清除全部通知 | Never bare “Clear All”. |
| Generic apps (drawer etc.) | Apps | 应用 | Distinct from Favorites. |

---

## 2. Do not use

| Avoid | Use |
|---|---|
| Home Apps, Homepage, 主屏应用 | Favorites / 常用应用 |
| 组件, 小组件 (for a UI widget) | 小部件 |
| Asterisk Notification, 星号通知 (for the badge toggle) | Notification badge / 通知角标 |
| Badge Noti. Font, 角标通知字体 | Label Notifications Font / 标注通知字体 |
| Presets (alone), Chosen Theme, 预设 (alone) | Theme presets / 主题预设; Selected Preset / 已选预设 |
| SimpleTray | Simple Tray |
| Widget Size (for quote text), Widget Font, Widget alignment | Quote Size / 引言字号; Quote Font / 引言字体; Quote Alignment / 引言对齐 |
| Wallpaper opacity (visible copy) | Wallpaper visibility / 壁纸可见性 |
| default home screen | default launcher |
| Nr, Noti. | Number, Notification |
| 字母 (for Letters), 信笺 | Letters |
| 中枢 | Hub |
| 简易托盘 | Simple Tray |

Allowed exceptions:

- `Home Screen` / 主屏 is fine for the **wallpaper target** (“Home screen / Lock screen / Both”).
- “组件” is correct in **developer docs** when it means an Android app component/Activity,
  but not in UI strings for a widget.
- Enum names and preference keys (`Custom`, `PublicSans`, `INKOS_WALLPAPER_PATH`, `inkos_theme`)
  are code, not copy — never translate them.

---

## 3. Style rules

### English

- Row/sheet titles: Title Case (`Theme Presets`, `Notification Badge`).
- Descriptions and helper text: sentence case (`Set wallpaper visibility`).
- Product names: `Bink OS` (with a space), `Android`, `GitHub`, `ZIP`, `e-ink`.
- No abbreviations such as `Nr`, `Noti.`, `op.` in visible text.
- Keep one space after punctuation; no trailing spaces.

### 简体中文

- Use full-width punctuation (`，。：；`), Chinese quotes `“”`, and `——` for dashes.
- No space between Chinese characters and punctuation; keep a space around embedded Latin
  product names where it improves reading (`Bink OS 设置`).
- Prefer the canonical noun from the table; do not mix synonyms in one screen
  (e.g. don't have 小部件 and 小组件 on the same panel).

### Both

- Preserve placeholders exactly: `%1$d`, `%1$s`, `%d`, `%s`. Reordering positional
  arguments is allowed only for translations, never dropping/renaming one.
- Android XML: escape apostrophes as `\'` (or quote the whole string), escape `&` as `&amp;`,
  and do not introduce raw `<`/`>`.
- Keep strings short enough for a 439dp-wide e-ink screen at the user's UI scale; check
  screenshots, not just the XML.

---

## 4. Tricky cases

| Key | Current value | Rule |
|---|---|---|
| `desc_home_apps_num` | Favorites shown on Home / 主屏显示的常用应用 | Same term as the row title (`Number of Favorites`). |
| `quote_text_size` | Quote Size / 引言字号 | This is the quote text size, not the Android widget height. |
| `widget_size` | Android Widget Size / Android 小部件大小 | Android widget height/margins. |
| `home_quote_alignment` | Quote Alignment / 引言对齐 | Quote widget alignment, not Android widget alignment. |
| `app_notification_font` | Label Notifications Font / 标注通知字体 | Font dialog for the Label Notifications row. |
| `show_notification_badge` | Notification Badge / 通知角标 | Same pref as `edit_menu_notification_badge`. |
| `edit_panel_background` | Background / 背景 | Edit-mode entry says Background; do not call the panel “Theme”. |
| `edit_panel_widget` | Bottom Widget / 底部小部件 | |
| `theme_scope_hint` | Appearance only — your favorites, app aliases, and notification settings are not affected / 仅外观设置——常用应用、应用别名与通知设置不受影响 | Theme file scope; do not promise files/apps are backed up. |

---

## 5. Workflow

1. Read this table; pick the canonical term.
2. Edit **both** `app/src/main/res/values/strings.xml` and
   `app/src/main/res/values-zh/strings.xml` (never only one).
3. Keep the same key names; keys are stable identifiers, not copy.
4. Run the locale checker:
   ```bash
   python tools/check-locales.py
   ```
   It fails on missing keys, duplicate keys, empty values and placeholder mismatches,
   and warns about known non-canonical terms.
5. Build and spot-check the affected screens:
   ```bash
   ./gradlew assembleDebug
   ```
   Text can truncate at `Extra Large` UI scale; screenshot both languages when possible.
6. If you introduced a genuinely new concept, add it to §1 and (if it has a known bad variant)
   §2 **before** landing the strings.

### For AI agents

- Never hardcode user-visible UI text in Kotlin; use `stringResource(...)` / `context.getString(...)`.
- Do not rename existing string keys or JSON/enum values as part of a copy change.
- Do not “fix” a term in one locale only.
- Do not translate technical identifiers, file names, `type` values, or resource keys.
- Run `python tools/check-locales.py` and `./gradlew assembleDebug` before reporting done.
- If a string is intentionally a developer term (e.g. “Android component” in `docs/`), leave it.

---

## 6. Relationship to other docs

- [automation-entry.md](automation-entry.md) — automation `dest` contract.
- [testing-playbook.md](testing-playbook.md) — manual test checklists.
- [ui-scroll-vs-paged.md](ui-scroll-vs-paged.md) — paging vs scrolling decisions.
- [ui-search-fields.md](ui-search-fields.md) — search field inventory.

Those docs use Chinese prose; when they refer to **UI** widgets/apps, follow this glossary
(小部件 / 常用应用). When they refer to **Android components/Activity**, the technical term
“组件” is correct.
