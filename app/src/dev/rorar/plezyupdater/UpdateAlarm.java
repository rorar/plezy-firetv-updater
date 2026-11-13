package dev.rorar.plezyupdater;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Arms the periodic background check. On API 25 there are no Doze-forced inexact limits or
 * background-service restrictions to fight, so a plain alarm plus a short service is enough.
 */
public final class UpdateAlarm {

    /** Twice a day. GitHub's unauthenticated limit is 60 requests per hour, so this is tiny. */
    private static final long INTERVAL_MS = 12L * 60L * 60L * 1000L;

    /** A few minutes after boot/launch, so the TV has settled its network. */
    private static final long FIRST_DELAY_MS = 2L * 60L * 1000L;

    private static final int REQUEST_CODE = 2001;
    private static final String ACTION = "dev.rorar.plezyupdater.action.CHECK";

    private UpdateAlarm() {
    }

    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pendingIntent(c);
        am.cancel(pi);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + FIRST_DELAY_MS, INTERVAL_MS, pi);
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pendingIntent(c));
        }
    }

    private static PendingIntent pendingIntent(Context c) {
        Intent i = new Intent(c, CheckReceiver.class);
        i.setAction(ACTION);
        return PendingIntent.getBroadcast(c, REQUEST_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
