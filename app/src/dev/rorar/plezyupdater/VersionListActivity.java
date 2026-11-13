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
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Release picker for the "Allow older versions" developer option. Returns the chosen tag and
 * its asset URL; the caller downloads and installs it exactly like a normal update.
 */
public class VersionListActivity extends Activity {

    public static final String EXTRA_TAG = "tag";
    public static final String EXTRA_URL = "url";

    private static final int MAX_RELEASES = 30;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PlezyRepo.Release> releases = new ArrayList<PlezyRepo.Release>();

    private ListView list;
    private TextView empty;
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
                + (installedVersion == null ? "none" : installedVersion));
        hint.setTextColor(Color.rgb(150, 168, 182));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setPadding(0, dp(6), 0, dp(12));
        root.addView(hint);

        FrameLayout frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        list = new ListView(this);
        list.setBackgroundColor(Color.rgb(16, 24, 32));
        list.setDivider(null);
        list.setDrawSelectorOnTop(false);
        list.setSelector(focusSelector());
        list.setVerticalScrollBarEnabled(true);
        frame.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setText("Loading releases…");
        empty.setTextColor(Color.rgb(150, 168, 182));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        empty.setGravity(Gravity.CENTER);
        frame.addView(empty, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);

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
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                PlezyRepo.Release release = releases.get(position);
                Intent result = new Intent();
                result.putExtra(EXTRA_TAG, release.tag);
                result.putExtra(EXTRA_URL, release.assetUrl);
                setResult(RESULT_OK, result);
                finish();
            }
        });
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
