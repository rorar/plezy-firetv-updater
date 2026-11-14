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
import android.util.TypedValue;
import android.view.Gravity;
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
    private BroadcastReceiver packageRemovedReceiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int pad = Ui.dp(this, 48);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ui.BG);
        content.setPadding(pad, pad, pad, pad);

        content.addView(Ui.title(this, "Settings"));

        addSection("User settings");
        addSwitch("Toast notifications", "Short message at the bottom of the screen.",
                Prefs.KEY_TOAST, Prefs.toastEnabled(this));
        addSwitch("Notifications", "Entry in the Fire TV notification center.",
                Prefs.KEY_NOTIFICATION, Prefs.notificationEnabled(this));

        addSection("Advanced settings");
        autoCheckSummary = addSwitch("Check automatically",
                "Runs on the interval below and after the TV boots.",
                Prefs.KEY_AUTO_CHECK, Prefs.autoCheckEnabled(this));
        addInterval();
        addSwitch("Delete APK after installing", "Remove the downloaded file once Plezy is "
                        + "installed, to save space on the stick.",
                Prefs.KEY_DELETE_APK, Prefs.deleteApkAfterInstall(this));
        addUninstall();

        addSection("Developer settings");
        addSwitch("Allow older versions", "Developer option: pick any release, not only newer ones.",
                Prefs.KEY_ALLOW_OLDER, Prefs.allowOlderVersions(this));

        addSection("Diagnostics");

        Button testToast = Ui.actionButton(this, "Test toast");
        testToast.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Notifier.toast(SettingsActivity.this,
                        "Test toast — updates will look like this");
            }
        });
        content.addView(testToast, buttonParams(8));

        Button testNotification = Ui.actionButton(this, "Test notification");
        testNotification.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Notifier.notification(SettingsActivity.this, "Plezy Updater",
                        "Test notification — press OK to open");
            }
        });
        content.addView(testNotification, buttonParams(12));

        Button back = Ui.actionButton(this, "Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        content.addView(back, buttonParams(12));

        TextView hint = Ui.muted(this,
                "A switched-off channel stays silent, including the test buttons above.");
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
                    Notifier.toast(SettingsActivity.this, "Plezy uninstalled");
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

    private void addInterval() {
        intervalButton = Ui.actionButton(this, "Check interval");
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
            intervalSummary.setText("Now " + Prefs.intervalLabel(this) + ".");
        }
        if (autoCheckSummary != null) {
            autoCheckSummary.setText("Runs " + Prefs.intervalLabel(this)
                    + " and after the TV boots.");
        }
    }

    private void addUninstall() {
        uninstallButton = Ui.actionButton(this, "Uninstall Plezy…");
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
                ? "Removes com.edde746.plezy and erases its data (servers, settings, downloads)."
                : "Plezy is not installed.");
    }

    /**
     * The system uninstall dialog is the only way to remove an app on API 25. Ask first, because a
     * fresh install afterwards starts from an empty app — everything Plezy stored is gone.
     */
    private void confirmUninstall() {
        new AlertDialog.Builder(this)
                .setTitle("Uninstall Plezy?")
                .setMessage("This removes Plezy and erases its data — servers, settings and "
                        + "downloads. Android will ask you to confirm.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Uninstall", new DialogInterface.OnClickListener() {
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
            Notifier.toast(this, "No uninstaller found");
        }
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
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        toggle.setTextColor(Ui.rowTextColors());
        toggle.setBackground(Ui.focusBackground(this));
        toggle.setChecked(checked);
        toggle.setShowText(false);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
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
        t.setPadding(Ui.dp(this, 12), 0, 0, Ui.dp(this, 4));
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
                Notifier.toast(this, "Toast notifications on");
            }
        } else if (Prefs.KEY_NOTIFICATION.equals(key)) {
            if (enabled) {
                Notifier.notification(this, "Plezy Updater", "Notifications are on");
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
