# 09 — Backlog

Ideas and requests, kept here so they are not lost. Nothing in this file is a commitment; it is a
parking lot. Status is checked against the code in `app/`.

## Requested — not started

_None. The last open request — main-screen icons — landed on 2026-11-14 (see below)._

## Already implemented

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
