#!/usr/bin/env python3
"""Run the plezy-firetv-updater flows against a connected Fire TV.

    python3 test/run.py install
    python3 test/run.py all --yes
    python3 test/run.py upgrade --yes                         # downgrade → Main View → upgrade
    python3 test/run.py install --app-lang de           # set the app to German, then run
    python3 test/run.py all --yes --build               # build + install the updater first
    python3 test/run.py all --yes --profile tv30        # against the API 30 Android TV emulator

The updater is runtime-switchable, so --app-lang is the language to *set* before the
flows run (via Settings → Language), not a build variant. Labels come from strings/:
--app-lang picks the app's language, --system-lang picks the stock installer's language
(auto-detected from the device by default).

--profile picks which device the run expects (config.json → "profiles"): the expected
device classification, the stock installer package, the device's locale, and how the app's
screens are driven. It defaults to the config's default_profile, the Fire TV stick.
"""

import argparse
import os
import subprocess
import sys
import time

import harness
import check_strings
from harness import Device, HarnessError, load_config, load_strings
from flows import Flows

FLOW_ORDER = ["install", "downgrade", "upgrade", "uninstall", "clean-install"]
DESTRUCTIVE = {"downgrade", "upgrade", "uninstall", "clean-install", "all"}


def build_and_install(device, args, package):
    app_dir = os.path.join(harness.REPO, "app")
    env = dict(os.environ)
    env.setdefault("BT", "/tmp/opencode/sdk/android-15")
    env.setdefault("AJ", "/tmp/opencode/sdk/android-35/android.jar")
    print(f"  building the updater ({app_dir}) …")
    subprocess.run(["./build.sh"], cwd=app_dir, env=env, check=False)
    apk = os.path.join(app_dir, "build", "plezy-updater.apk")
    if not os.path.exists(apk):
        raise HarnessError(f"build produced no APK at {apk}")
    print(f"  installing {apk} …")
    print("    " + device.sh("install", "-r", apk, timeout=180).strip())
    # From API 26 the install itself needs the "install unknown apps" appop, which defaults to
    # deny; grant it here so `--build` leaves the device able to install (see Device method).
    granted = device.grant_install_permission(package)
    if granted:
        print(f"  install permission: {granted}")


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Drive the plezy-firetv-updater flows on the Fire TV.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("flows", nargs="+", choices=FLOW_ORDER + ["all"])
    ap.add_argument("--app-lang", default="en", choices=list(harness.APP_LANGS),
                    help="language to set the updater to before the flows (default: en)")
    ap.add_argument("--from-lang", default=None, choices=list(harness.APP_LANGS) + ["system"],
                    help="the app's language now, if it should not be auto-detected")
    ap.add_argument("--no-set-language", action="store_true",
                    help="skip the Settings → Language step (assume --app-lang is already set)")
    ap.add_argument("--system-lang", default="auto",
                    help="language of the stock installer's dialogs (default: auto-detect)")
    ap.add_argument("--profile", default=None,
                    help="device profile from config.json (default: its default_profile)")
    ap.add_argument("--serial", default=None,
                    help="adb serial, e.g. 192.168.1.50:5555 (default: the profile's "
                         "serial_env, then $FIRE_TV_SERIAL — see local.env.example)")
    ap.add_argument("--adb", default="adb", help="adb binary (default: adb)")
    ap.add_argument("--latest", default=None, help="override the 'latest' version")
    ap.add_argument("--older", default=None, help="override the 'older' version")
    ap.add_argument("--build", action="store_true", help="build and install the updater first")
    ap.add_argument("--yes", action="store_true", help="confirm the destructive flows up front")
    ap.add_argument("-v", "--verbose", action="store_true", help="echo every adb command")
    args = ap.parse_args(argv)

    # Static parity first: it needs no device, and a drifted locale would make the flows look
    # for a label that is no longer there. Same check as `python3 test/check_strings.py`.
    print("=== strings ===")
    if check_strings.main() != 0:
        print("\nerror: fix the string parity problems above before running the flows.",
              file=sys.stderr)
        return 1

    config = load_config()
    try:
        profile = harness.apply_profile(config, args.profile)
    except HarnessError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if args.latest:
        config["versions"]["latest"] = args.latest
    if args.older:
        config["versions"]["older"] = args.older

    # Which device to talk to: an explicit --serial wins, then the profile's environment
    # variable (so `source local.env; run.py --profile phone35` finds the phone without a flag),
    # then the stick's historical default $FIRE_TV_SERIAL.
    serial = (args.serial
              or os.environ.get(profile.get("serial_env") or "", "")
              or os.environ.get("FIRE_TV_SERIAL"))

    device = Device(serial=serial, adb=args.adb, verbose=args.verbose)
    try:
        device.require_connected()
    except HarnessError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    system_lang = args.system_lang
    # A profile may name the stock installer's string file (e.g. the Material installer's
    # sentence-case buttons on API 31+). An explicit --system-lang overrides that choice.
    system_strings = None
    if system_lang == "auto":
        system_lang = device.detect_system_lang() or profile.get("system_lang", "en")
        system_strings = profile.get("system_strings")
    try:
        strings = load_strings(args.app_lang, system_lang, system_strings)
    except FileNotFoundError as exc:
        print(f"error: no such language file: {exc.filename}", file=sys.stderr)
        return 2

    runs = FLOW_ORDER if "all" in args.flows else [f for f in FLOW_ORDER if f in args.flows]

    print("plezy-firetv-updater — on-device flow tests")
    print(f"  device      : {serial or '(default)'}")
    print(f"  profile     : {config['profile']} "
          f"(expects {profile['expected_device']['kind']}, "
          f"navigation {profile.get('navigation', 'dpad')})")
    print(f"  app language: {strings.app_language} ({args.app_lang})"
          + ("  [not set]" if args.no_set_language else ""))
    print(f"  sys language: {strings.system_language} ({system_lang})"
          + (f"  [{system_strings}]" if system_strings else ""))
    print(f"  latest/older: {config['versions']['latest']} / {config['versions']['older']}")
    print(f"  flows       : {', '.join(runs)}")
    print(f"  Plezy now   : {device.installed_version(config['plezy_package']) or 'not installed'}")
    if not args.verbose:
        print()

    if DESTRUCTIVE & set(runs) and not args.yes:
        print("\nThis runs destructive flows: an uninstall / downgrade / clean install ERASES "
              "Plezy's data (servers, settings, downloads).\nRe-run with --yes to proceed.")
        return 3

    if args.build:
        build_and_install(device, args, config["updater_package"])

    # API 33+ only: grant POST_NOTIFICATIONS so the one-time dialog does not land in the middle
    # of a flow. Below 33 the permission does not exist and this is a no-op.
    notif = device.grant_notification_permission(config["updater_package"])
    if notif:
        print(f"  notification permission: {notif.splitlines()[0] if notif else notif}")

    flows = Flows(device, strings, config)

    # Exercise A3 the way it is meant to be used: a script triggers the check and reads the
    # report back over the broadcast, without a screen. The same report now carries the device
    # classification (item 2) and the notification state (item 5, step 33), so both are asserted
    # here rather than on a screen.
    print("\n=== diagnostics: GitHub quota + device + notifications ===")
    quota = flows.check_quota()
    if quota and quota.get("status") == "ok":
        print(f"  GitHub API quota: {quota.get('remaining')}/{quota.get('limit')} requests left")
    else:
        print(f"  (no quota report — {quota})")
    if quota:
        try:
            flows.assert_device(quota)
            flows.assert_notifications(quota)
        except HarnessError as exc:
            print(f"  ✗ {exc}")
            return 1
    else:
        print("  ✗ the diagnostics receiver did not answer — device detection not verified")
        return 1

    if not args.no_set_language:
        print("\n=== set the app language ===")
        try:
            flows.set_app_language(args.app_lang, from_lang=args.from_lang)
        except HarnessError as exc:
            print(f"  ✗ could not set the language: {exc}")
            return 1

    results = []
    for name in runs:
        print(f"\n=== {name} ===")
        started = time.time()
        try:
            getattr(flows, name.replace("-", "_"))()
        except HarnessError as exc:
            results.append((name, False, str(exc)))
            print(f"  ✗ {name} FAILED: {exc}")
        else:
            results.append((name, True, ""))
            print(f"  ✓ {name} passed ({time.time() - started:.0f}s)")

    print("\n=== summary ===")
    for name, ok, note in results:
        print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  — {note}" if note else ""))
    failed = [n for n, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} flows passed.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
