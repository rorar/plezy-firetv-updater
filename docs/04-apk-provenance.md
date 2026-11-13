# 04 — APK provenance and signing chain

This decides whether an update may be installed over an existing app, and whether a
third-party mirror can be trusted. Short version: **the mirror is a byte-for-byte copy, and the
official APKs are signed with the maintainer's release keystore — so both are the same
artifact, and signature-compatible updates are possible.**

## Why the mirror exists

Upstream ships Android builds as `.tar.gz`, not `.apk`:

```
plezy-android-arm64-v8a.tar.gz     49 MB
plezy-android-armeabi-v7a.tar.gz   48 MB
plezy-android-x86_64.tar.gz        51 MB
```

`derFrisson/plezy-apks` (created 2026-02-08) runs a daily GitHub Action
(`.github/workflows/extract.yaml`, cron `0 6 * * *`) that:

1. reads `api.github.com/repos/edde746/plezy/releases/latest`,
2. skips if that tag already has a release in the mirror,
3. downloads each Android tarball,
4. `tar -xzf` and `cp`s the APK out,
5. publishes it as `plezy-android-<abi>.apk` with the upstream changelog.

There is **no re-signing, no re-alignment, no repacking** — step 4 is a plain copy. That is
what makes the mirror auditable.

## What is inside the official tarball

`.github/workflows/build.yml`:

```bash
tar -czf plezy-android-armeabi-v7a.tar.gz \
  -C build/app/outputs/flutter-apk app-armeabi-v7a-release.apk \
  --transform 's/app-armeabi-v7a-release.apk/plezy.apk/'
```

So each archive contains exactly **one file, `plezy.apk`**, at the archive root, and the
compression is what takes 90.7 MB down to 48 MB (the APK stores its native libraries
uncompressed, which gzip then squeezes).

Consequence: extracting an APK **inside an app** is trivial —
`GZIPInputStream` → 512-byte tar header → `plezy.apk`. No third-party library needed. That is
why the mirror is only a convenience, not a requirement.

## Signing

CI signs with the project's real upload keystore:

```yaml
# .github/workflows/build.yml
- name: Configure Android signing
  run: |
    echo "${{ secrets.ANDROID_KEYSTORE_BASE64 }}" | base64 --decode > android/app/upload-keystore.jks
    ...
      storeFile=upload-keystore.jks
```

`android/app/build.gradle.kts` selects `signingConfigs.release` when
`android/key.properties` exists and falls back to the **debug** keystore otherwise. Official
releases therefore carry a stable, release-signed certificate; debug-signed local builds would
not be installable over them.

## Why this matters for updates

| Scenario | Result |
|---|---|
| Mirror APK over sideloaded mirror APK | ✅ normal update (`adb install -r`, `PackageInstaller`) |
| Mirror APK over *official* APK | ✅ same signer (mirror copies) |
| F-Droid-style rebuild with own key | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| Debug/dirty local build over official | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| Amazon App Store build over sideload | ⚠️ **unverified** — likely same keystore, but the store never offers it anyway ([05](05-update-strategies.md)) |

Whatever installs an update should **pin the signer**: read the installed app's
`PackageInfo.signatures[0]` SHA-256 and compare it with the candidate APK's
(`PackageManager.getPackageArchiveInfo`). This is the cheapest defence against a tampered
mirror or a typo-squatted repo, and it costs ~10 lines.

## Verifying provenance yourself

```bash
# 1. official artifact
curl -fLO https://github.com/edde746/plezy/releases/download/2.26.0/plezy-android-armeabi-v7a.tar.gz
tar -xzf plezy-android-armeabi-v7a.tar.gz          # -> plezy.apk
mv plezy.apk plezy-official.apk

# 2. mirror artifact
curl -fLO https://github.com/derFrisson/plezy-apks/releases/download/2.26.0/plezy-android-armeabi-v7a.apk

# 3. compare
sha256sum plezy-official.apk plezy-android-armeabi-v7a.apk
apksigner verify --print-certs plezy-official.apk
apksigner verify --print-certs plezy-android-armeabi-v7a.apk
```

Expected: identical SHA-256 (if the mirror truly copies) and identical certificate digests.

`apksigner` ships with Android `build-tools`, which is **not installed on this host**. Either
install `build-tools` via the SDK, or use the fallback comparison inside a throwaway APK
(`getPackageArchiveInfo`), or simply compare the SHA-256 — a byte-identical file has a
byte-identical signature by definition.

> Status: the tarball/mirror comparison has **not** been run on this host yet (no `apksigner`).
> The byte-copy claim above is read from the mirror's workflow, not independently verified.

## Residual trust considerations

- The mirror is one person's repository. Even a byte-copy workflow can be changed tomorrow;
  the defence is **signer pinning in the consumer**, not trust in the mirror.
- GitHub Actions releases from upstream are attributable to the maintainer's account and
  keystore. Extracting the tarball yourself removes even the mirror from the chain.
- Nothing here protects against a compromised upstream account or a malicious upstream
  release; signer pinning only guarantees *continuity*, not *good intent*.
