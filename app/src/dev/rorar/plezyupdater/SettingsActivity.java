package dev.rorar.plezyupdater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
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
 * The two notification channels are switchable, because on a TV both can be annoying:
 * a toast interrupts the bottom of the screen, a notification lives in the Fire TV center.
 * Turning a channel off suppresses it everywhere — every message goes through
 * {@link Notifier}, which reads these switches.
 *
 * <p>The rows are grouped into user / advanced / developer sections, and the focus colours are
 * set explicitly: the Fire OS default paints black text on the blue focus highlight, which is
 * unreadable on a dark background.
 */
public class SettingsActivity extends Activity {

    private static final int TEXT = Color.rgb(226, 233, 240);
    private static final int TEXT_MUTED = Color.rgb(150, 168, 182);
    private static final int TEXT_HEADING = Color.rgb(110, 168, 200);
    private static final int FOCUS_BG = Color.rgb(47, 110, 143);

    private LinearLayout content;
    private Button uninstallButton;
    private TextView uninstallSummary;
    private BroadcastReceiver packageRemovedReceiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int pad = dp(48);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.rgb(16, 24, 32));
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        content.addView(title);

        addSection("User settings");
        addSwitch("Toast notifications", "Short message at the bottom of the screen.",
                Prefs.KEY_TOAST, Prefs.toastEnabled(this));
        addSwitch("Notifications", "Entry in the Fire TV notification center.",
                Prefs.KEY_NOTIFICATION, Prefs.notificationEnabled(this));

        addSection("Advanced settings");
        addSwitch("Check automatically", "Every 12 hours and after the TV boots.",
                Prefs.KEY_AUTO_CHECK, Prefs.autoCheckEnabled(this));
        addSwitch("Delete APK after installing", "Remove the downloaded file once Plezy is "
                        + "installed, to save space on the stick.",
                Prefs.KEY_DELETE_APK, Prefs.deleteApkAfterInstall(this));
        addUninstall();

        addSection("Developer settings");
        addSwitch("Allow older versions", "Developer option: pick any release, not only newer ones. "
                        + "Android 7.1 still refuses downgrades of release builds.",
                Prefs.KEY_ALLOW_OLDER, Prefs.allowOlderVersions(this));

        addSection("Diagnostics");

        Button testToast = button("Test toast");
        testToast.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Notifier.toast(SettingsActivity.this,
                        "Test toast — updates will look like this");
            }
        });
        content.addView(testToast, buttonParams());

        Button testNotification = button("Test notification");
        testNotification.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Notifier.notification(SettingsActivity.this, "Plezy Updater",
                        "Test notification — press OK to open");
            }
        });
        content.addView(testNotification, buttonParams());

        Button back = button("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        content.addView(back, buttonParams());

        TextView hint = new TextView(this);
        hint.setText("A switched-off channel stays silent, including the test buttons above.");
        hint.setTextColor(TEXT_MUTED);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(24);
        content.addView(hint, hintParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 24, 32));
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
    protected void onDestroy() {
        if (packageRemovedReceiver != null) {
            unregisterReceiver(packageRemovedReceiver);
            packageRemovedReceiver = null;
        }
        super.onDestroy();
    }

    private void addUninstall() {
        uninstallButton = button("Uninstall Plezy…");
        uninstallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmUninstall();
            }
        });
        content.addView(uninstallButton, buttonParams());

        uninstallSummary = new TextView(this);
        uninstallSummary.setTextColor(TEXT_MUTED);
        uninstallSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        uninstallSummary.setPadding(dp(12), 0, 0, dp(4));
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
        TextView heading = new TextView(this);
        heading.setText(name.toUpperCase(java.util.Locale.US));
        heading.setTextColor(TEXT_HEADING);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(28);
        params.bottomMargin = dp(2);
        content.addView(heading, params);
    }

    private void addSwitch(String label, String summary, final String key, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        toggle.setTextColor(rowTextColors());
        toggle.setBackground(focusBackground());
        toggle.setChecked(checked);
        toggle.setShowText(false);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(dp(12), dp(12), dp(12), dp(12));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.set(SettingsActivity.this, key, isChecked);
                apply(key, isChecked);
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        content.addView(toggle, params);

        TextView description = new TextView(this);
        description.setText(summary);
        description.setTextColor(TEXT_MUTED);
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        description.setPadding(dp(12), 0, 0, dp(4));
        content.addView(description);
    }

    /** White while focused so the row stays readable on the blue highlight. */
    private static ColorStateList rowTextColors() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{Color.WHITE, TEXT});
    }

    private static StateListDrawable focusBackground() {
        StateListDrawable selector = new StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(FOCUS_BG));
        selector.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(FOCUS_BG));
        selector.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return selector;
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

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTextColor(TEXT);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        return p;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
