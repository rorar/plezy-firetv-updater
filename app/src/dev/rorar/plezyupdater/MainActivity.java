package dev.rorar.plezyupdater;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The whole UI: what is installed, what is available, and one button to install it.
 * Programmatic layout only — no XML, no support library, nothing to keep in sync.
 */
public class MainActivity extends Activity {

    /** In-app broadcast: an install just finished and the screen should refresh. */
    public static final String ACTION_INSTALL_FINISHED =
            "dev.rorar.plezyupdater.action.INSTALL_FINISHED";

    private static volatile MainActivity instance;

    /**
     * True while the screen exists in this process. The install dialog pauses the activity but
     * keeps it alive, so this stays true through the whole install and the result can refresh
     * the UI directly instead of going through a background notification.
     */
    public static boolean isAlive() {
        return instance != null;
    }

    private TextView statusView;
    private TextView statusHeadline;
    private TextView progressView;
    private TextView storageView;
    private TextView hintView;
    private Button checkButton;
    private Button installButton;
    private Button chooseButton;
    private Button whatsNewButton;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private BroadcastReceiver installFinishedReceiver;
    private volatile boolean busy;
    private PlezyRepo.Release pendingRelease;
    private String installedAtInstallCommit;
    private String installedVersion;
    private String latestVersion;
    private String lastError;
    /** A muted note shown under a version we kept from a previous check (e.g. rate-limited). */
    private String lastNote;
    /** The installed version seen by the last completed check, to spot install/uninstall. */
    private String checkedInstalledVersion;
    private boolean hasChecked;

    private InstallFlow installFlow;

    /** The screen side of the shared download/verify/install pipeline. */
    private final InstallFlow.Callback installCallback = new InstallFlow.Callback() {
        @Override
        public void onProgress(String text) {
            progressView.setText(text);
        }

        @Override
        public void onBusy(boolean value) {
            setBusy(value);
        }

        @Override
        public void onFailed(String message) {
            progressView.setText(getString(R.string.progress_update_failed, message));
            Notifier.toast(MainActivity.this, getString(R.string.progress_update_failed, message));
        }

        @Override
        public void onInstallCommitted() {
            installedAtInstallCommit = installedVersion;
        }

        @Override
        public void onPlezyRemoved() {
            installedVersion = null;
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Reached by CLEAR_TOP after a language change: rebuild so the new strings apply.
        recreate();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        buildUi();

        installFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onInstallFinished(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE));
            }
        };
        registerReceiver(installFinishedReceiver, new IntentFilter(ACTION_INSTALL_FINISHED));

        // The download/verify/install pipeline; it also owns the downgrade uninstall handshake.
        installFlow = new InstallFlow(this, installCallback);
        // Resume a downgrade that was interrupted (e.g. the process was killed in the dialog).
        installFlow.resumePendingDowngrade();

        ApkInstaller.cleanStaleCache(this);
        installedVersion = PlezyRepo.installedVersion(this);
        renderStatus();

        if (Prefs.autoCheckEnabled(this)) {
            UpdateJob.schedule(this);
        } else {
            UpdateJob.cancel(this);
        }
        checkForUpdates(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeRequestNotificationPermission();
        // A *null* read right around a PackageInstaller session is a transient — PackageManager
        // briefly hides the package being replaced — so the screen keeps showing the last known
        // version rather than flickering to "not installed".
        String current = PlezyRepo.installedVersion(this);
        if (current != null) {
            installedVersion = current;
        }

        // Downgrade flow: the uninstall dialog just closed. If Plezy is gone, install the APK we
        // downloaded earlier; if it is still there, the user cancelled and the flow says so. The
        // flow gets the *fresh* read, not the sticky field above: on Android 12+ this resume can
        // run before the ACTION_PACKAGE_REMOVED broadcast, and a stale non-null version would be
        // read as a cancellation, so the downgrade would never install.
        installFlow.onResume(current);

        if (installedAtInstallCommit != null && installedVersion != null
                && !installedVersion.equals(installedAtInstallCommit)) {
            // The install actually went through: drop the now-stale selection.
            if (pendingRelease != null
                    && PlezyRepo.compareVersions(installedVersion, pendingRelease.tag) >= 0) {
                pendingRelease = null;
            }
            installedAtInstallCommit = null;
        }
        chooseButton.setVisibility(Prefs.allowOlderVersions(this) ? View.VISIBLE : View.GONE);
        updateHint();
        renderStatus();
        // Plezy may have been installed or uninstalled while this screen was away (e.g. the
        // Settings screen's uninstall): ask again so the status is not stale. Skipped during a
        // downgrade, where Plezy is deliberately absent.
        if (hasChecked && !busy && !installFlow.isDowngradePending()
                && !same(installedVersion, checkedInstalledVersion)) {
            checkForUpdates(false);
        }
        // An install may have finished while this screen was paused behind the installer.
        OpenPrompt.maybeShow(this);
    }

    @Override
    protected void onDestroy() {
        if (installFinishedReceiver != null) {
            unregisterReceiver(installFinishedReceiver);
            installFinishedReceiver = null;
        }
        if (installFlow != null) {
            installFlow.dispose();
            installFlow = null;
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    /**
     * Ask for {@code POST_NOTIFICATIONS} once, when the app is first opened with notifications
     * switched on (roadmap item 5, step 33). The permission does not exist below API 33, so
     * {@link Notifier#needsPermission} is false on the stick and this does nothing there. The
     * persisted {@code notifAsked} flag keeps the dialog from reappearing on every resume; the
     * Settings row asks again if the user turns notifications back on after denying.
     */
    private void maybeRequestNotificationPermission() {
        if (!Prefs.notificationEnabled(this) || !Notifier.needsPermission(this)
                || Prefs.notifAsked(this)) {
            return;
        }
        Prefs.setNotifAsked(this, true);
        Notifier.requestPermission(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == Notifier.REQUEST_POST_NOTIFICATIONS) {
            // Nothing to redraw here — the Settings row and the diagnostics report the state — but
            // logging the outcome makes a phone-lane run readable from logcat.
            Notifier.logPermissionResult(permissions, grantResults);
        }
    }

    /** The install dialog is done: take the new version, ask GitHub again, offer to open Plezy. */
    private void onInstallFinished(int status) {
        installedVersion = PlezyRepo.installedVersion(this);
        pendingRelease = null;
        installedAtInstallCommit = null;
        renderStatus();
        checkForUpdates(false);
        OpenPrompt.onInstallFinished(this, status);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // The installer screen just closed: this is where a pending "Open Plezy?" lands.
            OpenPrompt.maybeShow(this);
        }
    }

    private void buildUi() {
        int pad = dp(32);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ui.BG);
        content.setPadding(pad, pad, pad, pad);

        TextView title = Ui.title(this, getString(R.string.app_name));
        Ui.leadingIcon(title, Ui.icon(this, R.drawable.ic_up, Ui.ACCENT_BRIGHT), 28);
        content.addView(title);

        TextView subtitle = Ui.subtitle(this, getString(R.string.main_subtitle));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(2);
        content.addView(subtitle, subtitleParams);
        // Status card: a one-line verdict, the versions behind it, and what to do next.
        LinearLayout statusCard = Ui.card(this);
        statusHeadline = Ui.text(this, "", 24, Ui.TEXT);
        statusCard.addView(statusHeadline);
        statusView = Ui.text(this, "", 17, Ui.TEXT_MUTED);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(4);
        statusCard.addView(statusView, detailParams);
        progressView = Ui.text(this, "", 16, Ui.LINK);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(6);
        statusCard.addView(progressView, progressParams);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(12);
        content.addView(statusCard, cardParams);

        installButton = Ui.primaryButton(this, getString(R.string.main_install_update), R.drawable.ic_download);
        installButton.setEnabled(false);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadAndInstall();
            }
        });
        content.addView(installButton, buttonParams(12));

        whatsNewButton = Ui.actionButton(this, getString(R.string.main_whats_new), R.drawable.ic_news);
        whatsNewButton.setVisibility(View.GONE);
        whatsNewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWhatsNew();
            }
        });
        content.addView(whatsNewButton, buttonParams(8));

        checkButton = Ui.actionButton(this, getString(R.string.main_check), R.drawable.ic_refresh);
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdates(true);
            }
        });
        content.addView(checkButton, buttonParams(8));

        chooseButton = Ui.actionButton(this, getString(R.string.main_choose_version), R.drawable.ic_versions);
        chooseButton.setVisibility(View.GONE);
        chooseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The picker installs the highlighted version itself, so there is no result to read.
                startActivity(new Intent(MainActivity.this, VersionListActivity.class));
            }
        });
        content.addView(chooseButton, buttonParams(8));

        Button settingsButton = Ui.actionButton(this, getString(R.string.main_settings), R.drawable.ic_settings);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        content.addView(settingsButton, buttonParams(8));

        hintView = Ui.muted(this, "");
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(14);
        content.addView(hintView, hintParams);

        storageView = Ui.muted(this, "");
        LinearLayout.LayoutParams storageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        storageParams.topMargin = dp(4);
        content.addView(storageView, storageParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(content);
        setContentView(scroll);

        updateHint();
        checkButton.requestFocus();
    }

    private LinearLayout.LayoutParams buttonParams(int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topMarginDp);
        return p;
    }

    /** The footer hint follows the configured interval. */
    private void updateHint() {
        if (hintView == null) {
            return;
        }
        hintView.setText(getString(R.string.main_hint, Prefs.intervalLabel(this)));
    }

    private void renderStatus() {
        String installed = installedVersion == null
                ? getString(R.string.main_not_installed_word) : installedVersion;
        int comparison = pendingRelease == null ? 0
                : PlezyRepo.compareVersions(latestVersion, installedVersion);

        String headline;
        int headlineColor;
        int headlineIcon;
        String detail;
        String action;

        if (pendingRelease == null && lastError != null) {
            headline = getString(R.string.status_check_failed);
            headlineColor = Ui.ERROR;
            headlineIcon = R.drawable.ic_error;
            detail = getString(R.string.status_installed_prefix, installed);
            action = lastError;
        } else if (pendingRelease != null && installedVersion == null) {
            headline = getString(R.string.status_not_installed);
            headlineColor = Ui.WARN;
            headlineIcon = R.drawable.ic_warning;
            detail = getString(R.string.status_latest_upstream, latestVersion);
            action = getString(R.string.status_press_install);
        } else if (pendingRelease != null && comparison > 0) {
            headline = getString(R.string.status_update_available);
            headlineColor = Ui.ACCENT_BRIGHT;
            headlineIcon = R.drawable.ic_up;
            detail = getString(R.string.status_installed_latest, installedVersion, latestVersion);
            action = getString(R.string.status_press_get, latestVersion);
        } else if (pendingRelease != null && comparison == 0) {
            headline = getString(R.string.status_selected, latestVersion);
            headlineColor = Ui.TEXT_HEADING;
            headlineIcon = R.drawable.ic_info;
            detail = getString(R.string.status_same_version, installedVersion);
            action = getString(R.string.status_reinstall);
        } else if (pendingRelease != null) {
            headline = getString(R.string.status_selected, latestVersion);
            headlineColor = Ui.TEXT_HEADING;
            headlineIcon = R.drawable.ic_down;
            detail = getString(R.string.status_older, installedVersion);
            action = getString(R.string.status_downgrade_note);
        } else if (latestVersion != null) {
            headline = getString(R.string.status_up_to_date);
            headlineColor = Ui.OK;
            headlineIcon = R.drawable.ic_check;
            detail = getString(R.string.status_installed_latest, installedVersion, latestVersion);
            action = "";
        } else {
            headline = getString(R.string.status_checking);
            headlineColor = Ui.TEXT_MUTED;
            headlineIcon = R.drawable.ic_refresh;
            detail = installedVersion == null
                    ? getString(R.string.status_not_installed_period)
                    : getString(R.string.status_installed_only, installedVersion);
            action = "";
        }

        statusHeadline.setText(headline);
        statusHeadline.setTextColor(headlineColor);
        Ui.leadingIcon(statusHeadline, Ui.icon(this, headlineIcon, headlineColor), 26);
        // A kept-from-last-time version still shows its status, with the reason underneath.
        if (lastNote != null && !lastNote.isEmpty()) {
            detail = detail + "\n" + lastNote;
        }
        statusView.setText(detail);
        // While a download/install is running, the progress line belongs to that flow.
        if (!busy) {
            progressView.setText(action);
        }

        installButton.setText(pendingRelease == null
                ? getString(R.string.main_install_update)
                : getString(R.string.main_install_version, latestVersion));
        installButton.setEnabled(pendingRelease != null && !busy);

        // The changelog only makes sense once a specific release is selected.
        whatsNewButton.setVisibility(pendingRelease == null ? View.GONE : View.VISIBLE);

        long free = ApkInstaller.freeBytes(getCacheDir());
        if (free >= 0) {
            String text = getString(R.string.free_space, ApkInstaller.formatSize(this, free));
            if (free < ApkInstaller.MIN_FREE_SPACE) {
                text += getString(R.string.free_space_low,
                        ApkInstaller.formatSize(this, ApkInstaller.MIN_FREE_SPACE));
            }
            storageView.setText(text);
        }
    }

    /** Open the release notes for the release that is currently selected. */
    private void showWhatsNew() {
        PlezyRepo.Release release = pendingRelease;
        if (release == null) {
            return;
        }
        Intent intent = new Intent(this, ChangelogActivity.class);
        intent.putExtra(ChangelogActivity.EXTRA_TAG, release.tag);
        intent.putExtra(ChangelogActivity.EXTRA_PUBLISHED, release.publishedAt);
        intent.putExtra(ChangelogActivity.EXTRA_BODY, release.body);
        startActivity(intent);
    }

    private void setBusy(boolean value) {
        busy = value;
        checkButton.setEnabled(!value);
        installButton.setEnabled(!value && pendingRelease != null);
    }

    private void checkForUpdates(final boolean manual) {
        if (busy) {
            return;
        }
        setBusy(true);
        lastError = null;
        lastNote = null;
        if (manual) {
            progressView.setText(getString(R.string.status_checking));
        }

        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateChecker.Result result = UpdateChecker.check(app, manual);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        applyCheckResult(result, manual);
                    }
                });
            }
        }, "plezy-check").start();
    }

    private void applyCheckResult(UpdateChecker.Result result, boolean manual) {
        setBusy(false);
        installedVersion = result.installedVersion;
        latestVersion = result.latestVersion;
        // A release we could still name (even if it came from the last check) is shown normally;
        // the reason it may be stale rides along as a note. Only a check with no version at all
        // is an error.
        lastError = result.release == null ? result.error : null;
        lastNote = result.release != null ? result.error : null;
        pendingRelease = result.isNewer() ? result.release : null;
        hasChecked = true;
        checkedInstalledVersion = result.installedVersion;

        if (result.release == null && result.error != null) {
            if (manual) {
                Notifier.toast(this, getString(R.string.toast_check_failed));
            }
        } else if (result.isNewer()) {
            if (manual) {
                Notifier.toast(this, getString(R.string.toast_available, result.latestVersion));
            }
        } else {
            if (manual) {
                Notifier.toast(this, getString(R.string.toast_up_to_date, result.installedVersion));
            }
        }
        renderStatus();
    }

    private void downloadAndInstall() {
        // The download/verify/install pipeline lives in InstallFlow, shared with the version picker.
        installFlow.install(pendingRelease, installedVersion);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
