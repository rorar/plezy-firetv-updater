package dev.rorar.plezyupdater;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;

/**
 * The Android side of {@link DeviceKind}: read the signals, classify once, and let callers ask a
 * question instead of re-deriving it from a pile of feature flags.
 *
 * <p>Every answer comes from {@link #signals(Context)} → {@link DeviceKind#classify}, so there is
 * a single place where "what is this device" is decided. The one exception is {@link #isTouch},
 * which is the raw {@code Configuration.touchscreen} flag rather than "the kind is TOUCH": an
 * Android TV with a touchscreen is {@code ANDROID_TV} <em>and</em> touch-capable, and the resource
 * system would still pick {@code values-finger} for it. Keeping the two separate is what makes the
 * touch wording ({@code res/values-finger/}) and the detection agree.
 *
 * <p>Nothing here is cached: the signals are cheap (three feature lookups, one system service, one
 * config read) and a cache would have to be invalidated on a configuration change.
 */
public final class Device {

    /** Amazon's own TV marker; present on Fire TV, absent on a plain Android TV. */
    public static final String FEATURE_FIRE_TV = "amazon.hardware.fire_tv";
    /** The Android TV marker most boxes carry. */
    public static final String FEATURE_LEANBACK = "android.software.leanback";
    /** The older/plainer TV marker; some AOSP boxes have this and not leanback. */
    public static final String FEATURE_TELEVISION = "android.hardware.type.television";

    private Device() {
    }

    /** The raw signals, for a caller that wants to reason about them itself. */
    public static DeviceKind.Signals signals(Context c) {
        PackageManager pm = c.getPackageManager();
        Configuration config = c.getResources().getConfiguration();
        return new DeviceKind.Signals(
                Build.MANUFACTURER,
                Build.MODEL,
                hasFeature(pm, FEATURE_FIRE_TV),
                hasFeature(pm, FEATURE_LEANBACK),
                hasFeature(pm, FEATURE_TELEVISION),
                isTelevisionUiMode(c),
                config.touchscreen == Configuration.TOUCHSCREEN_FINGER);
    }

    /** The one classification every other helper derives from. */
    public static DeviceKind kind(Context c) {
        return DeviceKind.classify(signals(c));
    }

    public static boolean isFireTv(Context c) {
        return kind(c) == DeviceKind.FIRE_TV;
    }

    /** True on a non-Amazon Android TV. A Fire TV answers {@link #isFireTv} instead. */
    public static boolean isAndroidTv(Context c) {
        return kind(c) == DeviceKind.ANDROID_TV;
    }

    public static boolean isTv(Context c) {
        return kind(c).isTv();
    }

    /**
     * True when Android would pick the {@code -finger} resources — i.e. when the app should say
     * "tap" instead of "press OK". Independent of {@link #kind}: a touch-capable TV is both.
     */
    public static boolean isTouch(Context c) {
        return c.getResources().getConfiguration().touchscreen
                == Configuration.TOUCHSCREEN_FINGER;
    }

    /**
     * A quote-free {@code key=value;…} suffix for the diagnostics report, e.g.
     * {@code device=fire_tv;tv=1;touch=0}. Never throws: an unreadable device becomes
     * {@code device=unknown;tv=0;touch=0} rather than taking the report down with it.
     */
    public static String report(Context c) {
        try {
            DeviceKind.Signals s = signals(c);
            DeviceKind kind = DeviceKind.classify(s);
            return "device=" + kind.slug()
                    + ";tv=" + (kind.isTv() ? 1 : 0)
                    + ";touch=" + (s.fingerTouch ? 1 : 0);
        } catch (Throwable t) {
            return "device=unknown;tv=0;touch=0";
        }
    }

    private static boolean hasFeature(PackageManager pm, String feature) {
        try {
            return pm != null && pm.hasSystemFeature(feature);
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code UI_MODE_TYPE_TELEVISION} catches AOSP boxes that carry neither TV feature. */
    private static boolean isTelevisionUiMode(Context c) {
        try {
            UiModeManager um = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            return um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        } catch (Throwable t) {
            return false;
        }
    }
}
