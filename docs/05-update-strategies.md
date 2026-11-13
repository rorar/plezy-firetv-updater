# 05 — Update strategies

Goal: Plezy installed on an AFTMM stick (Fire OS 6.7.1.1 / API 25) and kept current with as
little friction as possible.

## Comparison

| | Amazon Appstore | Obtainium v1.3.4 | PC script + cron | Own updater APK |
|---|---|---|---|---|
| Installs updates unattended | n/a (see below) | ❌ 1 remote click | ✅ fully | ❌ 1 remote click |
| TV UX | native | ❌ painful, touch-first | ✅ no UI at all | ✅ purpose-built |
| Can use upstream releases | ✅ | ❌ needs mirror | ✅ | ✅ (tar.gz in-app) |
| Third parties in the trust chain | Amazon | mirror | none | none |
| Signer pinning | Amazon's | partial | ✅ `apksigner` | ✅ in-app |
| Needs a PC awake | ❌ | ❌ | ✅ | ❌ |
| Effort | purchase price | ~10 min | ~30 lines | 1–3 days |
| Longevity | tied to Amazon catalogue | frozen at v1.3.4 | open | open |

## Option 1 — Amazon Appstore: structurally impossible

Plezy is a paid product in the Amazon App Store. Sideloading it and then expecting the store
to update it fails for three independent reasons:

1. **The store only updates what the store installed.** Amazon's client (`com.amazon.venezia`)
   keys updates off its own install/entitlement records. A sideloaded package is not adopted;
   there is no "register this package with the store" mechanism in Fire OS.
2. **No entitlement.** Even if the package were recognised, delivering the update requires the
   purchase entitlement on the account. A sideloaded install bypasses the purchase, so the store
   can only ever offer "Buy"/"Get".
3. **Signature is not the blocker.** Upstream signs with its real release keystore and the
   mirror copies byte-for-byte ([04](04-apk-provenance.md)), so a store build would *probably*
   install over the sideload without `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. That removes one
   obstacle; it does not create an update path.

> Marked as reasoning from Fire OS/Android behaviour, not measured. Measuring it would require
> buying the app.

Paying for the store version is the only path that yields genuinely unattended updates — a
legitimate choice, not a workaround.

## Option 2 — Obtainium v1.3.4 + mirror

Works, but every ceiling is permanent on this device:

- Obtainium itself can never be updated past `v1.3.4` (API 25 is below `v1.4.0`'s floor).
- The mirror must exist, because Obtainium cannot unpack `.tar.gz`.
- The UI is touch-first Flutter with no leanback entry; adding a source means typing a URL on
  a TV (mitigate with `adb shell input text`).
- Every update ends in the stock installer dialog → one remote click.

Setup: `https://github.com/derFrisson/plezy-apks`, APK filter `armeabi-v7a`, self-updates for
Obtainium pinned/ignored. Details in [02](02-obtainium-on-fireos6.md).

## Option 3 — PC-side updater over ADB (recommended for "just works")

~30 lines, zero TV interaction, and it can read upstream directly:

```bash
#!/usr/bin/env bash
set -euo pipefail
SERIAL=192.168.1.50:5555
REPO=derFrisson/plezy-apks              # or edde746/plezy + tar extraction
APK=plezy-android-armeabi-v7a.apk
PKG=com.edde746.plezy

adb connect "$SERIAL" >/dev/null
latest=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | jq -r .tag_name)
have=$(adb -s "$SERIAL" shell dumpsys package "$PKG" \
        | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1)
[ "$latest" = "$have" ] && exit 0

curl -fsSL -o /tmp/plezy.apk \
  "https://github.com/$REPO/releases/download/$latest/$APK"
adb -s "$SERIAL" install -r /tmp/plezy.apk
rm -f /tmp/plezy.apk
```

Run from a `systemd` timer (daily). Caveats:

- Requires the host to be on when the timer fires (a NAS or always-on box solves this).
- Compare `versionName`, not `versionCode`: upstream reports `2.26.0+160` while the APK says
  `versionCode 1160` ([03](03-plezy-sideload.md)).
- Silent by nature — the user never sees that an update happened, which is either the point or
  a surprise, depending on taste.

## Option 4 — Own updater APK

The only option that is both TV-native and independent of a PC. It cannot install silently
(no root, no device owner, `INSTALL_PACKAGES` unavailable — [06](06-own-updater-feasibility.md)),
so the realistic floor is *one remote click per update*, ideally preceded by a
**notification** that an update exists ([07](07-notification-design.md)).

Advantages over Obtainium: leanback UI, direct `.tar.gz` support (no mirror), signer pinning,
version/ABI/minSdk guards, ~200–400 KB instead of a generic source engine.

## Recommendation

- **Want it current with zero effort now** → Option 3, on an always-on host.
- **Want the TV to be self-sufficient** → Option 4 (notification + one click), with Option 3
  as the escape hatch for unattended installs.
- **Option 2 only** if Obtainium's other apps matter to you as well.
- **Option 1** if paying is acceptable — it is the only real unattended store path.

## State as of 2026-11-13

Plezy 2.26.0 is installed; **no updater of any kind is configured**. The decision is
deliberately deferred until the notification question in [07](07-notification-design.md) is
answered.
