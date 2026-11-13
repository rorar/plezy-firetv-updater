package dev.rorar.plezyupdater;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings: what the updater is allowed to show, and whether it checks by itself. */
public final class Prefs {

    private static final String FILE = "settings";

    public static final String KEY_TOAST = "toast_enabled";
    public static final String KEY_NOTIFICATION = "notification_enabled";
    public static final String KEY_AUTO_CHECK = "auto_check_enabled";
    public static final String KEY_ALLOW_OLDER = "allow_older_versions";
    public static final String KEY_DELETE_APK = "delete_apk_after_install";
    private static final String KEY_LAST_CHECK = "last_check_ms";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean toastEnabled(Context c) {
        return sp(c).getBoolean(KEY_TOAST, true);
    }

    public static boolean notificationEnabled(Context c) {
        return sp(c).getBoolean(KEY_NOTIFICATION, true);
    }

    public static boolean autoCheckEnabled(Context c) {
        return sp(c).getBoolean(KEY_AUTO_CHECK, true);
    }

    /**
     * Development aid: offer every release, not only newer ones. Off by default because a
     * downgrade can break app data, and the store-style flow is "forward only".
     */
    public static boolean allowOlderVersions(Context c) {
        return sp(c).getBoolean(KEY_ALLOW_OLDER, false);
    }

    /**
     * Free the extracted APK once it has been installed. On by default: the stick has very
     * little storage, and a kept APK is only useful to reinstall the same release.
     */
    public static boolean deleteApkAfterInstall(Context c) {
        return sp(c).getBoolean(KEY_DELETE_APK, true);
    }

    public static void set(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }

    public static long lastCheckMs(Context c) {
        return sp(c).getLong(KEY_LAST_CHECK, 0L);
    }

    public static void setLastCheckMs(Context c, long value) {
        sp(c).edit().putLong(KEY_LAST_CHECK, value).apply();
    }
}
