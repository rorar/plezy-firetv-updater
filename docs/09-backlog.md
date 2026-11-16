# 09 — Backlog

Ideas and requests, kept here so they are not lost. Nothing in this file is a commitment; it is a
parking lot. Status is checked against the code in `app/`.

## Requested — not started

The forward-looking items now live in the [roadmap](11-roadmap.md), so this stays a short pointer:

- **Self-update of the updater** — download and install a new `plezy-updater.apk` in place.
- **Device detection** (Fire TV vs Android TV vs phone) with the notification wording adapted.
- **Android TV / phone compatibility** (the layout is TV-first today).
- **`targetSdk` bump matrix** (25 → 26 → 28 → … ) and what each step breaks.

Nothing here is a commitment; the roadmap explains the order and the reasoning.

## Already implemented

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
  Upgrade and prove an in-place replace (`firstInstallTime` unchanged, `lastUpdateTime` moved). Five
  flows total. See [08](08-app-implementation.md#flow-tests-test) and
  [test/README](../test/README.md).

- **App languages: English, German, French and Spanish, with a Settings → Language picker.** All
  user-visible text now lives in string resources (`res/values/strings.xml` is the English default;
  `values-de|fr|es` override it), so the app follows the Fire TV's language out of the box and can
  be overridden per app. `LocaleHelper.wrap()` applies the stored choice in each activity's
  `attachBaseContext`; `""` means "follow the system". The picker lists **System default** plus each
  language by its own name with a bundled flag (no network — the flags ship in the APK). Units are
  `<plurals>`, and even the low-level download/verify errors are localised. See
  [08](08-app-implementation.md#language) and [10](10-tv-ui-design.md#language).

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
  without resource merging, so it is hand-built. See
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
