# 07 — Notification design

Idea: the updater should not be a silent icon you forget about — it should **tell you when a
new Plezy version exists**, via a Fire TV notification, and let you install with one click.

This is the right architecture for a TV device. What follows is what the stick can actually do.

## What exists on Fire OS 6

Probed on the device (`pm list packages`, `service list`, `dumpsys package`):

| Component | Finding | Usable? |
|---|---|---|
| Standard notification stack | `notification: [android.app.INotificationManager]`, `statusbar: [com.android.internal.statusbar.IStatusBarService]`, `com.android.systemui` | ✅ the normal API path exists |
| Fire TV notification center | `com.amazon.tv.notificationcenter` with `.NotificationCenterActivity` (category DEFAULT) and `.settings.NotificationSettingsActivity` (VIEW) | ✅ notifications are **persisted and browsable**, not just transient toasts |
| Amazon UX notifications | `/system/priv-app/UXNotificationService`, permission `com.amazon.device.permission.UX_NOTIFICATION` = `signature\|privileged` | ❌ closed to third-party apps |
| Alexa notification channel | `com.amazon.tv.alexanotifications` | ❌ Amazon-only |
| `adb shell cmd notification` | `No shell command implementation.` (API 25 Fire OS) | ❌ cannot test from the shell |
| `SYSTEM_ALERT_WINDOW` overlay | `appops` functional, grantable via `adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow` | ✅ reliable fallback |

**The one unproven premise:** whether Fire OS 6 renders notifications from an *unsigned
third-party* app. The infrastructure is clearly there (notification center + `INotificationManager`),
but Fire OS could filter senders. That cannot be answered from the shell on this build; it needs
a ~30-line test APK. Everything else in this design is settled.

## Proposed architecture

The updater is a **notifier**; the download happens only when the user acts.

```
AlarmManager (inexactRepeating, 12 h)  ──┐
BOOT_COMPLETED re-arm                  ──┤
                                         ▼
                              UpdateCheckReceiver
                                         │
              GET api.github.com/repos/edde746/plezy/releases/latest
              (ETag cache → 304 = no work; 60 req/h unauthenticated limit)
                                         │
                     versionName newer than installed?
                                         │ yes
                                         ▼
                  Notification "Plezy 2.27.0 available — tap to install"
                  channel-less legacy Notification (API 25 has no channels)
                  contentIntent → UpdaterActivity
                                         │
                                         ▼
                  UpdaterActivity (leanback, one focusable button)
                  → download → extract → verify signer → PackageInstaller
                  → stock installer dialog → one remote press
```

Why no foreground service: on **API 25** the Android 8 background-execution limits do not
exist, so a short network call in a `BroadcastReceiver` is legal and cheap. (`startForeground`
would put a permanent entry in the notification center — ugly, and unnecessary here.)

Why sync-on-notification rather than download-on-notification: 48–90 MB should not be pulled
every 12 hours just to tell the user something is available.

## Details that matter on a TV

- **No notification channels.** `NotificationChannel` is API 26+. Use the legacy
  `new Notification(icon, tickerText, when)` constructor.
- **No `POST_NOTIFICATIONS` runtime permission.** Introduced in API 33. On API 25 posting just
  works — unless Fire OS applies its own per-app toggle (the presence of
  `NotificationSettingsActivity` suggests it might; verify).
- **Content intent is the whole UX.** On Fire TV the user selects the notification and presses
  OK; that must land directly on the "Install 2.27.0" screen, not on a generic app home.
- **Keep the text short.** One line: `Plezy 2.27.0 available`. Second line: `Press OK to install`.
- **Dismiss/duplicate handling.** Use a fixed notification id and `setOnlyAlertOnce`, so a
  12-hour poll does not stack entries in the notification center.
- **Failure must not spam.** Back off after N consecutive network failures instead of posting
  an error notification every 12 h.
- **Do not notify about your own updates in the same channel-less style** unless the updater
  self-update flow exists; otherwise the entry is a dead end.

## Fallback: SYSTEM_ALERT_WINDOW overlay

If third-party notifications turn out to be filtered:

```bash
adb shell appops set <updater.pkg> SYSTEM_ALERT_WINDOW allow
```

Then show a small, focusable banner ("Plezy update available — OK to install, BACK to dismiss")
over the home screen from a background service. More intrusive, more code (focus handling,
lifecycle, don't block the launcher), but guaranteed to be visible and fully under our control.
Grant it once from the host; no root required.

## Test plan (before writing the real app)

1. **Throwaway APK** (~30 lines): posts one notification on launch + one from a
   `BroadcastReceiver` triggered by `adb shell am broadcast`.
2. Check whether it appears as a heads-up/toast, and whether
   `com.amazon.tv.notificationcenter` retains it.
3. Find the remote path into the notification center (likely *Settings → Notifications*) and
   record it here.
4. If invisible → repeat with the overlay fallback and record which path works.
5. Only then build the real updater ([06](06-own-updater-feasibility.md)).

Findings from step 2–4 belong in this file; the "do third-party notifications render?" question
is the single largest unknown in the whole project.
