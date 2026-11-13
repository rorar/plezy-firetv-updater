# 03 — Plezy sideload (verified)

**Result: installed and running.** This document records exactly what was done and measured,
so it can be repeated or audited later.

## What Plezy is

`edde746/plezy` — *"Modern cross-platform media server client built with Flutter"*,
application id **`com.edde746.plezy`**. Distributed as macOS/Windows/Linux desktop builds,
an iOS `.ipa`, and Android **`.tar.gz` archives** (since v1.13.0). On the Amazon App Store the
Android build is a paid product, which is why a sideload path is interesting in the first
place.

Crucially, the Android build is *designed* for this class of device:

```kotlin
// android/app/build.gradle.kts
minSdk = 25   // Fire OS 6.x (API 25); the mpv-android AAR shares the same floor
```

and the manifest carries TV affordances:

- `LEANBACK_LAUNCHER` on the default activity alias and on 23 alternate icon aliases
- `android:banner="@drawable/tv_banner"`, `android:appCategory="video"`
- `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />`
- `<uses-feature android:name="android.software.leanback" android:required="false" />`
- Watch Next / system-shelf providers for the launcher

So unlike Obtainium, Plezy is a legitimate TV app. It appears in the Fire TV home row and is
remote-navigable; no "Sideload Launcher" is needed.

## Source used

Mirror release `derFrisson/plezy-apks` **2.26.0**, asset:

```
https://github.com/derFrisson/plezy-apks/releases/download/2.26.0/plezy-android-armeabi-v7a.apk
90,748,577 bytes (87 MiB)
```

The mirror only re-publishes the APK contained in the official upstream tarball; see
[04-apk-provenance](04-apk-provenance.md) for why that is safe and how to verify it yourself.
The trustworthy alternative is extracting it from `edde746/plezy`'s own release.

## Commands

```bash
mkdir -p /tmp/opencode/plezy && cd /tmp/opencode/plezy
curl -fL -O https://github.com/derFrisson/plezy-apks/releases/download/2.26.0/plezy-android-armeabi-v7a.apk

# sanity check: only the right ABI may be inside
unzip -l plezy-android-armeabi-v7a.apk | grep -oE 'lib/[^/]+/' | sort -u
#   lib/armeabi-v7a/
#   lib/fonts/

adb install -r plezy-android-armeabi-v7a.apk
```

Output:

```
Performing Streamed Install
Success        # 15.1 s wall clock for 87 MB over Wi-Fi
```

Launch:

```bash
adb shell am start -a android.intent.action.MAIN \
  -c android.intent.category.LEANBACK_LAUNCHER \
  -n com.edde746.plezy/.MainActivity
```

## Verification

```
versionCode=1160 minSdk=25 targetSdk=36
versionName=2.26.0
firstInstallTime=2026-11-13 23:03:50
```

| Check | Result |
|---|---|
| Foreground activity | `mResumedActivity: com.edde746.plezy/.MainActivity` |
| Process | `pidof com.edde746.plezy` → 14511 |
| Cold start | `Displayed com.edde746.plezy/.MainActivity: +878ms` (launcher-measured 903 ms) |
| Flutter engine | `Plezy v2.26.0+1160 (671a0d7) [Skia] [effects: reduced (auto: 32-bit, lowRam:false, 1.8GiB)]` |
| Low-RAM path | `Low-RAM device: capped engine caches (renderer=Skia, oldGen=256MB)` |
| Render check | 1920×1080 screenshot, 892 distinct colours, mean brightness 0.28 → real UI, not a black screen or crash dialog |
| Launcher integration | 24 `LEANBACK_LAUNCHER` entries; Amazon launcher logs `Recent App Package was: com.edde746.plezy` |
| Disk | 87 MB in `/data/app/com.edde746.plezy-1`, 803 MB free afterwards |

Note the version metadata mismatch worth remembering for update logic: `pubspec.yaml` says
`2.26.0+160`, but the device reports `versionCode 1160`. **Compare tags or `versionName`,
never assume the pubspec build number equals the APK `versionCode`.**

## Benign log noise

```
I art: Caused by: java.lang.ClassNotFoundException:
  Didn't find class "android.view.accessibility.AccessibilityManager$AccessibilityServicesStateChangeListener"
  on path: DexPathList[[zip file "/system/priv-app/MetricsApi/MetricsApi.apk", ...]]
```

This comes from Amazon's own `MetricsApi` priv-app being loaded into the process, not from
Plezy. It is logged at `I` level and the app runs normally.

## Update behaviour

Plezy has no Android self-updater. `lib/services/update_service.dart` wires in-app updates to
Sparkle (macOS) and WinSparkle (Windows) only, so on Android/Fire OS *something external* must
supply new versions. See [05](05-update-strategies.md).

## State after install

- App installed, launchable from the Fire TV home row.
- **No updater installed** (deliberate; the update path is still a design decision).
- APK kept at `/tmp/opencode/plezy/plezy-android-armeabi-v7a.apk` on the host.
