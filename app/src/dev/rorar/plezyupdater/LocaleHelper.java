package dev.rorar.plezyupdater;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * The app's language.
 *
 * <p>By default the app follows the device: no override is stored, so its resources resolve to
 * whatever locale the Fire TV is set to. The user can override that in
 * <b>Settings → Language</b>; the choice is a language code in {@link Prefs}, and every screen is
 * wrapped with it in {@code attachBaseContext} so {@code getString} returns the picked language.
 *
 * <p>The list of shipped languages lives here ({@link #CODES}) together with the flag drawable and
 * the native-name string for each, so the picker and the resources cannot drift apart.
 */
public final class LocaleHelper {

    /** "Follow the system" — the default, stored as an empty string. */
    public static final String SYSTEM = "";

    /** The languages the app ships, in picker order. */
    public static final String[] CODES = {"en", "de", "fr", "es"};

    /** The native name of each language (the same in every locale, as language pickers do). */
    public static final int[] NAMES = {
            R.string.lang_en, R.string.lang_de, R.string.lang_fr, R.string.lang_es
    };

    /** The flag of each language, vendored from the MIT-licensed "flag-icons" pack. */
    public static final int[] FLAGS = {
            R.drawable.flag_en, R.drawable.flag_de, R.drawable.flag_fr, R.drawable.flag_es
    };

    private LocaleHelper() {
    }

    /**
     * Wrap a context so its resources use the picked language. A no-op while following the system,
     * so the device locale applies unchanged. Call from every activity's
     * {@code attachBaseContext}.
     */
    public static Context wrap(Context base) {
        String code = Prefs.appLanguage(base);
        if (code == null || code.isEmpty()) {
            return base;
        }
        Locale locale = new Locale(code);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    /** True if the app ships strings for {@code code}. */
    public static boolean isKnown(String code) {
        return indexOf(code) >= 0;
    }

    /** Position of {@code code} in {@link #CODES}, or -1. */
    public static int indexOf(String code) {
        if (code != null) {
            for (int i = 0; i < CODES.length; i++) {
                if (CODES[i].equals(code)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * The language the <em>device</em> asks for, if the app ships it, else English. Read from the
     * system resources so it is not affected by an override already applied to {@code c}.
     */
    public static String systemCode(Context c) {
        Locale device = Resources.getSystem().getConfiguration().locale;
        String language = device == null ? null : device.getLanguage();
        return isKnown(language) ? language : "en";
    }

    /** The name of a language code in that language, e.g. "Deutsch". */
    public static String name(Context c, String code) {
        int i = indexOf(code);
        return c.getString(NAMES[i < 0 ? 0 : i]);
    }
}
