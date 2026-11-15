package dev.rorar.plezyupdater;

/**
 * Turns the HTML that GitHub's Atom feed carries into the small Markdown subset
 * {@link Markdown#toStyled} already understands ({@code ## heading}, {@code - bullet}, inline
 * {@code **bold**} / {@code `code`} / links).
 *
 * <p>The metered API returns release notes as Markdown; the unmetered Atom feed returns the same
 * notes as HTML. This keeps the two paths feeding the same renderer, so a rate-limited fallback
 * still shows styled notes rather than a wall of tags. Anything it does not recognise is stripped.
 */
final class Html {

    private Html() {
    }

    /** HTML (as GitHub's feed emits it) → the Markdown subset the notes renderer reads. */
    static String toMarkdown(String html) {
        if (html == null || html.trim().isEmpty()) {
            return null;
        }
        String s = html;

        // Links first, while the anchor markup is still intact.
        s = s.replaceAll("(?is)<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)");

        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        for (int level = 1; level <= 6; level++) {
            String hashes = "######".substring(0, level);
            s = s.replaceAll("(?is)<h" + level + "[^>]*>", "\n" + hashes + " ");
            s = s.replaceAll("(?is)</h" + level + ">", "\n");
        }
        s = s.replaceAll("(?is)<li[^>]*>", "- ");
        s = s.replaceAll("(?is)</li\\s*>", "\n");
        s = s.replaceAll("(?is)</?(ul|ol)[^>]*>", "\n");
        s = s.replaceAll("(?is)</p\\s*>", "\n\n");
        s = s.replaceAll("(?is)<p[^>]*>", "");
        s = s.replaceAll("(?is)<(strong|b)[^>]*>", "**");
        s = s.replaceAll("(?is)</(strong|b)\\s*>", "**");
        s = s.replaceAll("(?is)<(em|i)[^>]*>", "*");
        s = s.replaceAll("(?is)</(em|i)\\s*>", "*");
        s = s.replaceAll("(?is)<code[^>]*>", "`");
        s = s.replaceAll("(?is)</code\\s*>", "`");

        // Anything left is a tag we do not style; drop it but keep its text.
        s = s.replaceAll("(?s)<[^>]+>", "");

        s = s.replace("&nbsp;", " ");
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");

        s = s.replaceAll("[ \\t]+\\n", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }
}
