package dev.rorar.plezyupdater;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.util.Log;

/**
 * Arms the background update check with {@link JobScheduler}.
 *
 * <p>This replaces the old {@code AlarmManager} + {@code IntentService} pair. JobScheduler exists
 * since API 21, so one code path runs on the API 25 stick and on newer Android; with
 * {@code targetSdk} 26 the platform would cut off a service started from the background, while a
 * job is the supported way to run a short network call out of the foreground.
 *
 * <p>The cadence comes from the settings (default 12 h, smallest the UI offers is 1 h). The
 * platform floors periodic jobs at 15 min, so the user's range is always legal.
 */
public final class UpdateJob {

    private static final String TAG = "PlezyUpdater";

    /** The repeating check. A stable id, so re-scheduling replaces rather than stacks. */
    private static final int PERIODIC_JOB_ID = 2001;
    /** A one-off "check soon": after boot, or after an install when no screen is up. */
    private static final int NOW_JOB_ID = 2002;

    /** Set on the one-off job when the caller acts for the user: skips the auto-check switch. */
    public static final String EXTRA_MANUAL = "manual";

    private UpdateJob() {
    }

    /** Arms (or re-arms) the periodic background check from the current settings. */
    public static void schedule(Context c) {
        JobScheduler js = scheduler(c);
        if (js == null) {
            return;
        }
        long interval = Prefs.intervalMs(c);
        JobInfo job = new JobInfo.Builder(PERIODIC_JOB_ID, service(c))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(interval)
                .build();
        js.schedule(job);
        Log.i(TAG, "periodic check armed every " + interval + " ms");
    }

    /** Cancels the periodic background check. */
    public static void cancel(Context c) {
        JobScheduler js = scheduler(c);
        if (js != null) {
            js.cancel(PERIODIC_JOB_ID);
        }
    }

    /**
     * Runs one check as soon as the platform has network, without waiting for the next period.
     * Used right after an install when no screen is up to show the result; {@code manual} skips
     * the "check automatically" switch, exactly like a press on "Check for updates".
     */
    public static void runNow(Context c, boolean manual) {
        JobScheduler js = scheduler(c);
        if (js == null) {
            return;
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_MANUAL, manual);
        JobInfo job = new JobInfo.Builder(NOW_JOB_ID, service(c))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .build();
        js.schedule(job);
    }

    private static JobScheduler scheduler(Context c) {
        return (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private static ComponentName service(Context c) {
        return new ComponentName(c.getApplicationContext(), CheckJobService.class);
    }
}
