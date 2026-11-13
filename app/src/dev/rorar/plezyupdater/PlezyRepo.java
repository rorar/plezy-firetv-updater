package dev.rorar.plezyupdater;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Talks to the upstream Plezy repository. No mirror, no third party. */
public final class PlezyRepo {

    /** The app being updated. */
    public static final String PACKAGE_NAME = "com.edde746.plezy";

    /** Upstream release feed. */
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/edde746/plezy/releases/latest";

    private static final String RELEASES_URL =
            "https://api.github.com/repos/edde746/plezy/releases";

    /** The 32-bit Android build that this stick needs. */
    public static final String ANDROID_ASSET = "plezy-android-armeabi-v7a.tar.gz";

    private PlezyRepo() {
    }

    public static final class Release {
        public final String tag;
        public final String assetUrl;

        Release(String tag, String assetUrl) {
            this.tag = tag;
            this.assetUrl = assetUrl;
        }
    }

    /** Version name of the installed Plezy, or null when it is not installed. */
    public static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PACKAGE_NAME, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public static Release fetchLatest() throws Exception {
        JSONObject json = new JSONObject(get(LATEST_RELEASE_URL));
        String tag = json.getString("tag_name");
        String assetUrl = assetUrl(json);
        if (assetUrl == null) {
            throw new IllegalStateException("Release " + tag + " has no " + ANDROID_ASSET);
        }
        return new Release(tag, assetUrl);
    }

    /** Newest first, drafts and releases without the 32-bit Android build skipped. */
    public static java.util.List<Release> fetchReleases(int limit) throws Exception {
        JSONArray array = new JSONArray(get(RELEASES_URL + "?per_page=" + limit));
        java.util.List<Release> releases = new java.util.ArrayList<Release>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.getJSONObject(i);
            if (json.optBoolean("draft", false)) {
                continue;
            }
            String tag = json.optString("tag_name", null);
            String url = assetUrl(json);
            if (tag != null && url != null) {
                releases.add(new Release(tag, url));
            }
        }
        return releases;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "plezy-firetv-updater");
            conn.setRequestProperty("Accept", "application/vnd.github+json");

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("GitHub API returned HTTP " + code);
            }

            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String assetUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && ANDROID_ASSET.equals(a.optString("name"))) {
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
