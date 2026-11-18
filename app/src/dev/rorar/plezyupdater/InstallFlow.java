package dev.rorar.plezyupdater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;

/**
 * The "get an APK onto the TV" pipeline: download the chosen release, verify it, then hand it to
 * the stock installer. Shared by the main screen and the version picker so the logic exists once.
 *
 * <p>Nothing here installs silently — that is impossible on this device (no root, no
 * {@code INSTALL_PACKAGES}). The last step is always the system confirmation dialog. A downgrade
 * (Android 7.1 refuses those for release builds) is done by uninstalling Plezy first and
 * installing once {@link Intent#ACTION_PACKAGE_REMOVED} arrives.
 */
public final class InstallFlow {

    private static final String TAG = "PlezyUpdater";

    /** How long, and how often, to wait for a successful uninstall to become visible. */
    private static final long UNINSTALL_RECHECK_MS = 750;
    private static final int UNINSTALL_RECHECK_TRIES = 5;

    /** The screen that owns a flow: it shows progress and re-reads its state when told to. */
    public interface Callback {
        /** One line of progress, e.g. "Downloading… 42%". */
        void onProgress(String text);

        /** A download/install started or stopped; the screen should disable its actions. */
        void onBusy(boolean busy);

        /** The flow stopped with a user-readable reason. */
        void onFailed(String message);

        /** The stock installer was opened; the screen should refresh once it closes. */
        void onInstallCommitted();

        /** Plezy has just been uninstalled (the first half of a downgrade). */
        void onPlezyRemoved();
    }

    private final Activity activity;
    private final Callback callback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver packageRemovedReceiver;

    private boolean busy;
    /** A verified APK waiting for Plezy to be uninstalled before it can be installed. */
    private File pendingDowngradeApk;
    /** A pending "did the uninstall really happen?" re-check, and how many checks are left. */
    private Runnable recheck;
    private int recheckTries;

    public InstallFlow(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.packageRemovedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Replacing an installed app (a normal upgrade) also broadcasts REMOVED, flagged
                // with EXTRA_REPLACING, right before the new package is added. Only a real
                // uninstall — the downgrade handshake — means Plezy is actually gone.
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    return;
                }
                Uri data = intent.getData();
                if (data != null && PlezyRepo.PACKAGE_NAME.equals(data.getSchemeSpecificPart())) {
                    handlePlezyRemoved();
                }
            }
        };
        IntentFilter removed = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        removed.addDataScheme("package");
        activity.registerReceiver(packageRemovedReceiver, removed);
    }

    /** Unregister the receiver; call from the screen's {@code onDestroy()}. */
    public void dispose() {
        cancelRecheck();
        try {
            activity.unregisterReceiver(packageRemovedReceiver);
        } catch (Throwable ignored) {
            // already gone
        }
    }

    public boolean isBusy() {
        return busy;
    }

    /** True between "uninstall Plezy" and the install that follows it. */
    public boolean isDowngradePending() {
        return pendingDowngradeApk != null;
    }

    /**
     * Call from the screen's {@code onResume()}. Finishes a downgrade whose uninstall dialog just
     * closed: installs the pending APK if Plezy is gone, otherwise reports the cancellation.
     *
     * <p>A non-null {@code installedVersion} here is <em>not</em> proof the user cancelled: on
     * Android 12+ this resume can run <em>before</em> the uninstall has propagated to
     * PackageManager, so Plezy still reads as installed for a moment after a successful uninstall.
     * On a real API 35 phone that made a successful downgrade look cancelled and no installer ever
     * appeared — the phone lane's first catch. So instead of cancelling on that stale read, give
     * the {@code ACTION_PACKAGE_REMOVED} broadcast — the authoritative signal — a moment to arrive,
     * and only cancel once Plezy is <em>still</em> there a few checks later.
     */
    public void onResume(String installedVersion) {
        if (pendingDowngradeApk == null || busy) {
            return;
        }
        if (installedVersion == null) {
            Log.i(TAG, "downgrade: Plezy is gone after the uninstall dialog — installing");
            installPendingDowngrade();
            return;
        }
        Log.i(TAG, "downgrade: Plezy still reads as " + installedVersion
                + " — re-checking before deciding it was cancelled");
        if (recheck == null) {
            recheckTries = UNINSTALL_RECHECK_TRIES;
        }
        postRecheck();
    }

    /** Poll for a few seconds; install the moment Plezy is really gone, else call it a cancel. */
    private void postRecheck() {
        if (recheck != null) {
            return;
        }
        recheck = new Runnable() {
            @Override
            public void run() {
                recheck = null;
                if (pendingDowngradeApk == null || busy) {
                    return;  // the broadcast (or a fresh resume) already settled it
                }
                if (PlezyRepo.installedVersion(activity) == null) {
                    Log.i(TAG, "downgrade: Plezy is gone — installing");
                    installPendingDowngrade();
                    return;
                }
                if (--recheckTries > 0) {
                    postRecheck();
                    return;
                }
                Log.i(TAG, "downgrade: Plezy is still installed — cancelled");
                clearPendingDowngrade();
                Notifier.toast(activity, activity.getString(R.string.toast_downgrade_cancelled));
            }
        };
        ui.postDelayed(recheck, UNINSTALL_RECHECK_MS);
    }

    private void cancelRecheck() {
        if (recheck != null) {
            ui.removeCallbacks(recheck);
            recheck = null;
        }
    }

    /**
     * Re-adopt a downgrade that process death interrupted: the verified APK is still in the cache
     * and the release tag is in the prefs. Call from the screen's {@code onCreate()}.
     */
    public void resumePendingDowngrade() {
        String tag = Prefs.pendingDowngrade(activity);
        if (tag == null) {
            return;
        }
        File apk = ApkInstaller.cachedApk(activity, tag);
        if (apk != null) {
            pendingDowngradeApk = apk;
        } else {
            Prefs.setPendingDowngrade(activity, null);
        }
    }

    /** Download + verify {@code release}, then open the installer (or confirm a downgrade). */
    public void install(final PlezyRepo.Release release, final String installedVersion) {
        if (release == null || busy) {
            return;
        }
        setBusy(true);
        callback.onProgress(activity.getString(R.string.progress_preparing));
        keepScreenOn(true);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File apk = ApkInstaller.cachedApk(activity, release.tag);
                    if (apk != null) {
                        post(activity.getString(R.string.progress_cached, release.tag));
                    } else {
                        ApkInstaller.cleanCache(activity);
                        apk = new File(activity.getCacheDir(), "plezy-update.apk");
                        ApkInstaller.requireSpace(activity, activity.getCacheDir(),
                                ApkInstaller.MIN_FREE_SPACE);
                        post(activity.getString(R.string.progress_downloading));
                        final File tar = new File(activity.getCacheDir(), "plezy-update.tar.gz");
                        try {
                            ApkInstaller.download(activity, release.assetUrl, tar,
                                    new ApkInstaller.Progress() {
                                @Override
                                public void onPercent(final int percent) {
                                    post(activity.getString(R.string.progress_downloading_pct, percent));
                                }
                            });
                            post(activity.getString(R.string.progress_unpacking));
                            ApkInstaller.extractApk(activity, tar, apk);
                        } finally {
                            // Never leave the tarball (or a partial one) behind.
                            deleteQuietly(tar);
                        }
                    }

                    final boolean allowOlder = Prefs.allowOlderVersions(activity);
                    final ApkInstaller.Verified verified =
                            ApkInstaller.verify(activity, apk, allowOlder);
                    // Only a verified APK is worth caching for a retry.
                    ApkInstaller.markCached(activity, release.tag);

                    final boolean downgrade = installedVersion != null
                            && PlezyRepo.compareVersions(verified.versionName, installedVersion) < 0;
                    if (downgrade) {
                        // Android 7.1 refuses a downgrade of a release build, so the installed
                        // Plezy has to go first. The APK is already verified and cached.
                        final File apkFile = apk;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                setBusy(false);
                                confirmDowngrade(release, apkFile, verified);
                            }
                        });
                        return;
                    }

                    post(activity.getString(R.string.progress_opening, verified.versionName));
                    ApkInstaller.install(activity, apk, allowOlder);
                    post(activity.getString(R.string.progress_confirm));
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onInstallCommitted();
                            setBusy(false);
                        }
                    });
                } catch (final Throwable t) {
                    Log.e(TAG, "install failed", t);
                    final String message = describe(t);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailed(message);
                            setBusy(false);
                        }
                    });
                } finally {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            keepScreenOn(false);
                        }
                    });
                }
            }

            private void post(final String text) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onProgress(text);
                    }
                });
            }
        }, "plezy-install").start();
    }

    /**
     * A downgrade needs the installed app gone first. Warn clearly — the uninstall erases Plezy's
     * data — then let the system uninstaller run; {@link #handlePlezyRemoved()} installs the APK.
     */
    private void confirmDowngrade(final PlezyRepo.Release release, final File apk,
            final ApkInstaller.Verified verified) {
        pendingDowngradeApk = apk;
        Prefs.setPendingDowngrade(activity, release.tag);
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.dialog_downgrade_title, verified.versionName))
                .setMessage(activity.getString(R.string.dialog_downgrade_message, verified.versionName))
                .setNegativeButton(activity.getString(R.string.action_cancel),
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearPendingDowngrade();
                        callback.onProgress(activity.getString(R.string.progress_downgrade_cancelled));
                    }
                })
                .setPositiveButton(activity.getString(R.string.dialog_downgrade_button),
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        launchUninstallForDowngrade();
                    }
                })
                .show();
    }

    private void launchUninstallForDowngrade() {
        callback.onProgress(activity.getString(R.string.progress_uninstalling));
        keepScreenOn(true);
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + PlezyRepo.PACKAGE_NAME));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            clearPendingDowngrade();
            callback.onFailed(activity.getString(R.string.toast_no_uninstaller));
        }
    }

    /** Plezy is gone: finish a pending downgrade by installing the verified APK. */
    private void handlePlezyRemoved() {
        callback.onPlezyRemoved();
        if (pendingDowngradeApk != null) {
            Log.i(TAG, "ACTION_PACKAGE_REMOVED: finishing the pending downgrade");
            installPendingDowngrade();
        }
    }

    private void installPendingDowngrade() {
        final File apk = pendingDowngradeApk;
        if (apk == null || busy) {
            return;
        }
        cancelRecheck();
        pendingDowngradeApk = null;
        Prefs.setPendingDowngrade(activity, null);
        setBusy(true);
        callback.onProgress(activity.getString(R.string.progress_installing));

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Plezy is uninstalled, so this is a plain install — no downgrade flag needed.
                    ApkInstaller.install(activity, apk, false);
                    post(activity.getString(R.string.progress_confirm));
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onInstallCommitted();
                            setBusy(false);
                        }
                    });
                } catch (final Throwable t) {
                    Log.e(TAG, "downgrade install failed", t);
                    final String message = describe(t);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailed(message);
                            setBusy(false);
                        }
                    });
                } finally {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            keepScreenOn(false);
                        }
                    });
                }
            }

            private void post(final String text) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onProgress(text);
                    }
                });
            }
        }, "plezy-downgrade-install").start();
    }

    private void clearPendingDowngrade() {
        cancelRecheck();
        pendingDowngradeApk = null;
        Prefs.setPendingDowngrade(activity, null);
    }

    private void setBusy(boolean value) {
        busy = value;
        callback.onBusy(value);
    }

    private void keepScreenOn(final boolean on) {
        try {
            if (on) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Throwable ignored) {
            // the window may already be gone
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "could not delete " + f);
        }
    }
}
