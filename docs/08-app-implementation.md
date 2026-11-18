# 08 — The updater app, as built

`app/` is the working updater: `dev.rorar.plezyupdater` **0.6.0** (`versionCode 6`),
minSdk **25** / targetSdk **31**, plain Java, programmatic UI, **no Gradle and no AndroidX**. It is
verified end-to-end on the API 25 stick (AFTMM, Fire OS 6.7.1.1 / API 25) and, on 2026-11-18, on the
emulator lane (Android TV API 25/30, phone API 34) **and on a real arm64 phone** (LineageOS 22.1 =
Android 15 = API 35). All five flows pass 5/5 in English and German, with French and Spanish
`install` smokes, on both the stick and the phone.

## What it does

```
JobScheduler (periodic job 2001, user-set interval, default 12 h)  ─┐
BOOT_COMPLETED → re-arm + one-off job 2002 (manual=false)          ─┤
                                        ▼
                         CheckJobService (JobService)
                           GET github.com/edde746/plezy/releases/latest (unmetered 302 → tag)
                           compare tag with the installed Plezy
                           newer → Notification "Plezy X available — press OK"
                                        │
                                        ▼
                         MainActivity (TV UI, D-pad focus)          VersionListActivity
                           [Install X] [What's new] [Check for updates]   ("Choose version…")
                           [Choose version…] [Settings]                    OK on a version
                           free-space line                                 │
                                   │                                         │
                                   └───────────────┬─────────────────────────┘
                                                   ▼
                                      InstallFlow (shared download/verify/install)
                                        → download plezy-android-<abi>.tar.gz (~48 MB); ABI from
                                          Build.SUPPORTED_ABIS (B2)
                                        → GZIPInputStream + 512-byte tar header → plezy.apk (~90 MB)
                                        → verify: package, versionCode, minSdk, ABI, signer SHA-256
                                        → PackageInstaller.Session.commit(IntentSender)
                                        → stock installer dialog → one remote press
                                        → InstallResultReceiver → toast + INSTALL_FINISHED refresh
                                          (no screen up → one-off job 2002, manual=true)
                                        → "Open Plezy?" prompt (A1, on by default)
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

## Finding 4 — replacing a package also broadcasts ACTION_PACKAGE_REMOVED

The downgrade handshake waits for `ACTION_PACKAGE_REMOVED` to learn that Plezy is gone
([Downgrade](#downgrade)). A normal **upgrade** through `PackageInstaller`, though, *also*
broadcasts `ACTION_PACKAGE_REMOVED` for the package — immediately before `ACTION_PACKAGE_ADDED`
installs the new one — and flags it with `Intent.EXTRA_REPLACING = true`.

Treating that as "Plezy was uninstalled" made the version picker's hint flip to *Installed: none*
for the whole time the installer dialog was up (and would have made a mid-upgrade state look like
a pending downgrade). The fix is one guard in `InstallFlow`'s receiver:

```java
if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
    return; // an upgrade, not an uninstall
}
```

`EXTRA_REPLACING` is exactly the platform's signal for "this removal is part of a replace", so the
handshake now only fires for a real uninstall. (Confirmed on the stick: `pm path` reported Plezy
installed and unchanged throughout the session, so the null was purely a UI-state artefact.)

## Finding 5 — the uninstall handshake raced the platform on Android 12+

The downgrade handshake finishes on whichever of two signals arrives first: the
`ACTION_PACKAGE_REMOVED` broadcast ([Finding 4](#finding-4--replacing-a-package-also-broadcasts-action_package_removed))
or the activity's `onResume`, which runs when the stock uninstaller closes
([Downgrade](#downgrade)). The activity kept the last known version in a field and passed *that*
to the flow, trusting the broadcast to have already blanked it. On the Fire TV (API 25) it had — the
resume saw `null` and installed the cached APK, which is why every stick run passed.

On Android 12+ the order is the other way round: `onResume` runs *before* the removal broadcast. The
field was still `2.26.0`, so the flow read a non-null version as *"the user cancelled the
uninstall"*: it cleared the pending downgrade and showed the **"Downgrade cancelled"** toast — while
Plezy was in fact already gone and the picker already said *Installed: none*. The downgrade never
installed and the progress line stuck on "Uninstalling Plezy…".

**First fix (the emulator lane, `phone34` / API 34).** Hand the flow the **fresh** read instead of
the sticky field, so the flow decides install-vs-cancel on what is true *now*:

```java
String current = PlezyRepo.installedVersion(this);
if (current != null) {
    installedVersion = current;   // the screen keeps the last known version for its own UI
}
installFlow.onResume(current);    // the flow decides install-vs-cancel on what is true *now*
```

The sticky field still guards the *UI* against the transient `null` PackageManager reports while it
swaps a package; the flow only consults the argument when a downgrade is actually pending, where a
`null` unambiguously means the uninstall we asked for happened.

**That was not enough (the real phone, `phone35` / API 35).** A *fresh* read is still not a truth
signal: on the phone the uninstall had not yet propagated to PackageManager, so `current` was still
`2.26.0` for ~137 ms after a **successful** uninstall. The fresh read was therefore read as a
cancellation too, and the downgrade failed again — this time with the APK already downloaded and
Plezy gone. Measured on-device: the stock uninstaller closed at `16:25:24.4`, `onResume` read the
version at `16:25:24.6`, and the `ACTION_PACKAGE_REMOVED` broadcast landed at `16:25:24.8`.

So `InstallFlow` no longer *decides* on that read at all. A non-null read schedules a **bounded
re-check** (750 ms × 5) and waits for the authoritative `ACTION_PACKAGE_REMOVED` broadcast; the
broadcast (or the first re-check that sees Plezy gone) installs the cached APK, and only a version
that is *still* there after all five checks is a real cancellation:

```java
public void onResume(String installedVersion) {
    if (pendingDowngradeApk == null || busy) return;
    if (installedVersion == null) { installPendingDowngrade(); return; }  // gone → install now
    scheduleUninstallRecheck();     // maybe gone → let the broadcast decide, don't cancel yet
}
```

The re-check is cancelled the moment either signal settles the downgrade (`installPendingDowngrade`
/ `clearPendingDowngrade` / `dispose`), so neither path installs twice and no callback outlives the
screen. Verified on the phone: logcat `downgrade: Plezy still reads as 2.26.0 — re-checking before
deciding it was cancelled`, then `downgrade: Plezy is gone — installing` as the broadcast arrived,
then the cached APK's installer appeared. Both signals remain, so either order works.

## Install routine (shared)

The "get an APK onto the TV" pipeline — download, extract, verify, `PackageInstaller` commit, the
downgrade handshake — lived twice, once in `MainActivity` and once implied by the picker. It is now
one class, **`InstallFlow`**, that both screens drive through a small `Callback` (`onProgress`,
`onBusy`, `onFailed`, `onInstallCommitted`, `onPlezyRemoved`). The screens own only their own
chrome; the flow owns the state machine.

**The picker installs in place.** Pressing OK on a highlighted version in **Choose version…** runs
the whole pipeline right there and leaves the user on the picker — there is no detour back to the
main screen to press **Install update**. The picker shows the progress line itself ("Downloading…
42 %", "Unpacking…", "Opening the installer…", "Confirm the install on the TV.", then
"Installed 2.26.0.") and refreshes its hint and `(installed)` tag from the `INSTALL_FINISHED`
broadcast, so the outcome is visible without navigating.

**The main screen keeps the initial-install flow.** When Plezy is *not* installed at all, the
dashboard's **Install update** button is the entry point and still runs the same `InstallFlow`
(verification falls back to the pinned signer, [below](#verification-with-nothing-installed)). The
version picker is a developer option (**Allow older versions**) and is not the intended path for a
first install.

`InstallResultReceiver` now always sends the in-app `INSTALL_FINISHED` broadcast (package-scoped),
carrying the raw `EXTRA_STATUS`, and only arms the one-off background job (`UpdateJob.runNow(c,
true)`) when no screen is up. That is what lets the picker — not just `MainActivity` — word the
outcome and refresh.

## Self-update (the updater)

The one thing that used to need `adb` was replacing the updater itself. **`SelfUpdate`** points the
same download/verify/install pipeline at the updater's own GitHub release
(`rorar/plezy-firetv-updater`), so a new build can arrive over the air like everything else.

- **The source is the unmetered redirect.** `PlezyRepo.latestTagFor(context, ownerRepo)` reads the
  tag from `github.com/<repo>/releases/latest` (a 301/302 to `/releases/tag/<tag>`), so the check
  costs **no API quota** — the same trick the Plezy check uses. The APK is the plain
  `plezy-updater.apk` asset; no tarball, no extraction.
- **Two checks against the running app.** `SelfUpdate.check` compares the release tag with the
  running `versionName` (`PlezyRepo.compareVersions`) and does nothing when it is not newer.
  `ApkInstaller.verifySelfUpdate` then re-checks the **downloaded** build: right package, a
  `versionCode` **strictly greater** than the installed one, and — the check that actually matters —
  the **same signing certificate as the running app** (`MessageDigest.isEqual` of the two signer
  digests). A tampered or wrongly-signed APK is refused with a readable message instead of letting
  the platform raise `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- **The result is worded for the updater.** `ApkInstaller.install(..., selfUpdate=true)` puts an
  extra on the session's result intent; `InstallResultReceiver`'s `self_update` branch then skips the
  Plezy re-check and the "Open Plezy?" prompt, cleans up the cached APK, and toasts
  `Plezy Updater <version> installed`. The swap can kill the process, so that toast is best-effort.
- **Manual, and last.** The entry point is **Settings → About → "Update Plezy Updater…"**; the last
  step is the stock installer's own confirmation. It is deliberately not automatic — replacing the
  running app is not something to do behind the user's back — and the row releases its busy state in
  `onResume` if the user cancels at the installer.

The **first** self-updating build still has to arrive by `adb install -r` once; the `v0.3.0` release
notes say so. See the [roadmap](11-roadmap.md#1-self-update-of-the-updater) for the on-device proof
(`versionCode` 2 → 3, `firstInstallTime` unchanged, `lastUpdateTime` moved).

## The "Open Plezy?" prompt (A1)

An install ends on the stock installer screen, which pauses the updater; when it closes the user is
back in the updater and the app they just installed is not running. So after **every successful
install** the app offers to start Plezy:

```
┌ Open Plezy? ─────────────────────────────┐
│ Plezy 2.26.0 is installed. Start it now? │
│                       [ Not now ] [ Open ]│   ← "Not now" holds the focus
└───────────────────────────────────────────┘
```

It is a **toggle**, not an extra row: **Settings → User settings → Prompt to open Plezy** (pref
`open_prompt_enabled`, **on** by default) turns the offer off entirely. There is no separate "Open
Plezy" button anywhere — the prompt *is* the way to open it after an install.

The state lives in `OpenPrompt`, not on a screen, because both the dashboard and the version picker
listen for the same `INSTALL_FINISHED` broadcast and either could be the one underneath the
installer. `OpenPrompt.onInstallFinished()` sets a static `pending` flag for a `STATUS_SUCCESS`; the
**first screen that regains window focus** claims it (`maybeShow` is called from the finished
handler, `onResume()` and `onWindowFocusChanged(true)`) and the other one finds it already taken.
Only a *success* prompts, and only while the toggle is on.

Two details were needed to make it behave on a TV remote:

- **"Not now" holds the focus.** `AlertDialog` focuses the positive button after `onShow` returns,
  so the request has to be **posted**, and the negative button has to be
  `setFocusableInTouchMode(true)` first — the root cause was that an injected `input tap` (and any
  phone) leaves the device in touch mode, where a bare `requestFocus()` is refused and the dialog
  silently falls back to focusing **Open**. With both in place a stray OK cannot launch Plezy.
- **The launch component is not hard-coded.** `OpenPrompt.open()` asks the `PackageManager` for
  `getLeanbackLaunchIntentForPackage(PlezyRepo.PACKAGE_NAME)` and falls back to
  `getLaunchIntentForPackage`. A `<queries>` element declares `com.edde746.plezy` in the manifest,
  which is what package visibility needs from API 30 on (harmless on this API 25 stick, correct if
  the updater is ever run on a newer TV).

## About and the version line (A7)

**Settings → About Plezy Updater…** opens `AboutActivity`: the version line, a **Licenses** block
and a **Thanks** note. The version is read back from the `PackageManager`
(`PackageInfo.versionName` / `versionCode`) rather than hard-coded, so it always matches the APK
that is actually installed:

> Plezy Updater 0.2.0 (build 2)

`AboutActivity.versionLine(Context)` is the single source for that string; Settings shows the same
line inline at the bottom, so the two cannot disagree. `build.sh` stamps `versionCode`/`versionName`
at link time with `aapt2 link --version-code/--version-name --replace-version`, so bumping the
version is one edit in the build script.

The third-party notice is read from **`res/raw/licenses.txt`** and is deliberately **not
translated** — licence text must not drift between languages. The only bundled third-party asset is
the flag-icons set ([Language](#language)), and its MIT notice lives there.

## Diagnostics and the remote quota check (A3)

The app lives under GitHub's 60-requests/hour limit, and the reset time is otherwise invisible, so
**Settings → Diagnostics → Check GitHub quota…** runs a live check and shows it as a summary line:

```
Check GitHub quota…       59 of 60 requests left · resets 13:21.
```

`/rate_limit` is exempt from the limit, so asking costs nothing. Both the Settings row and the
remote trigger go through one method, `Diagnostics.checkQuota`, so they persist (`Prefs.setQuota`)
and report identically; the row also renders the **last stored** values without a network call
("Not checked yet." until the first check).

**The check is remotely triggerable and answers back**, which is what a script needs:

```bash
adb shell am broadcast --include-stopped-packages \
    -a dev.rorar.plezyupdater.action.DIAGNOSTICS
# Broadcast completed: result=-1, data="status=ok;remaining=59;limit=60;reset_ms=…;checked_ms=…;device=fire_tv;tv=1;touch=0"
```

`DiagnosticsReceiver` is **exported** (so a shell broadcast reaches it) but guarded by
`android.permission.DUMP`, which the adb shell holds and ordinary apps do not. The answer travels in
the ordered-broadcast **result data** — `setResultCode(-1)` + `setResultData(report)`, printed by
`am broadcast` as `data="…"` — because that is the one channel a script can read without `run-as`.
The report is a **quote-free, space-free** `key=value;…` string (`status=ok;remaining=…;limit=…;
reset_ms=…;checked_ms=…;device=…;tv=…;touch=…`, or `status=error;code=…` on failure) so it survives
that channel intact, and the same report is written to **`quota.txt`** in the app's external cache
dir (`getExternalCacheDir()`) as a second, permission-free way in. The check is a network call, so it
runs off the main thread through `goAsync()`; `finish()` always runs, even on failure, or the
broadcast would hang until the system times it out.

The trailing `device=…;tv=…;touch=…` is `Device.report()` — the [device
classification](#device-detection-fire-tv--android-tv--touch) riding along on the check that was
already happening, because it is otherwise invisible from the outside. It is read on the main thread
(cheap) so both the success and the failure branch carry it, and it is appended to the string
*before* it is written to `quota.txt`, so the two channels cannot drift apart.

The flow harness uses exactly this path: `run.py` fires the broadcast before the flows, prints the
quota, and **asserts** the device fields against `test/config.json`'s `expected_device`
([test/README](../test/README.md)). A missing report or a mismatched value fails the run.

## Settings

The switches are grouped into sections — **User settings**, **Advanced settings**, **Developer
settings**, then a bottom **Diagnostics** block (quota check, test toast, test notification) and an
**About** block (the About row plus the version line). The rows are plain platform `Switch` widgets,
so their focus colours are set explicitly: white text on the `#2F6E8F` highlight, instead of the
Fire OS default of black-on-blue, which is unreadable on the dark background. The quota and About
rows are action rows, not switches.

| Row | Section | Key | Default | Effect |
|---|---|---|---|---|
| Toast notifications | User | `toast_enabled` | on | Bottom-of-screen toasts |
| Notifications | User | `notification_enabled` | on | Fire TV notification-centre entry |
| Prompt to open Plezy | User | `open_prompt_enabled` | **on** | Offers to start Plezy after a successful install (A1) |
| Language | User | `app_language` | System default | Opens the language picker (below) |
| Check automatically | Advanced | `auto_check_enabled` | on | Periodic job + boot re-arm |
| Edit check interval… | Advanced | `interval_value` / `interval_unit` | 12 hours | Opens the interval picker (below) |
| Delete APK after installing | Advanced | `delete_apk_after_install` | **on** | Removes the downloaded APK once installed |
| Uninstall Plezy… | Advanced | — | — | Stock uninstaller, after a confirm (below) |
| Allow older versions | Developer | `allow_older_versions` | off | Shows **Choose version…** and sets the hidden downgrade flag |
| Check GitHub quota… | Diagnostics | `quota_*` | Not checked yet | Live `/rate_limit` check, also remotely triggerable (A3) |
| About Plezy Updater… | About | — | — | About screen; the version line sits inline below it (A7) |

Every message goes through `Notifier`, so a switched-off channel stays silent everywhere,
including the "Test toast" / "Test notification" buttons.

**Toggled rows align with button rows.** The rows that carry a `Switch` on the right and the rows
that are plain buttons used to have different horizontal paddings, so their labels did not line up.
Both now share one row helper (`Ui.switchRow` and the button rows take the same 11 dp vertical
padding and inset), so a column of labels reads as one column however the rows are built (A4).

After an install finishes (success *or* failure) the app **re-runs the check** automatically: if
the screen is alive it is refreshed in place (via the in-app `INSTALL_FINISHED` broadcast),
otherwise a background check runs and only speaks up through a notification.

## Check interval

The cadence is no longer fixed. The Settings row is labelled **Edit check interval…** (A6) — it is an
*action* that opens `IntervalActivity`, not a value to flip, so it reads like the other opening
rows; the picker's own title stays **Check interval**. It is a two-pane picker:
numbers `1…30` on the left, units **Hours / Days / Weeks / Months** on the right, with a live
"Every 3 days" preview at the top. Up/down changes the highlighted value, left/right switches
column, OK advances to **Save**; the remote's Back key cancels (there is deliberately no Back
button). Saving writes `interval_value`/`interval_unit`, re-arms the job
(`UpdateJob.schedule` reads `Prefs.intervalMs`), fires a confirmation toast and updates both
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

## Background checks: `JobScheduler` (`targetSdk` 26)

The periodic check used to be an `AlarmManager` alarm plus a short `IntentService`
(`UpdateAlarm` / `UpdateCheckService` / `CheckReceiver`). Raising `targetSdk` to **26** made that
illegal: from Android 8 the platform stops a service started from the background after ~1 minute
and treats an `IntentService` fired from an alarm as exactly that. The replacement is a
**`JobScheduler` job** (`UpdateJob` + `CheckJobService`), chosen over `WorkManager` because it is
platform API — no AndroidX, no resource merging — and because it exists since **API 21**, so the
API 25 stick and any newer Android run the *same* code path (nothing here is behind a
`VERSION.SDK_INT` guard; the whole point was to avoid a second branch).

- **Two jobs, stable ids.** `2001` is the repeating check (`setPeriodic(Prefs.intervalMs)`); `2002`
  is a one-off "check soon" (`runNow`). A stable id means re-scheduling *replaces* the job instead
  of stacking a new one, so `schedule()` is idempotent and `cancel()` is a single call.
- **The auto-check switch is the master.** `MainActivity.onCreate`, the Settings switch and the
  interval picker call `UpdateJob.schedule` / `cancel`; `BootReceiver` re-arms and asks for one
  immediate check. The one-off job carries `EXTRA_MANUAL` so a "check now" that the user
  effectively asked for still runs when auto-check is off — the same distinction the old
  `UpdateCheckService.start(c, true)` made.
- **Constraints replace the delay.** The old alarm fired a fixed 2 minutes after boot; the job sets
  `NETWORK_TYPE_ANY` and lets the platform hold it until the TV actually has connectivity.
- **No channels below 26.** `Notifier.ensureChannel` creates the `"updates"` channel only when
  `SDK_INT >= O` and posts through `new Notification.Builder(app, CHANNEL_ID)`; on API 25 it uses
  the legacy `new Notification.Builder(app)` and does nothing else, so the stick is unchanged.

Two behaviour differences worth knowing:

- **A fresh job runs almost immediately.** On this API 25 build a newly scheduled periodic job runs
  once within a few seconds, then settles into its interval — so a launch does a foreground check
  (`checkForUpdates(false)`) *and* a background one. Harmless (the check is unmetered and cheap),
  and it means a `schedule()` from Settings or the interval picker triggers a check straight away.
- **A job does not wake a sleeping TV** the way the old `RTC_WAKEUP` alarm could. The check runs
  while the TV is on; the UI wording ("Runs while the TV is on", "Checks every 12 hours while the
  TV is on") already says so.

**Verified on a newer platform.** The API 26+ branches this stick cannot reach are exercised on the
emulator lane ([`test/README.md`](../test/README.md#emulator-lane)): on `tv30` (Android TV, API 30)
and `phone34` (API 34) the channel is created (`dumpsys notification` shows the `updates` channel
and the notification on it), `Notifier.ensureChannel` is a no-op-safe call on every post, and the
`JobScheduler` job runs under the background-execution limits the `targetSdk` 26 bump was about.
`phone34` additionally runs the whole Plezy flow, which is where the Android 12+ downgrade race
([Finding 5](#finding-5--the-uninstall-handshake-raced-the-platform-on-android-12)) was found.

## Staying under GitHub's rate limit

GitHub's REST API is **metered**: 60 requests per hour for an unauthenticated client, per IP. The
updater spends one on every screen and every boot, so a burst of checks (and the flow tests, which
open the picker and re-check several times) can exhaust the hour and leave the picker showing
`GitHub API returned HTTP 403`. The fix is to stop treating the API as the only source.

**The unmetered web endpoints are now the primary source.** `github.com/edde746/plezy/releases/latest`
is a `302` to `/releases/tag/<tag>`, and `releases.atom` is the release feed — both on `github.com`,
not `api.github.com`, so **neither counts against the API quota**. The latest tag comes from the
redirect's `Location`; the notes come from the Atom entry for that tag (its HTML is turned into the
Markdown subset `Markdown` renders by `Html.toMarkdown`); and the asset URL is built from the tag
and this device's ABI (`…/releases/download/<tag>/plezy-android-<abi>.tar.gz`, see
[below](#which-build-for-this-device-b2)), because Plezy ships one tarball per ABI on every release.
`api.github.com` is only the fallback.

The API calls that remain — the version picker, which wants 30 entries and Markdown notes — are
**conditional**: `PlezyRepo` stores the `ETag` and body per URL in `HttpCache` and sends
`If-None-Match`, so an unchanged feed is answered with a `304` that is served from disk and, per
GitHub, **does not count against the limit**. A `LATEST_TTL_MS` (15 min) cache in `Prefs` collapses
the launch/resume/boot burst into one request.

When the quota *is* spent and the web path also fails, the check no longer shows a bare `403`. The
last version seen is persisted (`Prefs.cachedLatest*`), `PlezyRepo` throws a
`RateLimitedException` carrying the reset time, and the main screen keeps the version with a muted
note — *"GitHub's hourly request limit is reached. Trying again after 12:08."* — instead of losing
the status. The same calm wording reaches the picker's error line.

The behaviour is verified against a **spent** quota: with `remaining: 0/60`, the main screen reports
*Plezy is up to date* and the Spanish `install` flow passes end to end — see
[test/README](../test/README.md).

## Which build for this device (B2)

Plezy publishes one Android tarball **per ABI** (`plezy-android-arm64-v8a.tar.gz`,
`plezy-android-armeabi-v7a.tar.gz`, `plezy-android-x86_64.tar.gz`), and the updater used to name
the 32-bit one unconditionally. `PlezyRepo.assetName()` now derives the file name from the device:

```java
for (String abi : Build.SUPPORTED_ABIS)        // best ABI first
    for (String known : ASSET_ABIS)            // arm64-v8a, armeabi-v7a, x86_64
        if (known.equals(abi)) return assetNameFor(known);
return assetNameFor("armeabi-v7a");            // nothing matched: name the 32-bit build
```

So the same APK picks the 64-bit tarball on a modern Android TV / phone and the 32-bit one on this
stick (`abilist = armeabi-v7a, armeabi` → `armeabi-v7a`). The URL is **derived from the tag and the
ABI on every use and never cached** — an earlier version stored the asset URL in `Prefs`, which
would have pinned a device to the wrong ABI after the setting was written once; every
`ANDROID_ASSET` use now goes through `assetName()`. When nothing matches, the 32-bit name is used
deliberately so the verify step rejects it with a readable message rather than the app silently
downloading nothing.

The verify step was generalised to match. `ApkInstaller.hasSupportedAbi()` **passes an APK with no
`lib/` directory at all** (a pure-Java build is fine on any device) and otherwise accepts the APK
when **any** of its `lib/<abi>` entries is in the device's `Build.SUPPORTED_ABIS` — not just ARM.
A rejection now names the device's ABIs
(`err_no_native_libs`: *"APK has no native libraries for this device (armeabi-v7a, armeabi)"*)
instead of the old ARM-only wording.

> The `arm64-v8a` and `x86_64` **signer hashes are verified** off-device (2026-11-16): both tarballs
> extract to an APK signed with the same certificate as the 32-bit one
> (`903b862c…316e72`, `CN=Edvard Wikhall, O=edde746`) and carry `lib/arm64-v8a` / `lib/x86_64`. All
> three installs are now exercised end to end: `armeabi-v7a` on every stick install, `x86_64` on the
> `phone34` emulator (abilist `x86_64,arm64-v8a` → `plezy-android-x86_64.tar.gz`), and **`arm64-v8a`
> on the real arm64 phone** (`phone35`, abilist `arm64-v8a,…` → `plezy-android-arm64-v8a.tar.gz`,
> `dumpsys package com.edde746.plezy` afterwards reports `primaryCpuAbi=arm64-v8a`). Only the
> `err_no_native_libs` branch stays logic-only.

## Device detection (Fire TV / Android TV / touch)

The app used to assume "a TV with a D-pad" everywhere. It now answers *what am I running on?* in one
place, so a caller can adapt instead of re-deriving it from a pile of feature flags.

Two classes, split so the decision can be tested without a device:

- **`DeviceKind`** — the decision, as a pure enum (`FIRE_TV`, `ANDROID_TV`, `TOUCH`, `OTHER`) with
  **no `android.*` import at all**. It takes a plain `Signals` bundle and returns one value, so
  `test/jvm/DeviceKindTest.java` can exercise every branch on the JVM
  ([JVM tests](#jvm-tests-devicekind)).
- **`Device`** — the Android side. Reads `Build.MANUFACTURER` / `MODEL`, three `PackageManager`
  features, the `Configuration` and `UiModeManager` once, hands them to `DeviceKind.classify`, and
  exposes `kind()`, `isFireTv()`, `isAndroidTv()`, `isTv()`, `isTouch()`, `report()`.

The signals are a **ladder with a fixed precedence — Fire TV > Android TV > touch > other** — not
independent flags, so a device that reports several signals (an Android TV with a touchscreen) is
still exactly one kind, with the more specific one winning:

| Signal | Fire TV stick here | Fire tablet | Android TV box | Phone |
|---|---|---|---|---|
| `MANUFACTURER` / `MODEL` | `Amazon` / `AFTMM` | `Amazon` / `KFTRWI` | `Google` / `sabrina` | `samsung` / `SM-…` |
| `amazon.hardware.fire_tv` | ✔ | ✘ | ✘ | ✘ |
| `android.software.leanback` | ✔ | ✘ | ✔ | ✘ |
| `android.hardware.type.television` | ✔ | ✘ | ✔ | ✘ |
| `UiModeManager` = `TELEVISION` | ✔ | ✘ | ✔ | ✘ |
| `Configuration.touchscreen` = `FINGER` | ✘ | ✔ | ✘ | ✔ |
| → `DeviceKind` | **`FIRE_TV`** | `TOUCH` | `ANDROID_TV` | `TOUCH` |

Three decisions in there are worth stating, because each was a wrong answer first:

- **`isTouch()` is the touchscreen *resource qualifier*, not the `android.hardware.touchscreen`
  feature flag.** It is literally `Configuration.touchscreen == TOUCHSCREEN_FINGER` — the same field
  Android matches `res/values-finger/` on ([Device-aware wording](#device-aware-wording)).
  The feature flag is tempting (it is *absent* on this stick, so it "works") but it is true on
  devices where the resource system would not pick `-finger`, which is exactly the drift this
  avoids. Two answers, one signal.
- **`UI_MODE_TYPE_TELEVISION` is checked as well as the two features.** Some AOSP boxes carry
  neither `leanback` nor `type.television` but do set the TV UI mode; without it they fall to
  `OTHER`.
- **The `AFT…` model prefix is the fallback for Fire TV, and it is null-guarded.** It catches a box
  that does not advertise `amazon.hardware.fire_tv`. A Fire **tablet** is also Amazon but a `KF…`
  model, so it correctly lands on `TOUCH` rather than `FIRE_TV`.

Nothing is cached: the signals are three feature lookups, one system-service call and one config
read, and a cache would have to be invalidated on a configuration change.

**The first caller is the diagnostics report** — `device=fire_tv;tv=1;touch=0` rides along on the
A3 broadcast ([Diagnostics](#diagnostics-and-the-remote-quota-check-a3)), because the classification
is otherwise invisible from outside the app. That is also what the flow harness asserts, so a
regression here fails a run rather than going unnoticed:

```
data="status=ok;remaining=53;limit=60;reset_ms=…;checked_ms=…;device=fire_tv;tv=1;touch=0"
```

**What it unlocks.** The notification wording (below), a touch-friendly layout factor, and possibly
a different default interval (a phone is on more often than a TV). The layout half is
[roadmap item 3](11-roadmap.md#3-android-tv--phone-compatibility) and is not done.

### JVM tests (`DeviceKind`)

Because `DeviceKind` touches no Android API, it is compiled against the plain JDK and tested
directly — 25 cases covering the Fire TV / Android TV / touch / other branches, the precedence
between them, null safety and the report slugs:

```bash
test/jvm/run.sh     # javac DeviceKind.java + DeviceKindTest.java, then java … DeviceKindTest
```

That is the point of the split: the Android TV and phone branches **cannot be produced on this
stick**, so without this they would be untested logic. `Device` only reads the platform; the
decision is tested. Exit code is 0/1, so it can be a CI step.

## Language

The app follows the Fire TV's language by default and can be overridden per app. The choice lives
in one preference, `app_language` (`Prefs.appLanguage` / `setAppLanguage`), where the empty string
means **System default**.

Everything the user can read is a string resource: `res/values/strings.xml` is the English default
and `values-de|fr|es/strings.xml` override it. There are no user-visible Java literals left — even
the low-level download/verify errors (`ApkInstaller`, `PlezyRepo`) and the installer-result wording
(`InstallResultReceiver.describe`) take a `Context` and resolve a string, and the notification is
built with the picked language. Units are `<plurals>`, so the interval preview reads "1 hour" /
"2 hours" ("1 Stunde" / "2 Stunden") rather than "1 hour(s)".

The override is applied in each activity's `attachBaseContext`:

```java
@Override protected void attachBaseContext(Context base) {
    super.attachBaseContext(LocaleHelper.wrap(base));
}
```

`LocaleHelper.wrap()` reads the stored code, and when it is set, returns a context whose
`Configuration` (and default `Locale`) carry it; `getString` then resolves to that language.
`LocaleHelper` also owns the language list — `CODES`, the native names and the flag drawables — so
the picker and the resources cannot drift apart. `systemCode()` reads the device language from
`Resources.getSystem()` for the "System default (Deutsch)" summary line.

**Settings → Language** opens `LanguageActivity`: **System default** followed by English, Deutsch,
Français and Español, each by its own name and with a bundled flag. The focus starts on the current
choice (`LocaleHelper.SYSTEM` → row 1, otherwise `indexOf(code) + 2`, because child 0 is the title).
Picking one stores the code and returns to the app, which rebuilds in the new language. The flags
are `lipis/flag-icons` 4×3 SVGs rendered to PNGs per density (plus a drawn globe for **System
default**); they ship in the APK, so the picker needs no network. The flags are drawn in a 40×30 dp
box so their labels line up; the globe (`ic_globe`) shares that viewport but draws its circle as a
~30×30 **square** centred in it (A5), so the "System default" glyph is round rather than stretched
to 4:3 like the flags.

### Build note — resources are linked positionally

`build.sh` compiles `res/` to `build/res.zip` and hands it to `aapt2 link` **positionally**, not
with `-R`:

```sh
"$BT/aapt2" link -o build/unsigned.apk -I "$AJ" \
  --manifest AndroidManifest.xml build/res.zip --java build/gen …
```

`-R` marks the archive as an *overlay*, and aapt2 then rejects `values-de|fr|es` with
*"resource … does not override an existing resource"* — it expects an overlay to replace, not a
locale variant to merge. Positionally, the per-locale configs merge with `values/` as intended.

### Device-aware wording

Two strings were TV-only and are now picked by the device — **without a line of Java**, because the
touchscreen is a resource qualifier:

| String | `values/` (no touch) | `values-finger/` (touch) |
|---|---|---|
| `notif_available_text` | `Press OK to open` | `Tap to open` |
| `set_test_notification_msg` | `Test notification — press OK to open` | `Test notification — tap to open` |
| `picker_hint` | `…press OK to install the highlighted version.` | `…tap to install the highlighted version.` |

Android picks `values-finger/` when `Configuration.touchscreen == TOUCHSCREEN_FINGER`, which is the
*same* field `Device.isTouch()` reads — so the wording and the detection cannot disagree. Per-locale
variants repeat the locale (`values-de-finger/`, `values-fr-finger/`, `values-es-finger/`), because
the locale qualifier outranks the touchscreen one; each is a pure **delta** over its locale file.

Three strings named the device where a generic word reads fine everywhere, so they were reworded
rather than duplicated four ways: `set_notification_desc` ("Fire TV notification center" →
"notification area"), `err_not_enough_space` ("on the Fire TV" → "on the device") and
`set_delete_desc` ("save space on the stick" → "save space"; the other locales never had the noun).
The notification text also said *"Press OK to **install**"* — but the notification **opens the
updater**, it does not install — so it now says *"Press OK to **open**"*.

Strings that describe *behaviour* rather than input — "runs while the TV is on", "after the TV
boots", "confirm the install on the TV" — are deliberately left TV-first: the **layout** is still
TV-first ([roadmap item 3](11-roadmap.md#3-android-tv--phone-compatibility)), and rewording those for
a phone whose screens are still 48 dp of padding would be the dishonest half of the job.

`test/check_strings.py` guards all of it, with no device:

```bash
python3 test/check_strings.py
```

It fails when a locale is missing a key that `values/` has (bar the handful that are deliberately
identical in every language), when a `-finger` file *invents* a key instead of overriding one, when
a translation drops or renumbers a `%1$s` placeholder, or when the `test/strings/app.<lang>.json`
harness labels disagree on their keys. It also runs as the pre-flight step of `test/run.py`, before
any adb traffic.

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
  version; pressing right moves into the notes pane to scroll a long changelog. **OK installs the
  highlighted version right here** (see [Install routine](#install-routine-shared)) — there is no
  detour back to the main screen just to press Install.
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

## Icons and artwork

Every glyph the app draws is **original and hand-written**, not pulled from an icon pack:

- **In-app glyphs** are `<vector>` drawables in `app/res/drawable/ic_*.xml` (24 dp viewport,
  ~2.2 stroke, round caps). `Ui.icon(c, resId, color)` loads and tints one;
  `Ui.stateIcon(c, resId)` wraps three tinted copies in a `StateListDrawable` so a row's glyph
  goes white on the focus fill, dimmed when disabled, body colour otherwise — the same states as
  `Ui.rowTextColors()`. `Ui.leadingIcon(view, glyph, sizeDp)` attaches it with the right bounds
  and spacing. The main screen uses `ic_download` / `ic_news` / `ic_refresh` / `ic_versions` /
  `ic_settings` on the action rows and a per-state glyph beside the status headline
  (`ic_check` / `ic_up` / `ic_warning` / `ic_error` / `ic_info` / `ic_down` / `ic_refresh`).
  See [10](10-tv-ui-design.md#icons) for the full table.
- **The launcher icon and TV banner** are PNGs generated by [`design/app_icon.py`](../design/README.md):
  a white "up + tray" mark on a rounded accent tile, and a 16:9 banner with the tile and the name.
  They are committed at every density, so building needs no Python. The manifest points
  `android:icon` at `@mipmap/ic_launcher` and `android:banner` at `@drawable/tv_banner`.
- **No Plezy assets are used.** Plezy is GPL-3.0, which covers its code — not its logo or name —
  and reusing them would imply an affiliation that does not exist. The updater has its own mark.
- **The notification small icon** (`ic_stat`) is a solid-white up arrow on transparent: Android
  draws a notification icon as an alpha-only silhouette, so it must not rely on colour.

`design/preview_vectors.py` rasterises the shipped `ic_*.xml` for a human to look at, so the vector
XML stays the single source of truth for the glyph paths.

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
Instead the app takes the uninstall-then-install route — the only one Android 7.1 allows, and the
one it uses on every API level so there is a single code path ([Finding
5](#finding-5--the-uninstall-handshake-raced-the-platform-on-android-12)):

1. **Download and verify first**, while Plezy is still installed, so a failed download never
   leaves the user with no Plezy. The verified APK is cached.
2. **Confirm** with a clear data-loss warning ("Plezy will be uninstalled first — its data
   (servers, settings, downloads) is erased — and then 2.25.0 installed").
3. **Uninstall** via `ACTION_DELETE`.
4. On `ACTION_PACKAGE_REMOVED` (or `onResume`, whichever comes first), **install** the cached APK
   through the normal `PackageInstaller` session → the usual install dialog. Which of the two wins
   is platform-dependent — on Android 12+ `onResume` runs first ([Finding
   5](#finding-5--the-uninstall-handshake-raced-the-platform-on-android-12)), so the flow is given
   the fresh installed-version read rather than a cached one. The removal must be a real uninstall —
   a replace also broadcasts `REMOVED`, so `InstallFlow` ignores anything flagged with
   `EXTRA_REPLACING` ([Finding 4](#finding-4--replacing-a-package-also-broadcasts-action_package_removed)).
   All of this lives in `InstallFlow`, so the picker can start a downgrade too.
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

## Flow tests (`test/`)

The five flows below are also written down as runnable code in [`test/`](../test/README.md). The
harness drives the real stick over ADB the way a person with the remote does — it reads the screen
with `uiautomator dump`, finds the labelled button, and taps its centre — so the tests double as a
record of *what to click* for each flow:

| Flow | What it drives |
|---|---|
| `install` | open **Choose version…**, install the highlighted (latest) release |
| `downgrade` | pick an older release and drive the uninstall-then-install handshake |
| `upgrade` | from the Main View, install the latest again (A2) — an in-place replace |
| `uninstall` | **Settings → Advanced → Uninstall Plezy…** plus the stock uninstaller |
| `clean-install` | install from the main screen when Plezy is not installed |

```bash
python3 test/run.py install                    # forward path (non-destructive)
python3 test/run.py all --yes                   # the whole sequence (five flows)
python3 test/run.py install --app-lang de       # against a German build
```

`all` runs them in that order and ends back on "latest installed". Every run also fires the
diagnostics broadcast first, so the quota check (A3) is exercised on each invocation — and its
report is now **asserted** for the device classification
([Device detection](#device-detection-fire-tv--android-tv--touch)). A **static string-parity check**
(`test/check_strings.py`) runs before any adb traffic, so a locale that drifted from `values/` cannot
make a flow look for a label that is gone. Both are device-free; the `DeviceKind` decision has its
own JVM tests ([above](#jvm-tests-devicekind)).

**No label is hard-coded.** The app's own strings come from `test/strings/app.<lang>.json` (selected
with `--app-lang`) and the stock installer/uninstaller strings from `test/strings/system.<lang>.json`
(selected from the device locale with `--system-lang`, auto-detected). All four app languages
(`en`/`de`/`fr`/`es`) ship as complete files, mirroring the `res/values*/strings.xml` they stand for;
the same flows therefore run against any app language and any device locale, and adding a language
is a matter of copying a pair of JSON files. A platform whose installer is worded differently points
at a `system.<lang>.<variant>.json` from its profile (`phone34` → `system.en.api34.json`, for the
Material installer's `Update` button); the variant must carry exactly the base file's keys, which
`check_strings.py` enforces. Labels are matched **case-insensitively**, because `Theme.Material`
upper-cases dialog buttons on API 31+ while Fire OS renders them as written.

**Emulator lane.** The same harness runs on emulators, not just the stick:
[`test/emulator.sh`](../test/README.md#emulator-lane) boots the AVDs headless and `run.py --profile
<name>` selects one from `test/config.json`. `phone34` (x86_64, API 34) runs the whole flow and
enforces the `targetSdk` 31 rules; `tv25` / `tv30` (x86, Android TV) cover the device-detection and
API 26+ branches but only updater-side, because Plezy has no 32-bit `x86` build. Only one emulator
runs at a time.

The **`upgrade` flow (A2)** is the one the user asked for after the downgrade work: it proves the
**Main View** path, not the picker. It goes back with the remote's BACK key — deliberately not a
force-stop/relaunch — only while the updater itself is in front (`_back_to_main()`), so a mis-timed
press cannot escape into the Fire TV launcher. It then presses **Install <version>** on the
dashboard and proves a *replace* from the package identity: `firstInstallTime` must not move while
`lastUpdateTime` must, because a fresh install would move both. If Plezy is not already on the older
release the flow downgrades first, so `upgrade` also runs on its own.

## Verified on hardware (2026-11-14/18)

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

Icon/artwork phase, same stick (2026-11-14):

| Step | Result |
|---|---|
| Title glyph | 28 dp up arrow in `#3F86AD` sampled beside "Plezy Updater" (334 ink px in the icon column) |
| Status glyph | 26 dp green check (`#68BE8A`) beside "Plezy is up to date"; 0 px of the text colour in that column, so it is the glyph's colour, not the label's |
| Action glyphs | download / circular-arrow / layers / sliders present in every row's left inset, in the row's own state colour: dimmed on the disabled **Install**, white on the focused **Check**, body colour on **Choose version** / **Settings** |
| Glyph shapes | each glyph crop printed as ASCII and matched its design (down-arrow-into-tray, circular arrow with a top gap, stacked layers, sliders) |
| App icon + banner | packaged at all densities and declared: `aapt2 dump badging` of the **installed** APK reports `application-icon-{160,240,320,480,640}` and `banner='…/tv_banner.png'` |
| Notification icon | `ic_stat` reworked to an alpha-only white up arrow |

Install-routine phase, same stick (2026-11-15):

| Step | Result |
|---|---|
| Picker opens | `Choose version` with title glyph, hint "…press OK to install the highlighted version.", the version list, the release-notes pane and a progress line |
| Direct install from the picker | OK on `2.26.0` ran download → "Unpacking…" → "Opening the installer…" → `com.android.packageinstaller/.PackageInstallerActivity` opened **without leaving the picker** |
| Confirm on the TV | the harness clicked **INSTALLIEREN**; `lastUpdateTime` jumped, focus returned to the picker |
| Picker reports the outcome | progress line became "Installed 2.26.0."; hint stayed "Installed: 2.26.0" and the row `2.26.0 (installed)` — **no "Installed: none" flicker** (Finding 4) |
| Nothing lost | `dumpsys package com.edde746.plezy` = `2.26.0` / `versionCode 1160` before and after; `pm path` unchanged throughout |
| **Downgrade from the picker** | OK on `2.25.0` → download → "Unpacking…" → app dialog **"Downgrade to 2.25.0?"** → **Uninstall & install** → system `UninstallerActivity` → **OK** → flow auto-opened the installer for the cached APK → **INSTALLIEREN** → Plezy `2.25.0` / `versionCode 1157`; picker reported "Installed 2.25.0." |
| **Upgrade / replacement** | with `2.25.0` installed, OK on `2.26.0` → **Update** variant of the dialog → **INSTALLIEREN** → Plezy `2.26.0` / `versionCode 1160`; hint never dropped to *Installed: none* (confirms the `EXTRA_REPLACING` guard, [Finding 4](#finding-4--replacing-a-package-also-broadcasts-action_package_removed)) |
| **Uninstall (from Settings)** | **Advanced → Uninstall Plezy…** → app dialog "Uninstall Plezy?" → **Uninstall** → system `UninstallerActivity` → **OK** → `pm path` empty; the row went `enabled=false` and its summary flipped to "Plezy is not installed." |
| **Clean install (main screen)** | dashboard showed "Plezy is not installed" / "Latest upstream: 2.26.0" / **Install 2.26.0**; OK → fresh-install dialog ("Möchtest du diese App installieren?") → **INSTALLIEREN** → Plezy `2.26.0`, `lastUpdateTime` fresh; dashboard refreshed to "Plezy is up to date" |
| **Delete-after-install** | with the setting on, `/data` free space rose across each run (e.g. `939 932` → `960 580` KB after the downgrade), so the downloaded tarball/APK was removed once the install succeeded |

Rate-limit phase, same stick (2026-11-15), run **with the quota spent**:

| Step | Result |
|---|---|
| Quota | `curl https://api.github.com/rate_limit` → `remaining: 0/60`, `used: 60`, both before and after the run |
| Main screen at 0 quota | the unmetered `releases/latest` `302` + Atom feed filled it in: "Plezy is up to date" / "Installed 2.26.0 · latest 2.26.0" — **no HTTP 403** |
| Picker at 0 quota | **Choose version** listed the releases (from the Atom feed) and installed `2.26.0` — the exact path that failed with *"GitHub API returned HTTP 403"* before |
| Spanish `install` flow | `python3 test/run.py install --app-lang es` → **1/1 PASS** at `0/60` (the failing run from the previous batch) |
| Atom notes render | `Html.toMarkdown` run on the real `2.26.0` Atom `<content>`: `## New features`, `- ` bullets, no leftover tags — compiled and executed from the shipped `Html.java` |

Round-1 features (A1–A7, B2), same stick (2026-11-16), updater `0.2.0` / `versionCode 2`:

| Step | Result |
|---|---|
| Settings row alignment (A4) | toggled rows and button rows share one label inset, so a column of Settings labels lines up |
| Globe not stretched (A5) | `ic_globe` keeps the 40×30 flag box but draws its circle as a ~30×30 square; the "System default" glyph is round, not 4:3 |
| Interval row wording (A6) | the row reads **Edit check interval…**; the picker title stays **Check interval** |
| About + version line (A7) | **Settings → About Plezy Updater…** shows "Plezy Updater 0.2.0 (build 2)"; the same line is inline at the bottom of Settings |
| Quota — Settings row (A3) | row read "Not checked yet." then, after OK, "59 of 60 requests left · resets 1:21 PM." |
| Quota — broadcast (A3) | `am broadcast --include-stopped-packages -a …action.DIAGNOSTICS` → `result=-1, data="status=ok;remaining=59;limit=60;reset_ms=…;checked_ms=…"`; `quota.txt` written to the external cache dir |
| Open prompt (A1) | after **every** successful install the "Open Plezy?" dialog appeared, focus on **Not now**; the toggle suppresses it |
| Per-ABI asset (B2) | `abilist = armeabi-v7a, armeabi` → the app fetched `plezy-android-armeabi-v7a.tar.gz`; a fresh install through it **replaced** Plezy (`firstInstallTime` unchanged, `lastUpdateTime` bumped) |
| `upgrade` flow (A2) | `python3 test/run.py all --yes` → **5/5 PASS**; `upgrade` proved an in-place replace (`firstInstallTime 2026-11-16 12:32:49` unchanged, `lastUpdateTime 12:32:49 → 12:33:28`) |
| All four languages | English `all` **5/5**, German `all` **5/5**, French `install` **1/1**, Spanish `install` **1/1** |

Device detection + device-aware wording, same stick (2026-11-17), updater `0.4.0` / `versionCode 4`
(the release APK, `sha256 10c334d1…`):

| Step | Result |
|---|---|
| English `all` (final APK) | **5/5 PASS** — install 52s, downgrade 69s, upgrade 32s, uninstall 72s, clean-install 44s |
| In-place upgrade | `firstInstallTime 2026-11-17 14:00:47` **unchanged**, `lastUpdateTime 14:01:27` moved — the 2.25.0 → 2.26.0 replace did not reinstall |
| Device report over the broadcast | `data="status=ok;remaining=51;limit=60;reset_ms=…;checked_ms=…;device=fire_tv;tv=1;touch=0"`; `quota.txt` carries the identical suffix |
| Device assertion in the harness | `✓ device: fire_tv (tv=1, touch=0)`; a missing report or a mismatch now **fails** the run (`config.json` → `expected_device`) |
| String parity | `test/check_strings.py` → `values/: 181   values-de/fr/es: 175   +finger: 3 keys per locale`; also the `run.py` pre-flight |
| `DeviceKind` JVM tests | `test/jvm/run.sh` → 25/25 pass (Fire TV / Android TV / touch / other, the precedence, null safety, the slugs) |
| Resource variants packaged | `aapt2 dump resources` lists `(finger)` / `(de-finger)` / `(fr-finger)` / `(es-finger)` for `notif_available_text`, `set_test_notification_msg`, `picker_hint`; the stick itself resolves `values/` (`am get-config` → `touchscreen=notouch`) |
| German / French / Spanish smokes | `install --app-lang de|fr|es` → **1/1 each** (51/52/54s), then English restored — 1/1 |
| German TV notification wording | posted the test notification from Settings → **Benachrichtigung testen**; `dumpsys notification --noredact` → `android.text=String (Test-Benachrichtigung — OK zum Öffnen drücken)` — the corrected *open*, not *install*, on the stick |
| Self-update `0.3.0` → `0.4.0` | the stick was reset to `v0.3.0` (API 25 refuses an in-place downgrade of a release build — [Finding 2](#finding-2--android-71-refuses-downgrades-of-release-builds)), then **Settings → About → "Plezy Updater aktualisieren…"** with the D-pad: the check found `0.4.0`, downloaded and verified it, and the stock installer showed an **update** dialog ("Bestehende Daten gehen nicht verloren"). After **INSTALLIEREN**: `versionCode 3 → 4`, `firstInstallTime 2026-11-17 14:15:33` **unchanged**, `lastUpdateTime 14:19:29`; the relaunched About row reads `Plezy Updater 0.4.0 (Build 4)` |

`targetSdk` 26 (`0.5.0`), same stick (2026-11-17), updater `0.5.0` / `versionCode 5`
(APK 311 018 B, `sha256 22f59dc0…`):

| Step | Result |
|---|---|
| Build identity | `dumpsys package dev.rorar.plezyupdater` → `versionCode=5 minSdk=25 targetSdk=26`, `versionName=0.5.0`; `aapt2 dump badging` → `targetSdkVersion:'26'`; the dex carries `CheckJobService` / `UpdateJob` and no removed class |
| Periodic job on API 25 | `dumpsys jobscheduler` → `JOB #u0a257/2001 … .CheckJobService`, `PERIODIC: interval=+12h0m0s0ms flex=+12h0m0s0ms`, `Network type: 1` |
| The job actually checks | `cmd jobscheduler run -f dev.rorar.plezyupdater 2001` → logcat `background check starting (manual=false)`; the next window advanced one full period (`jobFinished` ran) |
| Auto-check OFF / ON | switch off → the job disappears from `dumpsys jobscheduler` (and the immediate check stops); switch on → `JOB #…/2001` re-appears |
| Interval change re-arms | set to **1 hour**: `PERIODIC: interval=+1h0m0s0ms`, logcat `periodic check armed every 3600000 ms`; restored to 12 h → `+12h0m0s0ms` |
| **One-off job 2002 (`runNow`)** | the app was **force-stopped while the stock installer was up** (the only way to evict it — `am kill` / `am kill-all` leave a process that still owns a task alone). Confirming the install delivered the session result into a *fresh* process, which logged `background check starting (manual=true)` |
| …with auto-check **off** | repeated with `Check automatically` off (no job 2001 at all): the one-off path still ran `manual=true` — it is not silently skipped by the switch |
| API 25 notification unchanged | Settings → **Test notification**: `dumpsys notification --noredact` → `android.title=String (Plezy Updater)`, `android.text=String (Test notification — press OK to open)`; no crash, legacy `Notification.Builder` path |
| Quota / device report | every flow run asserts `data="status=ok;…;device=fire_tv;tv=1;touch=0"` — unchanged |
| English `all` | **5/5 PASS** — install 58s, downgrade 66s, upgrade 34s, uninstall 72s, clean-install 43s |
| German / French / Spanish smokes | `install --app-lang de\|fr\|es` → **1/1 each** (61/54/74s), then English restored |
| **Not verifiable on the stick** | the API 26+ branches: `NotificationChannel` creation and the background-execution-limit avoidance a job buys. These are now covered by the emulator lane below |

`targetSdk` 31 (`0.6.0`), same stick (2026-11-18), updater `0.6.0` / `versionCode 6`
(APK 311 018 B, `sha256 8f718827…`):

| Step | Result |
|---|---|
| Build identity | `dumpsys package dev.rorar.plezyupdater` → `versionCode=6 minSdk=25 targetSdk=31`, `versionName=0.6.0` |
| Quota / device report | `data="status=ok;…;device=fire_tv;tv=1;touch=0"` — unchanged on API 25 |
| English `all` | **5/5 PASS** — install 55s, downgrade 69s, upgrade 34s, uninstall 71s, clean-install 54s |
| In-place upgrade | `firstInstallTime 2026-11-18 16:50:50` **unchanged**, `lastUpdateTime 16:51:27` moved — the 2.25.0 → 2.26.0 replace did not reinstall |
| Downgrade on API 25 | the removal broadcast still arrives *before* `onResume` here, so the new re-check is never even scheduled: **OK** → **INSTALLIEREN** immediately, Plezy `2.25.0` — the API 25 behaviour is byte-for-byte unchanged ([Finding 5](#finding-5--the-uninstall-handshake-raced-the-platform-on-android-12)) |
| "Open Plezy?" **Open** | the `clean-install` flow now presses **Open** (not just **Not now**) and asserts Plezy's `MainActivity` comes to the front: `✓ Open launched Plezy (Window{… com.edde746.plezy/.MainActivity})` |

### Emulator lane (2026-11-18), updater `0.6.0` / `versionCode 6`

A second and third platform, for the branches the API 25 stick cannot produce. `test/emulator.sh`
boots the AVDs headless and `run.py --profile` drives them
([`test/README.md`](../test/README.md#emulator-lane)); the real phone needs only `--profile phone35`.
The lane earned its keep immediately — it found the Android 12+ downgrade race
([Finding 5](#finding-5--the-uninstall-handshake-raced-the-platform-on-android-12)), then found it
*again* on the real phone where the emulator had passed, and caught the case-sensitivity the harness
had assumed away.

`phone34` (Google APIs phone, API 34 — the image that enforces the `targetSdk` 31 rules), full flow:

| Step | Result |
|---|---|
| Build identity | `dumpsys package dev.rorar.plezyupdater` → `versionCode=6 minSdk=25 targetSdk=31`, `versionName=0.6.0` |
| Device report | `data="…;device=touch;tv=0;touch=1"` — asserted against `config.json` |
| English `all` | **5/5 PASS** — install / downgrade / upgrade / uninstall / clean-install |
| In-place upgrade | `firstInstallTime` unchanged, `lastUpdateTime` moved — the replace did not reinstall |
| Downgrade (Android 12+) | this is where the Android 12+ ordering first bit: `onResume` ran **before** `ACTION_PACKAGE_REMOVED`, so a successful uninstall looked cancelled. The fix is [Finding 5](#finding-5--the-uninstall-handshake-raced-the-platform-on-android-12); the flow passes now |
| Installer labels | the Material installer's `install`/`update`/`cancel` strings pulled from its APK match `strings/system.en.api34.json` (`Install` / `Update` / `Cancel`) |

`tv30` (AOSP Android TV, API 30 — the first image here where the API 26+ branches are real):

| Step | Result |
|---|---|
| Device report | `data="…;device=android_tv;tv=1;touch=1"` — the `android_tv` kind the stick cannot produce, and a TV that *does* report a touchscreen |
| Channel created | `dumpsys notification --noredact` → `NotificationChannel{mId='updates', mName=Update notifications, mImportance=3}` |
| Notification on the channel | `NotificationRecord(pkg=dev.rorar.plezyupdater id=1001 … channel=updates)` |
| No trampoline | its `contentIntent` is `PendingIntentRecord{… startActivity …}` — an activity started directly, the shape Android 12 requires |
| Periodic job under the limits | `JOB #…/2001 … PERIODIC interval=+12h`, `Network type: INTERNET`, satisfied constraints include `BACKGROUND_NOT_RESTRICTED` / `WITHIN_QUOTA` — the background-execution machinery the `targetSdk` 26 bump is about |
| The job actually checks | `cmd jobscheduler run -f … 2001` → logcat `background check starting (manual=false)`, then `notification posted: Plezy 2.26.0 available` |

`tv25` (AOSP Android TV, API 25 — the stick's platform without Fire OS):

| Step | Result |
|---|---|
| Device report | `data="…;device=android_tv;tv=1;touch=0"` — classified `android_tv`, not `fire_tv`, on the same API level as the stick |
| Job armed | `JOB #…/2001 … PERIODIC interval=+12h0m0s0ms` |
| Installer labels | the Google installer's `install`/`cancel`/`ok` strings match `strings/system.en.json` (`Install` / `Cancel` / `OK`) |

The `tv25`/`tv30` profiles run **updater-side checks only**: Plezy ships no 32-bit `x86` build, so
the full Plezy flows cannot install on those images. `phone34` (x86_64) is the one that runs them.

### Real phone (2026-11-18), updater `0.6.0` / `versionCode 6`

The emulator lane proved the API 31 rules, but an emulator is not real hardware — and the biggest
remaining gap was **arm64**. A real phone closes both: an arm64 phone running a LineageOS 22.1
(Android 15 = **API 35**) build, touchscreen, `de-DE`. Its address and identity are not in the repo —
the profile names an environment variable (`PHONE_SERIAL`, see
[`test/README.md`](../test/README.md#device-profiles)) that lives only in the gitignored
`local.env`. It is driven as `--profile phone35`.

| Step | Result |
|---|---|
| Build identity | `dumpsys package dev.rorar.plezyupdater` → `versionCode=6 minSdk=25 targetSdk=31`, `versionName=0.6.0`; the phone reports `POST_NOTIFICATIONS` **not granted** (the API 33 item, below) |
| Device report | `data="…;device=touch;tv=0;touch=1"` — a real touch phone, asserted against `config.json` |
| **arm64 install** | abilist `arm64-v8a,…` → `assetName()` picks `plezy-android-arm64-v8a.tar.gz`; `dumpsys package com.edde746.plezy` afterwards → `primaryCpuAbi=arm64-v8a` — the 64-bit install branch, now verified on real hardware (B2) |
| English `all` | **5/5 PASS** — install 42s, downgrade 51s, upgrade 23s, uninstall 63s, clean-install 37s |
| German `all` | **5/5 PASS** — install 44s, downgrade 59s, upgrade 23s, uninstall 96s, clean-install 35s |
| French / Spanish smokes | `install --app-lang fr\|es` → **1/1 each** (40/41s) |
| In-place upgrade | `firstInstallTime 2026-11-18 16:38:45` **unchanged**, `lastUpdateTime 16:39:11` moved |
| **Downgrade (the real catch)** | the *first* fix was not enough here: a **fresh** `installedVersion` read was still `2.26.0` ~137 ms after a successful uninstall, so the downgrade was cancelled and no installer appeared. The bounded re-check fixed it — logcat `downgrade: Plezy still reads as 2.26.0 — re-checking…`, then `downgrade: Plezy is gone — installing`. This is [Finding 5](#finding-5--the-uninstall-handshake-raced-the-platform-on-android-12) |
| "Open Plezy?" **Open** | `clean-install` pressed **Open** and asserted Plezy's `MainActivity` came to the front (`✓ Open launched Plezy (…com.edde746.plezy/.MainActivity)`) |
| Installer labels | the AOSP PackageInstaller on Android 15 is German: **`Installieren`** (fresh) / **`Aktualisieren`** (update) / **`Trotzdem aktualisieren`** (update from a different installer) / **`Abbrechen`** — pulled from its APK and recorded in `strings/system.de.api34.json` |
| Destructive, and expected | the downgrade / uninstall / clean-install flows **erased Plezy's data** on the phone (the user accepted this); it was left back on `2.26.0` |

The `Trotzdem aktualisieren` button is a real finding: a sideloaded Plezy was installed by an earlier
installer, so an update from *this* one first asks "Soll diese App von … aktualisiert werden?" — a
**second** confirmation dialog the harness had never seen. `installer.confirm` / `installer.update_confirm`
may now each be a *list* of candidate labels, and `_confirm_system_install` taps whichever is on
screen, up to three dialogs, so a two-dialog platform needs no special case.

## Known limitations

- One remote confirmation per install (silent install is impossible; [06](06-own-updater-feasibility.md)).
- A downgrade costs two confirmations and **erases Plezy's data**; the app warns before starting
  ([Downgrade](#downgrade)).
- **Device detection and the touch wording are verified on real hardware now.** This stick produces
  the `FIRE_TV` / no-touch case; the Android TV branch (`tv25`/`tv30`) and the touch branch
  (`phone34`) are **device**-verified on the emulator lane, and the touch branch is also verified on
  a **real** touch phone (`phone35`, API 35) — see
  [above](#emulator-lane-2026-11-18-updater-060--versioncode-6). `test/jvm/run.sh` (25 cases) and
  `test/check_strings.py` still cover the decision logic and the `values-finger` wording off-device,
  and no real Android TV (only emulators) has been seen. The **touch layout** is still not adapted at
  all — the app is TV-first (32–48 dp padding, ≥ 50 dp rows, D-pad focus, two-pane pickers that move
  left/right) — so a phone gets TV-sized UI; `phone34`/`phone35` *drive* the flows over touch but that
  is not the same as a tuned touch layout. See
  [roadmap item 3](11-roadmap.md#3-android-tv--phone-compatibility).
- **The API 26+ notification-channel and job-scheduling branches are now device-verified.** The
  stick is API 25, so `Notifier.ensureChannel` and `new Notification.Builder(app, CHANNEL_ID)` never
  run there; `tv30` (API 30) shows the `updates` channel and the notification on it, and its job runs
  under the background-execution limits the bump is about
  ([above](#emulator-lane-2026-11-18-updater-060--versioncode-6)). The API 25 path is what the stick
  flows cover. See [Background checks](#background-checks-jobscheduler-targetsdk-26).
- **`POST_NOTIFICATIONS` is not granted on the API 35 phone.** The manifest declares it, but the
  updater targets 31, so the runtime permission is not requested and the platform denies it by
  default (`appops get … POST_NOTIFICATION` → `ignore`) — the notification is posted but not shown.
  That is the first item of the `targetSdk` 33 step, not a regression
  ([roadmap item 5](11-roadmap.md#5-targetsdk-bump-matrix)).
- **A job does not wake a sleeping TV.** The old `RTC_WAKEUP` alarm could; `JobScheduler` runs the
  check while the TV is on, which the UI already promises ("Runs while the TV is on"). Also, a newly
  scheduled periodic job runs once almost immediately on this build before settling into its
  interval, so a launch checks twice (foreground + background) — harmless, the check is unmetered.
- All **three install branches are now verified end to end**: `armeabi-v7a` on the stick, `x86_64`
  on the `phone34` emulator, and **`arm64-v8a` on the real `phone35`** (which then reports
  `primaryCpuAbi=arm64-v8a`). Only the `err_no_native_libs` branch stays logic-only. See
  [Which build for this device](#which-build-for-this-device-b2).
- GitHub's unauthenticated limit is 60 req/h. The app no longer spends it: the latest check uses
  the unmetered web endpoints and a 15-minute TTL, the picker's API call is conditional
  (`304`s are free), and a spent quota falls back to the web feed and the last known version
  ([Staying under GitHub's rate limit](#staying-under-githubs-rate-limit)).
- The ADB link drops out periodically on this stick; toggling **ADB debugging** off/on in Fire TV
  developer options is the reliable fix ([01](01-adb-access.md#the-adb-daemon-stops-on-its-own--toggle-it-off-and-on)).
