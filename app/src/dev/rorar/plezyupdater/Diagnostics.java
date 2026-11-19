package dev.rorar.plezyupdater;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * The diagnostics behind Settings → Diagnostics and its remote trigger.
 *
 * <p>The one check so far is the GitHub quota: how many of the 60 unauthenticated API requests
 * this hour are left, and when the window resets. It is worth surfacing because the whole app
 * works around that budget (see {@link PlezyRepo}) and the reset time is otherwise invisible.
 *
 * <p>Both the Settings row and {@link DiagnosticsReceiver} go through {@link #checkQuota}, so they
 * persist and report identically, and both leave the same {@code quota.txt} behind for a script
 * that cannot read a broadcast result.
 */
public final class Diagnostics {

    private static final String TAG = "PlezyUpdater";

    /** Report file in the app's external cache dir; readable over adb by a test script. */
    public static final String REPORT_FILE = "quota.txt";

    private Diagnostics() {
    }

    /** What one quota check produced: a machine report and a human line. */
    public static final class QuotaResult {
        public final boolean ok;
        /** Quote-free {@code key=value;…} string, suitable for a broadcast result or a file. */
        public final String report;
        /** A localised sentence for the UI (the reset summary, or the failure reason). */
        public final String message;

        QuotaResult(boolean ok, String report, String message) {
            this.ok = ok;
            this.report = report;
            this.message = message;
        }
    }

    /**
     * Ask GitHub for the current quota, remember it, and describe the outcome. Never throws: a
     * network failure becomes {@code ok == false} with the reason in {@link QuotaResult#message}.
     */
    public static QuotaResult checkQuota(Context c) {
        long now = System.currentTimeMillis();
        try {
            PlezyRepo.Quota q = PlezyRepo.fetchQuota(c);
            Prefs.setQuota(c, q.remaining, q.limit, q.resetMs, now);
            String report = "status=ok;remaining=" + q.remaining + ";limit=" + q.limit
                    + ";reset_ms=" + q.resetMs + ";checked_ms=" + now;
            return new QuotaResult(true, report, summary(c, q.remaining, q.limit, q.resetMs));
        } catch (Throwable t) {
            Log.e(TAG, "quota check failed", t);
            String report = "status=error;code=" + code(t) + ";checked_ms=" + now;
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return new QuotaResult(false, report, message);
        }
    }

    /**
     * The full machine report: the quota result plus the things a script cannot see from the
     * outside — the device classification, whether Android is allowing notifications, the platform
     * version and (on API 35+) the insets the UI actually applied:
     * {@code device=…;tv=…;touch=…;notif=allowed|blocked;sdk=…;insets=l,t,r,b}. Below 35 there is no
     * {@code insets} field at all: the platform still reserves the bars and {@link Ui#applyInsets}
     * is a no-op. The Settings row and {@link DiagnosticsReceiver} both build their report here, so
     * the broadcast result and the {@code quota.txt} file stay identical.
     */
    public static String report(Context c, QuotaResult r) {
        String insets = Ui.insetsReport();
        return r.report + ";" + Device.report(c) + ";notif=" + Notifier.state(c)
                + ";sdk=" + Build.VERSION.SDK_INT
                + (insets == null ? "" : ";insets=" + insets);
    }

    /**
     * "57 of 60 left · resets 13:25", built from the last stored values so the row reads correctly
     * without a fresh network call. {@link #NEVER} values fall back to "not checked yet".
     */
    public static String summary(Context c, int remaining, int limit, long resetMs) {
        if (limit <= 0 || remaining < 0) {
            return c.getString(R.string.set_quota_never);
        }
        String time = resetMs <= 0 ? "—" : android.text.format.DateFormat.getTimeFormat(c)
                .format(new java.util.Date(resetMs));
        return c.getString(R.string.set_quota_now, remaining, limit, time);
    }

    /** Writes the report where a test script can read it, ignoring any failure. */
    public static void writeReport(Context c, String report) {
        File dir = c.getExternalCacheDir();
        if (dir == null) {
            return;
        }
        File out = new File(dir, REPORT_FILE);
        Writer w = null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
            w.write(report);
            w.write('\n');
        } catch (Exception e) {
            Log.w(TAG, "could not write " + out, e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
        }
    }

    /** A throwable as one quote-free, space-free token, so it survives a broadcast result. */
    private static String code(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.getClass().getSimpleName();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m.length() && sb.length() < 80; i++) {
            char ch = m.charAt(i);
            sb.append(Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_'
                    ? ch : '_');
        }
        return sb.length() == 0 ? "error" : sb.toString();
    }
}
