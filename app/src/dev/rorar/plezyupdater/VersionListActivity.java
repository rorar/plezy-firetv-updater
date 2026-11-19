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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Release picker for the "Allow older versions" developer option.
 *
 * <p>Two layouts, chosen by {@link Device#isTouch}:
 *
 * <ul>
 *   <li><b>TV / D-pad</b> (no finger touchscreen): two panes side by side — the versions on the
 *       left, the highlighted version's release notes on the right. The D-pad changes the version
 *       and the notes follow; pressing OK installs it.</li>
 *   <li><b>Touch</b> (finger touchscreen): a single pane of full-width version rows. Tapping a row
 *       installs that version; the row's notes icon (the main screen's <i>What's new</i> glyph)
 *       flies the release notes in from the right as an overlay with a close button and an
 *       "Install this version" action. The side-by-side layout reads badly on a phone, and
 *       especially in landscape.</li>
 * </ul>
 *
 * <p>Installing happens right here through {@link InstallFlow} either way — there is no detour
 * back to the main screen just to press Install. (The main screen keeps the plain "install the
 * latest" flow for when Plezy is not installed at all.)
 */
public class VersionListActivity extends Activity {

    private static final int MAX_RELEASES = 30;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PlezyRepo.Release> releases = new ArrayList<PlezyRepo.Release>();

    private boolean touch;
    private FrameLayout root;
    private ListView list;
    private TextView empty;
    private TextView hint;
    private TextView progressView;

    // TV layout: the release-notes pane is always on screen.
    private TextView changelogHeader;
    private TextView changelog;
    private ScrollView changelogScroll;

    // Touch layout: the same views live in an overlay that slides in from the right.
    private View changelogOverlay;
    private LinearLayout changelogPanel;
    private Button changelogInstallButton;
    private PlezyRepo.Release changelogRelease;

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
            if (changelogInstallButton != null) {
                changelogInstallButton.setEnabled(!busy);
            }
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
    public void onBackPressed() {
        // On touch the release notes are an overlay: Back closes it before leaving the screen.
        if (touch && changelogOverlay != null && changelogOverlay.getVisibility() == View.VISIBLE) {
            closeChangelog();
            return;
        }
        super.onBackPressed();
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
        touch = Device.isTouch(this);
        if (touch) {
            buildTouchUi();
        } else {
            buildTvUi();
        }
    }

    // ------------------------------------------------------------------ #
    // TV / D-pad: two panes
    // ------------------------------------------------------------------ #

    private void buildTvUi() {
        int pad = Ui.dp(this, 40);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);
        Ui.applyInsets(root, pad);

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

        addList(listFrame);

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

        addChangelogScroll(changelogColumn);

        // The download/install progress line, shared by every install started from this screen.
        progressView = Ui.text(this, "", 17, Ui.LINK);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = Ui.dp(this, 12);
        root.addView(progressView, progressParams);

        setContentView(root);
        list.requestFocus();
    }

    // ------------------------------------------------------------------ #
    // Touch: one pane + a release-notes overlay that flies in from the right
    // ------------------------------------------------------------------ #

    private void buildTouchUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        int pad = Ui.dp(this, 16);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        Ui.applyInsets(content, pad);

        TextView title = Ui.title(this, getString(R.string.picker_title));
        Ui.leadingIcon(title, Ui.icon(this, R.drawable.ic_versions, Ui.ACCENT_BRIGHT), 24);
        content.addView(title);

        hint = Ui.subtitle(this, "");
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = Ui.dp(this, 6);
        hintParams.bottomMargin = Ui.dp(this, 10);
        content.addView(hint, hintParams);
        updateHint();

        FrameLayout listFrame = new FrameLayout(this);
        addList(listFrame);
        content.addView(listFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        progressView = Ui.text(this, "", 16, Ui.LINK);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = Ui.dp(this, 10);
        content.addView(progressView, progressParams);

        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        changelogOverlay = buildChangelogOverlay();
        root.addView(changelogOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /** The release-notes overlay: a full-height panel that slides in from the right. */
    private View buildChangelogOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Ui.BG);
        overlay.setVisibility(View.GONE);
        // The panel covers the whole overlay, but only its buttons and scroll view are clickable.
        // Without this, a tap on a dead zone (padding, the gap between the header and the notes)
        // would fall through to the version list underneath and start an install.
        overlay.setClickable(true);

        changelogPanel = new LinearLayout(this);
        changelogPanel.setOrientation(LinearLayout.VERTICAL);
        changelogPanel.setBackgroundColor(Ui.BG);
        changelogPanel.setClickable(true);
        int pad = Ui.dp(this, 20);
        changelogPanel.setPadding(pad, pad, pad, pad);
        Ui.applyInsets(changelogPanel, pad);

        // Heading row: the section label, and the close button on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView heading = Ui.sectionHeading(this, getString(R.string.changelog_heading));
        header.addView(heading, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button close = new Button(this);
        close.setText(getString(R.string.picker_close));
        close.setContentDescription(getString(R.string.picker_close_desc));
        close.setAllCaps(false);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        close.setTextColor(Ui.rowTextColors());
        close.setBackground(Ui.focusBackground(this));
        close.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setMinimumHeight(Ui.dp(this, 44));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeChangelog();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        changelogPanel.addView(header);

        changelogHeader = Ui.text(this, "", 20, android.graphics.Color.WHITE);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = Ui.dp(this, 8);
        changelogPanel.addView(changelogHeader, headerParams);

        addChangelogScroll(changelogPanel);

        changelogInstallButton = Ui.primaryButton(this, getString(R.string.picker_install_this));
        changelogInstallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (changelogRelease != null) {
                    install(changelogRelease);
                }
                closeChangelog();
            }
        });
        LinearLayout.LayoutParams installParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        installParams.topMargin = Ui.dp(this, 12);
        changelogPanel.addView(changelogInstallButton, installParams);

        overlay.addView(changelogPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return overlay;
    }

    /** The release-notes body, into whichever container the layout gives it. */
    private void addChangelogScroll(LinearLayout parent) {
        changelogScroll = new ScrollView(this);
        changelogScroll.setBackground(Ui.rounded(this, Ui.SURFACE, 10));
        changelogScroll.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                Ui.dp(this, 16), Ui.dp(this, 12));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = Ui.dp(this, 8);
        parent.addView(changelogScroll, scrollParams);

        changelog = Ui.body(this, getString(R.string.picker_select));
        changelogScroll.addView(changelog);
    }

    /** The version list and its "loading"/"empty" placeholder. */
    private void addList(FrameLayout frame) {
        list = new ListView(this);
        list.setBackgroundColor(Ui.BG);
        list.setDivider(null);
        list.setSelector(Ui.listSelector());
        list.setVerticalScrollBarEnabled(true);
        frame.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        empty = Ui.text(this, getString(R.string.picker_loading), 18, Ui.TEXT_MUTED);
        empty.setGravity(Gravity.CENTER);
        frame.addView(empty, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
    }

    private void openChangelog(PlezyRepo.Release release) {
        changelogRelease = release;
        showChangelog(release);
        changelogInstallButton.setText(getString(R.string.picker_install_this));
        changelogPanel.setVisibility(View.VISIBLE);
        // The panel is GONE (and so unmeasured) until now, so fall back to the root width.
        int width = changelogPanel.getWidth() > 0 ? changelogPanel.getWidth() : root.getWidth();
        changelogPanel.setTranslationX(width);
        changelogOverlay.setVisibility(View.VISIBLE);
        changelogPanel.animate().translationX(0f).setDuration(220).start();
    }

    private void closeChangelog() {
        if (changelogPanel == null) {
            return;
        }
        int width = changelogPanel.getWidth() > 0 ? changelogPanel.getWidth() : root.getWidth();
        changelogPanel.animate().translationX(width).setDuration(180)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (changelogOverlay != null) {
                            changelogOverlay.setVisibility(View.GONE);
                        }
                    }
                }).start();
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
        if (touch) {
            list.setAdapter(new TouchAdapter());
        } else {
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
        }
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

    /** "2.26.0", plus the "(installed)" marker when it is the one on the device. */
    private String rowLabel(PlezyRepo.Release release) {
        String label = release.tag;
        if (installedVersion != null
                && PlezyRepo.compareVersions(release.tag, installedVersion) == 0) {
            label += "   " + getString(R.string.picker_installed_suffix);
        }
        return label;
    }

    /** TV rows: the version, as a plain selectable line. */
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
            row.setText(rowLabel(releases.get(position)));
            row.setTextColor(Ui.TEXT);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(VersionListActivity.this, 20), Ui.dp(VersionListActivity.this, 14),
                    Ui.dp(VersionListActivity.this, 20), Ui.dp(VersionListActivity.this, 14));
            return row;
        }
    }

    /**
     * Touch rows: the version on the left (tap installs it) and an icon button on the right (tap
     * flies the release notes in). The icon is the main screen's <i>What's new</i> glyph, so the
     * affordance reads the same on both screens; its content description carries the word. Both
     * children carry their own click listener, so the row itself is not clickable — the label is
     * the install target.
     */
    private class TouchAdapter extends BaseAdapter {

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
            final PlezyRepo.Release release = releases.get(position);
            LinearLayout row = convertView instanceof LinearLayout
                    ? (LinearLayout) convertView : buildTouchRow();
            TextView label = (TextView) row.getChildAt(0);
            ImageButton notes = (ImageButton) row.getChildAt(1);
            label.setText(rowLabel(release));
            label.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    install(release);
                }
            });
            notes.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openChangelog(release);
                }
            });
            return row;
        }
    }

    private LinearLayout buildTouchRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = Ui.text(this, "", 20, Ui.TEXT);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        label.setClickable(true);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton notes = Ui.iconButton(this, getString(R.string.picker_notes),
                R.drawable.ic_news);
        row.addView(notes, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }
}
