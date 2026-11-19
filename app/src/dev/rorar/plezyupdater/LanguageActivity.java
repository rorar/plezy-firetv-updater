package dev.rorar.plezyupdater;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Pick the app's language, or leave it on <b>System default</b>. Each row shows the language's
 * flag and its own name (English, Deutsch, Français, Español), so the list reads the same whatever
 * the app is currently set to.
 *
 * <p>Choosing a language stores it and returns to the main screen, which is rebuilt in the new
 * language. The flags are bundled with the app (no network) — see {@link LocaleHelper#FLAGS}.
 */
public class LanguageActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = Ui.dp(this, 40);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);
        root.addView(Ui.title(this, getString(R.string.language_title)));

        String current = Prefs.appLanguage(this);

        // "System default" first, then one row per shipped language.
        root.addView(row(getString(R.string.language_system), R.drawable.ic_globe,
                LocaleHelper.SYSTEM.equals(current), LocaleHelper.SYSTEM));
        for (int i = 0; i < LocaleHelper.CODES.length; i++) {
            final String code = LocaleHelper.CODES[i];
            root.addView(row(getString(LocaleHelper.NAMES[i]), LocaleHelper.FLAGS[i],
                    code.equals(current), code));
        }

        Button back = Ui.actionButton(this, getString(R.string.action_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backParams.topMargin = Ui.dp(this, 16);
        root.addView(back, backParams);

        // The whole page scrolls: a landscape phone is only ~410 dp tall here, so without this the
        // last language row and the Back button fall off the bottom with no way to reach them.
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(root);
        setContentView(scroll);
        // Put focus on the current choice. Child 0 is the title, then "System default" and the
        // shipped languages, so a language at index i sits at child i + 2 (and "System default"
        // at child 1).
        int index = LocaleHelper.SYSTEM.equals(current) ? 1 : LocaleHelper.indexOf(current) + 2;
        root.getChildAt(index).requestFocus();
    }

    /** One selectable row: flag, name, focus/selected fill. */
    private View row(String label, int flagRes, boolean checked, final String code) {
        TextView row = Ui.text(this, label, 24, Ui.TEXT);
        row.setTextColor(Ui.pickerTextColors());
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.pickerRowSelector());
        row.setPadding(Ui.dp(this, 24), Ui.dp(this, 14), Ui.dp(this, 24), Ui.dp(this, 14));

        Drawable flag = getResources().getDrawable(flagRes, getTheme());
        flag.setBounds(0, 0, Ui.dp(this, 40), Ui.dp(this, 30));
        row.setCompoundDrawablesRelative(flag, null, null, null);
        row.setCompoundDrawablePadding(Ui.dp(this, 16));
        row.setSelected(checked);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose(code);
            }
        });
        return row;
    }

    private void choose(String code) {
        Prefs.setAppLanguage(this, code);
        // Rebuild from the top so every screen re-reads its strings in the new language. MainActivity
        // is singleTop, so CLEAR_TOP lands on onNewIntent(), which recreates it.
        Intent main = new Intent(this, MainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(main);
        finish();
    }
}
