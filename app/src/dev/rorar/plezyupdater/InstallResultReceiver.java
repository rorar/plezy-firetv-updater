package dev.rorar.plezyupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * Result of the PackageInstaller session.
 *
 * <p>On API 25 the system does not show the confirmation screen itself: after
 * {@code Session.commit()} it reports {@link PackageInstaller#STATUS_PENDING_USER_ACTION} and
 * hands the app the intent that shows it. Launching that intent is what turns the commit into
 * an actual dialog — without it, the session just sits there and nothing happens.
 */
public class InstallResultReceiver extends BroadcastReceiver {

    private static final String TAG = "PlezyUpdater";

    @Override
    public void onReceive(Context c, Intent i) {
        // A receiver has no activity to wrap; build a context that resolves strings in the picked
        // language so the toast matches the rest of the app.
        Context ctx = LocaleHelper.wrap(c);
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "install result: status=" + status + " message=" + message);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                Notifier.toast(c, ctx.getString(R.string.toast_install_unconfirmed));
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Log.i(TAG, "launching confirmation intent " + confirm);
            try {
                c.startActivity(confirm);
            } catch (Throwable t) {
                Log.e(TAG, "could not launch confirmation intent", t);
                Notifier.toast(c, ctx.getString(R.string.toast_install_unconfirmed));
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            if (Prefs.deleteApkAfterInstall(c)) {
                ApkInstaller.cleanCache(c);
            } else {
                // Keep the verified APK for a later reinstall of this same release.
                ApkInstaller.deleteDownload(c);
            }
            Notifier.cancelNotification(c);
            Notifier.toast(c, ctx.getString(R.string.toast_installed, PlezyRepo.installedVersion(c)));
        } else {
            Notifier.toast(c, ctx.getString(R.string.toast_install_failed,
                    describe(ctx, status, message)));
        }

        // A finished install (or a failed one) is the moment the shown state is most likely
        // stale, so ask GitHub again right away.
        recheck(c, status, message);
    }

    /**
     * Re-runs the update check. The screens are told to refresh through an in-app broadcast (it
     * carries the raw status so a screen can word the outcome itself); when no screen is up, a
     * background check runs and only speaks up through a notification if something newer appeared.
     */
    private static void recheck(Context c, int status, String message) {
        Intent refresh = new Intent(MainActivity.ACTION_INSTALL_FINISHED);
        refresh.setPackage(c.getPackageName());
        refresh.putExtra(PackageInstaller.EXTRA_STATUS, status);
        refresh.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message);
        c.sendBroadcast(refresh);
        if (!MainActivity.isAlive()) {
            UpdateCheckService.start(c, true);
        }
    }

    /**
     * Turns the platform result into one short sentence. The raw {@code EXTRA_STATUS_MESSAGE} is
     * a code such as {@code INSTALL_FAILED_VERSION_DOWNGRADE}, which means nothing on a TV.
     */
    private static String describe(Context c, int status, String message) {
        String code = message == null ? "" : message;
        if (code.contains("INSTALL_FAILED_VERSION_DOWNGRADE")) {
            String text = c.getString(R.string.result_downgrade);
            if (Prefs.allowOlderVersions(c)) {
                text += c.getString(R.string.result_downgrade_note);
            }
            return text;
        }
        if (code.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
            return c.getString(R.string.result_no_space);
        }
        if (code.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                || code.contains("INSTALL_FAILED_ALREADY_EXISTS")) {
            return c.getString(R.string.result_different_signer);
        }
        if (code.contains("INSTALL_FAILED_OLDER_SDK")) {
            return c.getString(R.string.result_needs_newer_android);
        }
        if (code.contains("INSTALL_FAILED_INVALID_APK")) {
            return c.getString(R.string.result_invalid_package);
        }
        if (code.length() > 0) {
            return code;
        }
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return c.getString(R.string.result_cancelled);
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return c.getString(R.string.result_blocked);
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return c.getString(R.string.result_conflict);
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return c.getString(R.string.result_incompatible);
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return c.getString(R.string.result_invalid);
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return c.getString(R.string.result_storage);
            default:
                return c.getString(R.string.result_status, status);
        }
    }
}
