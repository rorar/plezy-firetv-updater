#!/usr/bin/env python3
"""Capture the README screenshots from a connected device.

    source local.env
    python3 test/screenshots.py                          # the stick → docs/screenshots/tv-*.png
    python3 test/screenshots.py --profile phone34 --serial emulator-5554
                                                         # the phone lane → docs/screenshots/phone-*.png

The images are **generated**, not hand-taken: the script drives the app to each screen the README
shows, `screencap`s it, and checks the capture *structurally* with a `uiautomator dump` — an
expected label must be on the same screen — the same "the harness cannot see images, so assert on
the tree" rule the flow tests use. Re-run it after a UI change to refresh the images.

It is **non-destructive**: nothing is installed. It sets the app language (default English, the
README's language) and enables the developer *Allow older versions* switch only while it needs the
Choose-Version picker, then puts both back. A touch device also gets a release-notes overlay shot;
a TV fills the notes pane by highlighting a row first, so the two-pane layout shows real notes.
"""

import argparse
import os
import sys
import time

import harness
from harness import Device, HarnessError, load_config, load_strings
from flows import Flows

#: The screens to capture, in order (the file is ``<prefix>-<name>.png``). "notes" only exists on
#: a touch device; a TV's notes live in the picker's right-hand pane and are part of that shot.
SHOTS = ("main", "settings", "picker", "notes", "interval")


def _png_size(path):
    """``(width, height)`` from a PNG's IHDR, or None when the file is not a PNG.

    Read straight off the header so this script stays stdlib-only (no PIL) like the rest of
    ``test/``.
    """
    with open(path, "rb") as fh:
        head = fh.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


class Shooter:
    """Drive the app to each screen, capture it, and prove the capture shows the right screen."""

    def __init__(self, device, strings, config, out_dir, prefix, log=print):
        self.d = device
        self.s = strings
        self.c = config
        self.out_dir = out_dir
        self.prefix = prefix
        self.log = log
        self.flows = Flows(device, strings, config, log=log)
        self.shots = []
        self.allow_older_was = False  # the developer switch's state before the picker shots

    # -- capture ----------------------------------------------------------

    def shot(self, name, expect=None, expect_desc=None):
        """Screencap the current screen to ``<prefix>-<name>.png`` and verify it structurally.

        ``expect`` (a text label) and ``expect_desc`` (a content description) must each be present
        in the *same* screen's dump — if one is missing we captured the wrong screen, so this fails
        loudly rather than committing a bad image. The byte size is printed as a sanity check: an
        all-black frame compresses to a few KB, a real screen does not.
        """
        remote = "/sdcard/plezy_shot.png"
        self.d.shell(f"rm -f {remote}", check=False)
        self.d.shell(f"screencap -p {remote}")
        local = os.path.join(self.out_dir, f"{self.prefix}-{name}.png")
        self.d.sh("pull", remote, local)

        screen = self.d.dump()
        if expect is not None and screen.find(exact=expect) is None:
            raise HarnessError(
                f"{self.prefix}-{name}.png: label {expect!r} is not on screen:\n{screen.pretty()}")
        if expect_desc is not None and screen.find(desc=expect_desc) is None:
            raise HarnessError(
                f"{self.prefix}-{name}.png: {expect_desc!r} content-desc is not on screen:\n"
                f"{screen.pretty()}")

        size = os.path.getsize(local)
        dims = _png_size(local)
        dim = f"{dims[0]}x{dims[1]}" if dims else "not a PNG"
        note = "  (warning: very small — a black frame?)" if size < 8000 else ""
        self.shots.append((name, dim, size))
        self.log(f"  ✓ {self.prefix}-{name}.png  {dim}  {size} B{note}")
        return screen

    # -- the screens ------------------------------------------------------

    def capture_all(self):
        touch = self.c.get("expected_device", {}).get("touch") == "1"
        self._main()
        self._settings()
        self._picker(touch)
        self._interval()
        return self.shots

    def _main(self):
        self.flows.open_updater()
        self.shot("main", expect=self.s.app("main.title"))

    def _settings(self):
        self.flows.open_settings()
        self.shot("settings", expect=self.s.app("settings.title"))

    def _picker(self, touch):
        # "Choose version…" only exists while the developer switch is on. Remember its state so
        # main() can put it back the way it found it.
        self.allow_older_was = self.flows.set_allow_older(True)
        self.flows.open_picker()
        if touch:
            self.shot("picker", expect=self.s.app("picker.title"))
            self._notes_overlay()
        else:
            # Fill the notes pane so the two-pane layout shows real notes, not the placeholder.
            for _ in range(6):
                if not self.d.dump().has(self.s.app("picker.select")):
                    break
                self.d.key("KEYCODE_DPAD_DOWN")  # the first event after the window opens can drop
                time.sleep(1)
            self.shot("picker", expect=self.s.app("picker.title"))
        self.d.key("KEYCODE_BACK")
        self.d.wait_focus("MainActivity", timeout=20)
        time.sleep(1)

    def _notes_overlay(self):
        """Touch picker: open the release-notes fly-in and capture it (then close it)."""
        notes = self.d.dump().find(desc=self.s.app("picker.notes_button"))
        if notes is None:
            raise HarnessError("the touch picker's notes icon is not on screen")
        self.d.tap(notes.cx, notes.cy)
        deadline = time.time() + 20
        while time.time() < deadline:
            if self.d.dump().has(self.s.app("picker.install_button")):
                break
            time.sleep(1)
        self.shot("notes", expect=self.s.app("picker.notes_heading"))
        close = self.d.dump().find(exact=self.s.app("picker.close"))
        if close is not None:
            self.d.tap(close.cx, close.cy)
            time.sleep(1.5)

    def _interval(self):
        self.flows.open_settings()
        self.flows.open_interval()
        self.shot("interval", expect=self.s.app("interval.title"))
        self.d.key("KEYCODE_BACK")
        self.d.wait_focus("SettingsActivity", timeout=20)
        time.sleep(1)


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Capture the README screenshots from a connected device.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("--profile", default=None,
                    help="device profile from config.json (default: its default_profile)")
    ap.add_argument("--serial", default=None,
                    help="adb serial, e.g. 192.168.1.50:5555 (default: the profile's serial_env, "
                         "then $FIRE_TV_SERIAL — see local.env.example)")
    ap.add_argument("--app-lang", default="en", choices=list(harness.APP_LANGS),
                    help="language to set the updater to for the shots (default: en)")
    ap.add_argument("--prefix", default=None,
                    help="file prefix (default: 'phone' on a touch device, else 'tv')")
    ap.add_argument("--out", default=os.path.join(harness.REPO, "docs", "screenshots"),
                    help="output directory (default: docs/screenshots)")
    ap.add_argument("--adb", default="adb", help="adb binary (default: adb)")
    ap.add_argument("--keep-allow-older", action="store_true",
                    help="leave 'Allow older versions' on instead of restoring its original state")
    ap.add_argument("--keep-language", action="store_true",
                    help="leave the app in --app-lang instead of restoring the original language")
    ap.add_argument("-v", "--verbose", action="store_true", help="echo every adb command")
    args = ap.parse_args(argv)

    config = load_config()
    try:
        profile = harness.apply_profile(config, args.profile)
    except HarnessError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    # Which device to talk to: an explicit --serial wins, then the profile's environment variable
    # (so `source local.env; screenshots.py --profile phone35` needs no flag), then $FIRE_TV_SERIAL.
    serial = (args.serial
              or os.environ.get(profile.get("serial_env") or "", "")
              or os.environ.get("FIRE_TV_SERIAL"))
    device = Device(serial=serial, adb=args.adb, verbose=args.verbose)
    try:
        device.require_connected()
    except HarnessError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    system_lang = device.detect_system_lang() or profile.get("system_lang", "en")
    strings = load_strings(args.app_lang, system_lang, profile.get("system_strings"))

    touch = config.get("expected_device", {}).get("touch") == "1"
    prefix = args.prefix or ("phone" if touch else "tv")
    os.makedirs(args.out, exist_ok=True)

    print("plezy-firetv-updater — README screenshots")
    print(f"  device  : {serial or '(default)'}")
    print(f"  profile : {config['profile']} ({'touch' if touch else 'TV'} layout)")
    print(f"  language: {strings.app_language} ({args.app_lang})")
    print(f"  output  : {args.out}/{prefix}-*.png")
    print()

    device.wake()
    # API 33+ only: grant POST_NOTIFICATIONS so a one-time dialog does not land on the Main View.
    device.grant_notification_permission(config["updater_package"])

    shooter = Shooter(device, strings, config, args.out, prefix)
    flows = shooter.flows

    # Remember what to restore, then force the README's language.
    flows.open_updater()
    original_lang = flows.read_app_language()
    flows.set_app_language(args.app_lang)

    try:
        shots = shooter.capture_all()
    except HarnessError as exc:
        print(f"\n✗ {exc}", file=sys.stderr)
        return 1

    # Put the device back the way it was found (the picker shots need the developer switch on).
    flows.open_updater()
    if not args.keep_allow_older:
        flows.set_allow_older(shooter.allow_older_was)
    if not args.keep_language and original_lang != args.app_lang:
        flows.set_app_language(original_lang)

    print("\n=== summary ===")
    for name, dim, size in shots:
        print(f"  {prefix}-{name}.png  {dim}  {size} B")
    print(f"\n{len(shots)} screenshots written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
