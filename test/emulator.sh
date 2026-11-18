#!/usr/bin/env bash
# Boot and manage the emulator AVDs the flow tests run against.
#
#   test/emulator.sh create    tv30   # create the AVD (needs its system image)
#   test/emulator.sh start     tv30   # boot it headless, wait for `adb`
#   test/emulator.sh provision tv30   # install the updater + grant "install unknown apps"
#   test/emulator.sh stop      tv30   # kill it
#   test/emulator.sh list             # the AVDs that exist
#
# The AVD name and system image for a profile come from test/config.json's "profiles", so
# this and `run.py --profile <name>` agree on what a profile means. Needs the SDK at
# $ANDROID_HOME (default ~/Android/Sdk) with emulator + platform-tools + the system image
# installed. The device-free `python3 test/run.py` still runs against the stick; this script
# is only for the emulator lane.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME
# sdkmanager/avdmanager need a JDK; the emulator itself does not.
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
[ -x "$JAVA_HOME/bin/java" ] || JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
export JAVA_HOME
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# emulator/ is not on PATH by default, and `adb` must be the SDK's, not a stale one.
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

# The stick may be connected over TCP at the same time, so never let adb guess which device:
# every call below goes through this. Empty until `start` finds the running emulator.
SERIAL=""
adb_() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

emulator_serial() {
  "$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device/{print $1; exit}'
}

profile_json() {
  python3 - "$HERE/config.json" "$1" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
name = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else cfg["default_profile"]
p = cfg["profiles"].get(name)
if p is None:
    raise SystemExit(f"unknown profile {name!r}")
for key in ("avd", "image", "description"):
    print(p.get(key, ""))
PY
}

usage() {
  echo "usage: $0 {create|start|provision|stop|list} [profile]" >&2
  echo "  profiles: $(python3 -c "import json;print(', '.join(k for k in json.load(open('$HERE/config.json'))['profiles'] if not k.startswith('_')))")" >&2
  exit 2
}

cmd="${1:-}"; profile="${2:-}"
[ -n "$cmd" ] || usage

mapfile -t FIELDS < <(profile_json "$profile")
AVD="${FIELDS[0]:-}"; IMAGE="${FIELDS[1]:-}"; DESC="${FIELDS[2]:-}"

case "$cmd" in
  list)
    "$AVDMANAGER" list avd
    ;;

  create)
    [ -n "$AVD" ] || { echo "profile ${profile:-<default>} has no 'avd' — it is not an emulator profile" >&2; exit 2; }
    if "$AVDMANAGER" list avd | grep -q "Name: $AVD$"; then
      echo "AVD $AVD already exists"
      exit 0
    fi
    echo "== ensuring $IMAGE is installed"
    # `yes` closes the licence prompt; it gets SIGPIPE once sdkmanager is done, so swallow that
    # (and any "already installed") instead of letting `set -o pipefail` abort the script.
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "$IMAGE" >/dev/null || true
    echo "== creating AVD $AVD"
    # 'no' declines the custom-hardware-profile prompt; the default (a TV or phone per the
    # image) is what the flows expect.
    echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" --force
    ;;

  start)
    [ -n "$AVD" ] || { echo "profile ${profile:-<default>} has no 'avd'" >&2; exit 2; }
    SERIAL="$(emulator_serial)"
    if [ -n "$SERIAL" ]; then
      echo "an emulator is already running: $SERIAL"
      exit 0
    fi
    echo "== booting $AVD headless ($DESC)"
    # -no-window/-no-audio: this is a headless CI-style lane. swiftshader_indirect is the
    # software GPU that works without a real display; -no-snapshot keeps each boot clean so a
    # stale snapshot cannot hide a first-boot behaviour. /dev/kvm must be usable.
    "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot -netdelay none -netspeed full \
      >/tmp/opencode/emulator-"$AVD".log 2>&1 &
    echo "  waiting for the device to come up …"
    # `adb wait-for-device` would return at once because the stick is also attached, so poll for
    # an *emulator* serial to reach the "device" state instead.
    for _ in $(seq 1 150); do
      SERIAL="$(emulator_serial)"
      [ -n "$SERIAL" ] && break
      sleep 2
    done
    [ -n "$SERIAL" ] || { echo "no emulator appeared in adb (see /tmp/opencode/emulator-$AVD.log)" >&2; exit 1; }
    adb_ wait-for-device
    until [ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
      sleep 3
    done
    # Give the launcher a moment, then stop the screen from sleeping under the tests.
    sleep 3
    adb_ shell input keyevent KEYCODE_WAKEUP || true
    adb_ shell svc power stayon true || true
    adb_ shell settings put global window_animation_scale 0 || true
    adb_ shell settings put global transition_animation_scale 0 || true
    adb_ shell settings put global animator_duration_scale 0 || true
    echo "  ✓ $AVD is up on $SERIAL: API $(adb_ shell getprop ro.build.version.sdk | tr -d '\r'),"
    echo "    $SERIAL $(adb_ shell getprop ro.product.model | tr -d '\r') ($(adb_ shell getprop ro.product.cpu.abilist | tr -d '\r'))"
    echo "    run the flows with:  python3 test/run.py all --yes --profile <profile> --serial $SERIAL"
    ;;

  provision)
    # Install the updater and give it the two things a *test* device needs that a real user
    # would grant by hand. The flows then need no manual setup step.
    SERIAL="${PLEZY_SERIAL:-$(emulator_serial)}"
    [ -n "$SERIAL" ] || { echo "no emulator running — start one first (or set PLEZY_SERIAL)" >&2; exit 1; }
    APK="$HERE/../app/build/plezy-updater.apk"
    PKG="$(python3 -c "import json;print(json.load(open('$HERE/config.json'))['updater_package'])")"
    API="$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "== provisioning $SERIAL (API $API) for $PKG"
    if [ -f "$APK" ]; then
      echo "   installing $APK"
      adb_ install -r "$APK" 2>&1 | tail -1
    else
      echo "   (no APK at $APK — build it first: cd app && ./build.sh)"
    fi
    if [ "${API:-0}" -ge 26 ]; then
      # From API 26 "install unknown apps" is a per-app appop, denied by default; without it the
      # stock installer aborts with INSTALL_FAILED_ABORTED: User rejected permissions.
      adb_ shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow || true
      echo "   appops: $(adb_ shell appops get "$PKG" REQUEST_INSTALL_PACKAGES | tr -d '\r')"
    fi
    if [ "${API:-0}" -ge 33 ]; then
      # From API 33 POST_NOTIFICATIONS is a runtime permission the app asks for on first launch;
      # grant it here so the one-time dialog does not interrupt a flow (run.py grants it too).
      adb_ shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
      echo "   notif: $(adb_ shell appops get "$PKG" POST_NOTIFICATION | tr -d '\r')"
    fi
    adb_ shell input keyevent KEYCODE_WAKEUP || true
    adb_ shell svc power stayon true || true
    adb_ shell settings put global window_animation_scale 0 || true
    adb_ shell settings put global transition_animation_scale 0 || true
    adb_ shell settings put global animator_duration_scale 0 || true
    echo "   ✓ ready — python3 test/run.py all --yes --profile ${profile:-<default>} --serial $SERIAL"
    ;;

  stop)
    [ -n "$AVD" ] || { echo "profile ${profile:-<default>} has no 'avd'" >&2; exit 2; }
    SERIAL="$(emulator_serial)"
    adb_ emu kill 2>/dev/null || pkill -f "qemu-system.*$AVD" || true
    echo "  sent kill to $AVD${SERIAL:+ ($SERIAL)}"
    ;;

  *)
    usage
    ;;
esac
