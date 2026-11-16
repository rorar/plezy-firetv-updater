# 11 — Roadmap

Where the updater goes after Round 1 (the A1–A7 + B2 set, updater `0.2.0`). Each item says what it
is, what it would take, what can go wrong, and **how it would be verified on the stick** — including
the honest cases where this hardware cannot verify it. Order is a suggestion, not a commitment.

The constraint behind all of it: **one APK must keep running on API 25** (the stick) while not being
broken on newer Android. There is no AndroidX and no Gradle, so every newer platform API has to be
called directly and guarded with `Build.VERSION.SDK_INT` — nothing here can lean on
`NotificationCompat`, `WorkManager` or the support library.

## 1. Self-update of the updater

The one item the user asked for that is still missing: today a new updater build is installed with
`adb install -r`. Everything else can ship over the air, but not the app doing the shipping.

**Shape.** The updater already knows how to download a tarball, verify an APK and drive
`PackageInstaller` ([08](08-app-implementation.md#install-routine-shared)). Self-update is the same
pipeline pointed at its own APK, with three differences:

- **A source for the APK.** The repo has no releases yet. The natural home is a GitHub release on
  this repo carrying `plezy-updater.apk`, because the unmetered `releases/latest` redirect is
  already the primary way the Plezy check works — so the updater's own check costs no API quota
  either. A plain HTTPS URL works too, but then there is no tag to compare against.
- **A version comparison against itself.** `PackageManager.getPackageInfo(getPackageName(), 0)`
  gives the installed `versionCode`; the release tag gives the available one. Compare **versionCode**
  (`0.2.0` → code `2`), never the name string.
- **The same key.** A new build only installs over the running one if it is signed with
  `app/debug.keystore` (or the real release key). So the published APK must be built from this repo
  with that key, and the check should verify the download's signer SHA-256 up front for a readable
  error instead of letting the platform fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

**What can go wrong.**

- **Replacing yourself while running.** The `PackageInstaller` session copies the APK before the
  swap, so the running process may be killed mid-flight — the install must not depend on state held
  only in memory, and the *delete after install* setting must not race the session's own copy.
- **Strictly increasing `versionCode`.** `build.sh` already takes `VERSION_CODE` / `VERSION_NAME`
  (`VERSION_CODE=3 VERSION_NAME=0.3.0 ./build.sh`); the release step must bump it every time.
- **Chicken and egg.** The first self-updating build still has to arrive by `adb install -r` once.
  That is fine — the user already does it — but it should be said in the release notes.
- **Don't self-update mid-install.** If a Plezy install or downgrade is pending, wait: two
  concurrent `PackageInstaller` sessions and a process restart is not worth reasoning about.

**Verify on the stick.** Build `0.3.0` (`versionCode 3`), publish the APK, trigger the check, click
**INSTALLIEREN**, then `dumpsys package dev.rorar.plezyupdater` must show `versionCode 3` with
`firstInstallTime` unchanged and `lastUpdateTime` moved — the same in-place-replace proof the
`upgrade` flow uses for Plezy ([test/README](../test/README.md)).

## 2. Device detection (Fire TV vs Android TV vs phone)

Today the app assumes "a TV with a D-pad". That assumption leaks into the notification wording
("press OK on the remote") and into the check cadence. A small `Device` helper would answer the
question once and let each caller adapt.

**Signals measured on this stick** (2026-11-16):

| Signal | Value here |
|---|---|
| `Build.MANUFACTURER` | `Amazon` |
| `Build.MODEL` | `AFTMM` |
| `ro.build.characteristics` | `tv` |
| `PackageManager.hasSystemFeature("android.software.leanback")` | true |
| `hasSystemFeature("android.hardware.type.television")` | true |
| `hasSystemFeature("amazon.hardware.fire_tv")` | true |
| `hasSystemFeature("android.hardware.touchscreen")` | **absent** |

So a workable ladder:

```
isFireTv()    = MANUFACTURER == "Amazon" && MODEL starts with "AFT"
                (or hasSystemFeature("amazon.hardware.fire_tv"))
isAndroidTv() = hasSystemFeature("android.software.leanback")
                || hasSystemFeature("android.hardware.type.television")
isTv()        = isFireTv() || isAndroidTv()
isTouch()     = hasSystemFeature("android.hardware.touchscreen")
```

The manifest already declares `android.software.leanback` and `android.hardware.touchscreen` as
`required="false"`, so the same APK installs on all three. `Device` is a pure function of the
`PackageManager` and `Build`, so it is testable by feeding it a fake feature set.

**What it unlocks:** the notification wording (item 3), a touch-friendly layout factor, and possibly
a different default interval (a phone is on more often than a TV).

**Verify on the stick.** `Device.isFireTv()` must be true and `isTouch()` false — both readable from
`pm list features` / `getprop` exactly as in the table above. The Android TV and phone branches
cannot be verified here; they need an emulator or another device (item 3).

## 3. Android TV / phone compatibility

The UI is TV-first by design: 32–48 dp screen padding, 17 sp body text, ≥ 50 dp rows, and a focus
model built for a D-pad ([10](10-tv-ui-design.md)). On a phone that reads as oversized, and the
two-pane pickers (interval, version) assume **left/right moves between panes** — a touch user has no
left/right.

**Minimum viable support.**

- **Make everything tappable.** The `Switch` rows, action rows and dialog buttons already respond to
  touch; the version rows are `focusableInTouchMode` and install on tap (the flow tests rely on
  it). The two-pane pickers are the real work: on a touch device they should be one column with a
  segmented control, or at least accept a tap on a pane to focus it.
- **A size factor in `Ui`.** Scale the screen padding and the row heights down when `!Device.isTv()`,
  so the same layouts fit a 5-inch screen. `Ui` is already the single place sizes live.
- **Keep the D-pad path intact.** The focus model must not regress on the TV; the touch behaviour is
  additive.

**Notification wording, device-aware.** `Notifier` picks the string by device instead of shipping
one TV-flavoured sentence:

| | TV | Phone |
|---|---|---|
| Update available | "Plezy 2.27.0 available — press OK to install" | "Plezy 2.27.0 available — tap to install" |
| Installed | "Plezy updated to 2.27.0" | same |
| Not installed | "Plezy is not installed — press OK to install" | "… — tap to install" |

This is the part the user specifically called out; it is small and it depends only on item 2.

**Verify.** No Android TV or phone is available, so this is the item that **cannot be verified on
this hardware**. Options, in order of cost: an API 25 TV emulator (verifies the leanback branch and
the touch absence), then any real Android TV / phone (verifies the touch layout). Until then it
stays a documented, unverified branch — the same honesty the B2 note uses.

## 4. 64-bit / `arm64-v8a`

**Mostly done.** B2 already picks Plezy's tarball per device from `Build.SUPPORTED_ABIS`
([08](08-app-implementation.md#which-build-for-this-device-b2)), and the **updater's own APK carries
no native code** — it is pure Java + resources, so it is ABI-independent and already "armv8a
compatible". There is nothing to port.

**The one gap is verification.** The `arm64-v8a` asset's *signer hash* was never checked, only its
URL reachability. That can be closed **without a 64-bit device**, on the PC:

```bash
curl -LO https://github.com/edde746/plezy/releases/download/2.26.0/plezy-android-arm64-v8a.tar.gz
tar xzf plezy-android-arm64-v8a.tar.gz           # → plezy.apk
apksigner verify --print-certs plezy.apk | grep SHA-256
# compare with PINNED_SIGNER_SHA256 (docs/04)
```

If it matches the pinned value, the arm64 path is verified as far as the code goes; the on-device
install still needs a 64-bit device. Worth doing first — it is cheap and needs no hardware.

## 5. `targetSdk` bump matrix

The app targets **25** today, which is what the stick is. Raising it is only worth doing if the
updater must also run well on newer Android (which items 2–3 imply). Each step is a known set of
changes; all of them can be guarded by `Build.VERSION.SDK_INT` so the API 25 behaviour is unchanged.

| `targetSdk` | Platform | What breaks / is required |
|---|---|---|
| 26 | Android 8.0 | **Notification channels are mandatory** — `Notifier` must create one and post into it; on API 25 it must keep the old path. **Background execution limits**: `UpdateCheckService` (an `IntentService` started from the alarm) is cut off after ~1 min and is the wrong tool — replace it with a `JobScheduler` job (no `WorkManager`, no AndroidX). |
| 27 | 8.1 | Minor; no change expected. |
| 28 | 9.0 | **Cleartext HTTP is blocked** by default — all endpoints are already HTTPS, so fine. `Build.SERIAL` is restricted (unused). |
| 29 | 10.0 | **Scoped storage** — the app uses `cacheDir` and `getExternalCacheDir()`, both app-specific, so fine; no legacy flag needed. **Background activity starts** are restricted: the install-confirmation activity is started from the app while it is in the foreground (the user just pressed a button), so allowed — but a future auto-install path would not be. |
| 30 | 11.0 | **Package visibility** — `<queries>` for `com.edde746.plezy` is already declared. `ACTION_DELETE` still launches the stock uninstaller; installing still needs `REQUEST_INSTALL_PACKAGES` (declared). |
| 31 | 12.0 | **`PendingIntent` mutability** — every app-created `PendingIntent` must set `FLAG_IMMUTABLE`/`FLAG_MUTABLE`. **Notification trampolines** are blocked: a notification tap must not start an activity from a receiver — post the notification or use an activity instead. |
| 33 | 13.0 | **`POST_NOTIFICATIONS` is a runtime permission** — must be requested; on the stick there is no such permission, so guard it. |
| 34 | 14.0 | Foreground-service types and further implicit-intent restrictions; check the notification and alarm paths. |

The order of the real work inside a bump is: notification channel (26) → job scheduling (26) →
`PendingIntent` flags (31) → notification permission (33). Each is hand-written against the platform
API and each keeps an API 25 fallback.

**Verify.** A bump must not change behaviour on the stick: re-run the five flows and the quota
broadcast on API 25 after every step, and additionally on a newer emulator for the branch that
actually changed.

## Suggested order

1. **arm64 signer check** (item 4) — cheap, no device, closes a documented gap.
2. **Self-update** (item 1) — the most-requested, and it makes every later change shippable without
   `adb`.
3. **Device detection + notification wording** (items 2–3, wording half) — small, and the part the
   user called out.
4. **`targetSdk` bump** (item 5) — only if newer Android matters; the biggest single change.
5. **Touch UI** (item 3, layout half) — last, because it is the one that cannot be verified here.

## Guardrails

- **API 25 keeps working.** Every newer API is behind a `VERSION.SDK_INT` check; the flows are the
  regression test.
- **One build, one key.** The APK stays ABI-free, and everything is signed with the same key, so any
  build can replace any other ([Build and install](08-app-implementation.md#build-and-install)).
- **Say what was not verified.** The docs already mark the arm64/x86_64 and `err_no_native_libs`
  paths as logic-only; device detection and touch support would join that list until a second device
  exists.
