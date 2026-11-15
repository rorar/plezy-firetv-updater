#!/usr/bin/env python3
"""Run the plezy-firetv-updater flows against a connected Fire TV.

    python3 test/run.py install
    python3 test/run.py all --yes
    python3 test/run.py install --app-lang de           # German build
    python3 test/run.py all --yes --build               # build + install the updater first

Labels come from strings/: --app-lang picks the app's language, --system-lang
picks the Fire OS dialogs' language (auto-detected from the device by default).
"""

import argparse
import os
import subprocess
import sys
import time

import harness
from harness import Device, HarnessError, load_config, load_strings
from flows import Flows

FLOW_ORDER = ["install", "downgrade", "uninstall", "clean-install"]
DESTRUCTIVE = {"downgrade", "uninstall", "clean-install", "all"}


def build_and_install(device, args):
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


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Drive the plezy-firetv-updater flows on the Fire TV.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("flows", nargs="+", choices=FLOW_ORDER + ["all"])
    ap.add_argument("--app-lang", default="en", help="language of the updater build (default: en)")
    ap.add_argument("--system-lang", default="auto",
                    help="language of the Fire OS dialogs (default: auto-detect)")
    ap.add_argument("--serial", default=None, help="adb serial, e.g. 192.168.1.50:5555")
    ap.add_argument("--adb", default="adb", help="adb binary (default: adb)")
    ap.add_argument("--latest", default=None, help="override the 'latest' version")
    ap.add_argument("--older", default=None, help="override the 'older' version")
    ap.add_argument("--build", action="store_true", help="build and install the updater first")
    ap.add_argument("--yes", action="store_true", help="confirm the destructive flows up front")
    ap.add_argument("-v", "--verbose", action="store_true", help="echo every adb command")
    args = ap.parse_args(argv)

    device = Device(serial=args.serial, adb=args.adb, verbose=args.verbose)
    try:
        device.require_connected()
    except HarnessError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    config = load_config()
    if args.latest:
        config["versions"]["latest"] = args.latest
    if args.older:
        config["versions"]["older"] = args.older

    system_lang = args.system_lang
    if system_lang == "auto":
        system_lang = device.detect_system_lang()
    try:
        strings = load_strings(args.app_lang, system_lang)
    except FileNotFoundError as exc:
        print(f"error: no such language file: {exc.filename}", file=sys.stderr)
        return 2

    runs = FLOW_ORDER if "all" in args.flows else [f for f in FLOW_ORDER if f in args.flows]

    print("plezy-firetv-updater — on-device flow tests")
    print(f"  device      : {args.serial or '(default)'}")
    print(f"  app language: {strings.app_language} ({args.app_lang})")
    print(f"  sys language: {strings.system_language} ({system_lang})")
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
        build_and_install(device, args)

    flows = Flows(device, strings, config)
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
