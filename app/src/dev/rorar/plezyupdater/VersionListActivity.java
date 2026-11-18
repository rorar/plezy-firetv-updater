package dev.rorar.plezyupdater;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
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
 * left, the focused version's release notes on the right.
 *
 * <p>Pressing OK on a version installs it right here through {@link InstallFlow} — there is no
 * detour back to the main screen just to press Install. (The main screen keeps the plain
 * "install the latest" flow for when Plezy is not installed at all.)
 */
public class VersionListActivity extends Activity {

    private static final int MAX_RELEASES = 30;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PlezyRepo.Release> releases = new ArrayList<PlezyRepo.Release>();

    private ListView list;
    private TextView empty;
    private TextView hint;
    private TextView progressView;
    private TextView changelogHeader;
    private TextView changelog;
    private ScrollView changelogScroll;
    private String installedVersion;

    private InstallFlow installFlow;
    private BroadcastReceiver installFinishedReceiver;

    /** The screen side of the shared download/verify/install pipeline. */
    private final InstallFlow.Callback installCallback = new InstallFlow.Callback() {
        @Override
        public void onProgress(String text) {
            progressView.setText(text);
        }

        @Override
        public void onBusy(boolean busy) {
            // A running download/install owns the screen: the list may not be re-triggered.
            list.setEnabled(!busy);
        }

        @Override
        public void onFailed(String message) {
            progressView.setText(getString(R.string.toast_install_failed, message));
            Notifier.toast(VersionListActivity.this, getString(R.string.toast_install_failed, message));
        }

        @Override
        public void onInstallCommitted() {
            progressView.setText(getString(R.string.progress_confirm));
        }

        @Override
        public void onPlezyRemoved() {
            installedVersion = null;
            updateHint();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        installedVersion = PlezyRepo.installedVersion(this);
        buildUi();
        installFlow = new InstallFlow(this, installCallback);

        // The PackageInstaller session reports its outcome here; without this the progress line
        // would stay on "Confirm the install on the TV." after the install already finished.
        installFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onInstallFinished(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE));
            }
        };
        Receivers.register(this, installFinishedReceiver,
                new IntentFilter(MainActivity.ACTION_INSTALL_FINISHED));

        loadReleases();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // An install (from here or elsewhere) may have finished while this screen was paused.
        // A *null* read right around a PackageInstaller session is a transient — PackageManager
        // briefly hides the package being replaced — so the screen keeps showing the last known
        // version rather than flickering to "not installed".
        String current = PlezyRepo.installedVersion(this);
        if (current != null) {
            installedVersion = current;
        }
        // The flow gets the *fresh* read, not the sticky field above: only a fresh read says
        // whether a pending downgrade's uninstall really happened (null → install the APK we
        // already downloaded) or was cancelled (non-null → say so). On Android 12+ this resume
        // can run *before* the ACTION_PACKAGE_REMOVED broadcast, so passing the sticky field
        // would be read as a cancellation and the downgrade would never install.
        installFlow.onResume(current);
        refreshList();
        // An install may have finished while this screen was paused behind the installer.
        OpenPrompt.maybeShow(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // The installer screen just closed: this is where a pending "Open Plezy?" lands.
            OpenPrompt.maybeShow(this);
        }
    }

    @Override
    protected void onDestroy() {
        if (installFinishedReceiver != null) {
            unregisterReceiver(installFinishedReceiver);
            installFinishedReceiver = null;
        }
        if (installFlow != null) {
            installFlow.dispose();
            installFlow = null;
        }
        super.onDestroy();
    }

    /** The installer session ended: word the outcome and re-read the installed version. */
    private void onInstallFinished(int status) {
        String current = PlezyRepo.installedVersion(this);
        if (current != null) {
            installedVersion = current;
        }
        if (status == PackageInstaller.STATUS_SUCCESS && installedVersion != null) {
            progressView.setText(getString(R.string.progress_installed, installedVersion));
        }
        // On failure the InstallResultReceiver toast already says what went wrong; leave the
        // progress line as it was rather than repeating it.
        refreshList();
        OpenPrompt.onInstallFinished(this, status);
    }

    private void refreshList() {
        updateHint();
        if (list.getAdapter() instanceof BaseAdapter) {
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        }
    }

    private void buildUi() {
        int pad = Ui.dp(this, 40);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        TextView title = Ui.title(this, getString(R.string.picker_title));
        Ui.leadingIcon(title, Ui.icon(this, R.drawable.ic_versions, Ui.ACCENT_BRIGHT), 26);
        root.addView(title);

        hint = Ui.subtitle(this, "");
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = Ui.dp(this, 6);
        hintParams.bottomMargin = Ui.dp(this, 12);
        root.addView(hint, hintParams);
        updateHint();

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

        empty = Ui.text(this, getString(R.string.picker_loading), 18, Ui.TEXT_MUTED);
        empty.setGravity(Gravity.CENTER);
        listFrame.addView(empty, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);

        LinearLayout changelogColumn = new LinearLayout(this);
        changelogColumn.setOrientation(LinearLayout.VERTICAL);
        changelogColumn.setPadding(Ui.dp(this, 24), 0, 0, 0);
        columns.addView(changelogColumn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.3f));

        TextView changelogHeading = Ui.sectionHeading(this, getString(R.string.changelog_heading));
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

        changelog = Ui.body(this, getString(R.string.picker_select));
        changelogScroll.addView(changelog);

        // The download/install progress line, shared by every install started from this screen.
        progressView = Ui.text(this, "", 17, Ui.LINK);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = Ui.dp(this, 12);
        root.addView(progressView, progressParams);

        setContentView(root);
        list.requestFocus();
    }

    private void updateHint() {
        if (hint == null) {
            return;
        }
        hint.setText(getString(R.string.picker_hint,
                installedVersion == null ? getString(R.string.picker_none) : installedVersion));
    }

    private void loadReleases() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<PlezyRepo.Release> loaded =
                            PlezyRepo.fetchReleases(VersionListActivity.this, MAX_RELEASES);
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
                            empty.setText(getString(R.string.picker_could_not_load,
                                    e.getMessage() == null
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
            empty.setText(getString(R.string.picker_empty));
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
                install(releases.get(position));
            }
        });
        // Take focus so the D-pad changes the version (and the notes follow). Press right to move
        // into the notes pane to scroll long changelogs, left to come back.
        list.requestFocus();
    }

    /** Install the highlighted version without leaving this screen. */
    private void install(PlezyRepo.Release release) {
        installedVersion = PlezyRepo.installedVersion(this);
        installFlow.install(release, installedVersion);
    }

    private void showChangelog(PlezyRepo.Release release) {
        changelogHeader.setText(release.tag
                + (release.publishedAt == null ? "" : "   " + date(release.publishedAt)));
        CharSequence notes = Markdown.toStyled(release.body,
                getResources().getDisplayMetrics().density);
        if (notes.length() == 0) {
            notes = getString(R.string.changelog_no_notes);
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
                label += "   " + getString(R.string.picker_installed_suffix);
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
