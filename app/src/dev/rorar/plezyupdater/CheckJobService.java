package dev.rorar.plezyupdater;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * Runs one update check off the main thread for {@link UpdateJob}.
 *
 * <p>A {@code JobService} rather than the old {@code IntentService}: with {@code targetSdk} 26 a
 * service started from the background is illegal, and even on API 25 a job is the right tool for a
 * short, deferred network call. The job exists on API 25, so there is no second code path to keep.
 */
public class CheckJobService extends JobService {

    private static final String TAG = "PlezyUpdater";

    @Override
    public boolean onStartJob(final JobParameters params) {
        boolean manual = params.getExtras() != null
                && params.getExtras().getBoolean(UpdateJob.EXTRA_MANUAL, false);
        if (!manual && !Prefs.autoCheckEnabled(this)) {
            Log.i(TAG, "background check skipped: auto check disabled");
            return false;
        }

        Log.i(TAG, "background check starting (manual=" + manual + ")");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    UpdateChecker.checkAndNotify(CheckJobService.this);
                } catch (Throwable t) {
                    Log.e(TAG, "background check failed", t);
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "plezy-check-job").start();

        // Work continues on our thread; tell the system not to keep the job alive for us.
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The system stopped us (constraints lost, or the period elapsed). The periodic job
        // re-runs on its own and a one-off "check soon" is cheap to lose, so do not reschedule.
        return false;
    }
}
