# 02 — Obtainium on Fire OS 6 (API 25)

**Conclusion: Obtainium's current release cannot be installed on this device, and the newest
release that can be installed is `v1.3.4` (`minSdk 24`), which is a dead end.**

## The failure

`v1.7.4` (latest at the time of writing) requires **`minSdk 26`** — see
`android/app/build.gradle.kts`:

```kotlin
minSdk = 26
```

The stick reports `ro.build.version.sdk = 25`, so every asset (universal, `arm64-v8a`,
`armeabi-v7a`, `x86_64`, fdroid or not) fails identically:

```
Failure [INSTALL_FAILED_OLDER_SDK: Failed parse during installPackageLI:
/data/app/vmdl…/base.apk (at Binary XML file line #…):
Requires newer sdk version #26 (current version is #25)]
```

This is **not** a corrupt download, not an ABI mismatch, and not an Amazon sideload
restriction. The APK simply declares an Android floor above the device.

## minSdk history (measured per tag, not guessed)

| Tag | `minSdk` | Installable on API 25? |
|---|---|---|
| v1.0.0 | 24 | ✅ |
| v1.1.0 | 24 | ✅ |
| v1.2.0 | 24 | ✅ |
| v1.3.0 – **v1.3.4** | **24** | ✅ **newest that works** |
| v1.4.0 – v1.7.4 | 26 | ❌ `INSTALL_FAILED_OLDER_SDK` |

The bump happened in **v1.4.0**. `v1.3.4` was published 2026-01-31.

## Working install (if Obtainium is still wanted)

Asset for a 32-bit stick — **`armeabi-v7a`**, not `arm64-v8a`:

```bash
adb connect 192.168.1.50:5555
curl -LO https://github.com/ImranR98/Obtainium/releases/download/v1.3.4/app-armeabi-v7a-release.apk
adb install -r app-armeabi-v7a-release.apk
adb shell pm list packages | grep obtainium     # dev.imranr.obtainium
```

Package name is **`dev.imranr.obtainium`** (not `io.github.imranr98.obtainium`).

## Caveats that make this a dead end

1. **Version freeze.** Obtainium can never be updated past `v1.3.4` on this stick, because
   every later release needs API 26. The app will keep offering updates that cannot install.
2. **No deep links in v1.3.4.** The manifest registers an `obtainium://` VIEW filter, but
   `lib/main.dart` contains no URI handling at all — the current
   [deep-link docs](https://wiki.obtainium.imranr.dev/deep_links/) (`obtainium://add/<url>`,
   `obtainium://app/<json>`) do not apply to this version. Adding a source must be done
   through the UI, i.e. typing a URL on a TV. Workaround:
   focus the field and use `adb shell input text "<url>"` + `input keyevent 66`.
3. **Touch-first UI.** Flutter app with no leanback entry; D-pad navigation is painful.
4. **Obtainium cannot read Plezy's `.tar.gz` releases**, so it needs the third-party mirror
   ([04](04-apk-provenance.md)).
5. Each update still ends in the stock installer dialog, which needs a remote click.

## What was actually configured

Nothing. Obtainium was **not** installed — after the discovery above it was dropped in favour
of a Plezy-only path (see [05](05-update-strategies.md)).

## If it were installed anyway

- Source: `https://github.com/derFrisson/plezy-apks`
- APK filter regex: `armeabi-v7a`
- Disable self-updates for Obtainium itself (they can only fail), e.g. pin version `1.3.4`.
