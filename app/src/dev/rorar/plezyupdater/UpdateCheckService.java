package dev.rorar.plezyupdater;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Does the actual network call off the main thread. A service rather than {@code goAsync()}
 * in the receiver, because a 48 MB download-sized timeout does not fit a broadcast's ~10 s
 * budget — API 25 has no background-service limits, so a short-lived IntentService is the
 * simplest legal option.
 */
public class UpdateCheckService extends IntentService {

    private static final String TAG = "PlezyUpdater";

    /** Set by the UI when the user pressed "Check for updates": ignores the auto-check switch. */
    public static final String EXTRA_MANUAL = "manual";

    public UpdateCheckService() {
        super("PlezyUpdateCheck");
    }

    public static void start(Context c, boolean manual) {
        Intent i = new Intent(c, UpdateCheckService.class);
        i.putExtra(EXTRA_MANUAL, manual);
        c.startService(i);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        boolean manual = intent != null && intent.getBooleanExtra(EXTRA_MANUAL, false);
        if (!manual && !Prefs.autoCheckEnabled(this)) {
            Log.i(TAG, "background check skipped: auto check disabled");
            return;
        }
        UpdateChecker.checkAndNotify(this);
    }
}
