package dev.rorar.plezyupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-arms the background check after a reboot; scheduled jobs do not survive it. It also asks for
 * one check soon, which is what the old 2-minute-after-boot alarm delay used to do — the job's
 * network requirement holds it until the TV's network is actually up.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        if (Prefs.autoCheckEnabled(c)) {
            UpdateJob.schedule(c);
            UpdateJob.runNow(c, false);
        }
    }
}
