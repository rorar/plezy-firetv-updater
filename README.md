# plezy-firetv-updater

Findings, design notes and (eventually) an app for keeping [Plezy](https://github.com/edde746/plezy)
up to date on an Amazon **Fire TV Stick 4K (2018)** that is stuck on **Fire OS 6 / Android 7.1.2 (API 25)**.

Plezy is a FOSS media-server client that is paid on the Amazon App Store, ships only `.tar.gz`
Android archives on GitHub, and therefore does not fit either of the "normal" update paths
(Amazon Appstore, Obtainium). This repo records what was measured on real hardware and what
follows from it.

> Status: **a working updater app** (`app/`) plus the findings that led to it (`docs/`), the
> artwork sources (`design/`), the on-device **flow tests** (`test/`) and a throwaway probe
> (`probe/`). Verified end-to-end on the stick, UI overhaul, original artwork, the shared
> install routine, and the Round-1 feature set included.

Verified on **2026-11-13/16** with Plezy `2.26.0`, ADB `1.0.41` (platform-tools `37.0.0`).

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
   stock dialog. It also shows per-version changelogs, can uninstall Plezy, lets the user pick
   the check interval, installs a chosen version straight from the version picker (one shared
   `InstallFlow`), offers to **open Plezy after an install**, shows its own **version/About**
   screen, and can **report the GitHub quota on demand** (from Settings or by a broadcast a script
   triggers). It picks the **right per-ABI tarball** for the device, so one APK works on this 32-bit
   stick and on a 64-bit Android TV. It ships **English, German, French and Spanish** and follows
   the Fire TV's language, with a **Settings → Language** override. Two surprises are documented: on
   API 25 the app must launch the installer dialog itself, and Android 7.1 refuses downgrades of
   release builds — so a downgrade is done by uninstalling and reinstalling. It also **stays under
   GitHub's 60-requests/hour API limit**: the latest check uses the unmetered web feed and the
   picker's API call is conditional, so the updater keeps working with the quota spent. The UI is a
   TV-first dashboard
   (large text, one primary action, unmistakable D-pad focus) with real release-notes typography
   and hand-drawn icons; the launcher icon and TV banner are original artwork. See
   [docs/08](docs/08-app-implementation.md) and [docs/10](docs/10-tv-ui-design.md).

## Current state of the stick

| Item | Value |
|---|---|
| Installed Plezy | `com.edde746.plezy` `2.26.0` (`versionCode 1160`, minSdk 25, targetSdk 36) |
| Launcher | 24 `LEANBACK_LAUNCHER` entries, app appears in the Fire TV home row |
| APK used | `derFrisson/plezy-apks` `2.26.0` → `plezy-android-armeabi-v7a.apk` (90,748,577 bytes) |
| Installed size | ~87 MB in `/data/app/com.edde746.plezy-1` |
| Updater | `dev.rorar.plezyupdater` `0.2.0` (`versionCode 2`) — see [docs/08](docs/08-app-implementation.md) |

## The updater app

`app/` builds to a ~276 KB APK (the launcher icon, TV banner and the language flags at every density
are the bulk of it; the code and vectors are small). It polls the upstream GitHub release on a
user-set interval (default twice a day) and after boot, posts a notification when something newer
exists, and installs it with one confirmation on the stock Fire OS installer screen. Settings are
grouped into User / Advanced / Developer sections:

- **Toast notifications** and **Notifications** — each output can be turned off independently.
- **Prompt to open Plezy** — on by default: after a successful install, offer to start Plezy.
- **Check automatically** — enables the interval alarm + boot re-arm.
- **Edit check interval…** — every *n* hours / days / weeks / months (1–30), default 12 hours.
- **Language** — **System default** (follows the Fire TV) or English / Deutsch / Français /
  Español, each with a bundled flag. The choice is stored and applied on the next screen.
- **Delete APK after installing** — on by default, frees the downloaded APK once installed.
- **Uninstall Plezy…** — removes Plezy through the stock uninstaller, after a confirm.
- **Allow older versions** — developer option: pick any release, not just newer ones. A downgrade
  uninstalls Plezy and reinstalls the older build (Android 7.1 cannot downgrade directly), so it
  costs two confirmations and erases Plezy's data; the app warns first.
- **Check GitHub quota…** (Diagnostics) — the live `/rate_limit` value and reset time, also
  triggerable by a script over a broadcast that returns the report.
- **About Plezy Updater…** (Diagnostics) — the installed version, the third-party licence and a
  thanks note.

**Choose version…** opens a two-pane picker (versions left, release notes right), and **What's
new** shows the selected release's notes. Release notes are rendered with real typography —
coloured, larger section headings and hanging-indented bullets — through a hand-written
`Markdown.toStyled`. It also shows free space and refuses to start a download that would not fit.

The picker **installs in place**: pressing OK on a version downloads, verifies and installs it right
there, showing the progress and the outcome on the same screen — no trip back to the dashboard. The
dashboard keeps the first install for when Plezy is not yet present. Both screens drive the one
shared `InstallFlow` (download → extract → verify → `PackageInstaller` → downgrade handshake), and
after a successful install the app offers to **open Plezy**. The tarball it downloads is chosen per
device from `Build.SUPPORTED_ABIS` (`arm64-v8a` / `armeabi-v7a` / `x86_64`), so one APK fits a 32-bit
stick and a 64-bit Android TV alike.

The dashboard reads at a glance: a status glyph beside the headline (green check, accent up-arrow,
amber warning, red cross), a leading icon on every action row, and an original app icon and TV
banner. The glyphs are hand-written `<vector>` drawables (no icon pack, no AndroidX — the build has
no resource merging) and the icon/banner are generated procedurally. See
[`design/`](design/README.md).

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
| [08-app-implementation](docs/08-app-implementation.md) | The updater app as built: pending-user-action, the shared install routine, the open-Plezy prompt, About/version, quota diagnostics, per-ABI asset, downgrade, uninstall, changelog typography, check interval, staying under GitHub's rate limit, language, the `Ui` toolkit, icons/artwork, storage, settings |
| [09-backlog](docs/09-backlog.md) | Done items; open ideas point at the roadmap |
| [10-tv-ui-design](docs/10-tv-ui-design.md) | The TV UI plan: layout grid, focus model, icons, per-screen design (including the language picker, About and dialogs), how the look is verified |
| [11-roadmap](docs/11-roadmap.md) | What comes next: self-update, device detection, Android TV/phone compatibility, the `targetSdk` bump matrix |

## Flow tests

The five flows — **install**, **downgrade**, **upgrade**, **uninstall**, **clean-install** — are
written down as code in [`test/`](test/README.md). They drive the real stick over ADB exactly as a
person with the remote would (find the labelled button, tap it), so they double as a record of what
to click:

```bash
python3 test/run.py install          # forward path (non-destructive)
python3 test/run.py all --yes        # the whole sequence
python3 test/run.py install --app-lang de   # set the app to German, then run
```

The updater is runtime-switchable, so `--app-lang` sets the app's language (via Settings →
Language) before the flows run — one build is exercised in every language. Every label the flows
look for lives in per-language JSON (`test/strings/app.<lang>.json` for the app,
`test/strings/system.<lang>.json` for the stock Fire OS dialogs), so no label is hard-coded and the
same flows run against any app language and any device locale.

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
