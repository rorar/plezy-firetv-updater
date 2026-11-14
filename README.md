# plezy-firetv-updater

Findings, design notes and (eventually) an app for keeping [Plezy](https://github.com/edde746/plezy)
up to date on an Amazon **Fire TV Stick 4K (2018)** that is stuck on **Fire OS 6 / Android 7.1.2 (API 25)**.

Plezy is a FOSS media-server client that is paid on the Amazon App Store, ships only `.tar.gz`
Android archives on GitHub, and therefore does not fit either of the "normal" update paths
(Amazon Appstore, Obtainium). This repo records what was measured on real hardware and what
follows from it.

> Status: **a working updater app** (`app/`) plus the findings that led to it (`docs/`) and a
> throwaway probe (`probe/`). Verified end-to-end on the stick.

Verified on **2026-11-13** with Plezy `2.26.0`, ADB `1.0.41` (platform-tools `37.0.0`).

---

## TL;DR

1. The device is an **AFTMM / "mantis"** stick: Fire OS `6.7.1.1`, Android `7.1.2`, **API 25**,
   `armeabi-v7a` only, 2 GB RAM, no root, `ro.build.type=user`.
2. **Obtainium cannot be installed in its current form**: `v1.4.0+` requires `minSdk 26`.
   The newest release that runs on API 25 is **v1.3.4** (`minSdk 24`), which is a dead end
   and has no deep-link support. See [docs/02](docs/02-obtainium-on-fireos6.md).
3. **Plezy itself is the opposite case**: it declares `minSdk = 25` with the comment
   *"Fire OS 6.x (API 25); the mpv-android AAR shares the same floor"*, ships a proper
   `LEANBACK_LAUNCHER` + TV banner, and **installs and runs fine** — verified. See [docs/03](docs/03-plezy-sideload.md).
4. **The Amazon Appstore can never update a sideloaded Plezy**: it only updates what it
   installed, and the app is paid there, so no entitlement exists. See [docs/05](docs/05-update-strategies.md).
5. The promising design is a **small TV-native updater** that polls GitHub, posts a
   **notification**, and installs with one remote click. Silent install is impossible on this
   device (no root, no device-owner, `INSTALL_PACKAGES` is `signature|privileged`).
   See [docs/06](docs/06-own-updater-feasibility.md).
6. **Verified end-to-end on the hardware**: a debug-signed sideloaded app can show a toast, post
   a notification **from the background**, have it retained in the Fire TV notification center,
   and open its activity when the user presses OK on it. The only remaining UX cost is the
   installer confirmation dialog. See [docs/07](docs/07-notification-design.md).
7. **The updater is built and works.** `app/` (`dev.rorar.plezyupdater`, plain Java, no Gradle)
   checks GitHub, downloads the upstream tarball, verifies the signer, and installs through the
   stock dialog. It also shows per-version changelogs and can uninstall Plezy. Two surprises are
   documented: on API 25 the app must launch the installer dialog itself, and Android 7.1 refuses
   downgrades of release builds — so a downgrade is done by uninstalling and reinstalling.
   See [docs/08](docs/08-app-implementation.md).

## Current state of the stick

| Item | Value |
|---|---|
| Installed Plezy | `com.edde746.plezy` `2.26.0` (`versionCode 1160`, minSdk 25, targetSdk 36) |
| Launcher | 24 `LEANBACK_LAUNCHER` entries, app appears in the Fire TV home row |
| APK used | `derFrisson/plezy-apks` `2.26.0` → `plezy-android-armeabi-v7a.apk` (90,748,577 bytes) |
| Installed size | ~87 MB in `/data/app/com.edde746.plezy-1` |
| Updater | `dev.rorar.plezyupdater` `0.1.0` — see [docs/08](docs/08-app-implementation.md) |

## The updater app

`app/` builds to a ~42 KB APK. It polls the upstream GitHub release twice a day (and after boot),
posts a notification when something newer exists, and installs it with one confirmation on the
stock Fire OS installer screen. Settings are grouped into User / Advanced / Developer sections:

- **Toast notifications** and **Notifications** — each output can be turned off independently.
- **Check automatically** — the 12 h alarm + boot re-arm.
- **Delete APK after installing** — on by default, frees the downloaded APK once installed.
- **Uninstall Plezy…** — removes Plezy through the stock uninstaller, after a confirm.
- **Allow older versions** — developer option: pick any release, not just newer ones. A downgrade
  uninstalls Plezy and reinstalls the older build (Android 7.1 cannot downgrade directly), so it
  costs two confirmations and erases Plezy's data; the app warns first.

**Choose version…** opens a two-pane picker (versions left, release notes right), and **What's
new** shows the selected release's notes. It also shows free space and refuses to start a download
that would not fit.

Build (no Gradle — `aapt2` + `javac` + `d8` + `zipalign` + `apksigner`):

```bash
cd app
BT=/tmp/opencode/sdk/android-15 AJ=/tmp/opencode/sdk/android-35/android.jar ./build.sh
adb install -r build/plezy-updater.apk
```

## Docs

| File | Content |
|---|---|
| [00-device-profile](docs/00-device-profile.md) | Exact hardware/OS/permission profile of the stick |
| [01-adb-access](docs/01-adb-access.md) | Connecting, authorising, and a reusable command cheat-sheet |
| [02-obtainium-on-fireos6](docs/02-obtainium-on-fireos6.md) | Why Obtainium fails, and the v1.3.4 ceiling |
| [03-plezy-sideload](docs/03-plezy-sideload.md) | The verified Plezy install, step by step |
| [04-apk-provenance](docs/04-apk-provenance.md) | Signing chain: upstream keystore, tarball layout, mirror trust |
| [05-update-strategies](docs/05-update-strategies.md) | Amazon store vs Obtainium vs PC cron vs own APK |
| [06-own-updater-feasibility](docs/06-own-updater-feasibility.md) | What an own updater can and cannot do here |
| [07-notification-design](docs/07-notification-design.md) | Notification infrastructure findings + architecture |
| [08-app-implementation](docs/08-app-implementation.md) | The updater app as built: pending-user-action, downgrade, uninstall, changelog, storage, settings |
| [09-backlog](docs/09-backlog.md) | Done items plus the open ideas (changelog typography, custom interval, UI overhaul) |

## Quick start (reproduce the install)

```bash
adb connect 192.168.1.50:5555        # accept the RSA dialog on the TV once
adb install -r plezy-android-armeabi-v7a.apk
adb shell am start -a android.intent.action.MAIN \
  -c android.intent.category.LEANBACK_LAUNCHER \
  -n com.edde746.plezy/.MainActivity
```

Download the APK from the mirror release page, or extract it from the official tarball
(see [docs/04](docs/04-apk-provenance.md) — extraction is the more trustworthy route).

## Not affiliated

Not affiliated with edde746/Plezy, ImranR98/Obtainium or derFrisson/plezy-apks.
All trademarks belong to their owners. Numbers in these docs are measurements from one
device, not general claims.
