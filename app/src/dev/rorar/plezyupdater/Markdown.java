package dev.rorar.plezyupdater;

/**
 * A deliberately tiny Markdown-to-plain-text pass for GitHub release notes on a TV.
 *
 * <p>API 25 has no MarkdownView and the app carries no libraries, so this just strips the
 * syntax that would otherwise show up as noise ({@code ##}, {@code **}, backticks, link
 * brackets) and turns bullets into a dot. It is not a parser; it is good enough for changelogs.
 */
public final class Markdown {

    private Markdown() {
    }

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
