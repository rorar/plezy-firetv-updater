# 00 — Device profile

All values read from the device with `adb shell getprop` / `dumpsys` on 2026-11-13.

## Identity

| Property | Value |
|---|---|
| `ro.product.model` | `AFTMM` |
| Marketing name | Fire TV Stick 4K (1st gen, 2018), codename **"mantis"** |
| `ro.product.manufacturer` | `Amazon` |
| `ro.product.device` | `mantis` |
| Network | `192.168.1.50`, MAC `MAC redacted`, mDNS `Android.local` |
| ADB endpoint | `192.168.1.50:5555` (ADB debugging permanently enabled) |

## OS

| Property | Value |
|---|---|
| `ro.build.version.name` | `Fire OS 6.7.1.1 (NS6711/5908)` |
| `ro.build.version.release` | `7.1.2` |
| `ro.build.version.sdk` | **`25`** |
| `ro.build.type` | `user` |
| `ro.debuggable` | `0` |
| `ro.secure` | `1` |

**API 25 is the single most important fact on this page.** It decides every compatibility
question in this repo:

- Obtainium `v1.4.0+` needs API 26 → cannot install (see [02](02-obtainium-on-fireos6.md)).
- Android 8 background-execution limits do **not** apply → simple `AlarmManager` +
  `BroadcastReceiver` scheduling is legal and reliable (see [07](07-notification-design.md)).
- `POST_NOTIFICATIONS` runtime permission does not exist yet (introduced in API 33).
- Per-app "install unknown apps" grants do not exist yet (introduced in API 26);
  sideloading is governed by the global `install_non_market_apps` setting.

## Hardware

| Property | Value |
|---|---|
| `ro.product.cpu.abi` | `armeabi-v7a` |
| `ro.product.cpu.abilist` | `armeabi-v7a,armeabi` |
| RAM | 2 GB (`ram = 2_0GB` in `LaunchTimeProcessor` log) |
| Heap cap reported by Plezy | `Low-RAM device: capped engine caches (renderer=Skia, oldGen=256MB)` |
| Screen | 1920×1080 |

**32-bit only.** APKs must be `armeabi-v7a` or universal; `arm64-v8a` and `x86_64` builds
fail with `INSTALL_FAILED_NO_MATCHING_ABIS`.

## Storage

```
Filesystem      Size  Used Avail Use% Mounted on
/dev/block/...  5.3G  4.5G  803M  86% /data
```

~800 MB headroom. Plezy's APK is 87 MB and 87 MB installed, so a full download →
extract → install cycle (~250 MB transient peak) fits, but cached artifacts must be deleted.

## Relevant settings

| Setting | Value | Meaning |
|---|---|---|
| `secure install_non_market_apps` | `1` | Sideloading allowed globally (API 25 model) |
| `global package_verifier_enable` | `1` | Package verifier active |
| `global verifier_verify_adb_installs` | `null` | Verifier not explicitly applied to adb installs |
| Device owner | none | `dpm set-device-owner` blocked — accounts already exist |
| Accounts | `amazon.account`, `com.amazon.kso.blackbird.ACCT_TYPE_*`, … | Prevents device-owner provisioning |

## Installed package landscape (relevant subset)

| Package | Role |
|---|---|
| `com.amazon.venezia` | Amazon Appstore |
| `com.amazon.tv.launcher` | Fire TV home screen |
| `com.amazon.tv.settings.v2` | Settings |
| `com.android.packageinstaller` | Stock APK installer UI (what the confirm dialog comes from) |
| `com.amazon.tv.notificationcenter` | Fire TV notification center (`NotificationCenterActivity`) |
| `com.amazon.uxnotification` | `/system/priv-app/UXNotificationService`, signature-only permission |
| `com.android.systemui` | System UI + `statusbar` service |

## Consequences for an updater

1. Target `minSdk 25`, build `armeabi-v7a` (or pure Java/Kotlin with no native code at all).
2. Never assume `arm64`.
3. Keep APK downloads to one at a time and stream to disk — 256 MB heap, ~800 MB free.
4. Silent install is **not** achievable (see [06](06-own-updater-feasibility.md)).
