# Pure-reading mode (root branch design)

Status: design + skeleton. Branch: `feat/root-power`.

Bink OS ships as a normal APK. Pure-reading mode is therefore split into two
layers: everything that an ordinary app can do (the base layer, always
available), and the kernel-level controls that only exist when the user has
granted root (the deep layer, optional and gated at runtime).

The base launcher must never require root, and must never break because the deep
layer is missing.

---

## 1. Capability ladder

Measured on a B651 CN running 3.4.6 (mt6877, Android 14, stock, no root).

| Command / node | app uid | `adb shell` (uid 2000) | `root` |
|---|---|---|---|
| `cmd appops get/set` | — | yes | yes |
| `am set-standby-bucket` | — | yes | yes |
| `cmd deviceidle force-idle` | — | yes | yes |
| `cmd connectivity airplane-mode` | — | yes | yes |
| `settings put system/global` | — | yes | yes |
| `dumpsys` (power/deviceidle/alarm) | — | yes | yes |
| `pm disable-user` | — | **unverified** | yes |
| `/sys/.../scaling_cur_freq` (read) | — | yes | yes |
| `/sys/.../scaling_governor` | — | **denied** | yes |
| `/proc/ppm/policy_status` | — | **denied** | yes |
| `/proc/mtk_battery_cmd/*` | — | **denied** | yes |
| `/sys/power/state` | — | **denied** | yes |

Consequences:

- The deep layer is **not** "optimisation", it is the frequency/PPM/charge
  control. Without root it cannot be approximated.
- `su` detection is the only switch that matters. Shizuku (SHELL tier) buys the
  app-ops half and nothing else.

---

## 2. What each mode means

### PURE_READ (toggled on)

1. No calls, no SMS. Cellular radio off; user is warned explicitly before the
   first switch, and the state is visible in the UI while active.
2. Wi-Fi and Bluetooth stay available while the screen is on (online reading,
   BLE page turners). Both are cut when the screen goes off.
3. Every app outside the whitelist loses background execution: app-ops
   `RUN_ANY_IN_BACKGROUND` / `RUN_IN_BACKGROUND` / `WAKE_LOCK` /
   `START_FOREGROUND` set to `ignore`, standby bucket `restricted`, removed from
   the Doze whitelist. The input method is always whitelisted.
4. Display refresh mode is **read, never written** - the vendor's e-ink centre
   owns that. The current mode only selects the CPU frequency ceiling.
5. Screen off means minimum standby: force-idle, radios down, sensors off, no
   auto power-off.

### NORMAL (toggled off)

1. Full telephony back.
2. Background behaviour restored to the values recorded **before** the switch,
   not to defaults.
3. Every change is reversible from the journal; nothing is "best effort".

---

## 3. Strategy table (v1)

`pure` / `normal` are the commands; `probe` records the real `before` value.

| key | tier | pure | normal |
|---|---|---|---|
| `radio.cell` | shell | `cmd connectivity airplane-mode enable` + keep `airplane_mode_radios=cell` | `cmd connectivity airplane-mode disable` |
| `radio.wifi.off_screen` | shell | applied on screen-off only | restore on screen-on |
| `radio.bt.off_screen` | shell | applied on screen-off only | restore on screen-on |
| `bg.appops.<pkg>` | shell | 4 × `cmd appops set … ignore` | 4 × `cmd appops set … <before>` |
| `bg.bucket.<pkg>` | shell | `am set-standby-bucket <pkg> restricted` | `am set-standby-bucket <pkg> <before>` |
| `bg.doze.<pkg>` | shell | `dumpsys deviceidle whitelist -<pkg>` | `… +<pkg>` |
| `doze.force` | shell | `cmd deviceidle force-idle deep` (on screen-off) | `cmd deviceidle unforce` |
| `sensors.mute` | shell | six `settings put system … 0` | restore each probed value |
| `ppm.unlock` | **root** | `echo 7 0 > /proc/ppm/policy_status` | restore policy state |
| `cpu.cap.small` | **root** | `echo 0 1351000 > …/hard_userlimit_max_cpu_freq` (policy 0, cpu0-5) | `-1` |
| `cpu.cap.big` | **root** | `echo 1 1430000 > …/hard_userlimit_max_cpu_freq` (policy 6, cpu6-7) | `-1` |
| `uart.stub` | **root** | bind a stub over `/system/bin/start_uart2serport.sh` (until a ROM build can ship it) | unmount / reboot |

Notes that came out of reading the firmware:

- The little cluster is **cpu0-5 (policy0)**, the big cluster is **cpu6-7
  (policy6)**. Offlining `cpu4-7` - the approach used by other HiBreak tools -
  would kill two little cores and two big ones, so this table never uses it.
- `PPM_POLICY_USER_LIMIT` (policy 7) is re-locked by `mtkpower` on every screen
  on. Writing `scaling_max_freq` while it is set appears to succeed and has no
  effect, so `ppm.unlock` must always run **before** any frequency cap.
- `/system/bin/start_uart2serport.sh` is missing in 3.4.6 while
  `PowerStandbyManager` keeps calling `ctl.start uart2serport`: the service sits
  in `restarting` and `sys.init.updatable_crashing` stays `1`. This is a
  continuous init retry loop and is the cheapest deep win available.

---

## 4. State machine

```
mode: NORMAL | PURE_READ         persisted
journal: [Change(key, before, after)]   persisted, cleared on success
```

`apply(target)`:

1. filter the table by capability (`requiresRoot` ops are skipped outright);
2. for each op: probe `before`, run `pure`/`normal`, append to journal;
3. on the first failure: revert everything applied in this transition, leave the
   mode flag untouched, return false;
4. on success: persist the mode flag and clear the journal.

`reconcileOnStart()`: a non-empty journal means a transition was interrupted, so
the safe action is to revert to NORMAL rather than assume PURE_READ completed.
The boot-loop guard (three fast starts in a row -> force NORMAL) lives here too.

---

## 5. UX rules

- The capability tier is always visible next to the toggle:
  `Base` / `Deep (root)` / `Deep unavailable`.
- A gated layer must say **why**, not hide: "深度省电需要 root；当前为基础模式".
- The first switch to PURE_READ shows a blocking confirmation with the four
  consequences (no calls, no missed-call record, SMS queued by the carrier,
  OTP codes may expire before they arrive).
- Leaving PURE_READ shows progress until telephony re-registers (5-15 s) instead
  of silently returning to a home screen with no signal.
- If the executor disappears mid-mode (Shizuku dies, root revoked), the launcher
  reverts to NORMAL on next start and says so.

---

## 6. Non-goals

- Any EPDC waveform / refresh-mode control (vendor-owned, panel-specific).
- Auto power-off (it contradicts "no calls" being the only loss).
- Sharing code with PolyForm-Noncommercial projects. Ideas and API facts only -
  Bink OS is GPL-3.0 and a non-commercial licence cannot be combined with it.

---

## 7. Acceptance

Baseline captured on stock 3.4.6 (see `binkos-baseline/`):

- standby current: **~218 mA** measured over three asleep/discharging samples,
  methodology caveated (sampler wake-ups, frozen `charge_counter`);
- target for PURE_READ standby: **<= 40 mA**, verified with an overnight run and
  cross-checked against a capacity-drop estimate;
- reading-mode page-turn latency must not regress beyond 10% (P95), measured
  with the existing e-ink probe project.

---

## 8. Open questions

1. Whitelist granularity is package-level (per decision). Multi-user / clone
   profiles need a uid-level table later.
2. `pm disable-user` from a rooted app: keep, or rely on app-ops only? Freezing
   is stronger but harder to explain and to undo.
3. Does the deep layer ship as part of the same APK (root users get it for free)
   or as a separate module? Current assumption: same APK, runtime gating.
