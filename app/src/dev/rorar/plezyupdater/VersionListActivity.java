package dev.rorar.plezyupdater;

import android.app.Activity;
import android.content.Intent;
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
    private TextView changelogHeader;
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
        int pad = Ui.dp(this, 40);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.title(this, "Choose version"));

        TextView hint = Ui.subtitle(this, "Newest first. Installed: "
                + (installedVersion == null ? "none" : installedVersion)
                + "   —   press OK to install the highlighted version.");
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = Ui.dp(this, 6);
        hintParams.bottomMargin = Ui.dp(this, 12);
        root.addView(hint, hintParams);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout listFrame = new FrameLayout(this);
        columns.addView(listFrame, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        list = new ListView(this);
        list.setBackgroundColor(Ui.BG);
        list.setDivider(null);
        list.setSelector(Ui.listSelector());
        list.setVerticalScrollBarEnabled(true);
        listFrame.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        empty = Ui.text(this, "Loading releases…", 18, Ui.TEXT_MUTED);
        empty.setGravity(Gravity.CENTER);
        listFrame.addView(empty, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);

        LinearLayout changelogColumn = new LinearLayout(this);
        changelogColumn.setOrientation(LinearLayout.VERTICAL);
        changelogColumn.setPadding(Ui.dp(this, 24), 0, 0, 0);
        columns.addView(changelogColumn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.3f));

        TextView changelogHeading = Ui.sectionHeading(this, "Release notes");
        changelogHeading.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 6));
        changelogColumn.addView(changelogHeading);

        changelogHeader = Ui.text(this, "", 20, android.graphics.Color.WHITE);
        changelogColumn.addView(changelogHeader);

        changelogScroll = new ScrollView(this);
        changelogScroll.setBackground(Ui.rounded(this, Ui.SURFACE, 10));
        changelogScroll.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                Ui.dp(this, 16), Ui.dp(this, 12));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = Ui.dp(this, 8);
        changelogColumn.addView(changelogScroll, scrollParams);

        changelog = Ui.body(this, "Select a version to see its release notes.");
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
        changelogHeader.setText(release.tag
                + (release.publishedAt == null ? "" : "   " + date(release.publishedAt)));
        CharSequence notes = Markdown.toStyled(release.body,
                getResources().getDisplayMetrics().density);
        if (notes.length() == 0) {
            notes = "No release notes for this version.";
        }
        changelog.setText(notes);
        changelogScroll.scrollTo(0, 0);
    }

    /** "2026-11-13T…Z" → "2026-11-13". */
    static String date(String iso) {
        return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : iso;
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
            row.setTextColor(Ui.TEXT);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(VersionListActivity.this, 20), Ui.dp(VersionListActivity.this, 14),
                    Ui.dp(VersionListActivity.this, 20), Ui.dp(VersionListActivity.this, 14));
            return row;
        }
    }
}
