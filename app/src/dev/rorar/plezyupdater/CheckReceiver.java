package dev.rorar.plezyupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Entry point for the periodic alarm. Hands the work to a service and returns immediately. */
public class CheckReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        UpdateCheckService.start(c.getApplicationContext(), false);
    }
}
