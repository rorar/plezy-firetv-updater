# 07 — Notification design

Idea: the updater should not be a silent icon you forget about — it should **tell you when a
new Plezy version exists**, via a Fire TV notification, and let you install with one click.

This is the right architecture for a TV device, and **all the primitives have now been verified
on the real stick** with a throwaway probe app (see `probe/`).

## Measured results (2026-11-13, AFTMM, Fire OS 6.7.1.1 / API 25)

| Primitive | Result | Evidence |
|---|---|---|
| `Toast.makeText(...).show()` from a sideloaded third-party app | ✅ **visible** | Visually confirmed on the TV; screen diff isolates a `533×41` region at `x=693 y=911` (bottom centre of 1920×1080) |
| Toast from a **background** `BroadcastReceiver` (app not in foreground) | ✅ call succeeds | `onReceive what=toast` → `toast() called`, no exception, toast window drawn; visual confirmation not independently recorded |
| `NotificationManager.notify()` from the background | ✅ **posted and retained** | `dumpsys notification` → `NotificationRecord(pkg=dev.local.toasttest id=4242 importance=2 flags=0x10)`; `SALE_SaleDatabase: notificationPut Notification Added, Id : 4242`; `numPostedByApp=1` |
| Appears in the Fire TV notification center | ✅ **yes** | Opened `com.amazon.tv.notificationcenter/.NotificationCenterActivity`; UI dump lists `ToastTest` / `Notification from third-party app (launch)` / `Vor 1 Min.` |
| Selectable with the remote and tap-through | ✅ **yes** | Focused node = `com.amazon.tv.notificationcenter:id/notification_card`; `input keyevent 23` → resumed activity became `dev.local.toasttest/.MainActivity` (the `contentIntent`) |

So the **biggest unknown of the whole project is resolved**: Fire OS 6 does *not* filter
notifications by signer. A debug-signed sideloaded APK can notify and be opened from the
notification center.

### Observations worth knowing

- **Fire OS demotes the notification.** `post_frequency{...pkg=dev.local.toasttest, count=1,
  demoted=1}` in `dumpsys notification`, and `importance=2` (requested 3). It still lands in the
  notification center, but it is not treated as a high-priority/heads-up event. Cause not
  established (possibly: unknown package, no channel — API 25 has none, or Fire OS policy for
  non-Appstore apps). Acceptable for an updater; if a "pop up while watching" behaviour is
  wanted, this needs separate investigation.
- The notification is stored with `seen=false` and `flags=0x10` (`FLAG_AUTO_CANCEL`). It
  survived until opened; an updater should probably **not** set auto-cancel so the reminder
  does not disappear the moment it is tapped.
- The notification center is localised (system UI showed `Benachrichtigungen`, `Vor 1 Min.`).
  Notification text should be short and language-neutral-ish, or localised if effort permits.
- `com.amazon.venezia` uses the same center (`Appstore: 2 App-Updates verfügbar`) — which is
  also a reminder that the store will never announce updates for the sideloaded Plezy.
- A second notification with the **same id** replaced the first one, as expected. Use a fixed id
  plus `setOnlyAlertOnce(true)` so a 12-hourly poll does not stack entries.

## Infrastructure on Fire OS 6 (probed)

| Component | Finding | Usable? |
|---|---|---|
| Standard notification stack | `notification: [android.app.INotificationManager]`, `statusbar: [com.android.internal.statusbar.IStatusBarService]`, `com.android.systemui` | ✅ |
| Fire TV notification center | `com.amazon.tv.notificationcenter` — `.NotificationCenterActivity` (DEFAULT), `.settings.NotificationSettingsActivity` (VIEW), card id `notification_card` | ✅ confirmed |
| Amazon UX notifications | `/system/priv-app/UXNotificationService`, permission `com.amazon.device.permission.UX_NOTIFICATION` = `signature\|privileged` | ❌ closed to third-party apps |
| Alexa notification channel | `com.amazon.tv.alexanotifications` | ❌ Amazon-only |
| `adb shell cmd notification` | `No shell command implementation.` (API 25 Fire OS) | ❌ cannot test from the shell — use the probe APK |

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
                  Notification "Plezy 2.27.0 available — press OK to open"
                  legacy Notification builder (API 25 has no channels)
                  contentIntent → UpdaterActivity        ← verified to work
                                         │
                                         ▼
                  UpdaterActivity (leanback, one focusable button)
                  → download → extract → verify signer → PackageInstaller
                  → stock installer dialog → one remote press
```

Why no foreground service: on **API 25** the Android 8 background-execution limits do not exist,
so a short network call in a `BroadcastReceiver` is legal and cheap, and the notification is
retained by the notification center without a service keeping it alive (verified above).

Why sync-on-notification rather than download-on-notification: 48–90 MB should not be pulled
every 12 hours just to say that something is available.

## Details that matter on a TV

- **No notification channels.** `NotificationChannel` is API 26+. Use
  `Notification.Builder` (or the legacy constructor); verified working via the builder.
- **No `POST_NOTIFICATIONS` runtime permission.** Introduced in API 33.
- **Content intent is the whole UX.** Verified: selecting the card and pressing OK opens the
  target activity directly. It must land on "Install 2.27.0", not on a generic home screen.
- **Keep the text short.** Title `Plezy 2.27.0 available`, text `Press OK to open` (the
  notification *opens* the updater; it does not install). The wording is device-aware: on a touch
  device Android picks `res/values-finger/` and it reads `Tap to open`
  ([08](08-app-implementation.md#device-aware-wording)).
- **Don't auto-cancel** (unlike the probe), so the entry survives an accidental dismissal.
- **Failure must not spam.** Back off after N consecutive network failures instead of posting an
  error notification every 12 h.
- **Fixed notification id + `setOnlyAlertOnce`** to avoid stacking.

## Fallback (no longer needed, kept for reference)

If notifications had turned out to be filtered, the plan was a `SYSTEM_ALERT_WINDOW` overlay:

```bash
adb shell appops set <updater.pkg> SYSTEM_ALERT_WINDOW allow
```

`appops` is functional on the stick (no root required). Not needed — notifications work.

## Implemented

The architecture below is no longer just proposed — it is built and verified in `app/`
(see [08 — the updater app, as built](08-app-implementation.md)). Toast and notification output
are separately switchable in the app's Settings.

## Remaining unknowns

1. Does a notification show as a visible **heads-up banner** while video is playing, or only in
   the center? (Given `importance=2` + `demoted=1`, assume center-only until tested.)
2. Does `AlarmManager` fire reliably on an idle TV with the screensaver running?
3. ~~Default focus of the installer dialog~~ — settled: on API 25 the app must launch the dialog
   itself, and the confirmation is one press ([08](08-app-implementation.md#finding-1--on-api-25-the-app-must-launch-the-installer-dialog-itself)).
4. Whether repeated notifications from the same package get demoted further over time.

## Probe app

`probe/` in this repo is the throwaway app used for the measurements above: one activity and one
broadcast receiver, built without Gradle (see `probe/README.md`).

```bash
adb shell am broadcast -n dev.local.toasttest/.TestReceiver --es what toast
adb shell am broadcast -n dev.local.toasttest/.TestReceiver --es what notif
```
