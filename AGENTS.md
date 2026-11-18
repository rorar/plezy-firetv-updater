# Notes for agents working in this repo

## Device

- Fire TV Stick 4K (**AFTMM / mantis**), Fire OS `6.7.1.1` = Android `7.1.2` = **API 25**,
  `armeabi-v7a`, 2 GB RAM, no root, `ro.build.type=user`.
- ADB: the stick's address is **not** in this repo. It lives in the gitignored `local.env`
  (`FIRE_TV_SERIAL`); `source local.env` then `adb connect "$FIRE_TV_SERIAL"` (accept the RSA
  dialog once). The `192.168.1.50:5555` in the docs is a placeholder. Screen `1920x1080`,
  density `320` (2.0× → 960x540 dp).
- A real **phone** is also on the lane (`phone35`, see [below](#the-emulator-lane-testemulatorsh-and-the-real-phone-phone35)):
  its address is `PHONE_SERIAL` in the same gitignored `local.env`.
- **The TV sleeps.** Before any `screencap`/`uiautomator dump`, wake it:
  `adb shell input keyevent KEYCODE_WAKEUP` then `KEYCODE_HOME`. A sleeping screen gives an
  all-black screenshot and `uiautomator dump` fails with
  `ERROR: null root node returned by UiTestAutomationBridge`.
- The updater is **not debuggable** (no `run-as`, `Log.d` unreliable). Verify through
  `uiautomator dump`, `dumpsys`, and `screencap` pixel/ASCII analysis — the harness cannot see
  images. `uiautomator dump` omits `text=` values containing `"` and off-screen nodes.
- When a dump fails, delete the remote file first — a stale `/sdcard/*.xml` gets pulled otherwise.

## Build

```bash
cd app
BT=/tmp/opencode/sdk/android-15 AJ=/tmp/opencode/sdk/android-35/android.jar ./build.sh
adb install -r build/plezy-updater.apk
```

`build.sh` can exit `141` (SIGPIPE) even on success — check that the APK was produced. Keep
`app/debug.keystore` (gitignored); without it the installed updater can no longer be replaced.

## Driving the app over ADB

- `VersionListActivity` is **not exported**, so `am start` it fails with
  `SecurityException: not exported`. Navigate from `MainActivity`: focus starts on
  **Check for updates**; `KEYCODE_DPAD_DOWN` once reaches **Choose version…**; `KEYCODE_DPAD_CENTER`
  opens it.
- The main screen's button order is **Install update**, **What's new**, **Check for updates**,
  **Choose version…**, **Settings**.
- The app is **runtime-switchable** (Settings → Language: System default, English, Deutsch,
  Français, Español). The language persists in `Prefs` and survives `adb install -r`, so the app can
  come up in a language you did not build for. Read the current language off the main screen (the
  Settings button's wording) instead of assuming English.
- **Picker rows do respond to `input tap`.** The version rows are `focusableInTouchMode`, and an
  earlier note here claimed a tap only moved focus; in practice tapping a row's centre installs it
  (the `install` / `downgrade` / `upgrade` flows do exactly that, every run). `KEYCODE_DPAD_DOWN/UP`
  + `KEYCODE_DPAD_CENTER` still works and is the fallback if a tap lands off-target. Buttons
  (Settings rows, main-screen buttons, dialog buttons) respond to taps too.
- **The picker has two layouts.** `VersionListActivity` branches on `Device.isTouch(this)`: a **TV**
  (`touch=0`) gets the two-pane versions|notes picker driven by the D-pad (OK installs); a **touch**
  device gets a single pane where each row is a version label (tap installs) plus a **Notes** button
  that flies the release notes in from the right. The overlay closes with its `✕` button or Back, and
  its **Install this version** button installs. So on a phone the labels to drive are `picker.select`
  is *absent*, and `Notes` / `Install this version` / `✕` are present; on the TV the opposite. The
  harness's `picker-notes` flow covers both.
- **The interval picker has two layouts too** (`IntervalActivity`, `0.9.1`). A **TV** gets the
  two-pane `VALUE`|`UNIT` D-pad picker (focus selects; LEFT/RIGHT moves between panes; **Save** at
  the bottom; Back cancels). A **touch** device gets a **six-column grid of number chips** (1–30)
  plus a 2×2 grid of unit chips — tap a number and a unit, watch the live "Every N …" preview, then
  tap **Save** / **Cancel**. Both live behind **Settings → Edit check interval…** (which only exists
  while *Check automatically* is on). Two platform traps the touch branch handles: a window with no
  focused view **swallows the first tap**, and a `focusableInTouchMode` row spends the first tap
  giving itself focus — so the touch chips are clickable but not focusable and the root takes focus
  in touch mode up front ([docs/08](docs/08-app-implementation.md#finding-8--the-touch-interval-screen-swallowed-the-first-tap)).
  The harness's `interval` flow drives both branches and is non-destructive.
- **The install dialog can be slow to appear.** The download runs before the installer window, so on
  a slow link (or a throttled emulator) it can take minutes. The wait is `PLEZY_INSTALL_TIMEOUT`
  seconds (default 180); raise it rather than diagnosing a network wait as an app bug.
- The GitHub API allows **60 unauthenticated requests/hour**. The app now avoids the metered API:
  the latest check uses the unmetered `github.com/.../releases/latest` redirect + `releases.atom`,
  the picker's API call is conditional (`If-None-Match`; `304`s are free), and a spent quota falls
  back to the web feed and the last known version. So a flow run should **not** hit 403 any more.
  If you do see *"GitHub API returned HTTP 403"* (or the calm rate-limit wording), check
  `curl -s https://api.github.com/rate_limit` and wait for the reset — it is the quota, not the
  harness.
- **The updater must work with the API quota spent.** Verify it by reading `rate_limit` first and
  running the flows while `remaining` is `0`; that is the point of the web-feed fallback
  ([docs/08](docs/08-app-implementation.md#staying-under-githubs-rate-limit)).
- **Self-update is a Settings row, and its tap is unreliable.** **Settings → About → "Update Plezy
  Updater…"** checks this repo's own releases and installs a newer build in place. Drive it with the
  **D-pad** (`KEYCODE_DPAD_DOWN` to the row, then `KEYCODE_DPAD_CENTER`): `input tap` on that row
  often does not register even though it works on the rows above and below. While it runs the row
  disables and its summary line shows `Downloading… n%` → `Opening the installer…` → the stock
  installer appears (an *update* dialog for **Plezy Updater**). Confirm it like any install
  (`INSTALLIEREN` @ `1115 984`), then prove the in-place replace:
  `dumpsys package dev.rorar.plezyupdater` must show the new `versionCode` with `firstInstallTime`
  unchanged and `lastUpdateTime` moved. The swap kills the app, so the launcher may come up — just
  `am start` it again. To test end to end you need a **published release newer than the running
  build**; the check is `releases/latest` vs the installed `versionName`.

## Testing an install: click the installer button yourself

When you trigger an install to verify it, **do not leave the system installer dialog hanging —
confirm it yourself** and then check the result. A half-finished test (dialog left open, progress
line stuck on "Confirm the install on the TV.") proves nothing about the outcome.

The stock dialog is `com.android.packageinstaller/.PackageInstallerActivity`. On this German-locale
stick its buttons are:

- **`INSTALLIEREN`** — confirm (this is the one to click).
- **`ABBRECHEN`** — cancel (holds focus by default).

Find the button's bounds and click it (works reliably; `input tap` on the button's centre):

```bash
adb shell dumpsys window windows | grep mCurrentFocus            # wait for packageinstaller
adb shell uiautomator dump /sdcard/inst.xml && adb pull /sdcard/inst.xml /tmp/opencode/inst.xml
# read the INSTALLIEREN node's bounds, then tap its centre, e.g.:
adb shell input tap 1115 984
```

(Alternatively move the focus with `KEYCODE_DPAD_RIGHT` off `ABBRECHEN` and press
`KEYCODE_DPAD_CENTER`.) After the click, confirm the outcome: `dumpsys package com.edde746.plezy`
should show a fresh `lastUpdateTime`, and the updater's own screen should report the new version.

### Gotchas seen while driving the flow

- **The first D-pad key event after the installer/uninstaller window closes is swallowed** (a
  window-focus timing artefact). Press it again — the picker list otherwise ignores the first
  UP/DOWN, which silently re-installs the same version.
- **The "Open Plezy?" dialog appears after every successful install** (on by default). While it is
  up it is the **only window in a `uiautomator dump`**, so the screen underneath is invisible until
  it is dismissed — dismiss it (tap **Not now**) before reading the progress line or the picker.
  The harness does this in `Flows._dismiss_open_prompt`.
- The dialog buttons and their centres on this stick (German locale):
  - installer `INSTALLIEREN` → `[998,936][1232,1032]` → tap `1115 984`;
  - uninstaller `OK` → `[536,701][956,781]` → tap `746 741`;
  - app "Downgrade to …?" → `Uninstall & install` → tap `746 718`;
  - app "Uninstall Plezy?" → `Uninstall` → tap `746 694`;
  - app "Open Plezy?" → `Not now` → tap `1174 646`.
  Re-read the bounds with `uiautomator dump` before tapping; they shift with the dialog height.

## Driving the whole install / uninstall / downgrade flow

The user has **authorised the agent to drive the entire flow by itself** while verifying — no need
to ask before each click. That includes:

- clicking the app's own dialogs, e.g. **Uninstall & install** on the "Downgrade to …?" confirm;
- confirming the stock uninstaller (`ACTION_DELETE` → `UninstallerActivity`);
- clicking **`INSTALLIEREN`** on the stock installer (see above).

The buttons are German-locale; the app's own dialogs are English. Find the target node's `bounds`
with `uiautomator dump` and `adb shell input tap <cx> <cy>` its centre (or D-pad to it and press
`KEYCODE_DPAD_CENTER`).

**Still destructive:** an uninstall and a downgrade (uninstall-then-install) **erase Plezy's data**
(servers, settings, downloads). That is expected and allowed for testing, but say so in the
result — and when the test is done, leave Plezy back on the latest release rather than on the
downgraded build.

## The flow harness does all of this for you

Prefer the harness over hand-driving when you can:

```bash
cd ~/Code/plezy-firetv-updater
source local.env                                   # sets FIRE_TV_SERIAL
python3 -u test/run.py all --yes                   # seven flows
python3 -u test/run.py upgrade --yes
python3 -u test/run.py install --app-lang de
```

`--serial` defaults to the profile's `serial_env` variable, then `$FIRE_TV_SERIAL`; pass
`--serial <ip>:5555` to override.

Run it from `test/` if you want to import `harness` / `flows` in an ad-hoc snippet. It drives
Settings → Language, dismisses the "Open Plezy?" prompt, clicks `INSTALLIEREN`, and proves the
upgrade was an in-place replace (`firstInstallTime` unchanged, `lastUpdateTime` moved). Every run
also fires the quota diagnostics broadcast and **asserts** the `device=…;tv=…;touch=…` fields it now
carries against `test/config.json`'s `expected_device` (`fire_tv` / `1` / `0` on this stick) and the
`notif=` field against `expected_notif`; a missing report fails the run. On API 33+ the run grants
`POST_NOTIFICATIONS` up front so the one-time dialog never lands in the middle of a flow. Details in
[`test/README.md`](test/README.md).

Two things it does before/inside a run that are easy to get wrong by hand:

- **It clears a stray system window first.** If a previous run left the stock installer/uninstaller
  in front, `open_updater()` BACK-cancels it and fails loudly if it will not go away. Leftover
  dialogs otherwise make a run drive the wrong window and look like an app bug.
- **`clean-install` presses "Open" and asserts Plezy launches** (`assert_open_prompt_opens_plezy`),
  not just "Not now". The other flows only dismiss the prompt, so the positive button is covered
  here.

A downgrade on **Android 12+** races the platform: `onResume` can run *before* the
`ACTION_PACKAGE_REMOVED` broadcast, so a successful uninstall still reads as installed for ~137 ms.
`InstallFlow` handles it with a bounded re-check (750 ms × 5) and finishes on the broadcast — do not
"fix" a phone-only downgrade failure by trusting a version read (see
[`docs/08`](docs/08-app-implementation.md#finding-5--the-uninstall-handshake-raced-the-platform-on-android-12)).

Two checks need **no device** and are worth running after any string or device-logic change:

```bash
python3 test/check_strings.py    # locale/-finger/placeholder parity (also a run.py pre-flight)
test/jvm/run.sh                  # DeviceKind tests on the plain JVM (25 cases)
```

`DeviceKind` has no `android.*` import on purpose, so `test/jvm/run.sh` can cover the Android TV and
touch branches this stick cannot produce. Keep it that way: decision logic that touches no platform
API belongs in `DeviceKind`, not `Device`.

To trigger a quota check by hand:

```bash
adb shell am broadcast --include-stopped-packages -a dev.rorar.plezyupdater.action.DIAGNOSTICS
# → Broadcast completed: result=-1, data="status=ok;remaining=…;limit=60;reset_ms=…;checked_ms=…;device=fire_tv;tv=1;touch=0;notif=allowed"
```

## Verifying the background check (`JobScheduler`, since 0.5.0)

The periodic check is a `JobScheduler` job, not an alarm, so `dumpsys alarm` no longer shows it.

```bash
adb shell dumpsys jobscheduler | grep -A16 "JOB #.*plezyupdater"   # job 2001 = periodic, 2002 = one-off
adb shell cmd jobscheduler run -f dev.rorar.plezyupdater 2001       # force a run
adb shell logcat -d -s PlezyUpdater                                 # "background check starting (manual=…)"
```

- **The job runs almost immediately when (re)scheduled** on this build, then every interval. So a
  `periodic check armed every … ms` line in logcat is quickly followed by a check.
- **`am kill` and `am kill-all` cannot evict the updater** — it keeps a non-cached adj while it owns
  a task. To exercise the "no screen is up" path (`InstallResultReceiver` → `UpdateJob.runNow(c,
  true)`), start an install from the picker, wait for the stock installer, then
  `am force-stop dev.rorar.plezyupdater`. The session-result broadcast **is** still delivered to a
  fresh process (verified), which then logs `background check starting (manual=true)`.
- With `Check automatically` off, job 2001 is gone from `dumpsys jobscheduler`; the one-off 2002
  still runs when armed with `manual=true`.
- The updater targets **API 34** now. The channel path (`Notifier.ensureChannel`), the
  background-execution-limit behaviour, the `POST_NOTIFICATIONS` request and the API 34
  runtime-receiver export flag (`Receivers.register`, `RECEIVER_NOT_EXPORTED`) are **not** reachable
  on this API 25 stick — use the emulator lane (`tv30` / `phone34`, below) or the real `phone35` for
  those branches. A target-34 app that registers a receiver without an export flag is refused at
  `registerReceiver`, so a `SecurityException`/`IllegalArgumentException` in logcat is the symptom to
  watch for on API 34+.

## The emulator lane (`test/emulator.sh`) and the real phone (`phone35`)

A second platform to test the branches the API 25 stick cannot produce. `test/emulator.sh
create|start|provision|stop|list <profile>` boots an AVD headless and `run.py --profile <name>
--serial emulator-5554` runs the flows on it. Profiles (`test/config.json`): `tv25` / `tv30` are
x86 Android TV images (updater-side checks only — Plezy has no 32-bit x86 build, so the full flows
cannot run there), `phone34` is x86_64 API 34 and runs the whole Plezy flow **and** enforces the
`targetSdk` 31 rules. Only one emulator runs at a time — `start` refuses if one is already up — so
the lanes are sequential. Needs `$ANDROID_HOME` (default `~/Android/Sdk`). See
[`test/README.md`](test/README.md#emulator-lane).

`phone35` is **not** an emulator: a real arm64 phone (LineageOS 22.1 = Android 15 = API 35,
touchscreen, `de-DE`) over wireless adb. It is the only arm64 device here (so it is the only one that
exercises Plezy's `arm64-v8a` install) and the only real touch device. Its address lives only in the
gitignored `local.env` as `PHONE_SERIAL`; the profile names that variable (`serial_env`), so:

```bash
cd ~/Code/plezy-firetv-updater
source local.env                        # sets FIRE_TV_SERIAL and PHONE_SERIAL
python3 -u test/run.py all --yes --profile phone35    # no --serial needed
```

Its stock installer is the AOSP one on Android 15, in German: **`Installieren`** (fresh) /
**`Aktualisieren`** (update) / **`Trotzdem aktualisieren`** (update from a different installer, a
*second* dialog) / **`Abbrechen`** — recorded in `strings/system.de.api34.json`, which the profile
points at with `system_strings`. Wireless-debugging adbd **changes its port** whenever it restarts
(`adb root` / `unroot`, reboot); re-discover it with `adb mdns services` (`_adb-tls-connect._tcp`),
or just run `test/adb-keepalive.sh --daemon`, which watches the device and reconnects by itself
(`--once` prints the working address; the address stays in the gitignored `local.env`). A lost
*pairing* is different — pair once with `adb pair <ip>:<pair-port> <code>` from the on-screen code.
