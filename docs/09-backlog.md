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

## Requested — UI / UX

- **Button order.** Put `Check for updates` *below* `Install update` on the main screen
  (currently above it).
- **Changelog view.** Show the release notes for each version (GitHub release `body`), ideally in
  the version picker or a dedicated screen.
- **Settings: fix the focused-switch colours.** The selected/"hovered" setting renders black text
  on the dark-blue focus highlight and is hard to read. Give the `Switch` rows an explicit focused
  text colour (and a focus background) instead of relying on the Fire OS default.
- **Settings: sections + ordering.** Group the switches under **User settings**, **Advanced
  settings** and **Developer settings** with headings, and sort them accordingly.

## Requested — features to investigate

- **Uninstall Plezy.** Check whether the updater can offer to uninstall `com.edde746.plezy`
  (e.g. `Intent.ACTION_DELETE` / `ACTION_UNINSTALL_PACKAGE` with the package URI). On API 25 this
  is a system dialog like the install one — confirm it works on the stick and whether it is worth
  the clutter.

## Notes

- The two "already implemented" items were part of the 2026-11-14 build; the rest came in as
  follow-up requests on the same day.
