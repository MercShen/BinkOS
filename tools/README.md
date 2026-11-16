# tools/ — 桌面切换、automation 驱动与通知探针

本目录是**工具侧**说明。面向使用者的主文档（dest 全表、实现位置、与 action 的关系）在
`docs/automation-entry.md`，由主仓库维护；这里只写脚本契约、前置条件与 Windows / Bash 两种调用方式。

| 文件 | 作用 |
|---|---|
| `home.ps1` | 查看 / 设置 / 校验默认桌面（debug / release / ensure），仓库内独立版本 |
| `automate.ps1` | 封装 `adb ... --es dest`，用 `request_id` + automation_result 判定结果 |
| `check-locales.py` | 校验 EN/ZH 字符串 key 对齐、占位符、重复/空值，并告警非术语表写法 |
| `tests/` | 离线测试：fake adb + 参数契约 + 状态机断言（不连真机、不构建） |
| `notifprobe/` | 三个通知探针 APK（alpha/beta/gamma），见其 README |

仓库外的 `../tools/home.ps1`（workspace 级脚本）保持不动；本目录的 `home.ps1` 不依赖它。

## 前置条件

- Windows PowerShell 5.1+ 或 PowerShell 7（`pwsh`）。
- adb 查找顺序：`-AdbPath` > PATH 中的 `adb` > `$env:ANDROID_HOME` / `$env:ANDROID_SDK_ROOT` / `$env:ANDROID_SDK_HOME` 下的 `platform-tools/adb(.exe)`。
- 设备选择：`-Serial` 显式指定；否则只接受**恰好一台** `device` 状态的设备，0 台或多台都直接拒绝（防串机）。
- `automate.ps1` 默认驱动 debug 包 `app.binkos.debug`；`-App release` 切到 `app.binkos`。
- 通知探针在 Android 13（API 33）及以上需要运行时 `POST_NOTIFICATIONS` 授权（见 `notifprobe/README.md`）。

## home.ps1

```powershell
.\tools\home.ps1                                  # 查当前默认桌面 + 角色持有者
.\tools\home.ps1 debug                            # 设为 app.binkos.debug（不满足会补 role 后重试）
.\tools\home.ps1 release
.\tools\home.ps1 ensure debug                     # 只校验/修复，不改其它状态
.\tools\home.ps1 ensure release -Serial <serial>
.\tools\home.ps1 debug -AdbPath C:\AndroidSDK\sdk\platform-tools\adb.exe
```

- 设置后一定重新 `cmd package resolve-activity` + `cmd role get-role-holders` 校验；
  先检查目标包已安装（精确整行匹配，`app.binkos` 不会误判成 `app.binkos.debug`）。
- `adb install -r` debug 包后 HOME 可能变成“role 仍指向 debug、resolve 却落到 android”的半坏状态；
  `home.ps1 debug`/`ensure` 会补 role 再 set-home 并校验。只是临时装包测试时，收尾按
  `docs/testing-playbook.md` §7 恢复安装前桌面（release 或第三方），卸载 debug 前也要先切走。
- 退出码：`0` 成功 / `2` 参数错误 / `7` adb发现/设备选择/查询错误 / `9` 设置或校验失败（包括目标包不存在、设置命令失败、半坏状态修复后仍不对）。

Bash 等价：

```bash
ADB=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb
SERIAL=<serial>
"$ADB" -s "$SERIAL" shell cmd package set-home-activity app.binkos.debug/com.github.gezimos.inkos.MainActivity
"$ADB" -s "$SERIAL" shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
"$ADB" -s "$SERIAL" shell cmd role get-role-holders android.app.role.HOME
```

## automate.ps1

```powershell
# UI 请求建议带 -ExpectedText；只给 -Dest 通常只会拿到 dispatched，脚本按契约退出 6（不是成功）
.\tools\automate.ps1 -Dest settings -ExpectedText Settings
.\tools\automate.ps1 -Dest edit_clock -Tab 1 -ExpectedText Clock
.\tools\automate.ps1 -Dest pager_off -AllowStateChanges
.\tools\automate.ps1 -Dest bottomwidget -Type quote -AllowStateChanges # writes state, does not navigate
.\tools\automate.ps1 -Dest status # check bottomWidget="quote"
.\tools\automate.ps1 -Dest settings -ExpectedText Settings -Serial <serial> -AdbPath <path> -TimeoutSeconds 20
```

参数：

| 参数 | 说明 |
|---|---|
| `-Dest`（必填） | automation dest，见 `docs/automation-entry.md`；本地只接受 `^[A-Za-z_]+$`（≤ 48 字符），空格/分号直接退出 2 |
| `-Tab` / `-Value` | 分别映射 `--ei tab` / `--ei value`（仅在显式传入时下发） |
| `-Type` | 映射 `--es type`（如 `bottomwidget` 的 android/quote/events/...） |
| `-App` | `debug`（默认，`app.binkos.debug`）或 `release`（`app.binkos`）。当前入口在 release 明确拒绝；`-App release` 仅可验证拒绝路径，不用于正常自动化 |
| `-Serial` / `-AdbPath` | 设备与 adb 路径，规则见「前置条件」 |
| `-AllowStateChanges` | 解锁状态写 dest，并附加 `--ez confirm_write true` |
| `-ExpectedText` | 目标 UI 上必须出现的文字（text / content-desc 子串，不区分大小写）；只认 `package` 等于目标 App 的节点，且 bounds 宽高 > 0、落在 root bounds 内 |
| `-TimeoutSeconds` | 等待结果与 UI 锚点的**检查预算**，默认 15（3–120）。单次 `uiautomator dump`/`cat` 另有 1–4s 单次预算；adb 原生命令本身不保证硬性封顶，超预算会中止该次检查并以 5/8 退出 |

### 状态写 dest 的闸门

默认**本地拒绝**以下 dest（退出码 2，且不会发 `am start`）：

`pager_*`、`home_reset_*`、`bottom*`、`allowlist*`

确需改动时加 `-AllowStateChanges`。注意 `allowlist*_clear` 会写用户数据（清空白名单），
跑之前先获得授权；需要回滚点就先备份（设置 → 备份/恢复，或记录当前白名单）。

### 结果判定

脚本每次生成 `request_id`（UUID）并随 `--es request_id` 下发，只读取
`MainActivity` 标签下包含该 id 的 `automation_result: {json}` 行（不执行 `logcat -c`、不打印全量日志）。

| status | 含义 | 退出码 |
|---|---|---|
| `applied` | 应用已执行；若给了 `-ExpectedText` 还要通过 UI 校验 | 0（校验失败 8） |
| `dispatched` | 仅表示已分发；**必须**配 `-ExpectedText` 并通过 UI 校验 | 0 / 无锚点 6 / 校验失败 8 |
| `rejected` | 被 app 拒绝（例如缺 `confirm_write`） | 3 |
| `cancelled` | 被取消 | 4 |
| 超时未见结果 | 检查预算内（默认 15s）没有匹配 request_id 的结果 | 5 |

其它退出码：`2` 参数/dest 校验失败，`7` adb/设备/`am start` 原生失败。

`-ExpectedText` 用 `uiautomator dump` 的 XML 轮询确认，要求命中节点的
`bounds="[x1,y1][x2,y2]"` 宽高都 > 0（零尺寸/根窗口外不算，但仍可能被遮挡）；不打印整份 XML，样式正确性还要看截图。

### Bash 等价（无 PowerShell 时）

```bash
ADB=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb
SERIAL=<serial>
PKG=app.binkos.debug
REQ=$(uuidgen 2>/dev/null || python -c 'import uuid; print(uuid.uuid4())')

"$ADB" -s "$SERIAL" shell am start -n "$PKG/com.github.gezimos.inkos.MainActivity" \
  --es dest settings --es request_id "$REQ"

# 只看本进程、本 request_id 的结果（不要 logcat -c）
PID=$("$ADB" -s "$SERIAL" shell pidof "$PKG" | tr -d '\r')
"$ADB" -s "$SERIAL" shell logcat -d --pid="$PID" -s MainActivity:I | grep "$REQ"

# 可选：目标 UI 文字（text / content-desc 子串）；路径按 request_id 唯一化，
# 只认 package=app.binkos.debug 的节点，且 bounds 宽高 > 0 并为 root bounds 内
"$ADB" -s "$SERIAL" shell uiautomator dump "/sdcard/ui_$REQ.xml"
"$ADB" -s "$SERIAL" exec-out cat "/sdcard/ui_$REQ.xml" | grep -o '<node[^>]*>' | grep -i "Clock"
```


### 失败示例（这些不是成功）

```powershell
# 只给 dest：UI 请求通常 dispatched -> 退出码 6
.\tools\automate.ps1 -Dest settings

# 未知 dest：app 返回 rejected -> 退出码 3
.\tools\automate.ps1 -Dest no_such_dest

# 非法 dest（大小写以外的空格/分号会被本地拒绝）-> 退出码 2，不会发 am start
.\tools\automate.ps1 -Dest 'settings x'
```

### release 包注意

当前 App 在 `BuildConfig.DEBUG=false` 时拒绝 automation，脚本收到 `rejected` 退出 3。
正式包不提供这些测试能力；不得为了验收临时修改正式包的白名单、字体或设置。
脚本能指定 release 包不等于 App 允许执行。

## 测试

```powershell
pwsh -NoProfile -File tools/tests/run-tests.ps1
```

- 全部离线：`-AdbPath` 指向 `tools/tests/fake-adb.cmd`，场景由 `FAKE_*` 环境变量注入，
  不连接真机、不执行 gradle、不修改 B651。
- 覆盖：Parser 语法、参数契约、state dest 本地拒绝、`confirm_write`/`request_id` 透传、
  applied/dispatched/rejected/cancelled/timeout、ExpectedText（content-desc、零尺寸 bounds、
  跨包节点、root bounds 外的节点）、dest/type 字符集与长度、显式空 `-Type` 透传、
  UI dump 路径按 request_id 唯一化、设备唯一性、`am start` 原生失败、
  home 的状态/ensure/修复/校验失败/包缺失。
- 运行产物在 `tools/tests/state/`（已 gitignore）。
