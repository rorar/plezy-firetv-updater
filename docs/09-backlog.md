# 09 — Backlog

Ideas and requests not yet done, kept here so they are not lost. Nothing in this file is a
commitment; it is a parking lot. Status is checked against the code in `app/`.

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

## Requested — UI / UX

- **Changelog typography.** Take over the release-notes formatting: the current `Markdown` strip
  is deliberately minimal (headings and bullets flattened to plain text). Adapt the text so
  headings, sections and lists read well on a TV.
- **Custom update-check interval.** Let the user choose the schedule — every *n* hours, days, weeks
  or months — instead of the fixed 12 h. Look for a small prebuilt picker/dialog component rather
  than building a number wheel by hand.
- **UI overhaul.** Draft, plan and build a TV-friendly UI that is easy to drive with a remote
  (focus order, spacing, readable text sizes) — a general pass over the whole app, not one screen.

## Requested — features to investigate

- **Uninstall Plezy.** Check whether the updater can offer to uninstall `com.edde746.plezy`
  (e.g. `Intent.ACTION_DELETE` / `ACTION_UNINSTALL_PACKAGE` with the package URI). On API 25 this
  is a system dialog like the install one — confirm it works on the stick and whether it is worth
  the clutter.
- **Downgrade by uninstall-then-install.** When an older version is selected, uninstall the
  installed (newer) Plezy first, then install the chosen build. This is the only way around the
  Android 7.1 rule that blocks downgrades of release-signed apps
  ([08 — Finding 2](08-app-implementation.md#finding-2--android-71-refuses-downgrades-of-release-builds)).
  Two consequences to design for: the uninstall is another system dialog (so a downgrade costs two
  confirmations), and a fresh install **wipes Plezy's app data** — so warn clearly before doing it.

## Notes

- The two "already implemented" auto-check items were part of the 2026-11-14 build; the UI items
  (button order, changelog, focus colours, sections) landed the same day in the UI/changelog
  phases. Uninstall and downgrade are still open.
