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

## Destructive flows — ask first

An **uninstall** and a **downgrade** (which uninstalls then installs) **erase Plezy's data**
(servers, settings, downloads). Do not run them against the user's device without asking.
