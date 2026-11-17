package dev.rorar.plezyupdater;

import static dev.rorar.plezyupdater.DeviceKind.ANDROID_TV;
import static dev.rorar.plezyupdater.DeviceKind.FIRE_TV;
import static dev.rorar.plezyupdater.DeviceKind.OTHER;
import static dev.rorar.plezyupdater.DeviceKind.TOUCH;

import dev.rorar.plezyupdater.DeviceKind.Signals;

/**
 * Plain-Java tests for {@link DeviceKind}. No JUnit, no Android, no device — run them with
 *
 * <pre>test/jvm/run.sh</pre>
 *
 * They exist because this is the one piece of device logic that <em>can</em> be checked without
 * hardware: {@link DeviceKind} takes a {@link Signals} bundle instead of reaching into the
 * platform, so every branch — including the Fire TV / Android TV / touch cases this stick cannot
 * produce — is exercised here. {@link Device} only reads the platform; the decision is tested.
 *
 * <p>Exit code is 0 when everything passes and 1 otherwise, so it can be a CI step.
 */
public final class DeviceKindTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        // ---- the device this was written for ------------------------------------------- //
        expect(FIRE_TV, new Sig().amazon("AFTMM").fireTv().build(),
                "Fire TV Stick 4K: Amazon + AFT model + amazon.hardware.fire_tv");
        expect(FIRE_TV, new Sig().amazon("AFTMM").fireTv().leanback().television().build(),
                "Fire TV Stick 4K as measured (also carries leanback + television)");

        // ---- Fire TV detected from the model alone ------------------------------------- //
        expect(FIRE_TV, new Sig().amazon("AFTKA").leanback().television().build(),
                "a Fire TV that does not advertise amazon.hardware.fire_tv");
        expect(FIRE_TV, new Sig().mfr("amazon").model("AFTMM").fireTv().build(),
                "manufacturer case does not matter");

        // ---- other Android TVs --------------------------------------------------------- //
        expect(ANDROID_TV, new Sig().mfr("Google").model("sabrina").leanback().build(),
                "leanback alone (Chromecast with Google TV)");
        expect(ANDROID_TV, new Sig().mfr("NVIDIA").model("SHIELD").television().build(),
                "android.hardware.type.television alone");
        expect(ANDROID_TV, new Sig().mfr("SomeBox").model("x").tvUiMode().build(),
                "UI_MODE_TYPE_TELEVISION alone (AOSP box without leanback)");

        // ---- touch devices ------------------------------------------------------------- //
        expect(TOUCH, new Sig().amazon("KFTRWI").touch().build(),
                "Fire tablet: Amazon, but a KF model and a touchscreen -> not a Fire TV");
        expect(TOUCH, new Sig().mfr("samsung").model("SM-G991B").touch().build(),
                "ordinary phone");
        expect(TOUCH, new Sig().touch().build(), "a generic touch device");

        // ---- nothing recognisable ------------------------------------------------------ //
        expect(OTHER, new Sig().build(), "no TV signal, no touchscreen");
        expect(OTHER, new Sig().mfr("").model("").build(), "empty strings are not a TV");

        // ---- precedence ---------------------------------------------------------------- //
        expect(FIRE_TV, new Sig().amazon("AFTMM").fireTv().leanback().television().tvUiMode().touch().build(),
                "Fire TV wins over Android TV and over touch");
        expect(ANDROID_TV, new Sig().mfr("Google").model("sabrina").leanback().touch().build(),
                "an Android TV with a touchscreen is still ANDROID_TV");
        expect(ANDROID_TV, new Sig().mfr("SomeBox").model("x").tvUiMode().touch().build(),
                "the TV UI mode outranks touch in the ladder");

        // ---- null safety --------------------------------------------------------------- //
        expect(TOUCH, new Sig().nullDevice().touch().build(), "null manufacturer/model must not throw");
        expect(OTHER, new Sig().nullDevice().build(), "null manufacturer/model, nothing else");

        // ---- the slug that travels in the diagnostics report --------------------------- //
        expect("fire_tv", FIRE_TV.slug(), "FIRE_TV slug");
        expect("android_tv", ANDROID_TV.slug(), "ANDROID_TV slug");
        expect("touch", TOUCH.slug(), "TOUCH slug");
        expect("other", OTHER.slug(), "OTHER slug");

        // ---- the isTv() convenience ---------------------------------------------------- //
        expect(true, FIRE_TV.isTv(), "FIRE_TV is a TV");
        expect(true, ANDROID_TV.isTv(), "ANDROID_TV is a TV");
        expect(false, TOUCH.isTv(), "TOUCH is not a TV");
        expect(false, OTHER.isTv(), "OTHER is not a TV");

        System.out.println();
        if (failures == 0) {
            System.out.println("all " + checks + " DeviceKind checks passed");
            return;
        }
        System.out.println(failures + " of " + checks + " DeviceKind checks FAILED");
        System.exit(1);
    }

    // ----------------------------------------------------------------------------------- //

    private static void expect(DeviceKind want, Signals signals, String what) {
        checks++;
        DeviceKind got = DeviceKind.classify(signals);
        if (got == want) {
            System.out.println("ok   " + what + "  ->  " + got);
        } else {
            failures++;
            System.out.println("FAIL " + what + "  ->  expected " + want + ", got " + got);
        }
    }

    private static void expect(String want, String got, String what) {
        checks++;
        if (want.equals(got)) {
            System.out.println("ok   " + what + "  ->  " + got);
        } else {
            failures++;
            System.out.println("FAIL " + what + "  ->  expected " + want + ", got " + got);
        }
    }

    private static void expect(boolean want, boolean got, String what) {
        checks++;
        if (want == got) {
            System.out.println("ok   " + what + "  ->  " + got);
        } else {
            failures++;
            System.out.println("FAIL " + what + "  ->  expected " + want + ", got " + got);
        }
    }

    /** Test-only builder, so a case reads as prose instead of seven positional booleans. */
    private static final class Sig {
        private String manufacturer = "acme";
        private String model = "widget";
        private boolean fireTv;
        private boolean leanback;
        private boolean television;
        private boolean tvUiMode;
        private boolean touch;

        Sig mfr(String value) {
            manufacturer = value;
            return this;
        }

        Sig model(String value) {
            model = value;
            return this;
        }

        Sig amazon(String value) {
            manufacturer = "Amazon";
            model = value;
            return this;
        }

        Sig nullDevice() {
            manufacturer = null;
            model = null;
            return this;
        }

        Sig fireTv() {
            fireTv = true;
            return this;
        }

        Sig leanback() {
            leanback = true;
            return this;
        }

        Sig television() {
            television = true;
            return this;
        }

        Sig tvUiMode() {
            tvUiMode = true;
            return this;
        }

        Sig touch() {
            touch = true;
            return this;
        }

        Signals build() {
            return new Signals(manufacturer, model, fireTv, leanback, television, tvUiMode, touch);
        }
    }
}
