# 09 — Backlog

Ideas and requests, kept here so they are not lost. Nothing in this file is a commitment; it is a
parking lot. Status is checked against the code in `app/`.

## Requested — not started

The forward-looking items now live in the [roadmap](11-roadmap.md), so this stays a short pointer:

- **Main View footer: one line each, an interval glyph, a data glyph (user request, 2026-11-18).**
  Three small tweaks to the bottom of the main screen:
  - the update-check text and the free-space line each get their **own line** (today they read as
    one wrapped hint plus a line);
  - the long check sentence — `Checks every 12 hours while the device is on. An update needs one
    confirmation on the system installer screen.` — is shortened to **`⏳ Update check: <n> <m>`**
    (e.g. `⏳ Update check: 12 hours`); the "one confirmation on the installer" caveat moves into
    Settings (where the interval already lives);
  - a **data glyph** (`⛃` or similar) is added before the free-space text (`⛃ Free space: 5.83 GB`).
- **README: add Android TV + phone screenshots (user request, 2026-11-18).** The README has no
  screenshots; add captures of the Android TV (two-pane) and the phone (touch) views — main screen,
  Settings, and the two Choose-Version layouts.
- **README: rewrite for non-technical users (user request, 2026-11-18).** A second README pass aimed
  at the highest user experience for someone who just wants the app on their TV/phone: lead with
  *what it does* and *how to install*, keep the engineering depth in `docs/`. Do it in a separate
  session (`claude` from inside the repo) so it is not entangled with the release work.
- **Choose version (touch): use an icon, not the word "Notes" (user request, 2026-11-18).** The
  touch picker's per-row *Notes* button should reuse the main screen's **What's new** icon instead
  of the text label — a small, self-contained follow-up to the `0.9.0` touch picker.
- **`targetSdk` bump, further steps** — 25 → 26 landed in `0.5.0`, 26 → 31 in `0.6.0`, 31 → 33 in
  `0.7.0`, 33 → 34 in `0.8.0`; 35 → … remains, one step per release
  ([roadmap item 5](11-roadmap.md#5-targetsdk-bump-matrix)).

The former requests here — **make the touch Choose-Version phone-ready**, **device-aware wording
beyond the input hint** (`0.9.0`) and the **touch check-interval picker** (`0.9.1`) — have landed;
see [Already implemented](#already-implemented).

Nothing here is a commitment; the roadmap explains the order and the reasoning.

## Already implemented

- **`targetSdk` 25 → 26 (roadmap item 5, first step).** Landed 2026-11-17 (`0.5.0`): the notification
  now lives in a platform channel on API 26+ (`Notifier.ensureChannel`, `notif_channel_name`) while
  API 25 keeps the legacy builder, and the `AlarmManager` + `IntentService` pair was replaced by a
  `JobScheduler` job (`UpdateJob` / `CheckJobService`, periodic 2001, one-off 2002) — chosen because
  it exists since API 21, so the stick and newer Android share one code path with no AndroidX. The
  API 25 side was re-verified on the stick (jobs armed/cancelled/re-armed, the `runNow` one-off path,
  the legacy notification, all five flows, de/fr/es smokes); the API 26+ branches were logic-verified
  at the time and are now device-verified on the emulator lane (`tv30`). See
  [11 § 5](11-roadmap.md#5-targetsdk-bump-matrix) and
  [08](08-app-implementation.md#background-checks-jobscheduler-targetsdk-26).

- **`targetSdk` 26 → 31 (roadmap item 5, second step) and the emulator lane.** Landed 2026-11-18
  (`0.6.0`): `PendingIntent` mutability flags guarded by `SDK_INT >= S`, `REQUEST_DELETE_PACKAGES`,
  and the Android 12+ downgrade-handshake race fixed. The new `test/emulator.sh` + device profiles
  gave the project a second platform to test on for the first time, and a real API 35 arm64 phone
  (`phone35`) closed the arm64-install gap and verified the whole flow over touch. See
  [11 § 5](11-roadmap.md#5-targetsdk-bump-matrix).

- **`targetSdk` 31 → 33 (roadmap item 5, third step): `POST_NOTIFICATIONS`.** Landed 2026-11-18
  (`0.7.0`): from API 33 the permission is a *runtime* grant, so `Notifier` gained
  `systemAllows` / `needsPermission` / `requestPermission` (all guarded by `SDK_INT >= TIRAMISU`),
  `MainActivity` asks once on first resume while the *Notifications* switch is on, and the Settings
  row asks on toggle-on and shows a **Blocked by Android…** hint plus an **Open notification
  settings** action while the notification is blocked. The diagnostics report carries
  `notif=allowed|blocked`, asserted by the harness. Verified on `phone34` (API 34) and the real
  `phone35` (API 35) — the request dialog, the grant and a posted notification — with the API 25
  flows re-run unchanged on the stick. See
  [11 § 5](11-roadmap.md#5-targetsdk-bump-matrix) and
  [08](08-app-implementation.md#targetsdk-33--post_notifications-070-2026-11-18).

- **`targetSdk` 33 → 34 (roadmap item 5, fourth step): receiver export flags + the job network
  permission.** Landed 2026-11-18 (`0.8.0`): all four runtime receiver registrations go through the
  new `Receivers.register`, which passes `Context.RECEIVER_NOT_EXPORTED` from API 33
  (`SDK_INT >= TIRAMISU`) and the plain two-argument call below it, and the manifest declares
  `ACCESS_NETWORK_STATE` — without it API 34 refuses the network-constrained jobs with a
  `SecurityException` in `MainActivity.onCreate`, a hard crash on launch. Verified on `phone34`
  (API 34) and the real `phone35` (API 35) — the crash reproduced first, then the fix — with the API
  25 flows re-run unchanged on the stick. A variant built without `REQUEST_DELETE_PACKAGES` proved
  that permission is still required on API 34 (the stock uninstaller refuses). See
  [11 § 5](11-roadmap.md#5-targetsdk-bump-matrix) and
  [08](08-app-implementation.md#targetsdk-34-receiver-flags--access_network_state-080-2026-11-18).

- **Touch layout: the check-interval picker + a screen audit (roadmap item 3, follow-up).** Landed
  2026-11-18/19 (`0.9.1`): `IntervalActivity` branches once on `Device.isTouch(this)` — a TV keeps the
  two-pane VALUE|UNIT D-pad picker, a touch device gets a **six-column grid of number chips (1–30)**
  and a 2×2 grid of unit chips, both tapped, with the same live preview and an explicit **Save** /
  **Cancel** pair (stacked full-width, because a two-column row clips long labels on a narrow phone).
  Two platform behaviours bit on the way and are worth remembering: with no view focused the window
  stays in key mode and **swallows the first tap**, and a `focusableInTouchMode` row spends the first
  tap *giving itself focus* — so the touch rows are clickable but not focusable and the root takes
  focus in touch mode up front
  ([Finding 8](08-app-implementation.md#finding-8--the-touch-interval-screen-swallowed-the-first-tap)).
  The accompanying **screen audit** swept every other activity for the same D-pad-only gap and found
  none: `IntervalActivity` was the only focus-only screen, every other screen drives clickable
  `Button`/`Switch` rows. A new **`interval`** harness flow (seven flows total) drives the picker on
  both layouts — tap/scroll on touch, up/down/right on the D-pad — and is non-destructive. See
  [11 § 3](11-roadmap.md#3-android-tv--phone-compatibility).

- **Touch layout: single-pane Choose-Version + right-side changelog fly-in (roadmap item 3, layout
  half).** Landed 2026-11-18 (`0.9.0`): `VersionListActivity` branches once on `Device.isTouch(this)`
  into the unchanged TV two-pane layout and a new touch layout — full-width version rows (tap the
  label to install) with a per-row **Notes** button that flies the release notes in from the right as
  a full-width overlay (`✕` close, Back closes it, an **Install this version** action). The
  `-finger` wording was carried the rest of the way: the *behaviour* sentences (`main_subtitle`,
  `main_hint`, `set_auto_desc`, `set_interval_runs`, `interval_note`, `about_thanks_text`,
  `progress_confirm`) now say "device" not "TV" on a touch device, plus the new `picker_hint`. A new
  **`picker-notes`** harness flow opens and closes the notes on both layouts; the
  overlay is `clickable` so a tap on a dead zone is consumed instead of falling through to the list
  and starting an install
  ([Finding 7](08-app-implementation.md#finding-7--the-touch-notes-overlay-let-taps-fall-through)).
  Verified on the stick (two-pane), `phone34` and the real `phone35` (touch), and `tv30` — an
  Android TV that reports `touch=1`, so it exercises the touch picker on a TV. See
  [11 § 3](11-roadmap.md#3-android-tv--phone-compatibility) and
  [11 § 3 · Layout](11-roadmap.md#layout--done-2026-11-1819-090--091).

- **Device detection (roadmap item 2).** Landed 2026-11-17 (`0.4.0`): `DeviceKind` (the decision, a
  pure enum with no `android.*` import) and `Device` (the Android wrapper) answer *what is this
  running on?* once, with the precedence Fire TV > Android TV > touch > other. `isTouch()` is the
  touchscreen *resource qualifier*, not the `android.hardware.touchscreen` feature flag. The result
  rides along on the A3 diagnostics broadcast (`device=fire_tv;tv=1;touch=0`) and the flow harness
  asserts it; the branches this stick cannot produce are covered by `test/jvm/run.sh` (25 cases) and,
  since the emulator lane, seen on a device too (`android_tv` on `tv25`/`tv30`, `touch` on
  `phone34` and the real `phone35`). See
  [11 § 2](11-roadmap.md#2-device-detection-fire-tv-vs-android-tv-vs-phone) and
  [08](08-app-implementation.md#device-detection-fire-tv--android-tv--touch).

- **Device-aware wording (roadmap item 3, wording half).** Landed 2026-11-17 (`0.4.0`): Android's own
  touchscreen resource qualifier picks the sentence — `res/values-finger/` and its per-locale siblings
  override `notif_available_text`, `set_test_notification_msg` and `picker_hint` with "tap" instead of
  "press OK", so no Java decides it and the wording cannot disagree with `Device.isTouch()`. The
  notification text was corrected from "install" to "open" (it opens the updater), and three strings
  that named the device were reworded. `test/check_strings.py` keeps the locale files in parity. See
  [11 § 3](11-roadmap.md#3-android-tv--phone-compatibility) and
  [08](08-app-implementation.md#device-aware-wording).

- **Self-update of the updater (roadmap item 1).** Landed 2026-11-17 (`0.3.0`): **Settings → About →
  "Update Plezy Updater…"** checks this repo's own GitHub releases and installs a newer
  `plezy-updater.apk` in place, after verifying the download's package, `versionCode` and — the
  check that matters — its **signer against the running app's**. Verified on the stick: `versionCode`
  2 → 3 with `firstInstallTime` unchanged. See
  [11 § 1](11-roadmap.md#1-self-update-of-the-updater) and
  [08](08-app-implementation.md#self-update-the-updater).

- **"Open Plezy?" prompt after every install (A1).** A successful install is followed by an offer to
  start Plezy, with the focus on **Not now** so a stray OK cannot launch it; the launch intent is
  resolved through the package manager (leanback first). **Settings → Prompt to open Plezy** (on by
  default) turns it off. See [08](08-app-implementation.md#the-open-plezy-prompt-a1).

- **About screen + inline version line (A7).** **Settings → About Plezy Updater…** shows the version
  read back from the package manager, plus Licenses (an untranslated `res/raw/licenses.txt`) and a
  Thanks note; the same version line sits inline in Settings. `build.sh` stamps the version at link
  time. See [08](08-app-implementation.md#about-and-the-version-line-a7).

- **GitHub quota check, remotely triggerable (A3).** **Settings → Diagnostics → Check GitHub quota…**
  shows the live `/rate_limit` value and reset time; an exported, `DUMP`-guarded receiver answers
  `am broadcast` with a quote-free `key=value;…` report (also written to `quota.txt`). See
  [08](08-app-implementation.md#diagnostics-and-the-remote-quota-check-a3).

- **Per-device ABI asset (B2).** The tarball name is derived from `Build.SUPPORTED_ABIS`
  (arm64-v8a / armeabi-v7a / x86_64) and never cached, so one APK works on this 32-bit stick and on
  a 64-bit Android TV; the native-lib check accepts any supported ABI (and passes a pure-Java APK).
  See [08](08-app-implementation.md#which-build-for-this-device-b2).

- **Settings polish (A4/A5/A6).** Toggled rows and button rows share one label inset so the column
  aligns (A4); the "System default" globe is drawn square inside the flag box instead of stretched to
  4:3 (A5); the row reads **Edit check interval…** because it opens a picker (A6).

- **`upgrade` flow in the harness (A2).** The flow tests now drive Downgrade → **Main View** →
  Upgrade and prove an in-place replace (`firstInstallTime` unchanged, `lastUpdateTime` moved). Seven
  flows total (the `picker-notes` flow joined in `0.9.0`, the `interval` flow in `0.9.1`). See
  [08](08-app-implementation.md#flow-tests-test) and
  [test/README](../test/README.md).

- **App languages: English, German, French and Spanish, with a Settings → Language picker.** All
  user-visible text now lives in string resources (`res/values/strings.xml` is the English default;
  `values-de|fr|es` override it), so the app follows the Fire TV's language out of the box and can
  be overridden per app. `LocaleHelper.wrap()` applies the stored choice in each activity's
  `attachBaseContext`; `""` means "follow the system". The picker lists **System default** plus each
  language by its own name with a bundled flag (no network — the flags ship in the APK). Units are
  `<plurals>`, and even the low-level download/verify errors are localised. See
  [08](08-app-implementation.md#language) and
  [10](10-tv-ui-design.md#language--one-row-per-shipped-language).

- **Staying under GitHub's 60-requests/hour API limit.** The updater no longer depends on the
  metered API: the latest check reads the unmetered `releases/latest` `302` and `releases.atom`,
  the asset URL is built from the tag, and the picker's remaining API call is conditional
  (`ETag` / `If-None-Match`; `304`s are free) with a 15-minute TTL on top. When the quota *is*
  spent it falls back to the web feed and shows the last known version with a calm *"trying again
  after 12:08"* note instead of a bare `403`. Verified with `remaining: 0/60` — the Spanish
  `install` flow passes. See
  [08](08-app-implementation.md#staying-under-githubs-rate-limit).

- **Install directly from the version picker.** Pressing OK on a version in **Choose version…**
  installs it right there — download, verify, install, progress line and outcome — so the trip back
  to the main screen just to press **Install update** is gone. The pipeline is now one shared
  `InstallFlow` used by both screens, and the main screen keeps the initial-install flow for when
  Plezy is not installed at all. A related bug was fixed on the way: a package *replacement* also
  broadcasts `ACTION_PACKAGE_REMOVED`, which used to read as "Plezy is gone" (the picker's hint
  flickered to *Installed: none*); the flow now ignores removals flagged `EXTRA_REPLACING`. See
  [08](08-app-implementation.md#install-routine-shared) and
  [08](08-app-implementation.md#finding-4--replacing-a-package-also-broadcasts-action_package_removed).
- **Icons on the main screen.** The dashboard now reads at a glance: an up-arrow on the title, a
  state glyph beside the status headline (green check when up to date, bright accent up-arrow when
  an update exists, amber warning when Plezy is absent, red circled cross on a failed check,
  heading-blue info/down-arrow for a selected release), and a leading glyph on every action row
  (download → install, document → what's new, circular arrow → check, layers → choose version,
  sliders → settings). The glyphs are hand-written `<vector>` drawables drawn through `Ui`, tinted
  by state through a `StateListDrawable` so they match their label (white when focused, dimmed when
  disabled). See [10](10-tv-ui-design.md#icons) and
  [08](08-app-implementation.md#icons-and-artwork).
- **An original app icon and TV banner.** The updater ships its own artwork — a bold white
  "up + tray" mark on an accent-blue tile, and a 16:9 banner with the tile and the name. No Plezy
  assets are used (Plezy is GPL-3.0, which covers its code, not its logo or name). Sources and the
  redraw scripts live in [`design/`](../design/README.md).

- **Auto-check when the app opens.** `MainActivity.onCreate()` calls `checkForUpdates(false)`, so
  opening the screen refreshes the state once.
- **Auto re-check after an install.** `InstallResultReceiver` fires an `INSTALL_FINISHED`
  broadcast (or a background check) after every install result, so the app re-reads the installed
  version and asks GitHub again. See
  [08](08-app-implementation.md#settings).
- **Button order.** `Install update` now sits above `Check for updates` on the main screen.
- **Changelog view.** The version picker is two-pane (versions left, notes right) and a **What's
  new** button opens a single-release screen. See [08](08-app-implementation.md#changelog).
- **Settings: focused-switch colours.** Focused rows render white on the focus highlight instead of
  the Fire OS default black-on-blue.
- **Settings: sections + ordering.** Switches are grouped under **User settings**, **Advanced
  settings** and **Developer settings**, with a **Diagnostics** block at the bottom.
- **Uninstall Plezy.** An **Uninstall Plezy…** row in Advanced settings, with a pre-confirm and the
  stock uninstaller; a removed-Plezy broadcast refreshes the row. See
  [08](08-app-implementation.md#uninstall).
- **Install with nothing installed.** Verification falls back to the pinned signer when Plezy is
  absent, and the main screen treats the latest release as installable instead of "up to date".
- **Fixed: "not installed" showed "Update available".** With no Plezy installed the blue status
  line now reads "Plezy is not installed — press \"Install update\"."
- **Downgrade by uninstall-then-install.** Selecting an older version uninstalls the installed
  (newer) Plezy and then installs the chosen build — the only way past the Android 7.1 downgrade
  block. Two system confirmations, and Plezy's data is erased. See
  [08](08-app-implementation.md#downgrade).
- **Changelog typography.** `Markdown.toStyled` renders release notes as a styled `CharSequence`:
  headings bold/coloured/1.2×, bullets hanging-indented, inline bold/code/links emphasised. Used by
  both the version picker and **What's new**. See [08](08-app-implementation.md#changelog).
- **Custom update-check interval.** **Check interval** opens `IntervalActivity` — numbers 1–30 ×
  Hours/Days/Weeks/Months — persisted and re-armed immediately. A prebuilt library is not possible
  without resource merging, so it is hand-built. A TV gets the two-pane D-pad picker; a touch device
  a tappable number grid plus unit chips (`0.9.1`). See
  [08](08-app-implementation.md#check-interval).
- **UI overhaul.** A shared `Ui` toolkit (colours, sizes, focus selectors), a dashboard-style main
  screen, grouped settings, and a consistent D-pad focus model. See
  [08](08-app-implementation.md#the-tv-look-ui) and the design doc [10](10-tv-ui-design.md).
- **Interval highlight.** Each column keeps its own choice highlighted while the D-pad is in the
  other column, so the selected number stays visible when the user moves to the unit column (and
  vice versa): the focused row shows the full accent fill, the selected-but-unfocused row a dimmer
  one (`Ui.pickerRowSelector` / `Ui.pickerTextColors`). See
  [08](08-app-implementation.md#check-interval).

## Notes

- The auto-check items, the UI work (button order, changelog, focus colours, sections) and the
  uninstall/downgrade features all landed on 2026-11-14.
- The three former **Requested** items — changelog typography, the custom check interval and the
  UI overhaul — landed on 2026-11-14 as well (committed after the downgrade work). The interval
  picker's cross-column highlight was added the same day.
- **Icons on the main screen** and the **original app icon / TV banner** landed on 2026-11-14.
  With those in, there are **no open requests**; new ones go under *Requested* above.
- **Install directly from the version picker** landed on 2026-11-15 (shared `InstallFlow`; main
  screen keeps the initial-install flow). That closed the last request at the time.
- **Flow tests** landed on 2026-11-15: the four flows (install / downgrade / uninstall /
  clean-install) are written down as code in [`test/`](../test/README.md), with all labels loaded
  from per-language JSON so the same flows run against any app language and device locale.
- **Localisation** landed on 2026-11-15: English + German + French + Spanish resources, a
  **Settings → Language** picker with bundled flags, and a runtime override (`LocaleHelper`). The
  app follows the device by default. That closed the last request — the backlog is empty again.
- The flow tests now **set the app language themselves** (`--app-lang`) by driving
  Settings → Language with the D-pad, so the same runtime-switchable build is exercised in each
  language: English and German ran the full `all` sequence, French and Spanish the non-destructive
  `install` flow.
- **Rate-limit handling** landed on 2026-11-15, after a flow run failed with *"GitHub API returned
  HTTP 403"* in the picker: the unmetered web feed is now the primary source, the picker's API call
  is conditional, and a spent quota degrades to the last known version instead of an error.
- **Round 1 (A1–A7 + B2)** landed on 2026-11-16 (updater `0.2.0`): the install prompt, the About
  screen and version line, the quota diagnostics with its remote trigger, the per-ABI asset, the
  Settings alignment/globe/wording fixes, and the `upgrade` flow. All five flows pass in English and
  German; French and Spanish pass the non-destructive `install`. That left the backlog empty again —
  the four forward-looking items now live in the [roadmap](11-roadmap.md).
- **Self-update** landed on 2026-11-17 (updater `0.3.0`): **Settings → About → "Update Plezy
  Updater…"** checks this repo's own releases and installs a newer build in place, signer-checked.
- **Device detection + device-aware wording** (roadmap items 2–3, wording half) landed on 2026-11-17
  (updater `0.4.0`). The classification is asserted over the diagnostics broadcast and the wording
  rides the `-finger` resource qualifier; the parts this stick cannot produce are covered by
  `test/jvm/run.sh` and `test/check_strings.py`, both device-free. What was left then — the touch
  **layout** and the `targetSdk` bump — has since partly landed (the touch layout, below); the
  `targetSdk` 35 step remains.
- **`targetSdk` 25 → 26** (roadmap item 5, first step) landed on 2026-11-17 (updater `0.5.0`):
  notification channel on API 26+, `JobScheduler` in place of the alarm + service. **26 → 31**
  (second step) landed on 2026-11-18 (updater `0.6.0`): `PendingIntent` mutability flags,
  `REQUEST_DELETE_PACKAGES`, and the Android 12+ downgrade race fixed; the emulator lane that
  verified it is new too. **31 → 33** (third step) landed on 2026-11-18 (updater `0.7.0`):
  `POST_NOTIFICATIONS` requested once automatically and from the Settings switch, a blocked hint
  with an **Open notification settings** action, and `notif=` in the diagnostics report; verified on
  the API 34 emulator and the real API 35 phone. The remaining steps (34 → 35) are still open.
- **Touch layout** landed in two halves: the **Choose-Version picker** on 2026-11-18 (updater
  `0.9.0`) and the **check-interval picker** on 2026-11-18/19 (updater `0.9.1`), the latter with a
  screen audit that confirmed `IntervalActivity` was the only D-pad-only screen left. With both in,
  the touch **layout** is done; what remains for roadmap item 3 is the `targetSdk` 35 step (item 5)
  and the README work, and item 4 (the Main View footer) is still open.
