package dev.rorar.plezyupdater;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Pick how often the background check runs: a number and a unit ("every 3 days").
 *
 * <p>Two layouts, chosen by {@link Device#isTouch}:
 *
 * <ul>
 *   <li><b>TV / D-pad</b> (no finger touchscreen): two columns of focusable rows — numbers 1–30 on
 *       the left, units on the right. Up/down moves within a column, right/left (or OK) moves
 *       between them, and a live line at the top spells out the result so there is nothing to
 *       infer. Save commits; the remote's Back key cancels.</li>
 *   <li><b>Touch</b> (finger touchscreen): a grid of number chips (1–30, six across) plus a 2×2 grid
 *       of unit chips, both tapped, with the same live preview and an explicit Save / Cancel pair
 *       stacked full-width. The two focusable columns are a D-pad shape — on a phone there is no way
 *       to move between them and no visible way to commit, so the whole screen reads as dead, and a
 *       single-column number list shows barely one row. (This was the one screen the {@code 0.9.0}
 *       touch pass left behind; the fix is this branch.)</li>
 * </ul>
 *
 * <p>The rows are plain {@link TextView}s rather than a {@code ListView}: on the stick a
 * {@code ListView} keeps its own selection state, and the selection it reports when it first gains
 * focus is not the one that was set (observed: {@code setSelection(2)} reports 1 on focus), which
 * desynchronised the preview. Plain focusable rows have no such hidden state — the focused row
 * <em>is</em> the selection; on touch the selection is the row's {@code selected} state, set from
 * {@link #updatePreview()}.
 *
 * <p>The alternative the backlog suggested — a prebuilt picker library — is not available here:
 * the app builds with {@code aapt2}/{@code javac}/{@code d8} and no resource merging, so an AAR
 * with layouts and themes cannot be linked in. This is the same amount of code as wiring one up,
 * and it matches the app's look.
 */
public class IntervalActivity extends Activity {

    private static final int MAX_VALUE = 30;
    /** Columns in the touch number grid: 1–30 as five rows of six. */
    private static final int TOUCH_COLUMNS = 6;

    private final List<TextView> valueRows = new ArrayList<TextView>();
    private final List<Button> valueChips = new ArrayList<Button>();
    private final List<TextView> unitRows = new ArrayList<TextView>();
    private final List<Button> unitChips = new ArrayList<Button>();

    private boolean touch;
    private TextView preview;
    private Button saveButton;

    private int selectedValue;
    private String selectedUnit;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        selectedValue = Prefs.intervalValue(this);
        selectedUnit = Prefs.intervalUnit(this);
        buildUi();
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
    // TV / D-pad: two focusable columns
    // ------------------------------------------------------------------ #

    private void buildTvUi() {
        int pad = Ui.dp(this, 36);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.title(this, getString(R.string.interval_title)));

        TextView subtitle = Ui.subtitle(this, getString(R.string.interval_subtitle));
        root.addView(subtitle, topMargin(2));

        preview = Ui.text(this, "", 28, Ui.TEXT_HEADING);
        root.addView(preview, topMargin(12));

        TextView note = Ui.muted(this, getString(R.string.interval_note));
        root.addView(note, topMargin(2));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams columnsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        columnsParams.topMargin = Ui.dp(this, 14);
        root.addView(columns, columnsParams);

        buildValueColumn(columns);
        buildUnitColumn(columns);

        saveButton = Ui.primaryButton(this, getString(R.string.interval_save));
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        root.addView(saveButton, topMargin(16));

        root.addView(Ui.muted(this, getString(R.string.interval_cancel_hint)), topMargin(10));

        setContentView(root);
        valueRows.get(valueIndex(selectedValue)).requestFocus();
        updatePreview();
    }

    private void buildValueColumn(LinearLayout columns) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        buildValueRows(list);
        addColumn(columns, getString(R.string.interval_column_value), list);
    }

    private void buildUnitColumn(LinearLayout columns) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Prefs.UNITS.length; i++) {
            final String unit = Prefs.UNITS[i];
            // The column lists the plural form of each unit ("Hours", "Stunden", …); the preview
            // line below then picks the right singular/plural for the chosen count.
            TextView row = row(capitalise(Prefs.unitWord(this, unit, 2)), 24);
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        selectedUnit = unit;
                        updatePreview();
                    }
                }
            });
            row.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int code, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        focusValue(selectedValue);
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                        saveButton.requestFocus();
                        return true;
                    }
                    return false;
                }
            });
            unitRows.add(row);
            list.addView(row, rowParams());
        }
        addColumn(columns, getString(R.string.interval_column_unit), list);
    }

    /** A titled column that scrolls when the rows do not fit (the value column needs it). */
    private void addColumn(LinearLayout parent, String heading, LinearLayout rows) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, Ui.dp(this, 12), 0);
        parent.addView(column, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView label = Ui.sectionHeading(this, heading);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.bottomMargin = Ui.dp(this, 6);
        column.addView(label, labelParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(rows);
        column.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    // ------------------------------------------------------------------ #
    // Touch: a number grid + unit chips
    // ------------------------------------------------------------------ #

    private void buildTouchUi() {
        int pad = Ui.dp(this, 20);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ui.BG);
        content.setPadding(pad, pad, pad, pad);

        content.addView(Ui.title(this, getString(R.string.interval_title)));

        TextView subtitle = Ui.subtitle(this, getString(R.string.interval_subtitle));
        content.addView(subtitle, topMargin(2));

        preview = Ui.text(this, "", 28, Ui.TEXT_HEADING);
        content.addView(preview, topMargin(12));

        TextView note = Ui.muted(this, getString(R.string.interval_note));
        content.addView(note, topMargin(2));

        // Units first: four taps that never need scrolling, then the number grid below them.
        content.addView(Ui.sectionHeading(this, getString(R.string.interval_column_unit)),
                topMargin(18));
        content.addView(buildUnitChips(), topMargin(8));

        content.addView(Ui.sectionHeading(this, getString(R.string.interval_column_value)),
                topMargin(18));
        content.addView(buildValueGrid());

        // Save and Cancel are stacked full-width rather than side by side: on a narrow phone a
        // two-column row clips a long label ("Enregistrer", "Prüfintervall…") at the button size.
        saveButton = Ui.primaryButton(this, getString(R.string.interval_save));
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        content.addView(saveButton, topMargin(16));

        Button cancel = Ui.actionButton(this, getString(R.string.action_cancel));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        content.addView(cancel, topMargin(8));

        // One scroll for the whole page. A portrait phone shows all of it without scrolling, but a
        // short landscape screen (or the 320×640 emulator) cannot fit the fixed header, the chips
        // and the buttons: an inner weight-1 scroll view then collapses to zero height and pushes
        // Save/Cancel off the bottom edge (verified on a 1600×720 phone in landscape). With a single
        // outer scroll the number grid is wrap_content and every control stays reachable.
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(content);
        setContentView(scroll);
        // With no view focused the platform holds the window in key mode, and the *first* tap is
        // spent switching it to touch mode — so the first number or chip the user taps does
        // nothing (observed on the emulator and reproducible after any settle time). Focusing a
        // non-clickable, focusable-in-touch-mode container enters touch mode up front, so the
        // first tap does what it looks like it should. (The TV branch focuses its selected row
        // for the same reason, and the 0.9.0 picker focuses its list.)
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        updatePreview();
    }

    /**
     * The four units as a 2×2 grid of chips. A single row of four is too wide for a phone in some
     * languages ("Stunden Tage Wochen Monate"), so the grid wraps to two lines instead.
     */
    private LinearLayout buildUnitChips() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout[] lines = {new LinearLayout(this), new LinearLayout(this)};
        for (LinearLayout line : lines) {
            line.setOrientation(LinearLayout.HORIZONTAL);
        }
        for (int i = 0; i < Prefs.UNITS.length; i++) {
            final String unit = Prefs.UNITS[i];
            Button chip = Ui.chip(this, capitalise(Prefs.unitWord(this, unit, 2)));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedUnit = unit;
                    updatePreview();
                }
            });
            unitChips.add(chip);

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 0) {
                p.rightMargin = Ui.dp(this, 8);
            }
            lines[i / 2].addView(chip, p);
        }
        grid.addView(lines[0]);
        grid.addView(lines[1], topMargin(8));
        return grid;
    }

    /**
     * The numbers 1–30 as a grid of chips, {@link #TOUCH_COLUMNS} across.
     *
     * <p>A single column — the D-pad shape — shows barely one row on a phone and scrolls forever;
     * six across puts the whole range in five rows, keeps every tap target finger-sized, and reads
     * like the number pad people already expect. The rows are weighted so the grid fills the width
     * whatever the screen size.
     */
    private LinearLayout buildValueGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout line = null;
        for (int i = 1; i <= MAX_VALUE; i++) {
            if ((i - 1) % TOUCH_COLUMNS == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(line, lineParams(i == 1 ? 0 : 5));
            }
            final int value = i;
            Button chip = Ui.chip(this, String.valueOf(i), 17);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedValue = value;
                    updatePreview();
                }
            });
            valueChips.add(chip);

            // A fixed 44 dp cell height, not wrap_content: the platform button's own minimum is
            // 48 dp and ignores a smaller setMinimumHeight, which would push the last row off a
            // 360×800 phone. 44 dp still clears the finger-target floor.
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
            if ((i - 1) % TOUCH_COLUMNS != TOUCH_COLUMNS - 1) {
                p.rightMargin = Ui.dp(this, 6);
            }
            line.addView(chip, p);
        }
        return grid;
    }

    // ------------------------------------------------------------------ #
    // shared
    // ------------------------------------------------------------------ #

    /**
     * Build the 1–30 rows into {@code list} for the D-pad picker: a row focuses to select and
     * offers RIGHT/OK to jump to the unit column.
     */
    private void buildValueRows(LinearLayout list) {
        for (int i = 1; i <= MAX_VALUE; i++) {
            final int value = i;
            TextView row = row(String.valueOf(i), 24);
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        selectedValue = value;
                        updatePreview();
                    }
                }
            });
            row.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int code, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT
                            || code == KeyEvent.KEYCODE_DPAD_CENTER
                            || code == KeyEvent.KEYCODE_ENTER) {
                        focusUnit(selectedUnit);
                        return true;
                    }
                    return false;
                }
            });
            valueRows.add(row);
            list.addView(row, rowParams());
        }
    }

    /** One selectable row: full-width, focusable, unmistakable when focused or selected. */
    private TextView row(String label, float sp) {
        TextView row = Ui.text(this, label, sp, Ui.TEXT);
        row.setTextColor(Ui.pickerTextColors());
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.pickerRowSelector());
        row.setPadding(Ui.dp(this, 24), Ui.dp(this, 14),
                Ui.dp(this, 24), Ui.dp(this, 14));
        return row;
    }

    private void focusValue(int value) {
        valueRows.get(valueIndex(value)).requestFocus();
    }

    private void focusUnit(String unit) {
        unitRows.get(unitIndex(unit)).requestFocus();
    }

    private void updatePreview() {
        preview.setText(getString(R.string.interval_preview, selectedValue,
                Prefs.unitWord(this, selectedUnit, selectedValue)));
        // Keep each column's own choice highlighted while the D-pad is in the other column: the
        // focused row shows the full accent, the selected row shows the dimmer fill. On touch the
        // same "selected" fill is the only highlight, since there is no focus to move around.
        int value = valueIndex(selectedValue);
        for (int i = 0; i < valueRows.size(); i++) {
            valueRows.get(i).setSelected(i == value);
        }
        for (int i = 0; i < valueChips.size(); i++) {
            valueChips.get(i).setSelected(i == value);
        }
        int unitRow = unitIndex(selectedUnit);
        for (int i = 0; i < unitRows.size(); i++) {
            unitRows.get(i).setSelected(i == unitRow);
        }
        for (int i = 0; i < unitChips.size(); i++) {
            unitChips.get(i).setSelected(i == unitRow);
        }
    }

    private void save() {
        Prefs.setInterval(this, selectedValue, selectedUnit);
        // Re-arm the job with the new cadence (or leave it off if auto-check is disabled).
        if (Prefs.autoCheckEnabled(this)) {
            UpdateJob.schedule(this);
        }
        Notifier.toast(this, getString(R.string.interval_checking, Prefs.intervalLabel(this)));
        setResult(RESULT_OK);
        finish();
    }

    private static int valueIndex(int value) {
        return Math.max(0, Math.min(MAX_VALUE, value) - 1);
    }

    private static int unitIndex(String unit) {
        for (int i = 0; i < Prefs.UNITS.length; i++) {
            if (Prefs.UNITS[i].equals(unit)) {
                return i;
            }
        }
        return 0;
    }

    private static String capitalise(String s) {
        return s.substring(0, 1).toUpperCase(java.util.Locale.US) + s.substring(1);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(this, 4);
        return p;
    }

    /** Full-width params with a top margin, for a row of the number grid. */
    private LinearLayout.LayoutParams lineParams(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, topDp);
        return p;
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, dp);
        return p;
    }
}
