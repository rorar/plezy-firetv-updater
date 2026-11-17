# 11 — Roadmap

Where the updater goes after Round 1 (the A1–A7 + B2 set, updater `0.2.0`; self-update in `0.3.0`;
device detection and device-aware wording in `0.4.0`). Each item says what it
is, what it would take, what can go wrong, and **how it would be verified on the stick** — including
the honest cases where this hardware cannot verify it. Order is a suggestion, not a commitment.

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

The Android TV and phone branches cannot be produced on this stick, so they are covered by
`test/jvm/run.sh` — 25 cases against `DeviceKind` on the plain JVM, including the precedence between
the branches and null safety. They stay *logic*-verified, not hardware-verified (item 3).

## 3. Android TV / phone compatibility

**Wording: done** (2026-11-17, `0.4.0`). **Layout: not started** — and the layout is the one thing
that cannot be verified on this stick.

### Device-aware wording — done

The app used to ship one TV-flavoured sentence. It now picks it by device **without a line of Java**,
using Android's own touchscreen resource qualifier: `values-finger/` (and `values-de|fr|es-finger/`)
override three strings — `notif_available_text`, `set_test_notification_msg`, `picker_hint` — with
"tap" instead of "press OK". Because the qualifier *is* `Configuration.touchscreen ==
TOUCHSCREEN_FINGER`, the same field `Device.isTouch()` reads (item 2), the wording and the detection
cannot disagree. Two strings that named the device where the generic word reads fine everywhere were
reworded instead of duplicated four ways (`set_notification_desc` "Fire TV notification center" →
"notification area"; `err_not_enough_space` "on the Fire TV" → "on the device"; `set_delete_desc`
"save space on the stick" → "save space"). Strings that describe *behaviour* — "runs while the TV is
on", "after the TV boots" — stay TV-first, because the layout still is. See
[08 — Device-aware wording](08-app-implementation.md#device-aware-wording).

The sketch this section first carried invented three notification rows; there is really only **one**
notification (the update-available one), and its text said *install* although it **opens** the
updater:

| | TV (`values/`) | Touch (`values-finger/`) |
|---|---|---|
| Update available | `Plezy 2.27.0 available` · `Press OK to open` | `Plezy 2.27.0 available` · `Tap to open` |
| Test notification | `Test notification — press OK to open` | `Test notification — tap to open` |
| Picker hint | `…press OK to install the highlighted version.` | `…tap to install the highlighted version.` |

`test/check_strings.py` keeps the four `-finger` files honest: they may only *override* keys that
exist in the matching base file, placeholders must survive translation, and no locale may fall behind
`values/`. It runs as the pre-flight step of `test/run.py`, so a wording drift fails a run.

### Layout — not started

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

**Verify.** No Android TV or phone is available, so the layout half **cannot be verified on this
hardware** — and the wording half, although statically checked and tied to the JVM-tested
`Device.isTouch()`, has never been seen on a real touch device either. Options, in order of cost: an
API 25 TV emulator (verifies the leanback branch and the touch absence), then any real Android TV /
phone (verifies the touch layout). Until then it stays a documented, unverified branch — the same
honesty the B2 note uses.

## 4. 64-bit / `arm64-v8a`

**Done, except the on-device install.** B2 already picks Plezy's tarball per device from
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
the signer assumption are correct for the 64-bit branch too. **Still open:** the actual *install* on
a 64-bit device — this stick is 32-bit, so only real arm64 hardware (or an arm64 emulator) can
exercise it end to end. That is a "when a device appears" item, not work to do now.

## 5. `targetSdk` bump matrix

The app targets **26** today (it was 25 through `0.4.0`); raising it is only worth doing if the
updater must also run well on newer Android (which items 2–3 imply). Each step is a known set of
changes; all of them can be guarded by `Build.VERSION.SDK_INT` so the API 25 behaviour is unchanged.

| `targetSdk` | Platform | What breaks / is required |
|---|---|---|
| 26 ✅ | Android 8.0 | **Done in `0.5.0`.** **Notification channels are mandatory** — `Notifier.ensureChannel` creates the `"updates"` channel and posts through `new Notification.Builder(app, CHANNEL_ID)` when `SDK_INT >= O`; on API 25 it keeps the legacy builder. **Background execution limits**: the `IntentService` started from an alarm (`UpdateCheckService` / `UpdateAlarm` / `CheckReceiver`) was replaced by a `JobScheduler` job (`UpdateJob` + `CheckJobService`, periodic id 2001, one-off id 2002) — no `WorkManager`, no AndroidX, and it exists on API 21 so there is no second code path. |
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
actually changed. For the 26 step the API 25 side was done in full (jobs armed / cancelled /
re-armed, the one-off `runNow` path, the legacy notification, all five flows and the de/fr/es
smokes); the API 26+ side — channel creation and the background-execution limit the job is there to
avoid — had **no device or emulator to run on** and stays logic-verified
([08](08-app-implementation.md#background-checks-jobscheduler-targetsdk-26)).

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
   stick and the API 26+ branches logic-verified only (no newer device exists here).
5. **`targetSdk` bump, further steps** (28 → 31 → 33 → …) — item 5 continued, one step per release,
   each with the API 25 flows as the regression test.
6. **Touch UI** (item 3, layout half) — last, because it is the one that cannot be verified here.

## Guardrails

- **API 25 keeps working.** Every newer API is behind a `VERSION.SDK_INT` check; the flows are the
  regression test.
- **One build, one key.** The APK stays ABI-free, and everything is signed with the same key, so any
  build can replace any other ([Build and install](08-app-implementation.md#build-and-install)).
- **Say what was not verified.** The arm64/x86_64 *signers* are now checked off-device, but their
  *installs* and the `err_no_native_libs` path stay unverified on this 32-bit stick; the Android TV
  and touch branches of device detection and the `-finger` wording are **logic**-verified (JVM tests,
  string-parity check) but never seen on a real Android TV or phone; the API 26+ notification-channel
  and background-execution-limit branches are **logic**-verified too (no newer device here); and the
  touch *layout* is not done at all. They join the list until a second device exists.
- **Testable decisions live outside `android.*`.** `DeviceKind` takes a plain `Signals` bundle and has
  no platform import, which is what lets `test/jvm/run.sh` cover the branches this stick cannot
  produce. Keep new decision logic on that side of the line.
- **One signal, one place.** The touch wording and `Device.isTouch()` both read
  `Configuration.touchscreen`; do not let a second, "close enough" signal (like the
  `android.hardware.touchscreen` feature flag) creep in beside it.
