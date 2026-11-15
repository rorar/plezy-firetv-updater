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
    private static final String KEY_LANGUAGE = "app_language";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_PENDING_DOWNGRADE = "pending_downgrade_tag";
    private static final String KEY_INTERVAL_VALUE = "interval_value";
    private static final String KEY_INTERVAL_UNIT = "interval_unit";

    /** Units the user can pick for the check interval. */
    public static final String UNIT_HOURS = "hours";
    public static final String UNIT_DAYS = "days";
    public static final String UNIT_WEEKS = "weeks";
    public static final String UNIT_MONTHS = "months";

    public static final String[] UNITS = {UNIT_HOURS, UNIT_DAYS, UNIT_WEEKS, UNIT_MONTHS};

    /** The original fixed cadence, kept as the default. */
    public static final int DEFAULT_INTERVAL_VALUE = 12;
    public static final String DEFAULT_INTERVAL_UNIT = UNIT_HOURS;

    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static final long DAY_MS = 24L * HOUR_MS;
    private static final long WEEK_MS = 7L * DAY_MS;
    /** A month has no fixed length; the schedule treats one as 30 days and says so in the UI. */
    private static final long MONTH_MS = 30L * DAY_MS;

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

    // ---- language -----------------------------------------------------------

    /** The picked language code, or {@link LocaleHelper#SYSTEM} to follow the device. */
    public static String appLanguage(Context c) {
        String code = sp(c).getString(KEY_LANGUAGE, LocaleHelper.SYSTEM);
        return code == null ? LocaleHelper.SYSTEM : code;
    }

    public static void setAppLanguage(Context c, String code) {
        sp(c).edit().putString(KEY_LANGUAGE,
                code == null ? LocaleHelper.SYSTEM : code).apply();
    }

    public static long lastCheckMs(Context c) {
        return sp(c).getLong(KEY_LAST_CHECK, 0L);
    }

    public static void setLastCheckMs(Context c, long value) {
        sp(c).edit().putLong(KEY_LAST_CHECK, value).apply();
    }

    /**
     * Tag of a downgrade whose uninstall has been requested but not yet finished. Persisted so the
     * flow survives the system uninstall dialog (and a low-memory process restart).
     */
    public static String pendingDowngrade(Context c) {
        return sp(c).getString(KEY_PENDING_DOWNGRADE, null);
    }

    public static void setPendingDowngrade(Context c, String tag) {
        SharedPreferences.Editor e = sp(c).edit();
        if (tag == null) {
            e.remove(KEY_PENDING_DOWNGRADE);
        } else {
            e.putString(KEY_PENDING_DOWNGRADE, tag);
        }
        e.apply();
    }

    // ---- check interval -----------------------------------------------------

    public static int intervalValue(Context c) {
        return sp(c).getInt(KEY_INTERVAL_VALUE, DEFAULT_INTERVAL_VALUE);
    }

    public static String intervalUnit(Context c) {
        String unit = sp(c).getString(KEY_INTERVAL_UNIT, DEFAULT_INTERVAL_UNIT);
        for (String known : UNITS) {
            if (known.equals(unit)) {
                return unit;
            }
        }
        return DEFAULT_INTERVAL_UNIT;
    }

    public static void setInterval(Context c, int value, String unit) {
        int safeValue = Math.max(1, value);
        String safeUnit = unit;
        for (String known : UNITS) {
            if (known.equals(unit)) {
                safeUnit = unit;
                break;
            }
        }
        if (safeUnit == null) {
            safeUnit = DEFAULT_INTERVAL_UNIT;
        }
        sp(c).edit()
                .putInt(KEY_INTERVAL_VALUE, safeValue)
                .putString(KEY_INTERVAL_UNIT, safeUnit)
                .apply();
    }

    /** The interval in milliseconds, derived from the value and unit. */
    public static long intervalMs(Context c) {
        int value = intervalValue(c);
        String unit = intervalUnit(c);
        long base;
        if (UNIT_DAYS.equals(unit)) {
            base = DAY_MS;
        } else if (UNIT_WEEKS.equals(unit)) {
            base = WEEK_MS;
        } else if (UNIT_MONTHS.equals(unit)) {
            base = MONTH_MS;
        } else {
            base = HOUR_MS;
        }
        return value * base;
    }

    /** "every 12 hours", "every 1 day" — for summaries. */
    public static String intervalLabel(Context c) {
        int value = intervalValue(c);
        return c.getString(R.string.interval_every, value, unitWord(c, intervalUnit(c), value));
    }

    /**
     * The word for a unit at count {@code n}, correctly pluralised for the current language
     * ("hour"/"hours", "Stunde"/"Stunden", "heure"/"heures", …).
     */
    public static String unitWord(Context c, String unit, int n) {
        int id;
        if (UNIT_DAYS.equals(unit)) {
            id = R.plurals.unit_days;
        } else if (UNIT_WEEKS.equals(unit)) {
            id = R.plurals.unit_weeks;
        } else if (UNIT_MONTHS.equals(unit)) {
            id = R.plurals.unit_months;
        } else {
            id = R.plurals.unit_hours;
        }
        return c.getResources().getQuantityString(id, n);
    }
}
