package dev.rorar.plezyupdater;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Release picker for the "Allow older versions" developer option. Two panes: the versions on the
 * left, the focused version's release notes on the right. Returns the chosen tag, its asset URL
 * and its changelog; the caller downloads and installs it exactly like a normal update.
 */
public class VersionListActivity extends Activity {

    public static final String EXTRA_TAG = "tag";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_PUBLISHED = "published";

    private static final int MAX_RELEASES = 30;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PlezyRepo.Release> releases = new ArrayList<PlezyRepo.Release>();

    private ListView list;
    private TextView empty;
    private TextView changelog;
    private ScrollView changelogScroll;
    private String installedVersion;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        installedVersion = PlezyRepo.installedVersion(this);
        buildUi();
        loadReleases();
    }

    private void buildUi() {
        int pad = dp(40);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 24, 32));
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Choose version");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Newest first. Installed: "
                + (installedVersion == null ? "none" : installedVersion)
                + "   —   press OK to install the highlighted version.");
        hint.setTextColor(Color.rgb(150, 168, 182));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setPadding(0, dp(6), 0, dp(12));
        root.addView(hint);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout listFrame = new FrameLayout(this);
        columns.addView(listFrame, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        list = new ListView(this);
        list.setBackgroundColor(Color.rgb(16, 24, 32));
        list.setDivider(null);
        list.setSelector(focusSelector());
        list.setVerticalScrollBarEnabled(true);
        listFrame.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setText("Loading releases…");
        empty.setTextColor(Color.rgb(150, 168, 182));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        empty.setGravity(Gravity.CENTER);
        listFrame.addView(empty, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);

        LinearLayout changelogColumn = new LinearLayout(this);
        changelogColumn.setOrientation(LinearLayout.VERTICAL);
        changelogColumn.setPadding(dp(24), 0, 0, 0);
        columns.addView(changelogColumn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.3f));

        TextView changelogHeading = new TextView(this);
        changelogHeading.setText("RELEASE NOTES");
        changelogHeading.setTextColor(Color.rgb(110, 168, 200));
        changelogHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        changelogHeading.setPadding(0, dp(8), 0, dp(6));
        changelogColumn.addView(changelogHeading);

        changelogScroll = new ScrollView(this);
        changelogScroll.setBackgroundColor(Color.rgb(22, 32, 42));
        changelogScroll.setPadding(dp(16), dp(12), dp(16), dp(12));
        changelogColumn.addView(changelogScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        changelog = new TextView(this);
        changelog.setTextColor(Color.rgb(214, 222, 230));
        changelog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        changelog.setText("Select a version to see its release notes.");
        changelogScroll.addView(changelog);

        setContentView(root);
        list.requestFocus();
    }

    private void loadReleases() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<PlezyRepo.Release> loaded = PlezyRepo.fetchReleases(MAX_RELEASES);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            show(loaded);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            empty.setText("Could not load releases:\n"
                                    + (e.getMessage() == null
                                    ? e.getClass().getSimpleName() : e.getMessage()));
                        }
                    });
                }
            }
        }, "plezy-releases").start();
    }

    private void show(List<PlezyRepo.Release> loaded) {
        releases.clear();
        releases.addAll(loaded);
        if (releases.isEmpty()) {
            empty.setText("No releases found.");
            return;
        }
        list.setAdapter(new ReleaseAdapter());
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showChangelog(releases.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PlezyRepo.Release release = releases.get(position);
                Intent result = new Intent();
                result.putExtra(EXTRA_TAG, release.tag);
                result.putExtra(EXTRA_URL, release.assetUrl);
                result.putExtra(EXTRA_BODY, release.body);
                result.putExtra(EXTRA_PUBLISHED, release.publishedAt);
                setResult(RESULT_OK, result);
                finish();
            }
        });
        // Take focus so the D-pad changes the version (and the notes follow). Press right to move
        // into the notes pane to scroll long changelogs, left to come back.
        list.requestFocus();
    }

    private void showChangelog(PlezyRepo.Release release) {
        String notes = Markdown.toPlainText(release.body);
        String header = release.tag + (release.publishedAt == null ? "" : "   " + date(release.publishedAt));
        if (notes.isEmpty()) {
            notes = "No release notes for this version.";
        }
        changelog.setText(header + "\n\n" + notes);
        changelogScroll.scrollTo(0, 0);
    }

    /** "2026-11-13T…Z" → "2026-11-13". */
    static String date(String iso) {
        return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    private static StateListDrawable focusSelector() {
        StateListDrawable selector = new StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_focused},
                new ColorDrawable(Color.rgb(47, 110, 143)));
        selector.addState(new int[]{android.R.attr.state_pressed},
                new ColorDrawable(Color.rgb(47, 110, 143)));
        selector.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return selector;
    }

    private class ReleaseAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return releases.size();
        }

        @Override
        public Object getItem(int position) {
            return releases.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(VersionListActivity.this);
            PlezyRepo.Release release = releases.get(position);
            String label = release.tag;
            if (installedVersion != null
                    && PlezyRepo.compareVersions(release.tag, installedVersion) == 0) {
                label += "   (installed)";
            }
            row.setText(label);
            row.setTextColor(Color.WHITE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(14), dp(20), dp(14));
            return row;
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
