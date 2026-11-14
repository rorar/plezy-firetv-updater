# 08 — The updater app, as built

`app/` is the working updater: `dev.rorar.plezyupdater`, minSdk/targetSdk **25**, plain Java,
programmatic UI, **no Gradle and no AndroidX**. It was verified end-to-end on the stick
(AFTMM, Fire OS 6.7.1.1 / API 25) on 2026-11-13/14.

## What it does

```
AlarmManager (inexactRepeating, user-set interval, default 12 h)  ─┐
BOOT_COMPLETED re-arm                                             ─┤
                                        ▼
                         UpdateCheckService (IntentService, API 25 has no
                         background-service limit)
                           GET api.github.com/repos/edde746/plezy/releases/latest
                           compare tag with the installed Plezy
                           newer → Notification "Plezy X available — press OK"
                                        │
                                        ▼
                         MainActivity (TV UI, D-pad focus)
                           [Install X] [What's new] [Check for updates]
                           [Choose version…] [Settings]
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

The switches are grouped into sections — **User settings**, **Advanced settings**, **Developer
settings** and a bottom **Diagnostics** block (test toast, test notification, back). The rows are
plain platform `Switch` widgets, so their focus colours are set explicitly: white text on the
`#2F6E8F` highlight, instead of the Fire OS default of black-on-blue, which is unreadable on the
dark background.

| Switch | Section | Key | Default | Effect |
|---|---|---|---|---|
| Toast notifications | User | `toast_enabled` | on | Bottom-of-screen toasts |
| Notifications | User | `notification_enabled` | on | Fire TV notification-centre entry |
| Check automatically | Advanced | `auto_check_enabled` | on | Interval alarm + boot re-arm |
| Check interval | Advanced | `interval_value` / `interval_unit` | 12 hours | Opens the interval picker (below) |
| Delete APK after installing | Advanced | `delete_apk_after_install` | **on** | Removes the downloaded APK once installed |
| Allow older versions | Developer | `allow_older_versions` | off | Shows **Choose version…** and sets the hidden downgrade flag |

Every message goes through `Notifier`, so a switched-off channel stays silent everywhere,
including the "Test toast" / "Test notification" buttons.

After an install finishes (success *or* failure) the app **re-runs the check** automatically: if
the screen is alive it is refreshed in place (via the in-app `INSTALL_FINISHED` broadcast),
otherwise a background check runs and only speaks up through a notification.

## Check interval

The cadence is no longer fixed. **Check interval** opens `IntervalActivity`, a two-pane picker:
numbers `1…30` on the left, units **Hours / Days / Weeks / Months** on the right, with a live
"Every 3 days" preview at the top. Up/down changes the highlighted value, left/right switches
column, OK advances to **Save**; the remote's Back key cancels (there is deliberately no Back
button). Saving writes `interval_value`/`interval_unit`, re-arms the alarm
(`UpdateAlarm.schedule` reads `Prefs.intervalMs`), fires a confirmation toast and updates both
summaries on the Settings screen and the footer hint on the main screen.

Each column keeps its own choice highlighted while the D-pad is in the other one, so the selected
number stays visible when the user moves across to the units (and the selected unit stays visible
when they come back). A focused row gets the full accent fill (`#2F6E8F`); the selected-but-unfocused
row gets a dimmer one (`#2A607E`); both render white text. That is `Ui.pickerRowSelector()` +
`Ui.pickerTextColors()` plus a `setSelected(i == index)` on every row in `updatePreview()`.

The rows are plain focusable `TextView`s, not a `ListView`. On this device a `ListView` keeps its
own selection state and the position it reports when it first gains focus is not the one that was
set (`setSelection(2)` reported 1), which desynchronised the preview; plain rows have no hidden
state — the focused row *is* the selection, and `setSelected` drives the highlight. (The highlight
requirement is also why a `ListView` selector alone was not enough: the unfocused column has no
focus state to key off.)

A month has no fixed length, so the schedule treats one as **30 days** and the picker says so.

The backlog asked for a prebuilt picker library. That is not possible here: the build is
`aapt2` + `javac` + `d8` with **no resource merging**, so an AAR carrying layouts and themes
cannot be linked in. The hand-built picker is the same amount of code and matches the app's look
(the reasoning is repeated in `IntervalActivity`'s javadoc).

## Changelog

Release notes come straight from the GitHub release `body`, which `PlezyRepo.Release` carries
alongside `publishedAt`. There is no Markdown renderer on API 25 and the app carries no libraries,
so the notes are rendered by hand.

`Markdown.toStyled(String, density)` returns a styled `CharSequence` instead of stripped text. It
understands the shape every Plezy release uses — a flat list of `## Section` headings followed by
`- bullet` items — and degrades gracefully for anything else:

- **Headings** (and bold-only lines) are `BOLD`, tinted `Ui.TEXT_HEADING` and scaled to 120 % of
  the body size, so sections stand out at a distance.
- **Bullets** get a `•  ` glyph and a `LeadingMarginSpan` hanging indent, so wrapped lines align
  under the text rather than under the bullet.
- **Inline** `**bold**`, `*italic*`, `` `code` `` and `[links](url)` keep their emphasis as spans
  (bold / italic / monospace+green / underlined blue). None appear in the last 30 releases, but
  they are handled defensively.
- Blank lines collapse so blocks are separated by exactly one empty line.

`Markdown.toPlainText(String)` is kept for callers that want no formatting at all.

Two places show the notes:

- **`VersionListActivity`** is a two-pane picker: versions on the left, the highlighted version's
  notes on the right, updating as the D-pad moves. The list takes focus so up/down changes the
  version; pressing right moves into the notes pane to scroll a long changelog. OK returns the tag,
  asset URL, body and date to the main screen.
- **`ChangelogActivity`** is a single-release view opened from the main screen's **What's new**
  button, which is only visible while a release is selected.

An empty body shows "No release notes for this version."

## The TV look (`Ui`)

Every screen used to spell out its own colours, paddings and focus selectors. They now live in
`Ui.java`, so the app is one visual language and a change lands in one place. The rules that
matter on a 10-foot screen:

- **Large text.** Body 17 sp, rows 20–22 sp, titles 30 sp, section labels 16 sp all-caps. Nothing
  important is below 15 sp.
- **Unmistakable focus.** Focusable rows are white-on-accent when focused and light grey on the
  dark surface otherwise, via one `StateListDrawable`; `ListView` rows use a flat accent selector.
  The interval picker uses `pickerRowSelector()` / `pickerTextColors()`, which add a
  selected-but-unfocused fill so each column keeps its choice visible.
- **A raised surface** (`#18222D`) behind the status card and the changelog pane, on the `#101820`
  screen, with 8–10 dp rounded corners.
- **One primary action per screen** in the dimmer accent, everything else neutral.

The full rationale and the per-screen layout live in [10 — TV UI design](10-tv-ui-design.md).

## Uninstall

**Advanced settings** has an **Uninstall Plezy…** row. It first asks for confirmation (an
`AlertDialog`, because a fresh install afterwards starts from an empty app — servers, settings and
downloads are gone), then launches the stock uninstaller with
`Intent(ACTION_DELETE, "package:com.edde746.plezy")`. There is no `REQUEST_DELETE_PACKAGES` on
API 25; the intent is all that is needed, and the user confirms in the system dialog like an
install.

A receiver for `ACTION_PACKAGE_REMOVED` (data scheme `package`) matches the Plezy package and
refreshes the row to "Plezy is not installed" (button disabled). The main screen also notices the
change: `onResume` compares the installed version with the one the last check saw and, when they
differ, runs a fresh check — so returning from an uninstall immediately offers **Install 2.26.0**
instead of a stale "up to date".

## Downgrade

Selecting an older version while **Allow older versions** is on would normally die at
`INSTALL_FAILED_VERSION_DOWNGRADE` ([Finding 2](#finding-2--android-71-refuses-downgrades-of-release-builds)).
Instead the app takes the only route Android 7.1 allows — uninstall, then install:

1. **Download and verify first**, while Plezy is still installed, so a failed download never
   leaves the user with no Plezy. The verified APK is cached.
2. **Confirm** with a clear data-loss warning ("Plezy will be uninstalled first — its data
   (servers, settings, downloads) is erased — and then 2.25.0 installed").
3. **Uninstall** via `ACTION_DELETE`.
4. On `ACTION_PACKAGE_REMOVED` (or `onResume`, whichever comes first), **install** the cached APK
   through the normal `PackageInstaller` session → the usual install dialog.
5. `InstallResultReceiver` re-checks as always, so the screen shows the new version (and, since
   the latest release is now newer again, offers the upgrade back).

A downgrade therefore costs **two** system confirmations and **erases Plezy's data**. The pending
tag is persisted in `Prefs` so the flow survives the dialog and a low-memory process restart.

Cancelling at the system uninstaller is handled: `onResume` sees Plezy still installed, clears the
pending state and shows a **"Downgrade cancelled"** toast. (A toast rather than the progress line,
which `renderStatus()` overwrites in the same pass.) Cancelling earlier — at the app's own
"Downgrade to …?" dialog — clears the state directly.

Verification uses the pinned signer because Plezy is
absent ([below](#verification-with-nothing-installed)).

## Verification with nothing installed

When Plezy is not installed there is no signer to compare against, so `ApkInstaller.verify` falls
back to the pinned upstream certificate (`PINNED_SIGNER_SHA256`, the same `903b862c…1672` from
[04](04-apk-provenance.md)) and skips the version comparison. This also makes a fresh
stick work: the latest release is treated as something to install rather than "up to date".

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

UI/changelog phases, same stick:

| Step | Result |
|---|---|
| Button order | `INSTALL UPDATE` sits above `CHECK FOR UPDATES` |
| Settings sections | `USER SETTINGS` / `ADVANCED SETTINGS` / `DEVELOPER SETTINGS` / `DIAGNOSTICS` headings |
| Focus colours | focused row sampled as white text on `#2F6E8F` (pixel histogram), not black-on-blue |
| Two-pane picker | D-pad moves the version, the notes pane follows (`2.26.0` → `2.24.0`); `•` bullets, styled headings |
| What's new | hidden while up to date, appears with a selection; opens `ChangelogActivity` (`Plezy 2.24.0`, `2026-10-21`) |
| Uninstall | Advanced row → pre-confirm → stock `UninstallerActivity`; on confirm the row flips to "Plezy is not installed" (button disabled) |
| Install with nothing installed | pinned-signer verify passes, `INSTALL 2.26.0` offered, installs `2.26.0`, UI refreshes to "up to date" |
| Return from uninstall | main screen re-checks on `onResume`: "not installed" + `INSTALL 2.26.0` enabled |
| Downgrade 2.26.0 → 2.25.0 | download/verify → "Downgrade to 2.25.0?" → uninstall → auto install dialog → **`2.25.0` installed**, UI re-checked and offered `INSTALL 2.26.0` |
| Upgrade back | `2.26.0` installed again, UI back to "up to date" |
| Cancel downgrade | at the system uninstaller: Plezy stays `2.26.0`, no install dialog, pending state cleared, "Downgrade cancelled" toast (own window, `package=dev.rorar.plezyupdater`); the selected version remains `2.24.0` ("Older than installed…") |

UI overhaul / interval / typography phases, same stick (2026-11-14):

| Step | Result |
|---|---|
| Main dashboard | status card ("Plezy is up to date" / "Installed 2.26.0 · latest 2.26.0"), one primary action, footer hint; all on screen at 1080p, no scroll needed |
| Settings sections | `USER SETTINGS` / `ADVANCED SETTINGS` / `DEVELOPER SETTINGS` / `DIAGNOSTICS`, rows white-on-accent when focused |
| Interval picker renders | `IntervalActivity`: preview "Every 12 hours", `VALUE` column (12 selected) and `UNIT` column (Hours selected), **Save** + "Press Back to cancel." visible |
| Interval set to 3 days | preview live-updated to "Every 3 days"; **Save** fired a toast (`WindowStateAnimator{…Toast}`) and returned to Settings |
| Interval persisted | Settings summary "Runs every 3 days and after the TV boots."; main footer "Checks every 3 days while the TV is on."; `dumpsys alarm` shows `repeatInterval=259200000` (3 d) for `…action.CHECK` |
| Interval highlight (value focused) | screenshot fill sampling: value `12` = `#2F6E8F` (accent), unit `Hours` = `#2A607E` (selected) |
| Interval highlight (unit focused) | after RIGHT: value `12` stays `#2A607E`, unit `Hours` becomes `#2F6E8F`; after DOWN to `Days`, value `12` still selected, `Days` accent; after LEFT back, value `12` accent again and `Days` stays selected |
| Changelog typography | screenshot pixel analysis of the notes pane: heading ink 30 px in `#6EA8C8` vs body ink 25 px (1.2×, the `RelativeSizeSpan`), `•` hanging-indented bullets, section bands separated by a blank line |
| Changelog everywhere | both `VersionListActivity`'s notes pane and `ChangelogActivity` ("What's new" → `Plezy 2.26.0`) render the same styled structure |

## Known limitations

- One remote confirmation per install (silent install is impossible; [06](06-own-updater-feasibility.md)).
- A downgrade costs two confirmations and **erases Plezy's data**; the app warns before starting
  ([Downgrade](#downgrade)).
- Self-update is not implemented yet: a new updater build is installed with `adb install -r`.
- GitHub's unauthenticated limit is 60 req/h; the app polls at most hourly (the minimum the picker
  offers), which is far below it.
- The ADB link drops out periodically on this stick; toggling **ADB debugging** off/on in Fire TV
  developer options is the reliable fix ([01](01-adb-access.md#the-adb-daemon-stops-on-its-own--toggle-it-off-and-on)).
