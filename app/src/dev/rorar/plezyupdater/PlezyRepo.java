package dev.rorar.plezyupdater;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the upstream Plezy repository. No mirror, no third party.
 *
 * <p>GitHub's REST API is <em>metered</em>: 60 unauthenticated requests per hour, per IP. The
 * updater used to spend that budget on every screen and every boot, which is why a burst of checks
 * could exhaust it and leave the picker showing {@code HTTP 403}. Three habits keep it under the
 * limit, in order of how much they save:
 *
 * <ol>
 *   <li><b>The unmetered web endpoints are the primary source.</b> {@code releases/latest} (a 302
 *       to the tag) and {@code releases.atom} live on {@code github.com}, not {@code api.github.com},
 *       so they do not count against the API quota at all. The latest version and its notes come
 *       from there; the asset URL is constructed from the tag (Plezy ships the same asset name on
 *       every release). The API is only the fallback.</li>
 *   <li><b>Conditional requests.</b> The API calls that remain (the version picker) send
 *       {@code If-None-Match}; a {@code 304} costs nothing and is served from {@link HttpCache}.</li>
 *   <li><b>A TTL.</b> A "latest" answer is reused for {@link #LATEST_TTL_MS}, so a burst of checks
 *       (launch, resume, boot) collapses into one request.</li>
 * </ol>
 *
 * <p>When the API <em>is</em> spent and the web path also fails, a fetch throws
 * {@link RateLimitedException} carrying the reset time; {@link UpdateChecker} then shows the last
 * known version instead of a bare {@code 403}.
 */
public final class PlezyRepo {

    /** The app being updated. */
    public static final String PACKAGE_NAME = "com.edde746.plezy";

    /** The metered GitHub API (60 requests/hour unauthenticated; 304s are free). */
    private static final String API_LATEST =
            "https://api.github.com/repos/edde746/plezy/releases/latest";
    private static final String API_RELEASES =
            "https://api.github.com/repos/edde746/plezy/releases";

    /** The unmetered web endpoints — the primary source, and the fallback when the API is spent. */
    private static final String WEB_LATEST = "https://github.com/edde746/plezy/releases/latest";
    private static final String WEB_ATOM = "https://github.com/edde746/plezy/releases.atom";
    private static final String WEB_TAG = "/releases/tag/";
    private static final String WEB_DOWNLOAD =
            "https://github.com/edde746/plezy/releases/download/";

    /** The quota endpoint. GitHub exempts it from the limit, so it costs nothing to ask. */
    private static final String API_RATE_LIMIT = "https://api.github.com/rate_limit";

    /** The Android ABIs Plezy publishes a tarball for, best first. */
    private static final String[] ASSET_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64"};

    /** A "latest" answer is reused for this long, so a burst of checks costs one request. */
    private static final long LATEST_TTL_MS = 15L * 60L * 1000L;

    private PlezyRepo() {
    }

    public static final class Release {
        public final String tag;
        public final String assetUrl;
        /** GitHub release notes (Markdown), or null when the release has none. */
        public final String body;
        /** ISO-8601 publication date, or null. */
        public final String publishedAt;

        Release(String tag, String assetUrl) {
            this(tag, assetUrl, null, null);
        }

        Release(String tag, String assetUrl, String body, String publishedAt) {
            this.tag = tag;
            this.assetUrl = assetUrl;
            this.body = body;
            this.publishedAt = publishedAt;
        }
    }

    /** GitHub's hourly request limit is the reason a fetch failed. Carries the reset time. */
    public static final class RateLimitedException extends Exception {
        final long resetMs;

        RateLimitedException(String message, long resetMs) {
            super(message);
            this.resetMs = resetMs;
        }
    }

    /** The unauthenticated REST quota, as reported by {@code /rate_limit}. */
    public static final class Quota {
        public final int remaining;
        public final int limit;
        /** Epoch millis the window resets at. */
        public final long resetMs;

        Quota(int remaining, int limit, long resetMs) {
            this.remaining = remaining;
            this.limit = limit;
            this.resetMs = resetMs;
        }
    }

    /**
     * The asset file name for this device, e.g. {@code plezy-android-armeabi-v7a.tar.gz}. Plezy
     * ships one tarball per ABI; the right one is picked from {@link android.os.Build#SUPPORTED_ABIS}
     * (best ABI first), so the same APK works on a 32-bit Fire TV stick and on a 64-bit Android TV.
     * When none of the shipped ABIs is supported the 32-bit build is named anyway — the verify step
     * then rejects it with a readable message rather than silently downloading nothing.
     */
    public static String assetName() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            for (String known : ASSET_ABIS) {
                if (known.equals(abi)) {
                    return assetNameFor(known);
                }
            }
        }
        return assetNameFor("armeabi-v7a");
    }

    private static String assetNameFor(String abi) {
        return "plezy-android-" + abi + ".tar.gz";
    }

    /** This device's ABIs, comma-separated, for the "no native libraries" message. */
    public static String supportedAbis() {
        StringBuilder sb = new StringBuilder();
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(abi);
        }
        return sb.toString();
    }

    /**
     * The current unauthenticated REST quota. {@code /rate_limit} is exempt from the limit, so
     * this can be asked on demand without spending the budget it reports. Used by
     * Settings → Diagnostics and its remote trigger.
     */
    public static Quota fetchQuota(Context c) throws Exception {
        Resp r = request(c, API_RATE_LIMIT, null, false);
        if (r.code != 200 || r.body == null) {
            throw new IllegalStateException(c.getString(R.string.err_repo_http, r.code));
        }
        JSONObject core = new JSONObject(r.body).getJSONObject("resources").getJSONObject("core");
        return new Quota(core.getInt("remaining"), core.getInt("limit"),
                core.getLong("reset") * 1000L);
    }

    /** Version name of the installed Plezy, or null when it is not installed. */
    public static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PACKAGE_NAME, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public static Release fetchLatest(Context c) throws Exception {
        return fetchLatest(c, false);
    }

    /**
     * The newest release. Prefers the unmetered web path; falls back to the API only if that fails.
     *
     * @param force skip the TTL cache and ask now
     */
    public static Release fetchLatest(Context c, boolean force) throws Exception {
        if (!force) {
            Release cached = cachedLatest(c, LATEST_TTL_MS);
            if (cached != null) {
                return cached;
            }
        }

        Exception failure = null;
        try {
            Release live = latestFromWeb(c);
            storeLatest(c, live);
            return live;
        } catch (Exception e) {
            failure = e;
        }

        try {
            Release live = latestFromApi(c);
            storeLatest(c, live);
            return live;
        } catch (Exception e) {
            failure = e;
        }

        throw failure;
    }

    /** The last "latest" answer we stored, whatever its age, or null. */
    public static Release lastKnownLatest(Context c) {
        return cachedLatest(c, 0L);
    }

    /** Newest first, drafts and releases without the 32-bit Android build skipped. */
    public static List<Release> fetchReleases(Context c, int limit) throws Exception {
        try {
            return parseApiReleases(conditionalJson(c, API_RELEASES + "?per_page=" + limit).body);
        } catch (Exception e) {
            // The API is spent or unreachable: the Atom feed is unmetered and still works.
            try {
                List<Release> list = atomReleases(c);
                if (!list.isEmpty()) {
                    return list;
                }
            } catch (Exception ignored) {
            }
            // Offline: the last API body we cached is better than nothing.
            HttpCache.Entry cached = HttpCache.get(c, API_RELEASES + "?per_page=" + limit);
            if (cached != null) {
                try {
                    List<Release> list = parseApiReleases(cached.body);
                    if (!list.isEmpty()) {
                        return list;
                    }
                } catch (Exception ignored) {
                }
            }
            throw e;
        }
    }

    // ---- the unmetered web path ---------------------------------------------

    private static Release latestFromWeb(Context c) throws Exception {
        Resp redirect = request(c, WEB_LATEST, null, false);
        if (redirect.code != 301 && redirect.code != 302) {
            throw new IllegalStateException(c.getString(R.string.err_repo_http, redirect.code));
        }
        String tag = tagFromLocation(redirect.location);
        if (tag == null) {
            throw new IllegalStateException(c.getString(R.string.err_repo_http, redirect.code));
        }
        return new Release(tag, assetUrlForTag(tag), atomBodyFor(c, tag), null);
    }

    /** The Atom feed's release notes for one tag, or null if the feed has nothing (notes only). */
    private static String atomBodyFor(Context c, String tag) {
        try {
            for (Release r : atomReleases(c)) {
                if (tag.equals(r.tag)) {
                    return r.body;
                }
            }
        } catch (Exception ignored) {
            // Notes are a nicety; the version itself already came through.
        }
        return null;
    }

    private static List<Release> atomReleases(Context c) throws Exception {
        HttpCache.Entry cached = HttpCache.get(c, WEB_ATOM);
        Resp r = request(c, WEB_ATOM, cached == null ? null : cached.etag, true);
        String body;
        if (r.code == 304 && cached != null) {
            body = cached.body;
        } else if (r.code == 200) {
            body = r.body;
            HttpCache.put(c, WEB_ATOM, r.etag, body);
        } else {
            throw new IllegalStateException(c.getString(R.string.err_repo_http, r.code));
        }
        return parseAtom(body);
    }

    private static List<Release> parseAtom(String xml) throws Exception {
        List<Release> out = new ArrayList<Release>();
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(xml));

        String title = null;
        String link = null;
        String updated = null;
        String content = null;
        boolean inEntry = false;

        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("entry".equals(name)) {
                    inEntry = true;
                    title = link = updated = content = null;
                } else if (inEntry && "title".equals(name)) {
                    title = readText(p);
                } else if (inEntry && "link".equals(name)) {
                    String rel = p.getAttributeValue(null, "rel");
                    String href = p.getAttributeValue(null, "href");
                    if (href != null && (rel == null || "alternate".equals(rel))) {
                        link = href;
                    }
                } else if (inEntry && "updated".equals(name)) {
                    updated = readText(p);
                } else if (inEntry && "content".equals(name)) {
                    content = readText(p);
                }
            } else if (ev == XmlPullParser.END_TAG && "entry".equals(p.getName())) {
                String tag = tagFromLink(link);
                if (tag == null) {
                    tag = title;
                }
                if (tag != null && !tag.isEmpty()) {
                    out.add(new Release(tag, assetUrlForTag(tag), Html.toMarkdown(content), updated));
                }
                inEntry = false;
            }
        }
        return out;
    }

    /** The text of the element the parser is on, leaving it on that element's end tag. */
    private static String readText(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                sb.append(p.getText());
            } else if (ev == XmlPullParser.START_TAG) {
                depth++;
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            } else if (ev == XmlPullParser.END_DOCUMENT) {
                break;
            }
        }
        return sb.toString();
    }

    // ---- the metered API path -----------------------------------------------

    private static Release latestFromApi(Context c) throws Exception {
        JSONObject json = new JSONObject(conditionalJson(c, API_LATEST).body);
        String tag = json.getString("tag_name");
        String assetUrl = assetUrl(json);
        if (assetUrl == null) {
            throw new IllegalStateException(c.getString(R.string.err_repo_no_asset, tag, assetName()));
        }
        return new Release(tag, assetUrl, body(json), publishedAt(json));
    }

    private static List<Release> parseApiReleases(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        List<Release> releases = new ArrayList<Release>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject release = array.getJSONObject(i);
            if (release.optBoolean("draft", false)) {
                continue;
            }
            String tag = release.optString("tag_name", null);
            String url = assetUrl(release);
            if (tag != null && url != null) {
                releases.add(new Release(tag, url, body(release), publishedAt(release)));
            }
        }
        return releases;
    }

    /**
     * A conditional GET that must yield JSON. A {@code 304} is served from {@link HttpCache}; a
     * {@code 403} with no quota left becomes a {@link RateLimitedException}.
     */
    private static Resp conditionalJson(Context c, String url) throws Exception {
        HttpCache.Entry cached = HttpCache.get(c, url);
        Resp r = request(c, url, cached == null ? null : cached.etag, true);
        if (r.code == 304) {
            if (cached == null) {
                throw new IllegalStateException(c.getString(R.string.err_repo_http, r.code));
            }
            return new Resp(200, cached.body, cached.etag, null, r.rateRemaining, r.rateResetMs);
        }
        if (r.code == 200) {
            HttpCache.put(c, url, r.etag, r.body);
            return r;
        }
        if (r.code == 403 && r.rateRemaining == 0) {
            throw new RateLimitedException(rateLimitMessage(c, r.rateResetMs), r.rateResetMs);
        }
        throw new IllegalStateException(c.getString(R.string.err_repo_http, r.code));
    }

    /** The calm wording for an exhausted API quota, with the reset time when GitHub told us one. */
    private static String rateLimitMessage(Context c, long resetMs) {
        if (resetMs <= 0) {
            return c.getString(R.string.err_rate_limited_soon);
        }
        String time = android.text.format.DateFormat.getTimeFormat(c)
                .format(new java.util.Date(resetMs));
        return c.getString(R.string.err_rate_limited, time);
    }

    // ---- HTTP ---------------------------------------------------------------

    private static final class Resp {
        final int code;
        final String body;
        final String etag;
        final String location;
        /** X-RateLimit-Remaining, or -1 when the header is absent. */
        final int rateRemaining;
        /** X-RateLimit-Reset as epoch millis, or 0 when absent. */
        final long rateResetMs;

        Resp(int code, String body, String etag, String location, int rateRemaining, long rateResetMs) {
            this.code = code;
            this.body = body;
            this.etag = etag;
            this.location = location;
            this.rateRemaining = rateRemaining;
            this.rateResetMs = rateResetMs;
        }
    }

    private static Resp request(Context c, String url, String etag, boolean follow) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(follow);
            conn.setRequestProperty("User-Agent", "plezy-firetv-updater");
            conn.setRequestProperty("Accept", url.contains("api.github.com")
                    ? "application/vnd.github+json"
                    : "application/atom+xml, text/html, */*");
            if (etag != null) {
                conn.setRequestProperty("If-None-Match", etag);
            }

            int code = conn.getResponseCode();
            InputStream in = null;
            try {
                in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            } catch (Exception ignored) {
                // A status with no body (e.g. 304) can make the stream unavailable; that is fine.
            }
            String body = in == null ? null : read(in);

            long reset = longHeader(conn, "X-RateLimit-Reset");
            return new Resp(code, body, conn.getHeaderField("ETag"),
                    conn.getHeaderField("Location"),
                    (int) longHeader(conn, "X-RateLimit-Remaining"),
                    reset > 0 ? reset * 1000L : 0L);
        } finally {
            conn.disconnect();
        }
    }

    private static long longHeader(HttpURLConnection conn, String name) {
        String value = conn.getHeaderField(name);
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }

    // ---- small helpers ------------------------------------------------------

    /** The release tag out of a {@code /releases/tag/<tag>} URL or Location header. */
    static String tagFromLocation(String location) {
        return tagFromLink(location);
    }

    private static String tagFromLink(String link) {
        if (link == null) {
            return null;
        }
        int at = link.indexOf(WEB_TAG);
        if (at < 0) {
            return null;
        }
        String tag = link.substring(at + WEB_TAG.length());
        int slash = tag.indexOf('/');
        if (slash >= 0) {
            tag = tag.substring(0, slash);
        }
        int query = tag.indexOf('?');
        if (query >= 0) {
            tag = tag.substring(0, query);
        }
        try {
            return URLDecoder.decode(tag, "UTF-8");
        } catch (Exception e) {
            return tag;
        }
    }

    /** The download URL for a release tag, built without the API. */
    static String assetUrlForTag(String tag) {
        return WEB_DOWNLOAD + tag + "/" + assetName();
    }

    private static Release cachedLatest(Context c, long ttlMs) {
        String tag = Prefs.cachedLatestTag(c);
        if (tag == null) {
            return null;
        }
        long ms = Prefs.cachedLatestMs(c);
        if (ttlMs > 0 && (ms == 0L || System.currentTimeMillis() - ms > ttlMs)) {
            return null;
        }
        // The URL is derived, never stored: it depends on the tag and on this device's ABI.
        return new Release(tag, assetUrlForTag(tag),
                Prefs.cachedLatestBody(c), Prefs.cachedLatestPublished(c));
    }

    private static void storeLatest(Context c, Release r) {
        Prefs.setCachedLatest(c, r.tag, r.body, r.publishedAt, System.currentTimeMillis());
    }

    private static String body(JSONObject release) {
        String b = release.optString("body", null);
        return b == null || b.trim().isEmpty() ? null : b;
    }

    private static String publishedAt(JSONObject release) {
        return release.optString("published_at", null);
    }

    private static String assetUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && assetName().equals(a.optString("name"))) {
                String url = a.optString("browser_download_url", null);
                if (url != null) {
                    return url;
                }
            }
        }
        return null;
    }

    /**
     * Compares dotted versions numerically. Returns > 0 when {@code a} is newer than {@code b}.
     * Non-numeric suffixes are ignored ("2.27.0-beta" == "2.27.0").
     */
    public static int compareVersions(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        String[] pa = normalise(a).split("\\.");
        String[] pb = normalise(b).split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? number(pa[i]) : 0;
            int vb = i < pb.length ? number(pb[i]) : 0;
            if (va != vb) {
                return va > vb ? 1 : -1;
            }
        }
        return 0;
    }

    private static String normalise(String v) {
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int dash = s.indexOf('-');
        if (dash >= 0) {
            s = s.substring(0, dash);
        }
        return s;
    }

    private static int number(String part) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char ch = part.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
