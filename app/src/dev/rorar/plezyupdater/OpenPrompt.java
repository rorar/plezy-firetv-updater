package dev.rorar.plezyupdater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Button;

/**
 * The "Open Plezy?" offer after an install.
 *
 * <p>An install is confirmed on the system installer screen, which pauses the updater; when that
 * screen closes, the updater comes back and this asks whether to start the app that was just
 * installed. Both the dashboard and the version picker listen for the same install-finished
 * broadcast, so the state lives here rather than on either screen: the first screen that has
 * window focus claims the prompt, and the other one finds it already taken.
 */
public final class OpenPrompt {

    private static final String TAG = "PlezyUpdater";

    /** An install succeeded and no screen has offered to open Plezy yet. */
    private static boolean pending;
    /** A dialog is up (or was just dismissed); never raise a second one. */
    private static boolean showing;

    private OpenPrompt() {
    }

    /**
     * An install finished. Only a success is worth a prompt, and only while the setting is on.
     * Safe to call from both screens' finished handlers: the second call is a no-op.
     */
    public static synchronized void onInstallFinished(Activity activity, int status) {
        if (status != PackageInstaller.STATUS_SUCCESS || !Prefs.openPromptEnabled(activity)) {
            return;
        }
        pending = true;
        maybeShow(activity);
    }

    /**
     * Show the pending prompt if this activity is the focused one. Call from the install-finished
     * handler, {@code onResume()} and {@code onWindowFocusChanged(true)}: the broadcast can arrive
     * before the updater has focus back from the installer, and one of those three is the first
     * moment it does.
     */
    public static synchronized void maybeShow(Activity activity) {
        if (!pending || showing) {
            return;
        }
        if (!Prefs.openPromptEnabled(activity)) {
            pending = false;
            return;
        }
        // Only the screen the user is actually looking at may raise the dialog. The other listener
        // leaves the pending flag alone; if this screen never gains focus the prompt simply waits.
        if (!activity.hasWindowFocus() || activity.isFinishing()) {
            return;
        }
        String version = PlezyRepo.installedVersion(activity);
        if (version == null) {
            // Installed and then removed before the prompt could show; there is nothing to open.
            pending = false;
            return;
        }
        pending = false;
        showing = true;
        show(activity, version);
    }

    private static void show(final Activity activity, String version) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.open_prompt_title))
                .setMessage(activity.getString(R.string.open_prompt_message, version))
                .setPositiveButton(activity.getString(R.string.open_prompt_open),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                open(activity);
                            }
                        })
                .setNegativeButton(activity.getString(R.string.open_prompt_not_now), null)
                .create();
        // "Not now" holds the focus, so a stray OK on the remote cannot launch the app. Two
        // details matter: the request has to be posted (AlertDialog assigns focus to the positive
        // button after onShow returns, overriding a synchronous call), and the button has to be
        // focusable in touch mode as well — otherwise View.requestFocus() refuses on a device that
        // happens to be in touch mode (e.g. a phone, or after an injected tap), and the dialog
        // silently falls back to focusing "Open".
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                final Button notNow = ((AlertDialog) d).getButton(DialogInterface.BUTTON_NEGATIVE);
                if (notNow != null) {
                    notNow.setFocusableInTouchMode(true);
                    notNow.post(new Runnable() {
                        @Override
                        public void run() {
                            notNow.requestFocus();
                        }
                    });
                }
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                showing = false;
            }
        });
        dialog.show();
    }

    /** Start Plezy through its leanback entry point, falling back to its normal launcher. */
    private static void open(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(PlezyRepo.PACKAGE_NAME);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(PlezyRepo.PACKAGE_NAME);
        }
        if (intent == null) {
            Log.w(TAG, "no launch intent for " + PlezyRepo.PACKAGE_NAME);
            Notifier.toast(activity, activity.getString(R.string.open_prompt_failed));
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "could not open Plezy", e);
            Notifier.toast(activity, activity.getString(R.string.open_prompt_failed));
        }
    }
}
