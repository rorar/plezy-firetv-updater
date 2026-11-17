package dev.rorar.plezyupdater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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
 */
public final class Notifier {

    private static final String TAG = "PlezyUpdater";
    private static final int NOTIFICATION_ID = 1001;

    /** The single platform channel the update notification is posted into (API 26+). */
    private static final String CHANNEL_ID = "updates";

    private Notifier() {
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
            PendingIntent pi = PendingIntent.getActivity(app, 0, open, 0);

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
