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
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The notification channels are switchable, because on a TV both can be annoying: a toast
 * interrupts the bottom of the screen, a notification lives in the Fire TV center. Turning a
 * channel off suppresses it everywhere — every message goes through {@link Notifier}, which reads
 * these switches.
 *
 * <p>Rows are grouped into user / advanced / developer sections with a diagnostics block at the
 * bottom, and styled through {@link Ui} so focus is always white-on-accent.
 */
public class SettingsActivity extends Activity {

    private static final int REQUEST_INTERVAL = 1;

    private LinearLayout content;
    private Button uninstallButton;
    private TextView uninstallSummary;
    private Button intervalButton;
    private TextView intervalSummary;
    private TextView autoCheckSummary;
    private TextView languageSummary;
    private Button quotaButton;
    private TextView quotaSummary;
    private BroadcastReceiver packageRemovedReceiver;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int pad = Ui.dp(this, 48);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ui.BG);
        content.setPadding(pad, pad, pad, pad);

        content.addView(Ui.title(this, getString(R.string.settings_title)));

        addSection(getString(R.string.section_user));
        addLanguage();
        addSwitch(getString(R.string.set_toast_label), getString(R.string.set_toast_desc),
                Prefs.KEY_TOAST, Prefs.toastEnabled(this));
        addSwitch(getString(R.string.set_notification_label), getString(R.string.set_notification_desc),
                Prefs.KEY_NOTIFICATION, Prefs.notificationEnabled(this));
        addSwitch(getString(R.string.set_open_prompt_label), getString(R.string.set_open_prompt_desc),
                Prefs.KEY_OPEN_PROMPT, Prefs.openPromptEnabled(this));

        addSection(getString(R.string.section_advanced));
        autoCheckSummary = addSwitch(getString(R.string.set_auto_label),
                getString(R.string.set_auto_desc),
                Prefs.KEY_AUTO_CHECK, Prefs.autoCheckEnabled(this));
        addInterval();
        addSwitch(getString(R.string.set_delete_label), getString(R.string.set_delete_desc),
                Prefs.KEY_DELETE_APK, Prefs.deleteApkAfterInstall(this));
        addUninstall();

        addSection(getString(R.string.section_developer));
        addSwitch(getString(R.string.set_allow_older_label), getString(R.string.set_allow_older_desc),
                Prefs.KEY_ALLOW_OLDER, Prefs.allowOlderVersions(this));

        addSection(getString(R.string.section_diagnostics));
        addQuota();

        Button testToast = Ui.actionButton(this, getString(R.string.set_test_toast));
        testToast.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Notifier.toast(SettingsActivity.this, getString(R.string.set_test_toast_msg));
            }
        });
        content.addView(testToast, buttonParams(8));

        Button testNotification = Ui.actionButton(this, getString(R.string.set_test_notification));
        testNotification.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Notifier.notification(SettingsActivity.this, getString(R.string.notifications_title),
                        getString(R.string.set_test_notification_msg));
            }
        });
        content.addView(testNotification, buttonParams(12));

        addSection(getString(R.string.section_about));
        Button about = Ui.actionButton(this, getString(R.string.about_label));
        about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
            }
        });
        content.addView(about, buttonParams(8));
        TextView aboutVersion = summary();
        aboutVersion.setText(AboutActivity.versionLine(this));
        content.addView(aboutVersion);

        Button back = Ui.actionButton(this, getString(R.string.action_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        content.addView(back, buttonParams(12));

        TextView hint = Ui.muted(this, getString(R.string.set_channels_hint));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = Ui.dp(this, 24);
        content.addView(hint, hintParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(content);
        setContentView(scroll);

        // Refresh the uninstall row when Android reports the removal, so the summary is correct
        // even before the screen is resumed.
        packageRemovedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Uri data = intent.getData();
                if (data != null
                        && PlezyRepo.PACKAGE_NAME.equals(data.getSchemeSpecificPart())) {
                    refreshUninstallRow();
                    Notifier.toast(SettingsActivity.this, getString(R.string.toast_uninstalled));
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        registerReceiver(packageRemovedReceiver, filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUninstallRow();
        refreshLanguageRow();
        refreshQuotaRow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INTERVAL) {
            refreshIntervalRow();
        }
    }

    @Override
    protected void onDestroy() {
        if (packageRemovedReceiver != null) {
            unregisterReceiver(packageRemovedReceiver);
            packageRemovedReceiver = null;
        }
        super.onDestroy();
    }

    /** The Language row: opens the picker; its summary shows what is chosen. */
    private void addLanguage() {
        Button languageButton = Ui.actionButton(this, getString(R.string.language_label));
        languageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, LanguageActivity.class));
            }
        });
        content.addView(languageButton, buttonParams(8));

        languageSummary = summary();
        content.addView(languageSummary);
        refreshLanguageRow();
    }

    private void refreshLanguageRow() {
        if (languageSummary == null) {
            return;
        }
        String current = Prefs.appLanguage(this);
        if (LocaleHelper.SYSTEM.equals(current)) {
            String device = LocaleHelper.systemCode(this);
            languageSummary.setText(
                    getString(R.string.language_system_current, LocaleHelper.name(this, device)));
        } else {
            languageSummary.setText(LocaleHelper.name(this, current));
        }
    }

    private void addInterval() {
        intervalButton = Ui.actionButton(this, getString(R.string.set_interval_label));
        intervalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(
                        new Intent(SettingsActivity.this, IntervalActivity.class),
                        REQUEST_INTERVAL);
            }
        });
        content.addView(intervalButton, buttonParams(12));

        intervalSummary = summary();
        content.addView(intervalSummary);
        refreshIntervalRow();
    }

    private void refreshIntervalRow() {
        if (intervalSummary != null) {
            intervalSummary.setText(getString(R.string.set_interval_now, Prefs.intervalLabel(this)));
        }
        if (autoCheckSummary != null) {
            autoCheckSummary.setText(getString(R.string.set_interval_runs, Prefs.intervalLabel(this)));
        }
    }

    private void addUninstall() {
        uninstallButton = Ui.actionButton(this, getString(R.string.set_uninstall_label));
        uninstallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmUninstall();
            }
        });
        content.addView(uninstallButton, buttonParams(12));

        uninstallSummary = summary();
        content.addView(uninstallSummary);
        refreshUninstallRow();
    }

    private void refreshUninstallRow() {
        if (uninstallButton == null) {
            return;
        }
        boolean installed = PlezyRepo.installedVersion(this) != null;
        uninstallButton.setEnabled(installed);
        uninstallSummary.setText(installed
                ? getString(R.string.set_uninstall_present)
                : getString(R.string.set_uninstall_absent));
    }

    /**
     * The system uninstall dialog is the only way to remove an app on API 25. Ask first, because a
     * fresh install afterwards starts from an empty app — everything Plezy stored is gone.
     */
    private void confirmUninstall() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.set_uninstall_title))
                .setMessage(getString(R.string.set_uninstall_message))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setPositiveButton(getString(R.string.set_uninstall_confirm),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                launchUninstall();
                            }
                        })
                .show();
    }

    private void launchUninstall() {
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + PlezyRepo.PACKAGE_NAME));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Notifier.toast(this, getString(R.string.toast_no_uninstaller));
        }
    }

    /**
     * The GitHub quota row. It is worth showing because the whole app budgets around that limit
     * (see {@link PlezyRepo}) and the reset time is otherwise invisible. The same check can be
     * triggered remotely with {@code am broadcast} — see {@link DiagnosticsReceiver}.
     */
    private void addQuota() {
        quotaButton = Ui.actionButton(this, getString(R.string.set_quota_label));
        quotaButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkQuota();
            }
        });
        content.addView(quotaButton, buttonParams(8));

        quotaSummary = summary();
        content.addView(quotaSummary);
        refreshQuotaRow();
    }

    /** Show the last stored quota, without a network call. */
    private void refreshQuotaRow() {
        if (quotaSummary == null) {
            return;
        }
        quotaSummary.setText(Diagnostics.summary(this, Prefs.quotaRemaining(this),
                Prefs.quotaLimit(this), Prefs.quotaResetMs(this)));
    }

    /** Ask GitHub for the quota off the main thread, then show the answer. */
    private void checkQuota() {
        if (quotaButton == null || !quotaButton.isEnabled()) {
            return;
        }
        quotaButton.setEnabled(false);
        quotaSummary.setText(getString(R.string.set_quota_checking));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Diagnostics.QuotaResult result = Diagnostics.checkQuota(SettingsActivity.this);
                Diagnostics.writeReport(SettingsActivity.this, result.report);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        quotaButton.setEnabled(true);
                        quotaSummary.setText(result.ok
                                ? result.message
                                : getString(R.string.set_quota_failed, result.message));
                    }
                });
            }
        }, "quota-check").start();
    }

    private void addSection(String name) {
        TextView heading = Ui.sectionHeading(this, name);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 28);
        params.bottomMargin = Ui.dp(this, 2);
        content.addView(heading, params);
    }

    /** Returns the description view so callers can update it later. */
    private TextView addSwitch(String label, String description, final String key, boolean checked) {
        Switch toggle = Ui.switchRow(this, label);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.set(SettingsActivity.this, key, isChecked);
                apply(key, isChecked);
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 8);
        content.addView(toggle, params);

        TextView descriptionView = summary();
        descriptionView.setText(description);
        content.addView(descriptionView);
        return descriptionView;
    }

    private TextView summary() {
        TextView t = Ui.muted(this, "");
        t.setPadding(Ui.dp(this, 24), 0, 0, Ui.dp(this, 4));
        return t;
    }

    private void apply(String key, boolean enabled) {
        if (Prefs.KEY_AUTO_CHECK.equals(key)) {
            if (enabled) {
                UpdateAlarm.schedule(this);
            } else {
                UpdateAlarm.cancel(this);
            }
        } else if (Prefs.KEY_TOAST.equals(key)) {
            if (enabled) {
                Notifier.toast(this, getString(R.string.toast_toast_on));
            }
        } else if (Prefs.KEY_NOTIFICATION.equals(key)) {
            if (enabled) {
                Notifier.notification(this, getString(R.string.notifications_title),
                        getString(R.string.toast_notifications_on));
            } else {
                Notifier.cancelNotification(this);
            }
        }
    }

    private LinearLayout.LayoutParams buttonParams(int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, topMarginDp);
        return p;
    }
}
