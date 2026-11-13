package dev.local.toasttest;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Throwaway probe: can a third-party app put a Toast and a Notification on a Fire OS 6 TV?
 * Triggered with:
 *   adb shell am broadcast -n dev.local.toasttest/.TestReceiver --es what toast
 *   adb shell am broadcast -n dev.local.toasttest/.TestReceiver --es what notif
 */
public class TestReceiver extends BroadcastReceiver {

    static final String TAG = "ToastTestProbe";
    static final int NOTIF_ID = 4242;

    @Override
    public void onReceive(Context c, Intent i) {
        String what = i == null ? null : i.getStringExtra("what");
        Log.i(TAG, "onReceive what=" + what);

        if (what == null || what.equals("toast") || what.equals("all")) {
            showToast(c, "TOAST from third-party app");
        }
        if (what == null || what.equals("notif") || what.equals("all")) {
            postNotification(c, "Plezy test",
                    "Third-party notification reached the system");
        }
    }

    static void showToast(Context c, String text) {
        try {
            Toast.makeText(c, text, Toast.LENGTH_LONG).show();
            Log.i(TAG, "toast() called");
        } catch (Throwable t) {
            Log.e(TAG, "toast() failed: " + t);
        }
    }

    static void postNotification(Context c, String title, String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);

            Intent open = new Intent(c, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(c, 0, open, 0);

            Notification n = new Notification.Builder(c)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setWhen(System.currentTimeMillis())
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();

            nm.notify(NOTIF_ID, n);
            Log.i(TAG, "notify() posted id=" + NOTIF_ID);
        } catch (Throwable t) {
            Log.e(TAG, "notify() failed: " + t);
        }
    }
}
