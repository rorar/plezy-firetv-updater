package dev.rorar.plezyupdater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

/**
 * One place for the runtime-receiver registration rules.
 *
 * <p>Android 14 (API 34) requires a {@code Context.registerReceiver} call to say whether the
 * receiver is exported, and refuses the call when a target-34 app leaves it out. The flag and the
 * three-argument overload only exist from API 33, so on the API 25 stick this stays the old
 * two-argument call and nothing about the legacy path changes.
 *
 * <p>Every receiver registered this way listens for an in-app broadcast
 * ({@link MainActivity#ACTION_INSTALL_FINISHED}) or a protected system broadcast
 * ({@link android.content.Intent#ACTION_PACKAGE_REMOVED}), so {@code RECEIVER_NOT_EXPORTED} is
 * always the correct answer — nothing outside this process may send those to it.
 */
final class Receivers {

    private Receivers() {
    }

    /**
     * Register {@code receiver} for {@code filter}, marked not-exported. Guarded so the API 25
     * stick keeps the exact two-argument call it had before.
     */
    static void register(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }
}
