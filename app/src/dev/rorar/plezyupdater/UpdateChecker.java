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
        return check(c, false);
    }

    /**
     * Blocking network call — never run this on the main thread.
     *
     * @param force skip the TTL cache (a user pressed "Check for updates")
     */
    public static Result check(Context c, boolean force) {
        // Wrap so the low-level error strings follow the app's picked language, not the device's.
        Context ctx = LocaleHelper.wrap(c);
        String installed = PlezyRepo.installedVersion(ctx);
        try {
            PlezyRepo.Release release = PlezyRepo.fetchLatest(ctx, force);
            Prefs.setLastCheckMs(c, System.currentTimeMillis());
            return new Result(installed, release.tag, release, null);
        } catch (Exception e) {
            Log.w(TAG, "update check failed", e);
            // Keep the last version we knew so the screen still says something useful; the note
            // explains why it may be out of date.
            PlezyRepo.Release known = PlezyRepo.lastKnownLatest(c);
            String note = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(installed, known == null ? null : known.tag, known, note);
        }
    }

    /** Background variant: silent on failure, notifies only when there is something new. */
    public static void checkAndNotify(Context c) {
        Result r = check(c, false);
        if (r.error == null && r.release != null && r.isNewer()) {
            Context ctx = LocaleHelper.wrap(c);
            Notifier.notification(c,
                    ctx.getString(R.string.notif_available_title, r.latestVersion),
                    ctx.getString(R.string.notif_available_text));
        }
    }
}
