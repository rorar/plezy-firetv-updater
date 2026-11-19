# 11 — Roadmap

Where the updater goes after Round 1 (the A1–A7 + B2 set, updater `0.2.0`; self-update in `0.3.0`;
device detection and device-aware wording in `0.4.0`; `targetSdk` 25 → 26 in `0.5.0`; the emulator
lane, a real-phone lane and `targetSdk` 26 → 31 in `0.6.0`; `targetSdk` 31 → 33 with
`POST_NOTIFICATIONS` in `0.7.0`; `targetSdk` 33 → 34 with the runtime-receiver export flags and the
`ACCESS_NETWORK_STATE` job permission in `0.8.0`; the touch layout — single-pane Choose-Version with
a right-side changelog fly-in — in `0.9.0`, and the touch check-interval picker with a screen audit
in `0.9.1`). Each item says what it
is, what it would take, what can go wrong, and **how it would be verified on the stick** — including
the honest cases where this hardware cannot verify it (the emulator lane now covers some of those).
Order is a suggestion, not a commitment.

The constraint behind all of it: **one APK must keep running on API 25** (the stick) while not being
broken on newer Android. There is no AndroidX and no Gradle, so every newer platform API has to be
called directly and guarded with `Build.VERSION.SDK_INT` — nothing here can lean on
`NotificationCompat`, `WorkManager` or the support library.

## 1. Self-update of the updater

**Done** (2026-11-17, updater `0.3.0`). The one item the user asked for that was missing: a new
updater build used to need `adb install -r`. Everything else can ship over the air, but not the app
doing the shipping — now it can. See
[08 — Self-update](08-app-implementation.md#self-update-the-updater) for how it works.

**Shape.** The updater already knew how to download a tarball, verify an APK and drive
`PackageInstaller` ([08](08-app-implementation.md#install-routine-shared)). Self-update is the same
pipeline pointed at its own APK, with three differences:

- **A source for the APK.** A GitHub release on this repo carrying `plezy-updater.apk`, because the
  unmetered `releases/latest` redirect is already the primary way the Plezy check works — so the
  updater's own check costs no API quota either (`PlezyRepo.latestTagFor`).
- **A version comparison against itself.** The release tag is compared with the running
  `versionName`; the download is then re-checked so its **`versionCode`** is strictly greater than
  the installed one (`0.2.0` → code `2`), never the name string.
- **The same key.** A new build only installs over the running one if it is signed with the same
  certificate, so `ApkInstaller.verifySelfUpdate` compares the download's signer with the *running
  app's* signer up front, for a readable error instead of `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

The check is deliberately **manual** — Settings → About → **Update Plezy Updater…** — and the last
step is always the system installer's own confirmation.

**What could go wrong (and how it is handled).**

- **Replacing yourself while running.** The `PackageInstaller` session copies the APK before the
  swap, so the running process may be killed mid-flight. The downloaded APK is left in the cache on
  purpose so the session's copy never races a delete, and the result is worded by
  `InstallResultReceiver`'s `self_update` branch (best-effort, since the swap may kill the process).
- **Strictly increasing `versionCode`.** `build.sh` takes `VERSION_CODE` / `VERSION_NAME`
  (`VERSION_CODE=4 VERSION_NAME=0.4.0 ./build.sh`); the release step bumps it every time, and
  `verifySelfUpdate` refuses anything not strictly newer.
- **Chicken and egg.** The first self-updating build still arrives by `adb install -r` once; the
  `v0.3.0` release notes say so.
- **Don't self-update mid-install.** Self-update is manual and lives on the Settings screen, away
  from the install flow, so two concurrent `PackageInstaller` sessions are not a normal path.

**Verified on the stick** (2026-11-17). Installed a code-2 build carrying the feature via
`adb install -r`, published `v0.3.0` (code 3), then drove the row with the D-pad: the check found
`v0.3.0`, downloaded `plezy-updater.apk`, verified it, and the stock installer showed an **update**
dialog for Plezy Updater. After **INSTALLIEREN**:

```
versionCode=2 → 3        versionName=0.2.0 → 0.3.0
firstInstallTime=2026-11-13 23:29:11   (unchanged — in-place replace)
lastUpdateTime=2026-11-17 13:13:46 → 13:21:42
```

The relaunched build's About row reads `Plezy Updater 0.3.0 (build 3)`, and a second check reports
`Up to date — 0.3.0 (build 3).`

**It then carried the next release too** (2026-11-17, `0.4.0`). The stick was reset to `v0.3.0`
(an in-place downgrade of a release build is refused on API 25 — [Finding 2](08-app-implementation.md#finding-2--android-71-refuses-downgrades-of-release-builds)), then the same row brought it
to `0.4.0`: `versionCode 3 → 4` with `firstInstallTime` unchanged and `lastUpdateTime` moved. So the
`adb install -r` in the chicken-and-egg bullet is now genuinely a one-time step.

## 2. Device detection (Fire TV vs Android TV vs phone)

**Done** (2026-11-17, updater `0.4.0`). See
[08 — Device detection](08-app-implementation.md#device-detection-fire-tv--android-tv--touch).

Two classes, split so the decision is testable without hardware: **`DeviceKind`** is the decision as
a pure enum with **no `android.*` import at all** (it takes a plain `Signals` bundle), and
**`Device`** is the thin Android wrapper that reads the signals once and hands them over. One value,
fixed precedence **Fire TV > Android TV > touch > other**.

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
| `Configuration.touchscreen` (`am get-config`) | `notouch` |

The ladder, as built — three corrections to the sketch this section first carried:

```
isFireTv()    = MANUFACTURER == "Amazon" && MODEL starts with "AFT"    (null-guarded)
                || hasSystemFeature("amazon.hardware.fire_tv")
isAndroidTv() = hasSystemFeature("android.software.leanback")
                || hasSystemFeature("android.hardware.type.television")
                || UiModeManager.getCurrentModeType() == UI_MODE_TYPE_TELEVISION
isTv()        = kind is FIRE_TV or ANDROID_TV
isTouch()     = Configuration.touchscreen == TOUCHSCREEN_FINGER       ← NOT the feature flag
```

- **`isTouch` is the touchscreen *resource qualifier*, not `android.hardware.touchscreen`.** It is
  the same field Android matches `res/values-finger/` on, so the wording (item 3) and the detection
  share one signal. The feature flag is tempting — it is absent on this stick, so it "works" — but it
  is true on devices where the resource system would not pick `-finger`, which is the drift this
  avoids.
- **`UI_MODE_TYPE_TELEVISION` is checked too**, for AOSP boxes that carry neither TV feature.
- **A Fire tablet (`KF…`, touch) classifies `TOUCH`, not `FIRE_TV`** — Amazon, but not a TV.

The manifest already declares `android.software.leanback` and `android.hardware.touchscreen` as
`required="false"`, so the same APK installs on all three.

**What it unlocks:** the notification wording (item 3), a touch-friendly layout factor, and possibly
a different default interval (a phone is on more often than a TV).

**Verified on the stick.** The classification rides along on the A3 diagnostics broadcast as
`device=…;tv=…;touch=…`, and the flow harness asserts it against `config.json`:

```
data="status=ok;remaining=53;limit=60;reset_ms=…;checked_ms=…;device=fire_tv;tv=1;touch=0"
```

The Android TV and phone branches cannot be produced on this stick. They are covered off-device by
`test/jvm/run.sh` — 25 cases against `DeviceKind` on the plain JVM, including the precedence between
the branches and null safety — and, since the emulator lane, seen on a device too: `tv25`/`tv30`
report `device=android_tv;tv=1;…`, `phone34` reports `device=touch;tv=0;touch=1`, and the **real**
`phone35` (API 35) reports the same touch classification. So the touch branch is verified on real
hardware; the Android TV branch only on emulators (no real Android TV here).

## 3. Android TV / phone compatibility

**Wording: done** (2026-11-17, `0.4.0`). **Layout: done** (2026-11-18/19, `0.9.0` + `0.9.1`). The
touch Choose-Version is now a single pane with a right-side changelog fly-in, the check-interval
picker is a tapped number grid plus unit chips, and the behaviour strings say "device" on a touch
device. The TV two-pane layouts and the D-pad path are unchanged.

### Device-aware wording — done

The app used to ship one TV-flavoured sentence. It now picks it by device **without a line of Java**,
using Android's own touchscreen resource qualifier: `values-finger/` (and `values-de|fr|es-finger/`)
override the strings that talk about *how* the user acts and *what the device is* — "tap" instead of
"press OK", and "device" instead of "TV". Because the qualifier *is* `Configuration.touchscreen ==
TOUCHSCREEN_FINGER`, the same field `Device.isTouch()` reads (item 2), the wording and the detection
cannot disagree. Three strings that named the device where the generic word reads fine everywhere were
reworded instead of duplicated four ways (`set_notification_desc` "Fire TV notification center" →
"notification area"; `err_not_enough_space` "on the Fire TV" → "on the device"; `set_delete_desc`
"save space on the stick" → "save space"). `0.9.0` carried the *behaviour* sentences the rest of the
way too: `main_subtitle`, `set_auto_desc`, `set_interval_runs`, `interval_note`,
`about_thanks_text` and `progress_confirm` say "device" in `values-*-finger/` ("runs while the device
is on", "after the device restarts", "confirm the install on the device"). `main_hint` left that
list in `0.10.0`: shortened to the device-neutral `⏳ Update check: <n> <m>`, it no longer names the
device, so it is no longer overridden in `values-*-finger/`. See
[08 — Device-aware wording](08-app-implementation.md#device-aware-wording).

The sketch this section first carried invented three notification rows; there is really only **one**
notification (the update-available one), and its text said *install* although it **opens** the
updater:

| | TV (`values/`) | Touch (`values-finger/`) |
|---|---|---|
| Update available | `Plezy 2.27.0 available` · `Press OK to open` | `Plezy 2.27.0 available` · `Tap to open` |
| Test notification | `Test notification — press OK to open` | `Test notification — tap to open` |
| Picker hint | `…press OK to install the highlighted version.` | `…tap a version to install it, or the notes icon to read the release notes.` |
| Auto-check | `…after the TV boots.` | `…after the device restarts.` |
| Install confirm | `Confirm the install on the TV.` | `Confirm the install on the device.` |

`test/check_strings.py` keeps the four `-finger` files honest: they may only *override* keys that
exist in the matching base file, placeholders must survive translation, and no locale may fall behind
`values/`. It runs as the pre-flight step of `test/run.py`, so a wording drift fails a run.

### Layout — done (2026-11-18/19, `0.9.0` + `0.9.1`)

Two activities branch once each, on `Device.isTouch(this)` — the same signal the `-finger` wording
rides — into two layouts.

**`VersionListActivity` (`0.9.0`):**

- **TV / D-pad** (`buildTvUi`): the original two panes, unchanged. The versions on the left, the
  highlighted version's release notes on the right, driven by the D-pad; OK installs the highlighted
  version.
- **Touch** (`buildTouchUi`): a single pane of full-width rows. Tapping a row's version label installs
  it; the row's **notes icon** (`0.10.0`; before that, a *Notes* text button) flies the release notes
  in from the right as a full-width overlay
  (`buildChangelogOverlay`) with a `✕` close button, a Back handler, and an **Install this version**
  action. The overlay is `clickable`, so a tap on a dead zone is consumed instead of falling through
  to the list beneath and starting an install.

**`IntervalActivity` (`0.9.1`):**

- **TV / D-pad**: the two-pane `VALUE`|`UNIT` picker, unchanged.
- **Touch**: a **six-column grid of number chips** (1–30) plus a 2×2 grid of unit chips, both tapped,
  with the same live preview and full-width stacked **Save** / **Cancel** buttons. A 30-row single
  column shows barely one row on a phone, so the grid is the whole point.

Only the *pickers* needed a second layout: the main and Settings screens are already plain vertical
lists that read fine on a phone. The D-pad paths are untouched (each touch branch is additive), and
the `values-*-finger/` wording and these layouts share one signal, so a device that gets "tap" gets
the touch pickers. A **screen audit** in `0.9.1` swept the other activities for the same D-pad-only
gap and found none: `IntervalActivity` was the last one, every other screen drives clickable
`Button`/`Switch` rows.

**Verify.** The emulator lane ([item 5](#5-targetsdk-bump-matrix)) provides an Android TV
(`tv25`/`tv30`) and a touch device (`phone34`), and the real `phone35` is a touch phone too, so the
*wording* half and the device classification are device-verified there, and `phone34`/`phone35` drive
the whole flow over touch. The **layout** half is now device-verified as well: the harness gained a
`picker-notes` flow that opens the notes (the overlay on touch, the pane on TV) and closes them again,
and an `interval` flow that drives the picker on both layouts (tap/scroll on touch, up/down/right on
the D-pad) and is non-destructive. The `picker-notes` flow passed on the stick (API 25, two-pane),
`phone34` (API 34) and the real `phone35` (API 35), plus `tv30` (a TV that reports `touch=1`, so it
exercises the *touch* picker on an Android TV) — see
[08](08-app-implementation.md#finding-7--the-touch-notes-overlay-let-taps-fall-through). The
`interval` flow passed on the stick (D-pad), `phone34` (touch) and `tv25` (D-pad) / `tv30` (touch), and
found the touch double-tap bug
([Finding 8](08-app-implementation.md#finding-8--the-touch-interval-screen-swallowed-the-first-tap)).
The hand checks covered what a flow cannot assert: the four `-finger` wordings (`device`/`Gerät`/
`appareil`/`dispositivo`), the fly-in/out animation, Back closing the overlay, and the interval grid
fitting at 360×800 and 320×640.

## 4. 64-bit / `arm64-v8a`

**Done, and now verified on hardware.** B2 already picks Plezy's tarball per device from
`Build.SUPPORTED_ABIS` ([08](08-app-implementation.md#which-build-for-this-device-b2)), and the
**updater's own APK carries no native code** — it is pure Java + resources, so it is ABI-independent
and already "armv8a compatible". There is nothing to port.

**The signer gap is closed** (2026-11-16). Both 64-bit tarballs were checked off-device, on the PC:

```bash
curl -LO https://github.com/edde746/plezy/releases/download/2.26.0/plezy-android-arm64-v8a.tar.gz
tar xzf plezy-android-arm64-v8a.tar.gz           # → plezy.apk
apksigner verify --print-certs plezy.apk | grep SHA-256
# → 903b862cf5e3a0bfbad9b5e049ec3de703f83422bba9c5559a7b019716316e72  (= PINNED_SIGNER_SHA256)
```

`arm64-v8a` and `x86_64` both extract to an APK signed with the pinned certificate
(`CN=Edvard Wikhall, O=edde746`) and carrying `lib/arm64-v8a` / `lib/x86_64`, so the asset name and
the signer assumption are correct for the 64-bit branch too. **Both 64-bit installs are now verified
end to end**: `x86_64` on the `phone34` emulator (abilist `x86_64,arm64-v8a` →
`plezy-android-x86_64.tar.gz`), and **`arm64-v8a` on the real `phone35`** (abilist `arm64-v8a,…` →
`plezy-android-arm64-v8a.tar.gz`; afterwards `dumpsys package com.edde746.plezy` reports
`primaryCpuAbi=arm64-v8a`). Nothing on this item is left unverified except the `err_no_native_libs`
branch, which stays logic-only.

## 5. `targetSdk` bump matrix

The app targets **34** today (25 through `0.4.0`, 26 in `0.5.0`, 31 in `0.6.0`, 33 in `0.7.0`, 34 in
`0.8.0`); raising it is only
worth doing if the updater must also run well on newer Android (which items 2–3 imply). Each step is a
known set of changes; all of them can be guarded by `Build.VERSION.SDK_INT` so the API 25 behaviour is
unchanged.

| `targetSdk` | Platform | What breaks / is required |
|---|---|---|
| 26 ✅ | Android 8.0 | **Done in `0.5.0`.** **Notification channels are mandatory** — `Notifier.ensureChannel` creates the `"updates"` channel and posts through `new Notification.Builder(app, CHANNEL_ID)` when `SDK_INT >= O`; on API 25 it keeps the legacy builder. **Background execution limits**: the `IntentService` started from an alarm (`UpdateCheckService` / `UpdateAlarm` / `CheckReceiver`) was replaced by a `JobScheduler` job (`UpdateJob` + `CheckJobService`, periodic id 2001, one-off id 2002) — no `WorkManager`, no AndroidX, and it exists on API 21 so there is no second code path. |
| 27 | 8.1 | Minor; no change expected. |
| 28 | 9.0 | **Cleartext HTTP is blocked** by default — all endpoints are already HTTPS, so fine. `Build.SERIAL` is restricted (unused). |
| 29 | 10.0 | **Scoped storage** — the app uses `cacheDir` and `getExternalCacheDir()`, both app-specific, so fine; no legacy flag needed. **Background activity starts** are restricted: the install-confirmation activity is started from the app while it is in the foreground (the user just pressed a button), so allowed. `InstallResultReceiver` starts it too, but only for `STATUS_PENDING_USER_ACTION` — which the platform stops sending once it shows the dialog itself (API 26+), so it can never be a background start on a platform that enforces the rule. A future auto-install path would not be allowed. |
| 30 | 11.0 | **Package visibility** — `<queries>` for `com.edde746.plezy` is already declared. `ACTION_DELETE` still launches the stock uninstaller; installing still needs `REQUEST_INSTALL_PACKAGES` (declared). |
| 31 ✅ | 12.0 | **Done in `0.6.0`.** **`PendingIntent` mutability** — `ApkInstaller`'s session-result `PendingIntent` is `FLAG_UPDATE_CURRENT \| FLAG_MUTABLE` (the platform writes the session status into it, and on API 25 the confirmation intent too); `Notifier`'s notification `PendingIntent` is `FLAG_IMMUTABLE` (it only opens `MainActivity`). Both add the flag only when `SDK_INT >= S`, so the API 25–30 flags stay `0`; `FLAG_MUTABLE`/`FLAG_IMMUTABLE` are compile-time constants, so naming them is safe and dex inlines them. **Notification trampolines** — the notification's `contentIntent` is already an activity, started directly, not a receiver that starts one: verified on `tv30`, nothing to change. **`REQUEST_DELETE_PACKAGES`** — declared; the platform's package-visibility rules make it necessary to send `ACTION_DELETE` on newer Android (declared, but *not* empirically tested by removing it). **Also fixed here** — the downgrade handshake raced the platform ([08](08-app-implementation.md#finding-5--the-uninstall-handshake-raced-the-platform-on-android-12)). Verified end to end on the `phone34` emulator (API 34) **and on the real `phone35` (API 35)**. |
| 33 ✅ | 13.0 | **Done in `0.7.0`.** **`POST_NOTIFICATIONS` is a runtime permission** — a `targetSdk 33` app's notifications are dropped until it is granted. `Notifier` gained `systemAllows` / `needsPermission` / `requestPermission` (all guarded by `SDK_INT >= TIRAMISU`, so the API 25 stick is untouched), `MainActivity` asks once on the first resume while the *Notifications* switch is on (persisted `notif_asked`), and the Settings row asks again on toggle-on and shows a **Blocked by Android…** hint plus an **Open notification settings** action while the notification is blocked. The diagnostics report gained `notif=allowed\|blocked`, asserted by the harness. Verified on `phone34` (API 34) and the real `phone35` (API 35) — request dialog, grant, and a posted notification — and the API 25 flows are unchanged ([08](08-app-implementation.md#targetsdk-33--post_notifications-070-2026-11-18)). |
| 34 ✅ | 14.0 | **Done in `0.8.0`.** **Runtime-registered receivers must declare an export flag** — a target-34 app that calls `Context.registerReceiver` without one is refused. All four sites (the custom `ACTION_INSTALL_FINISHED` receivers in `MainActivity` / `VersionListActivity`, the `ACTION_PACKAGE_REMOVED` ones in `SettingsActivity` / `InstallFlow`) now go through `Receivers.register`, which passes `Context.RECEIVER_NOT_EXPORTED` when `SDK_INT >= TIRAMISU` (the flag and the three-argument overload are API 33+) and the plain two-argument call below it, so the API 25 stick is unchanged. **`ACCESS_NETWORK_STATE` is required for a job with a network constraint** — this one is a *hard crash on launch* on API 34+: `UpdateJob.schedule` throws `SecurityException: android.permission.ACCESS_NETWORK_STATE required for jobs with a connectivity constraint`; both jobs require network, so the permission is now declared (inert on API 25). **Foreground-service types / implicit internal intents**: no foreground service and no implicit internal intents, so nothing else to change. Verified on `phone34` (API 34) and `phone35` (API 35) — the crash reproduced first, then the fix — with the API 25 flows re-run ([08](08-app-implementation.md#targetsdk-34-receiver-flags--access_network_state-080-2026-11-18)). |
| 35 | 15.0 | Nothing new beyond 33/34 seen on the `phone35` run (LineageOS 22.1): the seven flows pass on it, which is the point — the newest platform here is exercised end to end. |

The order of the real work inside a bump is: notification channel (26) → job scheduling (26) →
`PendingIntent` flags (31) → notification permission (33) → receiver export flags and the job
network permission (34). Each is hand-written against the platform
API and each keeps an API 25 fallback.

**Verify.** A bump must not change behaviour on the stick: re-run the eight flows and the quota
broadcast on API 25 after every step, and additionally on a newer emulator (or the real phone) for the
branch that actually changed. For the 26 step the API 25 side was done in full (jobs armed / cancelled
/ re-armed, the one-off `runNow` path, the legacy notification, all five flows and the de/fr/es
smokes); the API 26+ side — channel creation and the background-execution limit the job is there to
avoid — had no device to run on and stayed logic-verified. **The emulator lane now closes that gap**
([`test/README.md`](../test/README.md#emulator-lane)): `tv30` reaches the channel and
background-limit branches, `phone34` (Android 14) enforces the `targetSdk` 31 rules and runs the
whole Plezy flow, `tv25` repeats the API 25 platform without the stick, and `phone35` runs the whole
flow on a **real** API 35 arm64 phone. The 31 step was verified on `phone34` and `phone35` end to
end, and the 26 step's API 26+ branches are now exercised on `tv30`. The 33 step was verified the same
way, with the `POST_NOTIFICATIONS` request dialog driven by hand on both API 33+ devices (the harness
grants the permission up front so a flow is not at the mercy of the one-time dialog). The 34 step was
verified on the same two lanes, where the lane earned its keep: the `ACCESS_NETWORK_STATE` job
requirement reproduced as a **crash on launch** on API 34+ before the fix, and the runtime-receiver
export flag is exercised by every flow (both activities register a receiver on start).

## Suggested order

1. ~~**arm64 signer check** (item 4)~~ — **done** 2026-11-16; both 64-bit tarballs match the pinned
   signer. Only the on-device 64-bit install remains, and it needs hardware.
2. ~~**Self-update** (item 1)~~ — **done** 2026-11-17, `0.3.0`; verified in place on the stick. It
   makes every later change shippable without `adb`.
3. ~~**Device detection + notification wording** (items 2–3, wording half)~~ — **done** 2026-11-17,
   `0.4.0`; the classification is asserted over the diagnostics broadcast and the wording rides the
   `-finger` qualifier, so no Java decides it. The branches this stick cannot produce are covered by
   `test/jvm/run.sh`.
4. ~~**`targetSdk` bump, step 1 (25 → 26)**~~ — **done** 2026-11-17, `0.5.0`; notification channel +
   `JobScheduler` in place of the alarm + `IntentService`, with the API 25 path re-verified on the
   stick. The API 26+ branches were logic-verified at the time and are device-verified on `tv30`
   now that the lane exists.
5. ~~**Emulator lane + `targetSdk` bump to 31**~~ — **done** 2026-11-18, `0.6.0`; the SDK and three
   AVDs (`tv25`, `tv30`, `phone34`), `test/emulator.sh` and the device profiles, then the 26 → 31
   jump, plus the real-phone lane (`phone35`, API 35 arm64). The lane paid for itself at once: it
   caught the Android 12+ downgrade race (twice — the second time on the real phone) and the
   all-caps dialog buttons the harness had assumed away.
6. ~~**`targetSdk` bump, step 33**~~ — **done** 2026-11-18, `0.7.0`; `POST_NOTIFICATIONS` is requested
   (auto once + on toggle), the Settings row warns when Android blocks the notification, and the
   diagnostics report carries `notif=`. Verified on `phone34` (API 34) and `phone35` (API 35), with
   the API 25 flows re-run on the stick.
7. ~~**`targetSdk` bump, step 34**~~ — **done** 2026-11-18, `0.8.0`; the runtime-receiver export flag
   (`Receivers.register` → `RECEIVER_NOT_EXPORTED` from API 33) and the `ACCESS_NETWORK_STATE` job
   permission that API 34 turns into a hard crash on launch. Verified on `phone34` (API 34) and
   `phone35` (API 35) — the crash reproduced first, then the fix — with the API 25 flows re-run on the
   stick. Each step keeps the API 25 flows as the regression test and the lane for the new branch.
8. ~~**Touch UI** (item 3, layout half)~~ — **done** 2026-11-18, `0.9.0`: the single-pane touch
   Choose-Version with the right-side changelog fly-in, plus the `-finger` behaviour wording ("device"
   not "TV"). Verified with a new `picker-notes` harness flow on the stick, `phone34` and the real
   `phone35`, on `tv30` (touch picker on an Android TV), and by hand for the wording and the overlay.
9. ~~**Touch check-interval picker + screen audit**~~ — **done** 2026-11-18/19, `0.9.1`: the
   two-pane D-pad interval picker becomes a tapped number grid + unit chips on touch, and an audit
   confirmed it was the last D-pad-only screen. Verified with a new non-destructive `interval` flow
   on the stick (D-pad) and `phone34` (touch) — which caught the touch double-tap bug
   ([Finding 8](08-app-implementation.md#finding-8--the-touch-interval-screen-swallowed-the-first-tap)).
10. ~~**Main View footer polish + the touch picker's notes icon** (item 4)~~ — **done** 2026-11-19,
    `0.10.0`: the footer's two lines are each their own line, the long check sentence is shortened to
    `⏳ Update check: <n> <m>` (the "one confirmation on the installer" caveat moved into the Settings
    auto-check summary), the free-space line gains a `⛃` data glyph, and the touch picker's *Notes*
    text button becomes the **What's new** icon. Both glyphs were confirmed to render (not tofu) on the
    API 25 stick; the flows pass 7/7 on the stick and `phone34`. The README screenshots landed with it
    (generated by `test/screenshots.py`). A follow-up patch, **`0.10.1`**, closed the last layout gap:
    driving the touch screens in landscape found two that did not scroll and therefore clipped —
    the interval picker (number grid, Save/Cancel) and the language picker (last row, Back). Both now
    use one outer `ScrollView`, and a new `landscape` flow verifies them on `phone35` and `phone34`
    ([Finding 9](08-app-implementation.md#finding-9--short-landscape-screens-clipped-two-touch-pickers)).
    Next is the README's non-technical rewrite, and last…
11. **`targetSdk` bump, step 35** — deliberately after the touch UI and the footer work: API 35
    enforces edge-to-edge (window insets; `setStatusBarColor` deprecated), and the changelog fly-in's
    insets depend on the touch layouts existing first.

## Guardrails

- **API 25 keeps working.** Every newer API is behind a `VERSION.SDK_INT` check; the flows are the
  regression test.
- **One build, one key.** The APK stays ABI-free, and everything is signed with the same key, so any
  build can replace any other ([Build and install](08-app-implementation.md#build-and-install)).
- **Say what was not verified.** All three installs — `armeabi-v7a`, `x86_64`, `arm64-v8a` — are now
  verified end to end (stick, `phone34` emulator, real `phone35` phone); only the
  `err_no_native_libs` path stays logic-only. The Android TV and touch branches of device detection,
  the `-finger` wording, the touch pickers, and the API 26+ channel / background-limit branches are
  **device**-verified on the emulator lane and (touch, API 35) on the real phone. The touch layouts
  are also **device-verified in landscape** — the real phone (1600×720) and the `phone34` emulator at
  its native 640×320 — through `run.py landscape`; that check found, and `0.10.1` fixed, two clips:
  the interval picker's number grid/Save/Cancel and the language picker's last row/Back. What still
  has no device here: a real Android TV (only emulators) and Fire OS's own quirks beyond the stick.
- **Testable decisions live outside `android.*`.** `DeviceKind` takes a plain `Signals` bundle and has
  no platform import, which is what lets `test/jvm/run.sh` cover the branches this stick cannot
  produce. Keep new decision logic on that side of the line.
- **One signal, one place.** The touch wording and `Device.isTouch()` both read
  `Configuration.touchscreen`; do not let a second, "close enough" signal (like the
  `android.hardware.touchscreen` feature flag) creep in beside it.
