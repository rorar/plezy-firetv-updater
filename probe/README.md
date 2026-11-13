# probe — notification/toast probe app

Throwaway app used to answer one question: **can a sideloaded, debug-signed app put a Toast and
a Notification on a Fire OS 6 TV, and can the notification be opened with the remote?**

Answer: yes to all three. Results are recorded in
[`docs/07-notification-design.md`](../docs/07-notification-design.md).

Everything here is disposable. It is **not** the updater; it is the evidence.

## What it does

- `MainActivity` — leanback-launchable; fires one toast and one notification on launch and
  prints the adb commands on screen.
- `TestReceiver` — exported receiver, triggered from adb:
  - `--es what toast` → `Toast.makeText(...).show()`
  - `--es what notif` → `NotificationManager.notify(id=4242, …)`, `contentIntent` = MainActivity
  - `--es what all` (or no extra) → both

Package id: `dev.local.toasttest`. No resources, no dependencies, ~13 KB APK.

## Build without Gradle

The host has `javac` and `keytool` but no Android SDK tooling, so this uses a hand-rolled
`aapt2` + `javac` + `d8` + `zipalign` + `apksigner` pipeline. You need:

- `build-tools` (`aapt2`, `d8`, `zipalign`, `apksigner`)
- a platform `android.jar`

Both can be fetched without `sdkmanager`:

```bash
curl -fLO https://dl.google.com/android/repository/build-tools_r35_linux.zip   # ~59 MB
curl -fLO https://dl.google.com/android/repository/platform-35_r01.zip         # ~61 MB
unzip -q build-tools_r35_linux.zip   # -> android-15/
unzip -q platform-35_r01.zip         # -> android-35/android.jar
```

Then:

```bash
BT=/path/to/android-15
AJ=/path/to/android-35/android.jar
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk     # JDK 17/21; JDK 27 chokes on -source 8

"$BT/aapt2" link -o unsigned.apk -I "$AJ" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 25
"$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$AJ" \
  -d classes src/dev/local/toasttest/*.java
"$BT/d8" --lib "$AJ" --min-api 21 --output . classes/dev/local/toasttest/*.class

zip -q -u unsigned.apk classes.dex
"$BT/zipalign" -f 4 unsigned.apk aligned.apk
"$JAVA_HOME/bin/keytool" -genkeypair -keystore debug.keystore -storepass android \
  -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out toasttest.apk aligned.apk
```

## Install and run

```bash
adb install -r toasttest.apk
adb shell am start -n dev.local.toasttest/.MainActivity          # fires both once

adb shell am broadcast -n dev.local.toasttest/.TestReceiver --es what toast
adb shell am broadcast -n dev.local.toasttest/.TestReceiver --es what notif

# read the notification center instead of looking at the TV
adb shell am start -n com.amazon.tv.notificationcenter/.NotificationCenterActivity
adb shell uiautomator dump /sdcard/nc.xml && adb shell cat /sdcard/nc.xml
```

## Reading results without looking at the screen

- Toast: `adb shell 'dumpsys window windows | grep -i toast'`, plus a screenshot diff
  (`magick a.png b.png -compose difference -composite -threshold 8% -format "%@" info:`).
- Notification: `adb shell 'dumpsys notification | grep -A20 dev.local.toasttest'`.
- Tap-through: `input keyevent 23` on the focused card, then
  `dumpsys activity activities | grep mResumedActivity`.
