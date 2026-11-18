package dev.rorar.plezyupdater;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * The two user-visible outputs. Both are individually switchable in the settings, and every call
 * site goes through here so the switches can never be bypassed.
 *
 * <p>On Android 8+ the update notification also lives in a platform {@link NotificationChannel},
 * which the platform requires from {@code targetSdk} 26 on. On API 25 there are no channels and the
 * legacy builder is used, so the same code keeps working on the stick.
 *
 * <p>From API 33 (Android 13) a notification also needs the {@code POST_NOTIFICATIONS} runtime
 * permission. It does not exist before 33, so every use of it is guarded by a
 * {@link Build.VERSION#SDK_INT} check and the request is a no-op on the API 25 stick.
 */
public final class Notifier {

    private static final String TAG = "PlezyUpdater";
    private static final int NOTIFICATION_ID = 1001;

    /** The single platform channel the update notification is posted into (API 26+). */
    private static final String CHANNEL_ID = "updates";

    /** Request code for the {@code POST_NOTIFICATIONS} dialog; callers pass it back to compare. */
    public static final int REQUEST_POST_NOTIFICATIONS = 0x504E;

    private Notifier() {
    }

    /**
     * True when the platform will actually show what this app posts. On API 32 and below there is
     * no {@code POST_NOTIFICATIONS} permission, so the answer is only the app-level switch the user
     * controls in the system settings (which this app cannot see any other way). From API 33 on it
     * is false until the runtime permission is granted.
     *
     * <p>This is deliberately independent of the app's own {@code Notifications} switch: it answers
     * "would Android deliver it", which is what the Settings row warns about and what the
     * diagnostics report.
     */
    public static boolean systemAllows(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            // Never let a diagnostics or settings query take a screen down.
            Log.e(TAG, "could not read the notification state", t);
            return true;
        }
    }

    /**
     * True only on API 33+ while the {@code POST_NOTIFICATIONS} permission is still missing — i.e.
     * when {@link #requestPermission} would actually show a dialog. Always false on the stick.
     */
    public static boolean needsPermission(Context c) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Ask for {@code POST_NOTIFICATIONS} (API 33+). A no-op below 33 and when it is already
     * granted, so callers do not have to guard the call site. The result arrives in
     * {@code Activity.onRequestPermissionsResult} with {@link #REQUEST_POST_NOTIFICATIONS}.
     */
    public static void requestPermission(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || !needsPermission(activity)) {
            return;
        }
        try {
            activity.requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
        } catch (Throwable t) {
            Log.e(TAG, "could not request the notification permission", t);
        }
    }

    /** {@code allowed} / {@code blocked} for the diagnostics report — quote-free, like the rest. */
    public static String state(Context c) {
        return systemAllows(c) ? "allowed" : "blocked";
    }

    /** Logs the outcome of a {@code POST_NOTIFICATIONS} request so a phone-lane run is readable. */
    public static void logPermissionResult(String[] permissions, int[] grantResults) {
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "POST_NOTIFICATIONS request result: " + (granted ? "granted" : "denied"));
    }

    public static void toast(Context c, String message) {
        if (!Prefs.toastEnabled(c)) {
            Log.i(TAG, "toast suppressed by settings: " + message);
            return;
        }
        final Context app = c.getApplicationContext();
        final String msg = message;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Log.e(TAG, "toast failed", t);
                }
            }
        });
    }

    /** Posts a notification that opens the updater when the user activates it. */
    public static void notification(Context c, String title, String text) {
        if (!Prefs.notificationEnabled(c)) {
            Log.i(TAG, "notification suppressed by settings: " + title);
            return;
        }
        try {
            Context app = c.getApplicationContext();
            Intent open = new Intent(app, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // API 31 (Android 12) makes the mutability flag mandatory for an app targeting 31+.
            // Nothing fills extras into this one — it just opens MainActivity — so IMMUTABLE is
            // correct (and is what the notification-trampoline rule wants: an activity, started
            // directly, not a receiver that starts one). On API 25..30 the flags stay 0, exactly
            // as before; FLAG_IMMUTABLE is a compile-time constant, so naming it is safe.
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getActivity(app, 0, open, piFlags);

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ensureChannel(app);
                builder = new Notification.Builder(app, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(app);
            }

            Notification n = builder
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setWhen(System.currentTimeMillis())
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .build();

            NotificationManager nm =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, n);
            Log.i(TAG, "notification posted: " + title);
        } catch (Throwable t) {
            Log.e(TAG, "notification failed", t);
        }
    }

    public static void cancelNotification(Context c) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) {
            // nothing to do
        }
    }

    /**
     * Creates the platform channel on API 26+. Android 8 makes a channel mandatory for any
     * notification an app with {@code targetSdk >= 26} posts; creating one that already exists is a
     * no-op, so this is safe to call on every post. On API 25 there are no channels — do nothing.
     */
    private static void ensureChannel(Context app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager nm =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    app.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
        } catch (Throwable t) {
            Log.e(TAG, "could not create the notification channel", t);
        }
    }
}
