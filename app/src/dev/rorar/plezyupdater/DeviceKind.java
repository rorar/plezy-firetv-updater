package dev.rorar.plezyupdater;

import java.util.Locale;

/**
 * What the updater is running on: a Fire TV, another Android TV, a touch device, or none of the
 * above.
 *
 * <p>This class is deliberately free of any {@code android.*} import. Everything it needs is the
 * plain {@link Signals} bundle, so the decision can be unit-tested on the JVM
 * ({@code test/jvm/DeviceKindTest.java}) instead of only on a device. {@link Device} is the
 * Android side: it reads the signals from the {@code PackageManager}, {@code Build} and the
 * {@code Configuration} and hands them here.
 *
 * <p>The signals are a ladder, not a set of independent flags, and the result is one value with a
 * fixed precedence: <b>Fire TV &gt; Android TV &gt; touch &gt; other</b>. A device that reports
 * several signals (an Android TV with a touchscreen, say) is still exactly one kind, and the more
 * specific one wins.
 *
 * <p><b>The touch signal is the resource qualifier's.</b> {@code isTouch} means "Android picked
 * {@code values-finger} for this device", nothing more: it is
 * {@code Configuration.touchscreen == TOUCHSCREEN_FINGER}, the same field the resource system
 * matches on. That is why the wording in {@code res/values-finger/} and this classification can
 * never disagree. The {@code android.hardware.touchscreen} feature flag is deliberately
 * <em>not</em> used — it is true on devices where the resource system would not pick
 * {@code -finger} (and it is absent on this Fire TV stick, which is why it looks tempting).
 */
public enum DeviceKind {

    /** Amazon's Fire TV line: the stick and the boxes, not the Fire tablets. */
    FIRE_TV,

    /** An Android TV / Google TV device from anyone else. */
    ANDROID_TV,

    /** A phone or tablet: a touchscreen and no TV signal. */
    TOUCH,

    /** Nothing recognisable — a headless box, an emulator without a UI mode, … */
    OTHER;

    /** The raw signals, exactly as {@link Device} reads them from Android. */
    public static final class Signals {
        /** {@code Build.MANUFACTURER}, may be null. */
        public final String manufacturer;
        /** {@code Build.MODEL}, may be null. */
        public final String model;
        /** {@code amazon.hardware.fire_tv}. */
        public final boolean fireTvFeature;
        /** {@code android.software.leanback}. */
        public final boolean leanback;
        /** {@code android.hardware.type.television}. */
        public final boolean television;
        /** {@code UiModeManager.getCurrentModeType() == UI_MODE_TYPE_TELEVISION}. */
        public final boolean tvUiMode;
        /** {@code Configuration.touchscreen == TOUCHSCREEN_FINGER} (the {@code -finger} qualifier). */
        public final boolean fingerTouch;

        public Signals(String manufacturer, String model, boolean fireTvFeature, boolean leanback,
                       boolean television, boolean tvUiMode, boolean fingerTouch) {
            this.manufacturer = manufacturer;
            this.model = model;
            this.fireTvFeature = fireTvFeature;
            this.leanback = leanback;
            this.television = television;
            this.tvUiMode = tvUiMode;
            this.fingerTouch = fingerTouch;
        }
    }

    /** Decide the kind from the raw signals. Total: never throws, even on null input. */
    public static DeviceKind classify(Signals s) {
        if (isAmazonTvModel(s) || s.fireTvFeature) {
            return FIRE_TV;
        }
        if (s.leanback || s.television || s.tvUiMode) {
            return ANDROID_TV;
        }
        if (s.fingerTouch) {
            return TOUCH;
        }
        return OTHER;
    }

    /** True for an Android TV kind (never a Fire TV — that is {@link #FIRE_TV} instead). */
    public boolean isTv() {
        return this == FIRE_TV || this == ANDROID_TV;
    }

    /** The lower-case token that travels in the diagnostics report ({@code fire_tv}, …). */
    public String slug() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Fire TV boxes and sticks report {@code Amazon} and a model starting with {@code AFT}
     * ({@code AFTMM} here). A Fire <em>tablet</em> is also Amazon but a {@code KF…} model, so it
     * falls through to {@link #TOUCH}. The model check is the fallback for a box that does not
     * advertise {@code amazon.hardware.fire_tv}.
     */
    private static boolean isAmazonTvModel(Signals s) {
        return s.manufacturer != null && s.manufacturer.equalsIgnoreCase("Amazon")
                && s.model != null && s.model.startsWith("AFT");
    }
}
