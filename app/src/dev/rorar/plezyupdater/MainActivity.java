package dev.rorar.plezyupdater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * The whole UI: what is installed, what is available, and one button to install it.
 * Programmatic layout only — no XML, no support library, nothing to keep in sync.
 */
public class MainActivity extends Activity {

    private static final String TAG = "PlezyUpdater";

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
    private TextView progressView;
    private TextView storageView;
    private Button checkButton;
    private Button installButton;
    private Button chooseButton;
    private Button whatsNewButton;

    private static final int REQUEST_PICK_VERSION = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private BroadcastReceiver installFinishedReceiver;
    private BroadcastReceiver packageRemovedReceiver;
    private volatile boolean busy;
    private PlezyRepo.Release pendingRelease;
    private String installedAtInstallCommit;
    private String installedVersion;
    private String latestVersion;
    private String lastError;
    /** The installed version seen by the last completed check, to spot install/uninstall. */
    private String checkedInstalledVersion;
    private boolean hasChecked;
    /**
     * A verified APK waiting for Plezy to be uninstalled before it can be installed (a downgrade).
     * Non-null only between the uninstall confirmation and the install.
     */
    private File pendingDowngradeApk;

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

        // A downgrade installs only after the uninstall broadcast; catch it here.
        packageRemovedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Uri data = intent.getData();
                if (data != null
                        && PlezyRepo.PACKAGE_NAME.equals(data.getSchemeSpecificPart())) {
                    onPlezyRemoved();
                }
            }
        };
        IntentFilter removed = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        removed.addDataScheme("package");
        registerReceiver(packageRemovedReceiver, removed);

        // Resume a downgrade that was interrupted (e.g. the process was killed in the dialog).
        String pendingTag = Prefs.pendingDowngrade(this);
        if (pendingTag != null) {
            File apk = ApkInstaller.cachedApk(this, pendingTag);
            if (apk != null) {
                pendingDowngradeApk = apk;
            } else {
                Prefs.setPendingDowngrade(this, null);
            }
        }

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
        installedVersion = PlezyRepo.installedVersion(this);

        // Downgrade flow: the uninstall dialog just closed. If Plezy is gone, install the APK we
        // downloaded earlier; if it is still there, the user cancelled and we stop.
        if (pendingDowngradeApk != null && !busy) {
            if (installedVersion == null) {
                installPendingDowngrade();
            } else {
                clearPendingDowngrade();
                progressView.setText("Downgrade cancelled.");
            }
        }

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
        renderStatus();
        // Plezy may have been installed or uninstalled while this screen was away (e.g. the
        // Settings screen's uninstall): ask again so the status is not stale. Skipped during a
        // downgrade, where Plezy is deliberately absent.
        if (hasChecked && !busy && pendingDowngradeApk == null
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
        if (packageRemovedReceiver != null) {
            unregisterReceiver(packageRemovedReceiver);
            packageRemovedReceiver = null;
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_VERSION || resultCode != RESULT_OK || data == null) {
            return;
        }
        String tag = data.getStringExtra(VersionListActivity.EXTRA_TAG);
        String url = data.getStringExtra(VersionListActivity.EXTRA_URL);
        if (tag != null && url != null) {
            pendingRelease = new PlezyRepo.Release(tag, url,
                    data.getStringExtra(VersionListActivity.EXTRA_BODY),
                    data.getStringExtra(VersionListActivity.EXTRA_PUBLISHED));
            latestVersion = tag;
            lastError = null;
            renderStatus();
        }
    }

    private void buildUi() {
        int pad = dp(48);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 24, 32));
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Plezy Updater");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(184, 199, 212));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        root.addView(statusView, statusParams);

        progressView = new TextView(this);
        progressView.setTextColor(Color.rgb(128, 200, 255));
        progressView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(8);
        root.addView(progressView, progressParams);

        installButton = button("Install update");
        installButton.setEnabled(false);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadAndInstall();
            }
        });
        root.addView(installButton, buttonParams());

        whatsNewButton = button("What's new");
        whatsNewButton.setVisibility(View.GONE);
        whatsNewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWhatsNew();
            }
        });
        root.addView(whatsNewButton, buttonParams());

        checkButton = button("Check for updates");
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdates(true);
            }
        });
        root.addView(checkButton, buttonParams());

        chooseButton = button("Choose version…");
        chooseButton.setVisibility(View.GONE);
        chooseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(
                        new Intent(MainActivity.this, VersionListActivity.class),
                        REQUEST_PICK_VERSION);
            }
        });
        root.addView(chooseButton, buttonParams());

        Button settingsButton = button("Settings");
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        root.addView(settingsButton, buttonParams());

        TextView hint = new TextView(this);
        hint.setText("A check runs twice a day while the TV is on. "
                + "An update needs one confirmation on the system installer screen.");
        hint.setTextColor(Color.rgb(120, 136, 150));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(24);
        root.addView(hint, hintParams);

        storageView = new TextView(this);
        storageView.setTextColor(Color.rgb(120, 136, 150));
        storageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams storageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        storageParams.topMargin = dp(6);
        root.addView(storageView, storageParams);

        setContentView(root);
        checkButton.requestFocus();
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        return p;
    }

    private void renderStatus() {
        String installed = installedVersion == null ? "not installed" : installedVersion;
        StringBuilder sb = new StringBuilder();
        sb.append("Installed: ").append(installed);

        int comparison = 0;
        if (pendingRelease != null) {
            comparison = PlezyRepo.compareVersions(latestVersion, installedVersion);
            if (installedVersion == null) {
                sb.append("\nLatest upstream: ").append(latestVersion).append(" (not installed)");
            } else {
                sb.append("\nSelected: ").append(latestVersion);
            }
        } else if (lastError != null) {
            sb.append("\nLast check failed: ").append(lastError);
        } else if (latestVersion != null) {
            sb.append("\nLatest upstream: ").append(latestVersion).append(" (up to date)");
        }
        statusView.setText(sb.toString());

        if (pendingRelease != null) {
            if (installedVersion == null) {
                progressView.setText("Plezy is not installed — press \"Install update\".");
            } else if (comparison > 0) {
                progressView.setText("Update available — press \"Install update\".");
            } else if (comparison == 0) {
                progressView.setText("Same version as installed — installing re-downloads it.");
            } else {
                progressView.setText("Older than installed — enabled by \"Allow older versions\".");
            }
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
            progressView.setText("Check failed: " + result.error);
            if (manual) {
                Notifier.toast(this, "Update check failed");
            }
        } else if (result.isNewer()) {
            progressView.setText("Plezy " + result.latestVersion + " is available.");
            if (manual) {
                Notifier.toast(this, "Plezy " + result.latestVersion + " is available");
            }
        } else {
            progressView.setText("Plezy is up to date.");
            if (manual) {
                Notifier.toast(this, "Plezy " + result.installedVersion + " is up to date");
            }
        }
        renderStatus();
    }

    private void downloadAndInstall() {
        final PlezyRepo.Release release = pendingRelease;
        if (release == null || busy) {
            return;
        }
        setBusy(true);
        progressView.setText("Preparing…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final File tar = new File(getCacheDir(), "plezy-update.tar.gz");
        ApkInstaller.cleanStaleCache(this);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File apk = ApkInstaller.cachedApk(MainActivity.this, release.tag);
                    if (apk != null) {
                        post("Using the cached download of " + release.tag + "…");
                    } else {
                        ApkInstaller.cleanCache(MainActivity.this);
                        apk = new File(getCacheDir(), "plezy-update.apk");
                        ApkInstaller.requireSpace(getCacheDir(), ApkInstaller.MIN_FREE_SPACE);
                        post("Downloading…");
                        try {
                            ApkInstaller.download(release.assetUrl, tar, new ApkInstaller.Progress() {
                                @Override
                                public void onPercent(final int percent) {
                                    post("Downloading… " + percent + "%");
                                }
                            });
                            post("Unpacking…");
                            ApkInstaller.extractApk(tar, apk);
                        } finally {
                            // Never leave the tarball (or a partial one) behind.
                            deleteQuietly(tar);
                        }
                    }

                    final boolean allowOlder = Prefs.allowOlderVersions(MainActivity.this);
                    final ApkInstaller.Verified verified = ApkInstaller.verify(
                            MainActivity.this, apk, allowOlder);
                    // Only a verified APK is worth caching for a retry.
                    ApkInstaller.markCached(MainActivity.this, release.tag);

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

                    post("Opening the installer for " + verified.versionName + "…");
                    ApkInstaller.install(MainActivity.this, apk, allowOlder);
                    installedAtInstallCommit = installedVersion;
                    post("Confirm the install on the TV.");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                        }
                    });
                } catch (final Throwable t) {
                    Log.e(TAG, "update failed", t);
                    final String message = describe(t);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            progressView.setText("Update failed: " + message);
                            setBusy(false);
                            Notifier.toast(MainActivity.this, "Update failed: " + message);
                        }
                    });
                } finally {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        }
                    });
                }
            }

            private void post(final String text) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        progressView.setText(text);
                    }
                });
            }
        }, "plezy-install").start();
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    /**
     * A downgrade needs the installed app gone first. Warn clearly — the uninstall erases Plezy's
     * data — then let the system uninstaller run; {@link #onPlezyRemoved()} installs the APK.
     */
    private void confirmDowngrade(final PlezyRepo.Release release, final File apk,
            final ApkInstaller.Verified verified) {
        pendingDowngradeApk = apk;
        Prefs.setPendingDowngrade(this, release.tag);
        new AlertDialog.Builder(this)
                .setTitle("Downgrade to " + verified.versionName + "?")
                .setMessage("Android 7.1 cannot downgrade this app directly. Plezy will be "
                        + "uninstalled first — its data (servers, settings, downloads) is erased — "
                        + "and then " + verified.versionName + " installed. Android will ask you "
                        + "to confirm the uninstall.")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearPendingDowngrade();
                        progressView.setText("Downgrade cancelled.");
                    }
                })
                .setPositiveButton("Uninstall & install", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        launchUninstallForDowngrade();
                    }
                })
                .show();
    }

    private void launchUninstallForDowngrade() {
        progressView.setText("Uninstalling Plezy…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + PlezyRepo.PACKAGE_NAME));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            clearPendingDowngrade();
            progressView.setText("Update failed: no uninstaller found");
        }
    }

    /** Plezy is gone: finish a pending downgrade by installing the verified APK. */
    private void onPlezyRemoved() {
        installedVersion = null;
        if (pendingDowngradeApk != null) {
            installPendingDowngrade();
        }
    }

    private void installPendingDowngrade() {
        final File apk = pendingDowngradeApk;
        if (apk == null || busy) {
            return;
        }
        pendingDowngradeApk = null;
        Prefs.setPendingDowngrade(this, null);
        setBusy(true);
        progressView.setText("Installing…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Plezy is uninstalled, so this is a plain install — no downgrade flag needed.
                    ApkInstaller.install(MainActivity.this, apk, false);
                    installedAtInstallCommit = null;
                    post("Confirm the install on the TV.");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                        }
                    });
                } catch (final Throwable t) {
                    Log.e(TAG, "downgrade install failed", t);
                    final String message = describe(t);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            progressView.setText("Update failed: " + message);
                            setBusy(false);
                            Notifier.toast(MainActivity.this, "Update failed: " + message);
                        }
                    });
                } finally {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            getWindow().clearFlags(
                                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        }
                    });
                }
            }

            private void post(final String text) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        progressView.setText(text);
                    }
                });
            }
        }, "plezy-downgrade-install").start();
    }

    private void clearPendingDowngrade() {
        pendingDowngradeApk = null;
        Prefs.setPendingDowngrade(this, null);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void deleteQuietly(File f) {
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "could not delete " + f);
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
