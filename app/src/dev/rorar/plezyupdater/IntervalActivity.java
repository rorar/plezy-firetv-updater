package dev.rorar.plezyupdater;

import android.app.Activity;
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
 * <p>Two columns of focusable rows — numbers 1–30 on the left, units on the right — because that
 * is the shape that stays usable with only a D-pad. Up/down moves within a column, right/left (or
 * OK) moves between them, and a live line at the top spells out the result so there is nothing to
 * infer. Save commits; the remote's Back key cancels.
 *
 * <p>The rows are plain {@link TextView}s rather than a {@code ListView}: on this device a
 * {@code ListView} keeps its own selection state, and the selection it reports when it first gains
 * focus is not the one that was set (observed: {@code setSelection(2)} reports 1 on focus), which
 * desynchronised the preview. Plain focusable rows have no such hidden state — the focused row
 * <em>is</em> the selection.
 *
 * <p>The alternative the backlog suggested — a prebuilt picker library — is not available here:
 * the app builds with {@code aapt2}/{@code javac}/{@code d8} and no resource merging, so an AAR
 * with layouts and themes cannot be linked in. This is the same amount of code as wiring one up,
 * and it matches the app's look.
 */
public class IntervalActivity extends Activity {

    private static final int MAX_VALUE = 30;

    private final List<TextView> valueRows = new ArrayList<TextView>();
    private final List<TextView> unitRows = new ArrayList<TextView>();

    private TextView preview;
    private Button saveButton;

    private int selectedValue;
    private String selectedUnit;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        selectedValue = Prefs.intervalValue(this);
        selectedUnit = Prefs.intervalUnit(this);
        buildUi();
    }

    private void buildUi() {
        int pad = Ui.dp(this, 36);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.title(this, "Check interval"));

        TextView subtitle = Ui.subtitle(this,
                "How often the updater asks GitHub for a new release.");
        root.addView(subtitle, topMargin(2));

        preview = Ui.text(this, "", 28, Ui.TEXT_HEADING);
        root.addView(preview, topMargin(12));

        TextView note = Ui.muted(this,
                "Runs while the TV is on. A month is treated as 30 days.");
        root.addView(note, topMargin(2));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams columnsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        columnsParams.topMargin = Ui.dp(this, 14);
        root.addView(columns, columnsParams);

        buildValueColumn(columns);
        buildUnitColumn(columns);

        saveButton = Ui.primaryButton(this, "Save");
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        root.addView(saveButton, topMargin(16));

        root.addView(Ui.muted(this, "Press Back to cancel."), topMargin(10));

        setContentView(root);
        valueRows.get(valueIndex(selectedValue)).requestFocus();
        updatePreview();
    }

    private void buildValueColumn(LinearLayout columns) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 1; i <= MAX_VALUE; i++) {
            final int value = i;
            TextView row = row(String.valueOf(i));
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
        addColumn(columns, "VALUE", list);
    }

    private void buildUnitColumn(LinearLayout columns) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < Prefs.UNITS.length; i++) {
            final String unit = Prefs.UNITS[i];
            TextView row = row(capitalise(unit));
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
        addColumn(columns, "UNIT", list);
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

    /** One selectable row: full-width, focusable, unmistakable when focused. */
    private TextView row(String label) {
        TextView row = Ui.text(this, label, 24, Ui.TEXT);
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
        String unit = selectedValue == 1
                ? selectedUnit.substring(0, selectedUnit.length() - 1) : selectedUnit;
        preview.setText("Every " + selectedValue + " " + unit);
        // Keep each column's own choice highlighted while the D-pad is in the other column: the
        // focused row shows the full accent, the selected row shows the dimmer fill.
        int value = valueIndex(selectedValue);
        for (int i = 0; i < valueRows.size(); i++) {
            valueRows.get(i).setSelected(i == value);
        }
        int unitRow = unitIndex(selectedUnit);
        for (int i = 0; i < unitRows.size(); i++) {
            unitRows.get(i).setSelected(i == unitRow);
        }
    }

    private void save() {
        Prefs.setInterval(this, selectedValue, selectedUnit);
        // Re-arm the alarm with the new cadence (or leave it off if auto-check is disabled).
        if (Prefs.autoCheckEnabled(this)) {
            UpdateAlarm.schedule(this);
        }
        Notifier.toast(this, "Checking " + Prefs.intervalLabel(this));
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

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, dp);
        return p;
    }
}
