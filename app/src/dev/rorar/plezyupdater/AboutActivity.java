package dev.rorar.plezyupdater;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Version, licences and credits, opened from Settings → About.
 *
 * <p>The version is read back from the package manager, so it always matches the APK that is
 * actually installed ({@code build.sh} stamps it at link time). The third-party notice is a raw
 * resource, deliberately not translated: licence text must not drift between languages.
 */
public class AboutActivity extends Activity {

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
        Ui.applyInsets(root, pad);

        root.addView(Ui.title(this, getString(R.string.about_title)));

        TextView version = Ui.subtitle(this, versionLine(this));
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        versionParams.topMargin = Ui.dp(this, 4);
        versionParams.bottomMargin = Ui.dp(this, 12);
        root.addView(version, versionParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Ui.rounded(this, Ui.SURFACE, 10));
        int sp = Ui.dp(this, 20);
        scroll.setPadding(sp, Ui.dp(this, 16), sp, Ui.dp(this, 16));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);

        body.addView(Ui.sectionHeading(this, getString(R.string.about_licenses_heading)));
        TextView licenses = Ui.text(this, readLicenses(), 14, Ui.TEXT_MUTED);
        licenses.setTypeface(Ui.monospace());
        licenses.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams licensesParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        licensesParams.topMargin = Ui.dp(this, 6);
        body.addView(licenses, licensesParams);

        TextView thanksHeading = Ui.sectionHeading(this, getString(R.string.about_thanks_heading));
        LinearLayout.LayoutParams thanksHeadingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        thanksHeadingParams.topMargin = Ui.dp(this, 20);
        body.addView(thanksHeading, thanksHeadingParams);
        TextView thanks = Ui.body(this, getString(R.string.about_thanks_text));
        LinearLayout.LayoutParams thanksParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        thanksParams.topMargin = Ui.dp(this, 6);
        body.addView(thanks, thanksParams);

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

        back.requestFocus();
        setContentView(root);
    }

    /**
     * "Plezy Updater 0.2.0 (2)" — read from the installed package, never hard-coded, so it matches
     * whatever {@code build.sh} stamped at link time. The single source for the version line:
     * Settings shows the same string inline.
     */
    public static String versionLine(Context c) {
        String name = "";
        int code = 0;
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            name = info.versionName == null ? "" : info.versionName;
            code = info.versionCode;
        } catch (Exception ignored) {
            // Fall through: the row still shows the app name without a version.
        }
        return c.getString(R.string.about_version, name, code);
    }

    /** The bundled third-party notice, read from res/raw so it is never translated. */
    private String readLicenses() {
        InputStream in = null;
        try {
            in = getResources().openRawResource(R.raw.licenses);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
        }
    }
}
