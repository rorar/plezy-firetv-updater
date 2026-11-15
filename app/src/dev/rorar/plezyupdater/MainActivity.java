package dev.rorar.plezyupdater;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
            progressView.setText("Update failed: " + message);
            Notifier.toast(MainActivity.this, "Update failed: " + message);
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
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        buildUi();

        installFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onInstallFinished();
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
            UpdateAlarm.schedule(this);
        } else {
            UpdateAlarm.cancel(this);
        }
        checkForUpdates(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A *null* read right around a PackageInstaller session is a transient — PackageManager
        // briefly hides the package being replaced — so never downgrade a known version to null
        // here; a real uninstall arrives through InstallFlow.onPlezyRemoved().
        String current = PlezyRepo.installedVersion(this);
        if (current != null) {
            installedVersion = current;
        }

        // Downgrade flow: the uninstall dialog just closed. If Plezy is gone, install the APK we
        // downloaded earlier; if it is still there, the user cancelled and the flow says so.
        installFlow.onResume(installedVersion);

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

    /** The install dialog is done: take the new version and ask GitHub again. */
    private void onInstallFinished() {
        installedVersion = PlezyRepo.installedVersion(this);
        pendingRelease = null;
        installedAtInstallCommit = null;
        renderStatus();
        checkForUpdates(false);
    }

    private void buildUi() {
        int pad = dp(32);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ui.BG);
        content.setPadding(pad, pad, pad, pad);

        TextView title = Ui.title(this, "Plezy Updater");
        Ui.leadingIcon(title, Ui.icon(this, R.drawable.ic_up, Ui.ACCENT_BRIGHT), 28);
        content.addView(title);

        TextView subtitle = Ui.subtitle(this, "Keeps com.edde746.plezy up to date on this TV.");
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

        installButton = Ui.primaryButton(this, "Install update", R.drawable.ic_download);
        installButton.setEnabled(false);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadAndInstall();
            }
        });
        content.addView(installButton, buttonParams(12));

        whatsNewButton = Ui.actionButton(this, "What's new", R.drawable.ic_news);
        whatsNewButton.setVisibility(View.GONE);
        whatsNewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWhatsNew();
            }
        });
        content.addView(whatsNewButton, buttonParams(8));

        checkButton = Ui.actionButton(this, "Check for updates", R.drawable.ic_refresh);
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdates(true);
            }
        });
        content.addView(checkButton, buttonParams(8));

        chooseButton = Ui.actionButton(this, "Choose version…", R.drawable.ic_versions);
        chooseButton.setVisibility(View.GONE);
        chooseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The picker installs the highlighted version itself, so there is no result to read.
                startActivity(new Intent(MainActivity.this, VersionListActivity.class));
            }
        });
        content.addView(chooseButton, buttonParams(8));

        Button settingsButton = Ui.actionButton(this, "Settings", R.drawable.ic_settings);
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
        hintView.setText("Checks " + Prefs.intervalLabel(this)
                + " while the TV is on. An update needs one confirmation on the system "
                + "installer screen.");
    }

    private void renderStatus() {
        String installed = installedVersion == null ? "not installed" : installedVersion;
        int comparison = pendingRelease == null ? 0
                : PlezyRepo.compareVersions(latestVersion, installedVersion);

        String headline;
        int headlineColor;
        int headlineIcon;
        String detail;
        String action;

        if (pendingRelease == null && lastError != null) {
            headline = "Update check failed";
            headlineColor = Ui.ERROR;
            headlineIcon = R.drawable.ic_error;
            detail = "Installed: " + installed;
            action = lastError;
        } else if (pendingRelease != null && installedVersion == null) {
            headline = "Plezy is not installed";
            headlineColor = Ui.WARN;
            headlineIcon = R.drawable.ic_warning;
            detail = "Latest upstream: " + latestVersion;
            action = "Press \"Install update\" to install it.";
        } else if (pendingRelease != null && comparison > 0) {
            headline = "Update available";
            headlineColor = Ui.ACCENT_BRIGHT;
            headlineIcon = R.drawable.ic_up;
            detail = "Installed " + installedVersion + "   ·   latest " + latestVersion;
            action = "Press \"Install update\" to get " + latestVersion + ".";
        } else if (pendingRelease != null && comparison == 0) {
            headline = "Selected " + latestVersion;
            headlineColor = Ui.TEXT_HEADING;
            headlineIcon = R.drawable.ic_info;
            detail = "Installed " + installedVersion + "   ·   same version";
            action = "Installing re-downloads it.";
        } else if (pendingRelease != null) {
            headline = "Selected " + latestVersion;
            headlineColor = Ui.TEXT_HEADING;
            headlineIcon = R.drawable.ic_down;
            detail = "Installed " + installedVersion + "   ·   older";
            action = "Downgrade uninstalls first — enabled by \"Allow older versions\".";
        } else if (latestVersion != null) {
            headline = "Plezy is up to date";
            headlineColor = Ui.OK;
            headlineIcon = R.drawable.ic_check;
            detail = "Installed " + installedVersion + "   ·   latest " + latestVersion;
            action = "";
        } else {
            headline = "Checking for updates…";
            headlineColor = Ui.TEXT_MUTED;
            headlineIcon = R.drawable.ic_refresh;
            detail = installedVersion == null
                    ? "Plezy is not installed." : "Installed " + installedVersion + ".";
            action = "";
        }

        statusHeadline.setText(headline);
        statusHeadline.setTextColor(headlineColor);
        Ui.leadingIcon(statusHeadline, Ui.icon(this, headlineIcon, headlineColor), 26);
        statusView.setText(detail);
        // While a download/install is running, the progress line belongs to that flow.
        if (!busy) {
            progressView.setText(action);
        }

        installButton.setText(pendingRelease == null
                ? "Install update" : "Install " + latestVersion);
        installButton.setEnabled(pendingRelease != null && !busy);

        // The changelog only makes sense once a specific release is selected.
        whatsNewButton.setVisibility(pendingRelease == null ? View.GONE : View.VISIBLE);

        long free = ApkInstaller.freeBytes(getCacheDir());
        if (free >= 0) {
            String text = "Free space: " + ApkInstaller.formatSize(this, free);
            if (free < ApkInstaller.MIN_FREE_SPACE) {
                text += "  — too little for an update (about "
                        + ApkInstaller.formatSize(this, ApkInstaller.MIN_FREE_SPACE)
                        + " needed)";
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
        if (manual) {
            progressView.setText("Checking for updates…");
        }

        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateChecker.Result result = UpdateChecker.check(app);
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
        lastError = result.error;
        pendingRelease = result.isNewer() ? result.release : null;
        hasChecked = true;
        checkedInstalledVersion = result.installedVersion;

        if (result.error != null) {
            if (manual) {
                Notifier.toast(this, "Update check failed");
            }
        } else if (result.isNewer()) {
            if (manual) {
                Notifier.toast(this, "Plezy " + result.latestVersion + " is available");
            }
        } else {
            if (manual) {
                Notifier.toast(this, "Plezy " + result.installedVersion + " is up to date");
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
