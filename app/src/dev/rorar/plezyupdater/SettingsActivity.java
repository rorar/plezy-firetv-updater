package dev.rorar.plezyupdater;

import android.app.Activity;
import android.graphics.Color;
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
 */
public class SettingsActivity extends Activity {

    private LinearLayout content;

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

        addSwitch("Toast notifications", "Short message at the bottom of the screen.",
                Prefs.KEY_TOAST, Prefs.toastEnabled(this));

        addSwitch("Notifications", "Entry in the Fire TV notification center.",
                Prefs.KEY_NOTIFICATION, Prefs.notificationEnabled(this));

        addSwitch("Check automatically", "Every 12 hours and after the TV boots.",
                Prefs.KEY_AUTO_CHECK, Prefs.autoCheckEnabled(this));

        addSwitch("Allow older versions", "Developer option: pick any release, not only newer ones. "
                        + "Android 7.1 still refuses downgrades of release builds.",
                Prefs.KEY_ALLOW_OLDER, Prefs.allowOlderVersions(this));

        addSwitch("Delete APK after installing", "Remove the downloaded file once Plezy is "
                        + "installed, to save space on the stick.",
                Prefs.KEY_DELETE_APK, Prefs.deleteApkAfterInstall(this));

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
        hint.setTextColor(Color.rgb(120, 136, 150));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(24);
        content.addView(hint, hintParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 24, 32));
        scroll.addView(content);
        setContentView(scroll);
    }

    private void addSwitch(String label, String summary, final String key, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        toggle.setChecked(checked);
        toggle.setShowText(false);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(0, dp(12), 0, dp(4));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.set(SettingsActivity.this, key, isChecked);
                apply(key, isChecked);
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(16);
        content.addView(toggle, params);

        TextView description = new TextView(this);
        description.setText(summary);
        description.setTextColor(Color.rgb(150, 168, 182));
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        description.setPadding(dp(8), 0, 0, dp(4));
        content.addView(description);
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
