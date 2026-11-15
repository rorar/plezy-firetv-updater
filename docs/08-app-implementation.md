# 08 — The updater app, as built

`app/` is the working updater: `dev.rorar.plezyupdater`, minSdk/targetSdk **25**, plain Java,
programmatic UI, **no Gradle and no AndroidX**. It was verified end-to-end on the stick
(AFTMM, Fire OS 6.7.1.1 / API 25) on 2026-11-13/15.

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
                         MainActivity (TV UI, D-pad focus)          VersionListActivity
                           [Install X] [What's new] [Check for updates]   ("Choose version…")
                           [Choose version…] [Settings]                    OK on a version
                           free-space line                                 │
                                   │                                         │
                                   └───────────────┬─────────────────────────┘
                                                   ▼
                                      InstallFlow (shared download/verify/install)
                                        → download plezy-android-armeabi-v7a.tar.gz (~48 MB)
                                        → GZIPInputStream + 512-byte tar header → plezy.apk (~90 MB)
                                        → verify: package, versionCode, minSdk, ABI, signer SHA-256
                                        → PackageInstaller.Session.commit(IntentSender)
                                        → stock installer dialog → one remote press
                                        → InstallResultReceiver → toast + INSTALL_FINISHED refresh
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
carrying the raw `EXTRA_STATUS`, and only starts the background `UpdateCheckService` when no screen
is up. That is what lets the picker — not just `MainActivity` — word the outcome and refresh.

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
| Language | User | `app_language` | System default | Opens the language picker (below) |
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
(`…/releases/download/<tag>/plezy-android-armeabi-v7a.tar.gz`), because Plezy ships that asset name
on every release. `api.github.com` is only the fallback.

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
default**); they ship in the APK, so the picker needs no network.

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
Instead the app takes the only route Android 7.1 allows — uninstall, then install:

1. **Download and verify first**, while Plezy is still installed, so a failed download never
   leaves the user with no Plezy. The verified APK is cached.
2. **Confirm** with a clear data-loss warning ("Plezy will be uninstalled first — its data
   (servers, settings, downloads) is erased — and then 2.25.0 installed").
3. **Uninstall** via `ACTION_DELETE`.
4. On `ACTION_PACKAGE_REMOVED` (or `onResume`, whichever comes first), **install** the cached APK
   through the normal `PackageInstaller` session → the usual install dialog. The removal must be a
   real uninstall — a replace also broadcasts `REMOVED`, so `InstallFlow` ignores anything flagged
   with `EXTRA_REPLACING` ([Finding 4](#finding-4--replacing-a-package-also-broadcasts-action_package_removed)).
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

The four flows below are also written down as runnable code in [`test/`](../test/README.md). The
harness drives the real stick over ADB the way a person with the remote does — it reads the screen
with `uiautomator dump`, finds the labelled button, and taps its centre — so the tests double as a
record of *what to click* for each flow:

```bash
python3 test/run.py install                    # forward path (non-destructive)
python3 test/run.py all --yes                   # install → downgrade → uninstall → clean-install
python3 test/run.py install --app-lang de       # against a German build
```

**No label is hard-coded.** The app's own strings come from `test/strings/app.<lang>.json` (selected
with `--app-lang`) and the stock installer/uninstaller strings from `test/strings/system.<lang>.json`
(selected from the device locale with `--system-lang`, auto-detected). The same flows therefore run
against an English or a German build and against a German or English Fire OS; adding a language is
a matter of copying a pair of JSON files. The German app strings already exist as the *target* in
`app.de.json` — see the backlog ([09](09-backlog.md)).

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

## Known limitations

- One remote confirmation per install (silent install is impossible; [06](06-own-updater-feasibility.md)).
- A downgrade costs two confirmations and **erases Plezy's data**; the app warns before starting
  ([Downgrade](#downgrade)).
- Self-update is not implemented yet: a new updater build is installed with `adb install -r`.
- GitHub's unauthenticated limit is 60 req/h. The app no longer spends it: the latest check uses
  the unmetered web endpoints and a 15-minute TTL, the picker's API call is conditional
  (`304`s are free), and a spent quota falls back to the web feed and the last known version
  ([Staying under GitHub's rate limit](#staying-under-githubs-rate-limit)).
- The ADB link drops out periodically on this stick; toggling **ADB debugging** off/on in Fire TV
  developer options is the reliable fix ([01](01-adb-access.md#the-adb-daemon-stops-on-its-own--toggle-it-off-and-on)).
