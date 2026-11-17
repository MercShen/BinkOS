# Bink OS Changelog

Bink OS releases use `bink-vX.Y.Z` tags. Historical Bink OS release notes are available on [GitHub Releases](https://github.com/MercShen/BinkOS/releases).

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
