package dev.rorar.plezyupdater;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns GitHub release notes into a {@link Spanned} for a TV screen.
 *
 * <p>API 25 has no MarkdownView and the app carries no libraries, so this is a small hand-written
 * renderer rather than a parser. It understands exactly the shape the Plezy releases use — a flat
 * list of {@code ## Section} headings followed by {@code - bullet} items — and degrades gracefully
 * for anything else. Unlike the old strip-to-plain-text pass, the structure survives: headings are
 * larger and coloured, bullets get a hanging indent, and inline {@code **bold**}, {@code `code`}
 * and links keep their emphasis.
 *
 * <p>{@link #toPlainText(String)} is kept for callers that want no formatting at all.
 */
public final class Markdown {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.*)$");
    private static final Pattern BOLD_LINE = Pattern.compile("^\\*\\*([^*]+)\\*\\*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");

    private Markdown() {
    }

    /**
     * Render release notes as a styled {@link CharSequence}.
     *
     * @param density the display density, used to size the bullet indent
     */
    public static CharSequence toStyled(String markdown, float density) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "";
        }
        String s = markdown.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");

        SpannableStringBuilder out = new SpannableStringBuilder();
        String lastBlock = null;
        for (String raw : s.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            Matcher boldLine = BOLD_LINE.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);

            if (heading.matches()) {
                blankLine(out);
                appendHeading(out, heading.group(2), heading.group(1).length(), density);
                lastBlock = "h";
            } else if (boldLine.matches()) {
                blankLine(out);
                appendHeading(out, boldLine.group(1), 2, density);
                lastBlock = "h";
            } else if (bullet.matches()) {
                if (lastBlock != null && !"b".equals(lastBlock)) {
                    blankLine(out);
                }
                appendBullet(out, bullet.group(1), density);
                lastBlock = "b";
            } else if (numbered.matches()) {
                if (lastBlock != null && !"b".equals(lastBlock)) {
                    blankLine(out);
                }
                appendBullet(out, numbered.group(1), density);
                lastBlock = "b";
            } else if (RULE.matcher(line).matches()) {
                // A horizontal rule is just a visual break; the blank line covers it.
                lastBlock = null;
            } else {
                blankLine(out);
                appendInline(out, line, density);
                out.append("\n");
                lastBlock = "p";
            }
        }
        trimTrailingNewlines(out);
        return out;
    }

    private static void appendHeading(SpannableStringBuilder out, String text, int level,
            float density) {
        int start = out.length();
        appendInline(out, text, density);
        int end = out.length();
        out.append("\n");
        int size = level <= 1 ? 130 : level == 2 ? 120 : 112;
        out.setSpan(new RelativeSizeSpan(size / 100f), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(Ui.TEXT_HEADING), start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendBullet(SpannableStringBuilder out, String text, float density) {
        int start = out.length();
        out.append("•  ");
        appendInline(out, text, density);
        int end = out.length();
        out.append("\n");
        // Hang wrapped lines under the text rather than under the bullet glyph.
        out.setSpan(new LeadingMarginSpan.Standard(0, Math.round(density * 22)),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Append inline Markdown, keeping the emphasis as spans. */
    private static void appendInline(SpannableStringBuilder out, String text, float density) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);

            if (ch == '!' && i + 1 < n && text.charAt(i + 1) == '[') {
                // Image: keep the alt text, drop the target.
                int close = text.indexOf(']', i + 2);
                if (close > 0 && close + 1 < n && text.charAt(close + 1) == '(') {
                    int end = text.indexOf(')', close + 2);
                    if (end > 0) {
                        out.append(text, i + 2, close);
                        i = end + 1;
                        continue;
                    }
                }
            } else if (ch == '[') {
                int close = text.indexOf(']', i + 1);
                if (close > 0 && close + 1 < n && text.charAt(close + 1) == '(') {
                    int end = text.indexOf(')', close + 2);
                    if (end > 0) {
                        int start = out.length();
                        out.append(text, i + 1, close);
                        span(out, new ForegroundColorSpan(Ui.LINK), start);
                        span(out, new UnderlineSpan(), start);
                        i = end + 1;
                        continue;
                    }
                }
            } else if (ch == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i + 1) {
                    int start = out.length();
                    out.append(text, i + 1, close);
                    span(out, new TypefaceSpan("monospace"), start);
                    span(out, new ForegroundColorSpan(Ui.CODE), start);
                    i = close + 1;
                    continue;
                }
            } else if (ch == '*' || ch == '_') {
                if (i + 1 < n && text.charAt(i + 1) == ch) {
                    int close = text.indexOf("" + ch + ch, i + 2);
                    if (close > i + 2) {
                        int start = out.length();
                        out.append(text, i + 2, close);
                        span(out, new StyleSpan(Typeface.BOLD), start);
                        i = close + 2;
                        continue;
                    }
                } else {
                    int close = text.indexOf(ch, i + 1);
                    if (close > i + 1) {
                        int start = out.length();
                        out.append(text, i + 1, close);
                        span(out, new StyleSpan(Typeface.ITALIC), start);
                        i = close + 1;
                        continue;
                    }
                }
            }

            out.append(ch);
            i++;
        }
    }

    private static void span(SpannableStringBuilder out, Object what, int start) {
        out.setSpan(what, start, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Ensure the buffer ends with exactly one empty line, unless it is still empty. */
    private static void blankLine(SpannableStringBuilder out) {
        int len = out.length();
        if (len == 0) {
            return;
        }
        if (out.charAt(len - 1) != '\n') {
            out.append("\n\n");
        } else if (len < 2 || out.charAt(len - 2) != '\n') {
            out.append("\n");
        }
    }

    private static void trimTrailingNewlines(SpannableStringBuilder out) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
    }

    /**
     * The old behaviour: strip Markdown to plain text. Kept for any caller that wants no
     * formatting (and as the fallback used by the version picker's one-line rows).
     */
    public static String toPlainText(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "";
        }
        String s = markdown.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");           // images → alt text
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1 ($2)");     // links → text (url)
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");                         // headings
        s = s.replaceAll("(?m)^>\\s?", "");                              // blockquotes
        s = s.replaceAll("(?m)^\\s*[*+-]\\s+", "• ");                    // bullets
        s = s.replaceAll("(\\*\\*|__)(.*?)\\1", "$2");                   // bold
        s = s.replaceAll("(\\*|_)(.*?)\\1", "$2");                       // italic
        s = s.replaceAll("`([^`]*)`", "$1");                             // inline code
        s = s.replaceAll("(?m)^\\s*-{3,}\\s*$", "");                     // horizontal rules
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }
}
