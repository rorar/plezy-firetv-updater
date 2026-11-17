package dev.rorar.plezyupdater;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;

/**
 * The updater updating itself.
 *
 * <p>Everything else can ship over the air, but not the app doing the shipping — until now. This
 * reuses the Plezy pipeline ({@link ApkInstaller}) pointed at the updater's own GitHub release,
 * with three differences:
 *
 * <ul>
 *   <li><b>The source is this repo's release.</b> The newest tag comes from the unmetered
 *       {@code releases/latest} redirect ({@link PlezyRepo#latestTagFor}), so the check costs no
 *       API quota either. The APK is the plain {@code plezy-updater.apk} asset — no tarball.</li>
 *   <li><b>The comparison is against itself.</b> {@link #check} compares the release tag with the
 *       running {@code versionName}; {@link ApkInstaller#verifySelfUpdate} then re-checks the
 *       downloaded build's {@code versionCode} before anything is installed.</li>
 *   <li><b>The same key.</b> A new build only replaces the running one if it is signed with the
 *       same certificate, so the download's signer is compared with the running app's up front.</li>
 * </ul>
 *
 * <p>The first self-updating build still has to arrive by {@code adb install -r} once — that is the
 * only step adb is needed for now.
 */
public final class SelfUpdate {

    /** This project's GitHub repo, where the updater's own releases live. */
    public static final String REPO = "rorar/plezy-firetv-updater";

    /** The asset name on every release. */
    public static final String ASSET = "plezy-updater.apk";

    /** The downloaded APK's name in the cache (deliberately distinct from Plezy's artefacts). */
    static final String APK_NAME = "plezy-updater-update.apk";

    private static final String WEB_DOWNLOAD =
            "https://github.com/" + REPO + "/releases/download/";

    /** One line of progress for the screen driving the update. */
    public interface Progress {
        void onText(String text);
    }

    /** A newer build of the updater, ready to download. */
    public static final class Available {
        public final String tag;
        public final String assetUrl;

        Available(String tag, String assetUrl) {
            this.tag = tag;
            this.assetUrl = assetUrl;
        }
    }

    private SelfUpdate() {
    }

    /** The installed updater's version name, e.g. {@code "0.3.0"}. */
    public static String installedVersionName(Context c) {
        return info(c).versionName;
    }

    /** The installed updater's version code, e.g. {@code 3}. */
    public static int installedVersionCode(Context c) {
        return info(c).versionCode;
    }

    private static PackageInfo info(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            // The app is running, so its own package always exists; treat this as a hard error.
            throw new IllegalStateException("own package not found", e);
        }
    }

    /** The download URL for a release tag, built without the API. */
    public static String assetUrlForTag(String tag) {
        return WEB_DOWNLOAD + tag + "/" + ASSET;
    }

    /**
     * The newest release of this repo, or {@code null} when the running build is already current.
     * Costs no API quota: the tag comes from the unmetered {@code releases/latest} redirect.
     */
    public static Available check(Context c) throws Exception {
        String tag = PlezyRepo.latestTagFor(c, REPO);
        if (PlezyRepo.compareVersions(tag, installedVersionName(c)) <= 0) {
            return null;
        }
        return new Available(tag, assetUrlForTag(tag));
    }

    /**
     * Download, verify and hand the new build to the system installer. Blocking — call it off the
     * main thread. Nothing is installed silently: the last step is the system confirmation dialog.
     * The verified APK is left in the cache on purpose, so the session's own copy never races a
     * delete; {@link #cleanup} removes it once the result is known.
     */
    public static void downloadAndInstall(Context c, Available available, final Progress progress)
            throws Exception {
        final File apk = new File(c.getCacheDir(), APK_NAME);
        deleteQuietly(apk);

        progress.onText(c.getString(R.string.progress_downloading));
        ApkInstaller.download(c, available.assetUrl, apk, new ApkInstaller.Progress() {
            @Override
            public void onPercent(int percent) {
                progress.onText(c.getString(R.string.progress_downloading_pct, percent));
            }
        });

        ApkInstaller.Verified verified = ApkInstaller.verifySelfUpdate(c, apk);
        progress.onText(c.getString(R.string.progress_opening, verified.versionName));
        ApkInstaller.install(c, apk, false, true);
        progress.onText(c.getString(R.string.progress_confirm));
    }

    /** Removes the downloaded updater APK; safe to call at any time. */
    public static void cleanup(Context c) {
        deleteQuietly(new File(c.getCacheDir(), APK_NAME));
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) {
            android.util.Log.w("PlezyUpdater", "could not delete " + f);
        }
    }
}
