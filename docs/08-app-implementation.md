# 08 — The updater app, as built

`app/` is the working updater: `dev.rorar.plezyupdater`, minSdk/targetSdk **25**, plain Java,
programmatic UI, **no Gradle and no AndroidX**. It was verified end-to-end on the stick
(AFTMM, Fire OS 6.7.1.1 / API 25) on 2026-11-13/14.

## What it does

```
AlarmManager (inexactRepeating, 12 h)  ─┐
BOOT_COMPLETED re-arm                  ─┤
                                        ▼
                         UpdateCheckService (IntentService, API 25 has no
                         background-service limit)
                           GET api.github.com/repos/edde746/plezy/releases/latest
                           compare tag with the installed Plezy
                           newer → Notification "Plezy X available — press OK"
                                        │
                                        ▼
                         MainActivity (TV UI, D-pad focus)
                           [Check for updates] [Install X] [Choose version…] [Settings]
                           free-space line
                           → download plezy-android-armeabi-v7a.tar.gz (~48 MB)
                           → GZIPInputStream + 512-byte tar header → plezy.apk (~90 MB)
                           → verify: package, versionCode, minSdk, ABI, signer SHA-256
                           → PackageInstaller.Session.commit(IntentSender)
                           → stock installer dialog → one remote press
                           → InstallResultReceiver → toast + refresh
```

## Finding 1 — on API 25 the app must launch the installer dialog itself

This was the root cause of "the install button does nothing".

On Android 7.1 the commit of a `PackageInstaller` session does **not** show the confirmation
screen by itself. The result `Intent` arrives with:

- `EXTRA_STATUS = STATUS_PENDING_USER_ACTION` (`-1`), and
- `Intent.EXTRA_INTENT` = the activity intent that shows the confirmation.

The app has to start that intent. Confirmed against AOSP `android-7.1.2_r36`:

```
// core/java/android/content/pm/PackageInstaller.java
public static final int STATUS_PENDING_USER_ACTION = -1;   // "You can launch the intent
                                                           //  activity described by
                                                           //  Intent#EXTRA_INTENT"
```

`InstallResultReceiver` therefore handles `-1` by `startActivity(intent.getParcelableExtra(
Intent.EXTRA_INTENT))`. Without it the session sits at "committed" forever and nothing appears
— exactly the symptom observed before the fix. With it, the real
`com.android.packageinstaller/.PackageInstallerActivity` opens and the user can confirm.

> Log proof: `install result: status=-1 message=null` →
> `launching confirmation intent Intent { act=android.content.pm.action.CONFIRM_PERMISSIONS
> … cmp=com.android.packageinstaller/.PackageInstallerActivity }`.

## Finding 2 — Android 7.1 refuses downgrades of release builds

The "install an older version" option (below) can *select* any release, and the app even sets
the hidden `INSTALL_ALLOW_DOWNGRADE` flag (see next section), but the platform still blocks the
actual install:

```
W PackageManager: Downgrade detected: Update version code 1157 is older than current 1160
install result: status=4 message=INSTALL_FAILED_VERSION_DOWNGRADE   // code -25
```

From AOSP `PackageManagerService` (7.1.2):

```java
final boolean downgradeRequested = (installFlags & PackageManager.INSTALL_ALLOW_DOWNGRADE) != 0;
final boolean downgradePermitted = downgradeRequested
        && (Build.IS_DEBUGGABLE || packageDebuggable);
```

`Build.IS_DEBUGGABLE` is `0` on a user build, and the installed Plezy is release-signed and not
debuggable, so **no** downgrade is possible on this stick. `pm install -r -d` and
`adb install -r -d` fail the same way. The option still ships because the user asked for it and
it is useful for testing the flow (the download → extract → verify → confirm steps all run; only
the final PMS step is refused), and the failure toast explains the platform limit.

## Finding 3 — storage is the second real constraint

The stick has 2 GB RAM / 256 MB heap and little free space, so the app never buffers a whole
file and checks space before it spends it:

- **Streaming only**: download to `cacheDir` in 64 KB chunks; extract with `GZIPInputStream` +
  a 512-byte tar header; never hold the 48 MB / 90 MB artefacts in memory.
- **Up-front checks** (`ApkInstaller.requireSpace`): the download needs
  `downloadSize × 5 + 20 MB` (tarball + extracted APK + the copy `PackageInstaller` makes in its
  own staging area); installing needs `apk.length() + 50 MB`. Failures produce a readable
  "Not enough free space" message instead of a half-finished 48 MB download.
- **Free-space line** in the UI, with a warning when it drops below the ~300 MB an update needs.
- **Cache reuse**: a verified APK is kept with a small tag marker (`plezy-update.tag`) so a retry
  after a *failed* install does not pull 48 MB again. `cleanStaleCache` removes leftovers older
  than an hour after a crash.
- **Delete after install** (default **on**, see settings): a successful install frees the APK.
  The tarball is always deleted in a `finally`, even when extraction fails.

## Settings

| Switch | Key | Default | Effect |
|---|---|---|---|
| Toast notifications | `toast_enabled` | on | Bottom-of-screen toasts |
| Notifications | `notification_enabled` | on | Fire TV notification-centre entry |
| Check automatically | `auto_check_enabled` | on | 12 h alarm + boot re-arm |
| Allow older versions | `allow_older_versions` | off | Shows **Choose version…** and sets the hidden downgrade flag |
| Delete APK after installing | `delete_apk_after_install` | **on** | Removes the downloaded APK once installed |

Every message goes through `Notifier`, so a switched-off channel stays silent everywhere,
including the "Test toast" / "Test notification" buttons.

After an install finishes (success *or* failure) the app **re-runs the check** automatically: if
the screen is alive it is refreshed in place (via the in-app `INSTALL_FINISHED` broadcast),
otherwise a background check runs and only speaks up through a notification.

## Build and install

No Gradle. `app/build.sh` runs `aapt2` → `javac` (JDK 17) → `d8` → `zipalign` → `apksigner`:

```bash
cd app
BT=/tmp/opencode/sdk/android-15 \
AJ=/tmp/opencode/sdk/android-35/android.jar \
./build.sh
adb install -r build/plezy-updater.apk
```

`app/debug.keystore` (gitignored) signs the updater; **keep it**, or the installed updater can
no longer be replaced by a new build.

## Verified on hardware (2026-11-14)

| Step | Result |
|---|---|
| GitHub check | `Installed: 2.26.0 / Latest upstream: 2.26.0 (up to date)` |
| Toast off/on | suppressed (`toast suppressed by settings`) / visible (screen diff `314×33+803+911`) |
| Notification off/on | suppressed / `NotificationRecord pkg=dev.rorar.plezyupdater id=1001`, tappable |
| Release picker | lists `2.26.0 (installed)`, `2.25.0`, …; selecting sets `Selected:` + `INSTALL 2.25.0` |
| Download → extract → verify | `verified 2.25.0 (1157) signer 903b862c…631672` (matches installed) |
| Confirmation dialog | opens (Finding 1) |
| Downgrade to 2.25.0 | `INSTALL_FAILED_VERSION_DOWNGRADE` (Finding 2), explained in the toast |
| Reinstall 2.26.0 | **`INSTALL_SUCCEEDED`** (`status=0`), UI refreshed to "up to date", free space rose 415 MB → 830 MB |
| Delete-after-install | cache cleared on success (free space went up, not down) |

## Known limitations

- One remote confirmation per install (silent install is impossible; [06](06-own-updater-feasibility.md)).
- No downgrades of release builds ([Finding 2](#finding-2--android-71-refuses-downgrades-of-release-builds)).
- Self-update is not implemented yet: a new updater build is installed with `adb install -r`.
- GitHub's unauthenticated limit is 60 req/h; the app polls a few times a day, which is plenty.
