# Bink OS Changelog

Bink OS releases use `bink-vX.Y.Z` tags. Historical Bink OS release notes are available on [GitHub Releases](https://github.com/MercShen/BinkOS/releases).

## v0.3.0 — unreleased

### Wallpapers
- Custom wallpapers now carry a permanent trash button in the corner of the preview. Press it — tap it or move the D-pad onto it and press the centre key — and a confirmation asks before the entry leaves the gallery; the image file on the device is never touched. Previously the only way to reach removal was a long press on the preview, which no keyboard can do, and the button stayed hidden until you did it right. The centre key still applies the selected wallpaper directly.

### Keyboard
- Page Up and Page Down now turn pages everywhere that has pages: the settings pages, the paged pickers (multi-select lists, badge style, shortcut icon, icon style, theme apply scope, widget app list), the first-run guide and the in-app guide. Those surfaces only ever responded to a horizontal drag, which matters on devices whose side keys are configured to send page keys instead of volume — and unlike the volume keys, page keys have nothing else to do, so they are honoured without a switch. The settings header takes the same keys as the list below it: it steals focus when a page opens, and without that the keys would look dead until you pressed the D-pad once.

### Onboarding
- The first-run guide answers the Back key again: it goes to the previous page instead of doing nothing, and it still refuses to step past the first page, so the guide cannot be exited by accident. While the guide was up every key was swallowed, which left a keypad phone no way back except D-pading to the on-screen Back button.

### Notifications
- Volume-key paging now works in the Hub and the Simple Tray, not only on the home screen, the app drawer and Letters — those two screens silently dropped it. With the switch off, or while music is playing, the volume keys keep doing what volume keys do.
- Keyboard users can dismiss one notification on the Hub and in the Simple Tray: `Del`, the menu key and `1` clear the **highlighted** notification and `3` opens it. Those keys were wired through a side channel that no longer reached either screen — measured with injected keypad events, they did nothing at all — and the code behind them aimed at the first row of the page instead of the selection. Ongoing and media notifications stay protected by the same check the row's ✕ uses. The long-press-`Del`-to-clear-all shortcut went away with that dead path; clearing a category still happens from the Clear All row.
- The "Hide system notifications" switch now also hides clearable notifications sent by system apps, not just the ones that cannot be dismissed: OEM alerts like DuraSpeed's "apps were restricted" message disappear from the Hub, Tray, the home count and Letters while the switch is on. The system notification shade is left completely alone — those notifications still show there and can still be dismissed as usual. Calls are never hidden, and third-party app notifications are unaffected.

### Development
- The debug automation `status` now also reports whether a sheet is covering the screen, whether the short-swipe zone editor is open, whether the app is on the first-run guide, whether the window holds focus, and the real values of the volume-key paging and notification-access settings — so a scripted check can prove the screen it is testing is the screen it asked for. Add `volume_keys_on/off` to toggle volume-key paging without tapping through Settings. `docs/automation-entry.md` gains a keyboard-probing section (keycode table, which judgements are trustworthy, how to seed notifications); `docs/testing-playbook.md` records the measured keyboard/T9 reachability baseline and its known gaps.

### E-ink
- The short-swipe area editor gained −/+ rows for both boundaries, 8dp a step, so the area no longer needs a drag to set. Tapping them works today; reaching them from the keyboard still needs the editor to move into a dialog window of its own, which it does not have yet.
- Every Directional wipe (opening a screen, closing it, home paging) now holds its animation window for 350 ms instead of 280 ms, so the wipe shows up reliably when leaving the app drawer with the edge-back gesture. On-device HAL logs show the panel's next Regal full refresh can arrive only after the previous panel commit finishes, which the old window sometimes missed; 300 ms already dropped wipes again, and 325 ms still leaked occasionally in daily use, so 350 ms is now the shortest stable window.

## v0.2.9 — 2026-11-24

### Updates
- Add Settings → Support → Check for Update: the app asks GitHub Releases for the latest version, says whether one is available, and — when there is — offers a sheet that opens the release page in the browser. Bink OS still never downloads or installs an APK itself, so it needs no install permission; the only new permission is internet access, used for that single request. Any failure (offline, GitHub unreachable, unexpected reply) becomes "Couldn't check for updates. Tap to retry." instead of an error dialog, and the app version row stays as a way into App info.

### E-ink
- Refresh animation no longer writes the vendor's per-app animation setting: the Ripple style now uses the same temporary window as Directional (10 on open, 9 on close, 800 ms), so the E-ink center's own "animation for this app" value is left untouched and a stale value can no longer animate unrelated refreshes.
- Coming back to Home from another app keeps the vendor's full refresh plain in both styles: Ripple no longer adds a close animation to that refresh (Directional never did).
- Widen the direction-animation hold window from 100 ms to 280 ms (the Home screen is rebuilt on the way back from the app drawer and its first frame could miss a shorter window) and trim the trigger throttle from 500 ms to 300 ms so consecutive transitions still animate.

### Development
- Debug builds can force the vendor e-ink switches on devices without vendor firmware (`--es dest vendor_switches_on/off`); the flag is ignored by release builds.
- Add a `language` automation entry so tests can switch the app language without tapping the picker.

## v0.2.8 — 2026-11-18

### Language
- Fix the crash when switching the app language on Android 13+ (`IllegalStateException: Can't change activity type once set … activityType=home`). The platform per-app language API relaunches this app as the home task, and on the vendor Android 14 build that relaunch throws inside the system window manager before the choice is even stored. The app now keeps the choice in its own preference and applies it when the interface is rebuilt, so switching takes effect immediately, survives a restart, and never calls the system locale service on Android 13+.

### E-ink
- Short-swipe zone: first install now leaves the bottom 48dp clear (system gesture bar / navigation bar), so pulling the bottom handle no longer triggers the system back-to-home gesture; users can still drag it to 0.
- Vendor switches (full refresh, refresh animation, Smart Refresh Mode) and their guides only appear when the device actually exposes the vendor e-ink API; devices without it no longer show dead toggles or demos. Capabilities now live behind one `EinkCapabilities` seam so a second vendor can plug in without touching the UI.
- Add "Smart Refresh Mode" (Settings → Extras and the vendor page of onboarding, off by default): edit mode and the short-swipe zone editor temporarily switch this app to Extreme (179) and go back to Regal (180) when they close, so dragging feels responsive while page-turn animations still work elsewhere. Panel sheets stay on Regal: many are tap-only lists, and every waveform switch costs one vendor policy apply that looks like a full refresh. Turning it on for the first time shows a short guide.
- Rename "System Full Refresh (Bigme)" to "System Full Refresh".
- Tighten the direction animation hold window from 500 ms to 100 ms: on-device logs show the redraw happens within the same frame, and a shorter window keeps clock and badge refreshes from picking up the animation.
- Ripple refresh animation now covers every transition except home paging: entering a screen sends 10, going back or returning home sends 9, decided from the navigation stack rather than from long-swipe bindings, so screens without a gesture entry (wallpaper, guide, …) animate too. Falling back to a Directional wipe when the vendor call fails is gone.
- Returning to Home no longer plays a Directional page turn when the home page auto-resets: the auto-reset is not a user page turn, so it no longer animates (a Ripple close plays instead when coming back from another app).
- Home paging clears any lingering Ripple style first, so paging stays Directional in Ripple mode.

### Notifications, Tray and Hub
- Add an Advanced section at the bottom of Settings → Notifications with a single "Hide System Notification" switch (off by default): ongoing / no-clear notifications posted by system apps (NFC, USB debugging and the like) no longer appear in Hub, Tray or the unread count. Calls are never hidden, and third-party ongoing notifications are kept. The system notification shade is untouched.
- Remove the separate "enable Letters" master switch; the Letters action and shortcut are always available, so binding a gesture is enough.
- Keep ongoing-call notifications out of Hub (Tray still shows them, and neither the Tray counter nor Home count includes them); missed calls stay in Hub and land under the Calls tab, detected by category or by the default dialer package.
- Localize the call status line (ongoing / calling / missed) when the dialer's own text is empty or one of the known English phrases; any other wording is shown as-is.
- Move notification category fallback text (New message, Media playing, …) into string resources so it follows the app language.
- Drop 窗口/"Window" from the Letters strings so the product name stays as-is.

## v0.2.7 — 2026-11-17

### E-ink and onboarding
- Add the Ripple page-turn style alongside Directional; Ripple uses the vendor open/close circular animation.
- Give refresh animation its own switch and conditional style row in onboarding and Settings → Extras. Animation is off by default and the style is Directional by default; changing the style applies immediately.
- Show the refresh-animation demo only on the first enable, shared across onboarding and Settings; the Regal/HD256 notice now includes looping Directional and Ripple previews with localized labels.
- Align the onboarding vendor page typography and left edges with the Settings pages; update first-run guide and gesture copy.
- Default Bink OS edge-swipe back to off to avoid a second back action on top of Android gesture navigation.

### Notifications, Tray and Hub
- Add per-row dismiss buttons in Tray and Hub for dismissible notifications; keep ongoing / no-clear notifications non-dismissible.
- Exclude media/transport notifications from Home and Tray message counts while retaining the Tray media card.
- Let Hub switch category tabs with a horizontal swipe anywhere on the page (48dp, or a fast short flick); horizontal and vertical swipes now share one dominant-axis judgment (`ui/compose/AxisSwipeGestures.kt`), so diagonal short swipes no longer turn pages. Per-notification deletion uses the row X button, not a row swipe.
- Pin the Hub “clear all” row to the midpoint between the full notification frame and screen bottom. The reference row height is measured once and persisted, so paging, notification changes and process restarts no longer move it.
- Align Hub/Tray page dots between the message frame and screen edge, and close Tray/Hub on reverse long swipe only when the corresponding open gesture is bound.

### Reliability and development
- Harden settings and notification edge cases accumulated since 0.2.6.
- Add debug-only replay of one-time guide overlays and direct onboarding page navigation, with contract tests and automation documentation.
- Normalize product naming to “Bink OS” in in-app copy (notification access, edge-swipe back) and documentation, and rename the Gradle project accordingly; no behavior change.
- Remove the upstream author’s donation entries from the About page; credits, license and community links stay.

### Compatibility
- Package: `app.binkos`; version name `0.2.7`, version code `10`.
- Android 8.0+ (API 26); Bigme-specific integration requires supported vendor APIs.
- Signed with the existing Bink OS release key; install over an earlier signed release without uninstalling.

## v0.2.6 — 2026-11-15

### E-ink UI and editing
- Searchable, height-aware paged app selection in the widget picker; swap widgets without reopening a second sheet. A selected app's widget previews remain scrollable.
- Paged multi-choice, badge-indicator and shortcut-icon pickers, with localized labels and real app icons.
- Clearer edit-panel titles, tab hints and widget navigation; remove the retired remembered-widget-app preference.
- Consistent page indicators across Home, Simple Tray and Hub: enlarged current dot, shared shape rules, measured positioning and configurable margins.
- Separate Simple Tray and Hub options and page their notification lists; keep notification page counts stable while refreshing.
- Remove ripple/hover state layers for e-ink and fix sheet sizing, scroll interception, dismissal and nested-sheet transitions.

### Reliability and performance
- Validate ZIP backups before applying them; stage restored files and preferences with rollback on failure, archive limits and unsafe-path checks.
- Serialize/coalesce notification persistence, bound saved history and reduce unnecessary main-thread work.
- Cache widget previews and font-file parsing; coordinate app refreshes so stale work does not overwrite newer requests.
- Fix receiver registration cleanup, preserve flashlight state during ordinary refresh and align clock ticks to minute boundaries.
- Harden pinned-shortcut validation and cleanup.

### Development and repository
- Debug-only automation has validated parameters, explicit write confirmation, request IDs and structured results; release builds reject test automation.
- Add guarded backup probes, reliable PowerShell HOME/automation helpers, fake-adb regressions and JVM regression coverage.
- Run Android and Windows-tool checks on the `binkos` branch.
- Remove the unrelated upstream website, store/Fastlane metadata, funding configuration, unused distribution artwork/wallpaper copies and tracked OS/editor clutter.
- Keep GPL-3.0, upstream attribution, bundled font licensing, runtime assets and Bink OS development documentation. Formal releases use locally signed APKs and GitHub Releases, not the retired upstream tag workflow.

### Compatibility
- Package: `app.binkos`; version name `0.2.6`, version code `9`.
- Android 8.0+ (API 26); Bigme-specific integration requires supported vendor APIs.
- Signed with the existing Bink OS release key; install over an earlier signed release without uninstalling.

## Earlier history

- [Bink OS releases](https://github.com/MercShen/BinkOS/releases)
- [Upstream inkOS history](https://github.com/gezimos/inkOS/blob/main/CHANGELOG.md) — upstream versions are not Bink OS version numbers.

Bink OS remains a GPL-3.0 derivative of [inkOS](https://github.com/gezimos/inkOS) by gezimos, itself descended from mLauncher / oLauncher.
