package dev.rorar.plezyupdater;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A tiny on-disk store of one HTTP response per URL: the {@code ETag} and the body.
 *
 * <p>It exists for two reasons. First, conditional requests: sending {@code If-None-Match} and
 * getting a {@code 304} back lets GitHub answer without counting the request against the
 * 60/hour API quota. Second, resilience: when the network is gone (or the quota is spent) the
 * last body is still there to parse.
 *
 * <p>Best-effort by design — a cache miss or a read error just means "ask again".
 */
final class HttpCache {

    private static final String DIR = "http";

    static final class Entry {
        final String etag;
        final String body;
        final long savedMs;

        Entry(String etag, String body, long savedMs) {
            this.etag = etag;
            this.body = body;
            this.savedMs = savedMs;
        }
    }

    private HttpCache() {
    }

    static Entry get(Context c, String url) {
        File f = file(c, url);
        if (f == null || !f.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            String all = readAll(in);
            int nl = all.indexOf('\n');
            if (nl < 0) {
                return null;
            }
            String header = all.substring(0, nl);
            String body = all.substring(nl + 1);
            String[] parts = header.split("\t", -1);
            String etag = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : null;
            long saved = 0L;
            if (parts.length > 1) {
                try {
                    saved = Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            return new Entry(etag, body, saved);
        } catch (Exception e) {
            return null;
        } finally {
            close(in);
        }
    }

    static void put(Context c, String url, String etag, String body) {
        if (body == null) {
            return;
        }
        File f = file(c, url);
        if (f == null) {
            return;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            String header = (etag == null ? "" : etag) + "\t" + System.currentTimeMillis() + "\n";
            out.write(header.getBytes("UTF-8"));
            out.write(body.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // The cache is an optimisation, never a requirement.
        } finally {
            close(out);
        }
    }

    private static File file(Context c, String url) {
        File dir = new File(c.getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        // The URL identifies the entry; a stable 32-bit hash keeps the name short and filesystem-safe.
        return new File(dir, Integer.toHexString(url.hashCode()) + ".cache");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return new String(buf.toByteArray(), "UTF-8");
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
