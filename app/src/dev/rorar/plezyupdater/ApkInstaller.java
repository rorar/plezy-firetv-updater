package dev.rorar.plezyupdater;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

/**
 * Download, unpack and verify an upstream Plezy release, then hand it to the stock installer.
 *
 * <p>Nothing here tries to install silently — that is impossible on this device (no root, no
 * {@code INSTALL_PACKAGES}). The last step is deliberately the system's own confirmation
 * dialog, so the user always sees what is about to be installed.
 */
public final class ApkInstaller {

    private static final String TAG = "PlezyUpdater";

    private static final String TAR_NAME = "plezy-update.tar.gz";
    private static final String APK_NAME = "plezy-update.apk";
    private static final String TAG_MARKER = "plezy-update.tag";

    private static final long MB = 1024L * 1024L;

    /**
     * Room the whole cycle needs, guessed from the download size: the tarball, the extracted
     * APK (~1.9x the compressed size) and the copy PackageInstaller makes in its own staging
     * area. Fire TV sticks have very little free space, so this is checked up front instead of
     * failing halfway through a 48 MB download.
     */
    private static final long MIN_FREE_BYTES = 300L * MB;

    public static final long MIN_FREE_SPACE = MIN_FREE_BYTES;

    public interface Progress {
        void onPercent(int percent);
    }

    public static final class Verified {
        public final String versionName;
        public final int versionCode;

        Verified(String versionName, int versionCode) {
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
    }

    private ApkInstaller() {
    }

    /** Streams the tarball to {@code dest}; never buffers more than one chunk in memory. */
    public static void download(String url, File dest, Progress progress) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "plezy-firetv-updater");

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Download failed: HTTP " + code);
            }
            long total = conn.getContentLength();
            requireSpace(dest.getParentFile(), total > 0 ? requiredBytes(total) : MIN_FREE_BYTES);

            InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 64 * 1024);
            try {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int lastPercent = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int percent = (int) (done * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            progress.onPercent(percent);
                        }
                    }
                }
            } finally {
                out.close();
                in.close();
            }
            if (total > 0 && dest.length() != total) {
                throw new IOException("Download truncated: " + dest.length() + "/" + total + " bytes");
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Free bytes on the filesystem holding {@code dir}. */
    public static long freeBytes(File dir) {
        try {
            android.os.StatFs stat = new android.os.StatFs(dir.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Throwable t) {
            Log.w(TAG, "could not read free space", t);
            return -1L;
        }
    }

    /** Throws with a readable message when there is not enough room. */
    public static void requireSpace(File dir, long needed) throws IOException {
        long free = freeBytes(dir);
        if (free >= 0 && free < needed) {
            throw new IOException("Not enough free space: " + mb(free) + " free, need about "
                    + mb(needed) + ". Free up space on the Fire TV and try again");
        }
    }

    private static long requiredBytes(long downloadSize) {
        return downloadSize * 5 + 20 * MB;
    }

    private static String mb(long bytes) {
        return (bytes / MB) + " MB";
    }

    /** Localised size for the UI, e.g. "803 MB". */
    public static String formatSize(Context c, long bytes) {
        return android.text.format.Formatter.formatFileSize(c, bytes);
    }

    /**
     * Pulls the single {@code plezy.apk} out of the gzip'd tar without a tar library:
     * 512-byte header, octal size at offset 124, payload padded to 512 bytes.
     */
    public static void extractApk(File tarGz, File apkOut) throws IOException {
        InputStream in = new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(tarGz), 64 * 1024), 64 * 1024);
        try {
            byte[] header = new byte[512];
            while (true) {
                if (!readFully(in, header)) {
                    throw new IOException("Archive contains no .apk entry");
                }
                if (isZeroBlock(header)) {
                    continue; // trailing zero padding before EOF
                }
                String name = cString(header, 0, 100);
                String prefix = cString(header, 345, 155);
                if (prefix.length() > 0) {
                    name = prefix + "/" + name;
                }
                long size = octal(header, 124, 12);
                char type = (char) (header[156] & 0xff);
                boolean regularFile = type == '0' || type == '\0';
                if (regularFile && name.toLowerCase(Locale.US).endsWith(".apk")) {
                    OutputStream out = new BufferedOutputStream(new FileOutputStream(apkOut), 64 * 1024);
                    try {
                        copyExactly(in, out, size);
                    } finally {
                        out.close();
                    }
                    return;
                }
                skipExactly(in, size + padding(size));
            }
        } finally {
            in.close();
        }
    }

    /**
     * The mandatory gate: right package, right ABI, installable on this Android version, newer
     * than what is installed, and the <em>same signing certificate</em>. A tampered or
     * typo-squatted download stops here, before the installer dialog is ever shown.
     */
    public static Verified verify(Context c, File apk, boolean allowOlder) throws IOException {
        PackageManager pm = c.getPackageManager();

        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                PackageManager.GET_SIGNATURES);
        if (candidate == null) {
            throw new IOException("Downloaded file is not a valid APK");
        }
        if (!PlezyRepo.PACKAGE_NAME.equals(candidate.packageName)) {
            throw new IOException("Wrong package: " + candidate.packageName);
        }

        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(PlezyRepo.PACKAGE_NAME, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Plezy is not installed on this device");
        }

        if (candidate.versionCode < installed.versionCode && !allowOlder) {
            throw new IOException("Downloaded build " + candidate.versionCode
                    + " is older than installed " + installed.versionCode
                    + " — enable \"Allow older versions\" to install it anyway");
        }

        if (candidate.applicationInfo != null
                && candidate.applicationInfo.minSdkVersion > android.os.Build.VERSION.SDK_INT) {
            throw new IOException("Requires Android API " + candidate.applicationInfo.minSdkVersion
                    + ", this device is API " + android.os.Build.VERSION.SDK_INT);
        }

        if (!hasArmLibraries(apk)) {
            throw new IOException("APK has no armeabi-v7a libraries");
        }

        if (candidate.signatures == null || candidate.signatures.length == 0
                || installed.signatures == null || installed.signatures.length == 0) {
            throw new IOException("Missing signature information");
        }
        byte[] expected = digest(installed.signatures[0]);
        byte[] actual = digest(candidate.signatures[0]);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IOException("Signer mismatch — refusing to install");
        }

        Log.i(TAG, "verified " + candidate.versionName + " (" + candidate.versionCode
                + ") signer " + hex(actual));
        return new Verified(candidate.versionName, candidate.versionCode);
    }

    /** Commits a PackageInstaller session; the system dialog takes over from here. */
    public static void install(Context c, File apk, boolean allowDowngrade) throws IOException {
        // The system copies the APK into its own staging area, so there has to be room for a
        // second copy on top of the one already in the cache.
        requireSpace(c.getCacheDir(), apk.length() + 50 * MB);

        PackageManager pm = c.getPackageManager();
        PackageInstaller installer = pm.getPackageInstaller();

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        if (allowDowngrade) {
            requestDowngrade(params);
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            OutputStream out = session.openWrite("plezy.apk", 0, apk.length());
            InputStream in = new BufferedInputStream(new FileInputStream(apk), 64 * 1024);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            } finally {
                out.close();
                in.close();
            }

            Intent result = new Intent(c, InstallResultReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, sessionId, result,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pi.getIntentSender());
            Log.i(TAG, "install session " + sessionId + " committed");
        } catch (RuntimeException e) {
            try {
                session.abandon();
            } catch (Throwable ignored) {
                // best effort
            }
            throw e;
        } finally {
            session.close();
        }
    }

    /** Deletes leftover download/extract artefacts. Safe to call at any time. */
    public static void cleanCache(Context c) {
        deleteDownload(c);
        delete(new File(c.getCacheDir(), APK_NAME));
        delete(new File(c.getCacheDir(), TAG_MARKER));
    }

    /** Removes only the downloaded tarball, leaving a verified APK in the cache. */
    public static void deleteDownload(Context c) {
        delete(new File(c.getCacheDir(), TAR_NAME));
    }

    /**
     * A verified APK for this exact release is already in the cache. Keeps a retry after a
     * failed install from pulling 48 MB again.
     */
    public static File cachedApk(Context c, String tag) {
        File apk = new File(c.getCacheDir(), APK_NAME);
        File marker = new File(c.getCacheDir(), TAG_MARKER);
        if (!apk.isFile() || apk.length() == 0 || !marker.isFile()) {
            return null;
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(marker), "UTF-8"));
            try {
                String stored = r.readLine();
                return tag != null && tag.equals(stored == null ? null : stored.trim())
                        ? apk : null;
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Records which release the cached APK belongs to. */
    public static void markCached(Context c, String tag) throws IOException {
        OutputStream out = new FileOutputStream(new File(c.getCacheDir(), TAG_MARKER));
        try {
            out.write(tag.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    /** Removes artefacts that a crashed run left behind (older than one hour). */
    public static void cleanStaleCache(Context c) {
        long cutoff = System.currentTimeMillis() - 60L * 60L * 1000L;
        for (String name : new String[]{TAR_NAME, APK_NAME, TAG_MARKER}) {
            File f = new File(c.getCacheDir(), name);
            if (f.isFile() && f.lastModified() < cutoff && !f.delete()) {
                Log.w(TAG, "could not delete stale " + f);
            }
        }
    }

    private static void delete(File f) {
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "could not delete " + f);
        }
    }

    /**
     * API 25 has no {@code SessionParams.setAllowDowngrade()} (added in API 31), so the hidden
     * {@code installFlags} field is the only way to ask for a downgrade. Reflection to a hidden
     * framework field is ugly but this is a deliberate, user-enabled developer option; if the
     * field is not there, the install simply behaves as before (forward-only).
     */
    private static void requestDowngrade(PackageInstaller.SessionParams params) {
        final int installAllowDowngrade = 0x00000080;
        try {
            java.lang.reflect.Field field =
                    PackageInstaller.SessionParams.class.getDeclaredField("installFlags");
            field.setAccessible(true);
            field.setInt(params, field.getInt(params) | installAllowDowngrade);
            Log.i(TAG, "requested INSTALL_ALLOW_DOWNGRADE");
        } catch (Throwable t) {
            Log.w(TAG, "could not request downgrade: " + t);
        }
    }

    private static boolean hasArmLibraries(File apk) throws IOException {
        ZipFile zip = new ZipFile(apk);
        try {
            return zip.getEntry("lib/armeabi-v7a/") != null
                    || zip.getEntry("lib/armeabi-v7a/libflutter.so") != null
                    || zip.getEntry("lib/armeabi-v7a/libapp.so") != null;
        } finally {
            zip.close();
        }
    }

    private static byte[] digest(Signature s) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.toByteArray());
        } catch (Exception e) {
            throw new IOException("Could not hash signer", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    // --- tar plumbing -------------------------------------------------------

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off > 0;
            }
            off += n;
        }
        return true;
    }

    private static void copyExactly(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                throw new IOException("Unexpected end of archive");
            }
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static void skipExactly(InputStream in, long size) throws IOException {
        byte[] buf = new byte[8192];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                return;
            }
            left -= n;
        }
    }

    private static long padding(long size) {
        return (512 - (size % 512)) % 512;
    }

    private static String cString(byte[] buf, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset).trim();
    }

    private static long octal(byte[] buf, int offset, int length) {
        String s = cString(buf, offset, length);
        if (s.length() == 0) {
            return 0;
        }
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isZeroBlock(byte[] buf) {
        for (byte b : buf) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
