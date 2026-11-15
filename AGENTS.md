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
- **Picker rows do not respond to `input tap`** (they are `focusableInTouchMode`); a tap only moves
  focus. Use `KEYCODE_DPAD_DOWN/UP` + `KEYCODE_DPAD_CENTER`. Buttons (Settings rows, main-screen
  buttons, dialog buttons) do respond to taps.
- The GitHub API allows **60 unauthenticated requests/hour**. The app now avoids the metered API:
  the latest check uses the unmetered `github.com/.../releases/latest` redirect + `releases.atom`,
  the picker's API call is conditional (`If-None-Match`; `304`s are free), and a spent quota falls
  back to the web feed and the last known version. So a flow run should **not** hit 403 any more.
  If you do see *"GitHub API returned HTTP 403"* (or the calm rate-limit wording), check
  `curl -s https://api.github.com/rate_limit` and wait for the reset — it is the quota, not the
  harness.
- **The updater must work with the API quota spent.** Verify it by reading `rate_limit` first and
  running the flows while `remaining` is `0`; that is the point of the web-feed fallback
  ([docs/08](../docs/08-app-implementation.md#staying-under-githubs-rate-limit)).

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
- The dialog buttons and their centres on this stick (German locale):
  - installer `INSTALLIEREN` → `[998,936][1232,1032]` → tap `1115 984`;
  - uninstaller `OK` → `[536,701][956,781]` → tap `746 741`;
  - app "Downgrade to …?" → `Uninstall & install` → tap `746 718`;
  - app "Uninstall Plezy?" → `Uninstall` → tap `746 694`.
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
