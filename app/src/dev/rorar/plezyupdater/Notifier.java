package dev.rorar.plezyupdater;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * The two user-visible channels. Both are individually switchable in the settings, and every
 * call site goes through here so the switches can never be bypassed.
 */
public final class Notifier {

    private static final String TAG = "PlezyUpdater";
    private static final int NOTIFICATION_ID = 1001;

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

            Notification n = new Notification.Builder(app)
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
}
