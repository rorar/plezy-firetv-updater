package dev.rorar.plezyupdater;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
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
        int pad = dp(48);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 24, 32));
        root.setPadding(pad, pad, pad, pad);

        String tag = getIntent().getStringExtra(EXTRA_TAG);
        String published = getIntent().getStringExtra(EXTRA_PUBLISHED);
        String body = getIntent().getStringExtra(EXTRA_BODY);

        TextView title = new TextView(this);
        title.setText(tag == null ? "Release notes" : "Plezy " + tag);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        root.addView(title);

        if (published != null && published.length() >= 10) {
            TextView date = new TextView(this);
            date.setText(published.substring(0, 10));
            date.setTextColor(Color.rgb(150, 168, 182));
            date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            date.setPadding(0, dp(4), 0, dp(12));
            root.addView(date);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(22, 32, 42));
        scroll.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView notes = new TextView(this);
        String text = Markdown.toPlainText(body);
        notes.setText(text.isEmpty() ? "No release notes for this version." : text);
        notes.setTextColor(Color.rgb(214, 222, 230));
        notes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        notes.setTextIsSelectable(false);
        scroll.addView(notes);

        Button back = new Button(this);
        back.setText("Back");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        back.setTextColor(Color.rgb(226, 233, 240));
        back.setFocusable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backParams.topMargin = dp(16);
        root.addView(back, backParams);

        back.requestFocus();
        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
