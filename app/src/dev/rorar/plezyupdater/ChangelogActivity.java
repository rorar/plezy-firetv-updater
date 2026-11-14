package dev.rorar.plezyupdater;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Read-only view of one release's notes, opened from the main screen's "What's new". */
public class ChangelogActivity extends Activity {

    public static final String EXTRA_TAG = "tag";
    public static final String EXTRA_PUBLISHED = "published";
    public static final String EXTRA_BODY = "body";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = Ui.dp(this, 40);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        String tag = getIntent().getStringExtra(EXTRA_TAG);
        String published = getIntent().getStringExtra(EXTRA_PUBLISHED);
        String body = getIntent().getStringExtra(EXTRA_BODY);

        root.addView(Ui.title(this, tag == null ? "Release notes" : "Plezy " + tag));

        if (published != null && published.length() >= 10) {
            TextView date = Ui.subtitle(this, published.substring(0, 10));
            LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dateParams.topMargin = Ui.dp(this, 4);
            dateParams.bottomMargin = Ui.dp(this, 12);
            root.addView(date, dateParams);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Ui.rounded(this, Ui.SURFACE, 10));
        int sp = Ui.dp(this, 20);
        scroll.setPadding(sp, Ui.dp(this, 16), sp, Ui.dp(this, 16));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        CharSequence notes = Markdown.toStyled(body, getResources().getDisplayMetrics().density);
        if (notes.length() == 0) {
            notes = "No release notes for this version.";
        }
        TextView bodyView = Ui.body(this, notes);
        scroll.addView(bodyView);

        Button back = Ui.actionButton(this, "Back");
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
}
