package dev.rorar.plezyupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Remote trigger for the diagnostics, so a test script can ask the TV for the current GitHub quota
 * and read the answer back without a screen.
 *
 * <pre>
 *   adb shell am broadcast --include-stopped-packages \
 *       -a dev.rorar.plezyupdater.action.DIAGNOSTICS
 *   # Broadcast completed: result=-1, data="status=ok;remaining=57;limit=60;reset_ms=…;checked_ms=…;device=fire_tv;tv=1;touch=0;notif=allowed"
 * </pre>
 *
 * <p>The answer travels in the ordered-broadcast <em>result data</em> (not extras): a shell
 * broadcast prints it as {@code data="…"}, which is the one channel a script can read without
 * {@code run-as}. The same report is written to {@code quota.txt} in the app's external cache dir
 * as a second, permission-free way in.
 *
 * <p>The report carries a {@link Device#report} suffix ({@code device=…;tv=…;touch=…}) as well as
 * the quota: the device classification is the other thing a script cannot see from the outside,
 * and it rides along on the check that was already happening. Both the result and the file carry
 * it, so the two channels stay identical.
 *
 * <p>The check is a network call, so it runs off the main thread through {@link #goAsync()}; the
 * receiver stays alive until it finishes. {@code finish()} always runs, even on failure, or the
 * broadcast would hang until the system times it out.
 */
public class DiagnosticsReceiver extends BroadcastReceiver {

    /** Explicit action; the receiver is exported so a shell broadcast can reach it. */
    public static final String ACTION = "dev.rorar.plezyupdater.action.DIAGNOSTICS";

    @Override
    public void onReceive(Context c, Intent i) {
        final Context app = c.getApplicationContext();
        // Read the device classification and the notification state on the main thread (cheap) so
        // both the success and the failure branch can report them.
        final String device = Device.report(app);
        final String notif = Notifier.state(app);
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Diagnostics.QuotaResult r = Diagnostics.checkQuota(app);
                    String report = Diagnostics.report(app, r);
                    Diagnostics.writeReport(app, report);
                    // setResultData carries a plain string out of the broadcast; setResultCode(-1)
                    // is the success code a shell broadcast prints as "result=-1".
                    pending.setResultCode(-1);
                    pending.setResultData(report);
                } catch (Throwable t) {
                    // checkQuota already swallows everything; this is belt and braces so a stray
                    // error still leaves a result rather than a hung broadcast.
                    pending.setResultCode(0);
                    pending.setResultData("status=error;code=receiver;" + device + ";notif=" + notif);
                } finally {
                    pending.finish();
                }
            }
        }, "quota-check").start();
    }
}
