package dev.rorar.plezyupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms the repeating alarm after a reboot; alarms do not survive it. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        if (Prefs.autoCheckEnabled(c)) {
            UpdateAlarm.schedule(c);
        }
    }
}
