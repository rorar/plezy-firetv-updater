package dev.rorar.plezyupdater;

import android.content.Context;
import android.util.Log;

/** One update check: ask GitHub, compare with what is installed. */
public final class UpdateChecker {

    private static final String TAG = "PlezyUpdater";

    private UpdateChecker() {
    }

    public static final class Result {
        public final String installedVersion;
        public final String latestVersion;
        public final PlezyRepo.Release release;
        public final String error;

        Result(String installedVersion, String latestVersion, PlezyRepo.Release release, String error) {
            this.installedVersion = installedVersion;
            this.latestVersion = latestVersion;
            this.release = release;
            this.error = error;
        }

        public boolean isNewer() {
            if (release == null) {
                return false;
            }
            if (installedVersion == null) {
                // Nothing installed: the latest release is something to install, not "up to date".
                return true;
            }
            return PlezyRepo.compareVersions(latestVersion, installedVersion) > 0;
        }

        public boolean installed() {
            return installedVersion != null;
        }
    }

    /** Blocking network call — never run this on the main thread. */
    public static Result check(Context c) {
        String installed = PlezyRepo.installedVersion(c);
        try {
            PlezyRepo.Release release = PlezyRepo.fetchLatest(c);
            Prefs.setLastCheckMs(c, System.currentTimeMillis());
            return new Result(installed, release.tag, release, null);
        } catch (Exception e) {
            Log.w(TAG, "update check failed", e);
            return new Result(installed, null, null, e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Background variant: silent on failure, notifies only when there is something new. */
    public static void checkAndNotify(Context c) {
        Result r = check(c);
        if (r.release != null && r.isNewer()) {
            Context ctx = LocaleHelper.wrap(c);
            Notifier.notification(c,
                    ctx.getString(R.string.notif_available_title, r.latestVersion),
                    ctx.getString(R.string.notif_available_text));
        }
    }
}
