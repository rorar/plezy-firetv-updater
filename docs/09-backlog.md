# 09 — Backlog

Ideas and requests, kept here so they are not lost. Nothing in this file is a commitment; it is a
parking lot. Status is checked against the code in `app/`.

## Requested — not started

- **Icons on the main screen.** Add icons to the dashboard so it reads at a glance from across the
  room — e.g. an install/download glyph on the primary action and a check / warning / error glyph
  beside the status headline. Explicitly deferred by the user on 2026-11-14: the current work comes
  first. When it is picked up, the icons must be bundled drawables drawn through `Ui` (the build is
  `aapt2`/`javac`/`d8` with no resource merging, so no icon packs and no AndroidX).

## Already implemented

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
- **Icons on the main screen** is the one open request, deferred by the user on 2026-11-14.
