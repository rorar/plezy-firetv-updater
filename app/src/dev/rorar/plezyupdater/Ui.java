package dev.rorar.plezyupdater;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The shared TV look. Every screen used to spell out its own colours, paddings and focus
 * selectors; they live here instead so the app is one visual language and a change lands in one
 * place.
 *
 * <p>Two rules matter on a 10-foot screen: text is large, and focus is unmistakable. The focused
 * row is white on the accent fill, everything else is light grey on the dark surface.
 */
public final class Ui {

    /** Screen background. */
    public static final int BG = Color.rgb(16, 24, 32);
    /** Raised surface: cards, the changelog pane, inactive rows. */
    public static final int SURFACE = Color.rgb(24, 34, 45);
    public static final int TEXT = Color.rgb(226, 233, 240);
    public static final int TEXT_MUTED = Color.rgb(150, 168, 182);
    public static final int TEXT_DISABLED = Color.rgb(104, 118, 132);
    public static final int TEXT_HEADING = Color.rgb(110, 168, 200);
    /** The Fire OS focus highlight, kept so the app matches the system. */
    public static final int ACCENT = Color.rgb(47, 110, 143);
    /** The primary action's resting colour and its brighter focus state. */
    public static final int ACCENT_DIM = Color.rgb(35, 78, 102);
    public static final int ACCENT_BRIGHT = Color.rgb(63, 134, 173);
    public static final int LINK = Color.rgb(128, 200, 255);
    public static final int CODE = Color.rgb(198, 222, 188);
    public static final int OK = Color.rgb(104, 190, 138);
    public static final int WARN = Color.rgb(222, 168, 96);
    public static final int ERROR = Color.rgb(224, 116, 116);
    /** The fill for a row that is selected but whose column does not have focus. */
    public static final int SELECTED = Color.rgb(42, 96, 126);

    private Ui() {
    }

    public static int dp(Context c, int value) {
        return Math.round(c.getResources().getDisplayMetrics().density * value);
    }

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    /** A screen title: white, large. */
    public static TextView title(Context c, String s) {
        return text(c, s, 30, Color.WHITE);
    }

    public static TextView subtitle(Context c, String s) {
        return text(c, s, 16, TEXT_MUTED);
    }

    /** An all-caps section label, as used above groups of rows. */
    public static TextView sectionHeading(Context c, String s) {
        return text(c, s.toUpperCase(Locale.US), 16, TEXT_HEADING);
    }

    public static TextView muted(Context c, String s) {
        return text(c, s, 15, TEXT_MUTED);
    }

    /** A rounded, raised panel. Add content and a layout params. */
    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(c, SURFACE, 10));
        int p = dp(c, 16);
        card.setPadding(p, p, p, p);
        return card;
    }

    /** A full-width action row: left-aligned label, unmistakable focus fill. */
    public static Button actionButton(Context c, String label) {
        return styledButton(c, label, 20, focusBackground(c), rowTextColors());
    }

    /** A full-width action row with a leading glyph, e.g. {@code ic_refresh}. */
    public static Button actionButton(Context c, String label, int iconRes) {
        Button b = actionButton(c, label);
        if (iconRes != 0) {
            leadingIcon(b, stateIcon(c, iconRes), 22);
        }
        return b;
    }

    /** The one action the screen wants you to press. */
    public static Button primaryButton(Context c, String label) {
        return styledButton(c, label, 22, primaryBackground(c), rowTextColors());
    }

    /** The one action the screen wants you to press, with a leading glyph. */
    public static Button primaryButton(Context c, String label, int iconRes) {
        Button b = primaryButton(c, label);
        if (iconRes != 0) {
            leadingIcon(b, stateIcon(c, iconRes), 24);
        }
        return b;
    }

    private static Button styledButton(Context c, String label, int sp,
            StateListDrawable background, ColorStateList textColors) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(textColors);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(c, 24), dp(c, 11), dp(c, 24), dp(c, 11));
        b.setMinimumHeight(dp(c, 50));
        b.setBackground(background);
        b.setFocusable(true);
        return b;
    }

    public static StateListDrawable focusBackground(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, ACCENT, 8));
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, ACCENT, 8));
        s.addState(new int[]{}, rounded(c, SURFACE, 8));
        return s;
    }

    public static StateListDrawable primaryBackground(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, ACCENT_BRIGHT, 8));
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, ACCENT_BRIGHT, 8));
        s.addState(new int[]{android.R.attr.state_enabled}, rounded(c, ACCENT_DIM, 8));
        s.addState(new int[]{}, rounded(c, SURFACE, 8));
        return s;
    }

    public static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** Row highlight for a {@code ListView}: flat accent fill, no rounding. */
    public static StateListDrawable listSelector() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                new android.graphics.drawable.ColorDrawable(ACCENT));
        s.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(ACCENT));
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    /**
     * Row highlight for the interval picker: the focused row gets the full accent fill, a row that
     * is {@code selected} (its column is the one that is not focused) keeps a dimmer fill, and
     * everything else is transparent. Focus is listed first, so it always wins.
     */
    public static StateListDrawable pickerRowSelector() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                new android.graphics.drawable.ColorDrawable(ACCENT));
        s.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(ACCENT));
        s.addState(new int[]{android.R.attr.state_selected},
                new android.graphics.drawable.ColorDrawable(SELECTED));
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    /** White while focused or selected, so the row stays readable on either fill. */
    public static ColorStateList pickerTextColors() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_selected},
                        new int[]{}
                },
                new int[]{Color.WHITE, Color.WHITE, TEXT});
    }

    /** White while focused so the row stays readable on the accent fill. */
    public static ColorStateList rowTextColors() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{Color.WHITE, TEXT_DISABLED, TEXT});
    }

    /**
     * A bundled glyph tinted with one colour. The main-screen icons are hand-drawn vector
     * drawables in {@code res/drawable/}; nothing is pulled from a font or an icon pack, because
     * the build has no resource merging.
     */
    public static Drawable icon(Context c, int resId, int color) {
        Drawable d = c.getResources().getDrawable(resId, c.getTheme()).mutate();
        d.setTint(color);
        return d;
    }

    /**
     * A glyph that recolours with the view's state, so it matches the label it sits beside:
     * white while focused, dimmed while disabled, body colour otherwise. A {@link StateListDrawable}
     * of already-tinted copies is used rather than a stateful tint, so the state handling is
     * explicit and identical to {@link #focusBackground}.
     */
    public static Drawable stateIcon(Context c, int resId) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, icon(c, resId, Color.WHITE));
        s.addState(new int[]{-android.R.attr.state_enabled}, icon(c, resId, TEXT_DISABLED));
        s.addState(new int[]{}, icon(c, resId, TEXT));
        return s;
    }

    /** Puts a glyph before a label, sized and spaced for the 10-foot layout. */
    public static void leadingIcon(TextView v, Drawable glyph, int sizeDp) {
        Context c = v.getContext();
        int px = dp(c, sizeDp);
        glyph.setBounds(0, 0, px, px);
        v.setCompoundDrawablesRelative(glyph, null, null, null);
        v.setCompoundDrawablePadding(dp(c, 12));
    }

    /** A body font that is comfortable to read from a couch. */
    public static TextView body(Context c, CharSequence s) {
        TextView t = text(c, s, 17, TEXT);
        t.setLineSpacing(dp(c, 4), 1f);
        return t;
    }

    /** Bold monospace-ish section marker for the release-notes body. */
    public static Typeface monospace() {
        return Typeface.MONOSPACE;
    }
}
