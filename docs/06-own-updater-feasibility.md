# 06 — Feasibility of an own updater APK

**Verdict: very feasible (~1 day for an MVP), but silent installation is impossible on this
device. The floor is one remote click per update.**

The update *logic* is trivial; the constraint is a single Android permission.

## Can this device install an APK without user interaction?

Checked against the actual stick, not against assumptions:

| Escape hatch | Available? | Evidence |
|---|---|---|
| `INSTALL_PACKAGES` for a normal app | ❌ | `signature\|privileged` protection level only |
| Device owner (`DevicePolicyManager` + `installExistingPackage`) | ❌ | `dpm set-device-owner` refuses when accounts exist; device has `amazon.account`, `com.amazon.kso.blackbird.*`, `ContentSyncFramework`. A factory reset does not help — Fire OS re-provisions Amazon accounts during setup |
| Root | ❌ | `ro.build.type=user`, `ro.debuggable=0`, `ro.secure=1`; no known unlock for mantis on Fire OS 6.7.x |
| `PackageInstaller` + `setRequireUserAction(false)` | ❌ | API 31+ only, and still requires installer-of-record privilege; device is API 25 |
| Accessibility service auto-clicking the installer dialog | ⚠️ practically no | Fire OS's Accessibility settings only expose bundled services; third-party accessibility services are generally not listed. Unverified but assumed unavailable |
| Being the installer of record | ⚠️ only after the first install | The updater becomes installer of record *after* it installs something; that does **not** grant `INSTALL_PACKAGES` on API 25 |

Therefore the built-in installer UI (`com.android.packageinstaller`, present on the device)
always appears and always needs one D-pad confirmation.

> Unverified detail worth measuring early: which button has default focus in the Fire OS 6
> installer dialog ("Install" vs "Cancel"). It decides whether an update costs one or two
> remote presses.

## What the app should be

A single-purpose, TV-native notifier + installer. Not a generic source engine.

```
AlarmManager (inexactRepeating, 12 h) + BOOT_COMPLETED
  └── Receiver
        GET https://api.github.com/repos/edde746/plezy/releases/latest
        (ETag / If-None-Match; unauthenticated limit 60 req/h is plenty)
        compare versionName with installed Plezy
        └── newer? → Notification "Plezy 2.27.0 available"  [see 07]

Notification tap / app launch
  └── Activity (leanback) with one button: [Install 2.27.0]
        download plezy-android-armeabi-v7a.tar.gz (~48 MB)  → cacheDir
        GZIPInputStream → 512-byte tar header → plezy.apk (~90 MB)
        PackageManager.getPackageArchiveInfo()
          - packageName == com.edde746.plezy
          - versionCode > installed
          - minSdk <= 25, ABI contains armeabi-v7a
          - signatures[0] SHA-256 == installed signer       ← mandatory
        PackageInstaller.Session → commit(IntentSender)
        └── stock installer dialog → one remote click
        └── delete cache on success/failure
```

Why this beats Obtainium here:

1. **Direct `.tar.gz` support** → no third-party mirror in the trust chain.
2. **Signer pinning** against the installed app (Obtainium does not do this for you).
3. **Leanback UI**, D-pad focusable, no touch assumptions.
4. **~200–400 KB**, no Flutter, no GMS, no dependencies beyond the platform
   (`HttpURLConnection`, `org.json`, `PackageInstaller`).

## Effort

| Piece | Estimate |
|---|---|
| Toolchain setup — **no Android SDK on the host yet** | 20–40 min |
| MVP: check → download → extract → verify → install | ~1 day |
| Polish: progress UI, error states, self-update, scheduling | +1–2 days |
| Test loop | fast (`adb install -r` ≈ 15 s per build) |

## Toolchain status of the host (2026-11-13)

| Item | State |
|---|---|
| `gradle` | ✅ 9.8.2 |
| `kotlin` (CLI) | ✅ 2.4.10 |
| JDKs | ✅ 17, 21, 25, 27 in `/usr/lib/jvm` |
| `sdkmanager` / Android SDK | ❌ missing; no Arch package in the sync DB → Google `cmdline-tools` (AUR or manual) + `platforms;android-34` + `build-tools` |
| `flutter` / `dart` | ❌ absent (not needed — do **not** build this in Flutter) |
| `adb` | ✅ 1.0.41 |
| `gh` | ✅ authenticated (`rorar`), so publishing an updater APK release is easy |

⚠️ Gradle currently runs on **JDK 27**, which AGP does not support. Pin the build to
**JDK 17 or 21** (toolchain or `org.gradle.java.home`).

## Risks and gotchas

- **Heap**: 256 MB cap. Stream the 48 MB tarball and 90 MB APK to/from disk; never buffer.
- **Storage**: 803 MB free; a download+extract+install cycle peaks around 250 MB. Delete cache.
- **Background scheduling**: API 25 predates Android 8 background limits, so a plain
  `BroadcastReceiver` doing a short GET is legal. A foreground service is unnecessary — but
  also verify that Fire OS does not suppress alarms on an idle TV. *(Since `0.5.0` the app uses
  `JobScheduler` rather than `AlarmManager`, so one code path also works at `targetSdk` 26; see
  [08](08-app-implementation.md#background-checks-jobscheduler-targetsdk-26).)*
- **Boot**: re-arm the alarm from `BOOT_COMPLETED`; receivers are otherwise lost on reboot.
- **Installer of record** changes to the updater after the first update. Harmless, but it is
  one more reason the Amazon store will never manage this install.
- **Self-update**: the updater must be able to replace itself, or it goes stale. Host it on its
  own GitHub release and use the same flow. (Not implemented yet; `adb install -r` for now.)
- **Downgrades are blocked, not allowed** (corrected after building it): Android **7.1** only
  permits a lower `versionCode` when the *installed* app is debuggable
  (`Build.IS_DEBUGGABLE || packageDebuggable`), so release-signed Plezy cannot be downgraded
  here. See [08](08-app-implementation.md#finding-2--android-71-refuses-downgrades-of-release-builds).
- **Pending user action** (also only found while building): on API 25 a committed
  `PackageInstaller` session does **not** show the dialog by itself — the app must launch
  `Intent.EXTRA_INTENT` from the `STATUS_PENDING_USER_ACTION` result. See
  [08](08-app-implementation.md#finding-1--on-api-25-the-app-must-launch-the-installer-dialog-itself).
- **API rate limits**: 60/h unauthenticated per IP; cache with ETag and do not poll more than
  a few times a day.

## Open questions (answered while building)

1. Do third-party notifications actually render on Fire OS 6? — **Yes**, verified in
   [07](07-notification-design.md).
2. How does a user reach the notification center with only a remote? — It is a normal part of the
   Fire TV UI; the entry is selectable and OK opens the app.
3. Default focus of the installer dialog → the app just launches the dialog
   ([08](08-app-implementation.md)); the confirmation is one press either way.
4. Does the mirror even need to be involved? — **No**; direct tarball extraction from the upstream
   GitHub release is what the app does.

The updater itself is built and verified — see [08 — the updater app, as built](08-app-implementation.md).
