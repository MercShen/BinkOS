# BinkOS Documentation Index

BinkOS is a personal fork of inkOS focused on the Bigme HiBreak and e-ink use. This index points to the documentation maintained for this fork; it is not the upstream inkOS website or a complete specification of every setting.

## Using BinkOS

- [English overview, installation and feature screenshots](<README.md>)
- [简体中文：特性、安装与截图](<README.zh-CN.md>)
- [Signed APK downloads and release notes](https://github.com/MercShen/BinkOS/releases)

The README screenshots show BinkOS's home layout editor, short-swipe zone, gesture panel, e-ink extras and language picker. Bigme-specific refresh and page-turn behavior depends on the device/vendor implementation; a successful API request alone does not prove a physical refresh mode.

## Development and verification

| Document | What it covers |
|---|---|
| [Automation entry contract](<docs/automation-entry.md>) | Debug-only automation destinations, parameters, confirmation gates, request IDs, result statuses, preview selectors and state-writing operations. Release builds reject the debug automation interface. |
| [Testing playbook](<docs/testing-playbook.md>) | Device selection, default-HOME verification, UI anchors and screenshots, state restoration, offline tests and known verification boundaries. |
| [Scroll-versus-pagination inventory](<docs/ui-scroll-vs-paged.md>) | UI audit notes and linked B651 screenshots. Treat it as an audit record, not proof that every historical implementation detail remains current; use the automation contract for current command behavior. |
| [Tooling guide](<tools/README.md>) | PowerShell HOME/automation helpers, adb discovery, device selection, exit codes and offline script tests. |
| [Notification probe guide](<tools/notifprobe/README.md>) | Controlled notification test apps and their build, installation and permission requirements. |

Navigation and preview requests are not the same as persistent setting changes. `am start` success or a `dispatched` result is not UI acceptance: verify the target, inspect screenshots and check state. Configuration writes require explicit authorization; destructive restore/import tests also require a verified independent backup.

## Screenshots and evidence

- [BinkOS feature screenshots](<docs/img/bink/>) support both README languages.
- [B651 UI audit screenshots](<docs/images/scroll-audit/>) are retained as development evidence, including captures not yet linked individually by the audit inventory.

Screenshot hashes or file sizes show whether bytes changed, not whether a UI is correct. Historical captures do not replace verification of the build under test.

## Build and release boundaries

Build prerequisites and the local release-build command are listed in the [English README](<README.md>) and [Chinese README](<README.zh-CN.md>). Debug/JVM and offline-script verification commands are maintained in the [testing playbook](<docs/testing-playbook.md>).

BinkOS formal releases use a **locally signed APK** uploaded to GitHub Releases with the **`bink-vX.Y.Z` tag convention**. The stable signing key is required for compatible upgrades; signing secrets stay outside the repository. Publishing or replacing a release requires explicit authorization and is separate from ordinary development/testing.

The retained [build workflow](<.github/workflows/build.yml>) provides CI build/test coverage. It is not BinkOS's formal publishing path: the upstream `v*`/Google Play AAB release workflow and upstream website assets are not maintained as part of this fork.

## Upstream attribution

BinkOS derives from [gezimos/inkOS](https://github.com/gezimos/inkOS), itself descended from mLauncher/oLauncher. Removing the upstream website does not remove attribution or change the [GPL-3.0 license](<LICENSE>).

For credits and product context, see the [English README](<README.md>) or [Chinese README](<README.zh-CN.md>).
