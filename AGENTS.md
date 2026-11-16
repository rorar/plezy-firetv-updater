# Notes for agents working in this repo

## Device

- Fire TV Stick 4K (**AFTMM / mantis**), Fire OS `6.7.1.1` = Android `7.1.2` = **API 25**,
  `armeabi-v7a`, 2 GB RAM, no root, `ro.build.type=user`.
- ADB: `adb connect 192.168.1.50:5555` (accept the RSA dialog once). Screen `1920x1080`,
  density `320` (2.0× → 960x540 dp).
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
python3 -u test/run.py all --yes --serial 192.168.1.50:5555   # five flows
python3 -u test/run.py upgrade --yes --serial 192.168.1.50:5555
python3 -u test/run.py install --app-lang de --serial 192.168.1.50:5555
```

Run it from `test/` if you want to import `harness` / `flows` in an ad-hoc snippet. It drives
Settings → Language, dismisses the "Open Plezy?" prompt, clicks `INSTALLIEREN`, and proves the
upgrade was an in-place replace (`firstInstallTime` unchanged, `lastUpdateTime` moved). Every run
also fires the quota diagnostics broadcast. Details in [`test/README.md`](test/README.md).

To trigger a quota check by hand:

```bash
adb shell am broadcast --include-stopped-packages -a dev.rorar.plezyupdater.action.DIAGNOSTICS
# → Broadcast completed: result=-1, data="status=ok;remaining=…;limit=60;reset_ms=…;checked_ms=…"
```
