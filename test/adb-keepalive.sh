#!/usr/bin/env bash
# Keep a wireless-adb device reachable for the duration of a session.
#
# Wireless debugging's adbd picks a **new port** every time it restarts (adb root / unroot, a
# reboot, toggling Wireless debugging), which is why a plain `adb connect <ip>:<port>` goes stale
# mid-session and `run.py --profile phone35` suddenly says "no device ready". This watches the
# device and, when its address stops answering, re-discovers the new one over mDNS and reconnects.
#
#   source local.env                     # exports PHONE_SERIAL
#   test/adb-keepalive.sh                # foreground: keep $PHONE_SERIAL reachable
#   test/adb-keepalive.sh --daemon       # same, detached (writes a pidfile)
#   test/adb-keepalive.sh --stop         # stop the detached one
#   test/adb-keepalive.sh --once         # reconnect once, print the address, exit
#   test/adb-keepalive.sh 192.168.1.51:5555
#   test/adb-keepalive.sh --env PHONE_SERIAL --interval 15
#
# `--once` prints **only** the working address on stdout, so `addr=$(... --once)` is usable; the
# daemon logs to stderr. The device address is never hard-coded here: it comes from the environment
# (the gitignored `local.env` exports it) or from the argument, so nothing private lands in the repo.
# The last working address is remembered in a small state file, so a port change is noticed even
# before mDNS catches up.
set -u

SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
PROG="$(basename "$0")"
ORIG=("$@")

ADB="${ADB:-}"
SERIAL=""
ENV_VAR="PHONE_SERIAL"
INTERVAL="${KEEPALIVE_INTERVAL:-30}"
STATE="${KEEPALIVE_STATE:-${TMPDIR:-/tmp}/plezy-adb-keepalive.$(id -u)}"
PIDFILE="${KEEPALIVE_PIDFILE:-${STATE}.pid}"
ONCE=0
DAEMON=0
STOP=0
QUIET=0

usage() {
    sed -n '2,20p' "$SELF" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --once)     ONCE=1; shift ;;
        --daemon)   DAEMON=1; shift ;;
        --stop)     STOP=1; shift ;;
        --quiet)    QUIET=1; shift ;;
        --env)      ENV_VAR="${2:?--env needs a variable name}"; shift 2 ;;
        --interval) INTERVAL="${2:?--interval needs seconds}"; shift 2 ;;
        --state)    STATE="${2:?--state needs a path}"; PIDFILE="${STATE}.pid"; shift 2 ;;
        --pidfile)  PIDFILE="${2:?--pidfile needs a path}"; shift 2 ;;
        -h|--help)  usage; exit 0 ;;
        -*)         echo "$PROG: unknown option '$1'" >&2; usage >&2; exit 2 ;;
        *)          SERIAL="$1"; shift ;;
    esac
done

log() { [ "$QUIET" = 1 ] && return 0; printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }

if [ "$STOP" = 1 ]; then
    if [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null; then
        echo "stopped keepalive (pid $(cat "$PIDFILE"))"
    else
        echo "$PROG: no running keepalive ($PIDFILE)" >&2
    fi
    rm -f "$PIDFILE"
    exit 0
fi

if [ -z "$ADB" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    elif [ -x "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" ]; then
        ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
    else
        echo "$PROG: no adb found (set \$ADB or \$ANDROID_HOME)" >&2
        exit 1
    fi
fi

[ -z "$SERIAL" ] && SERIAL="${!ENV_VAR:-}"
if [ -z "$SERIAL" ]; then
    echo "$PROG: no address — pass one, or set \$$ENV_VAR (source local.env)" >&2
    exit 2
fi

# Detach: re-exec ourselves without --daemon, so the child runs the watch loop.
if [ "$DAEMON" = 1 ]; then
    args=()
    for a in "${ORIG[@]}"; do [ "$a" = "--daemon" ] || args+=("$a"); done
    KEEPALIVE_STATE="$STATE" KEEPALIVE_PIDFILE="$PIDFILE" \
        nohup "$SELF" ${args[@]+"${args[@]}"} >>"$STATE.log" 2>&1 &
    echo $! > "$PIDFILE"
    echo "keepalive started (pid $!, watching $SERIAL, log: $STATE.log)"
    exit 0
fi

state_get() { [ -f "$STATE" ] && head -n1 "$STATE" 2>/dev/null; return 0; }
state_put() { printf '%s\n' "$1" > "$STATE" 2>/dev/null; return 0; }

is_device() {
    [ -n "$1" ] || return 1
    [ "$("$ADB" -s "$1" get-state 2>/dev/null)" = "device" ]
}

# Every address the phone currently advertises over mDNS (`_adb-tls-connect._tcp`).
# Extract the `ip:port` by *shape*, not by field position: the service name can contain a space
# (adb appends " (2)" when a device is registered twice), which shifts `awk`'s fields so `$3`
# would yield the service type instead of the address. `$NF`/a regex survive that.
mdns_candidates() {
    "$ADB" mdns services 2>/dev/null \
        | awk '/_adb-tls-connect\._tcp/ {print $NF}' \
        | grep -E '^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]+$'
}

# Connect to one candidate and wait (briefly) for it to report `device`.
try_connect() {
    c="$1"
    [ -n "$c" ] || return 1
    is_device "$c" && return 0
    "$ADB" connect "$c" >/dev/null 2>&1 || true
    for _ in 1 2 3 4 5; do
        is_device "$c" && return 0
        sleep 1
    done
    return 1
}

# Remembered address first (cheap), then the configured one, then everything mDNS offers.
reconnect() {
    for c in "$(state_get)" "$SERIAL" $(mdns_candidates); do
        [ -n "$c" ] || continue
        if try_connect "$c"; then
            state_put "$c"
            printf '%s' "$c"
            return 0
        fi
    done
    return 1
}

if [ "$ONCE" = 1 ]; then
    addr="$(reconnect)" || { log "could not reach $SERIAL"; exit 1; }
    printf '%s\n' "$addr"
    exit 0
fi

log "watching for $SERIAL (adb: $ADB, every ${INTERVAL}s, state: $STATE)"
while true; do
    cur="$(state_get)"
    if ! is_device "$cur"; then
        if addr="$(reconnect)"; then
            [ "$addr" = "$cur" ] || log "connected $addr"
        else
            log "device unreachable — retrying"
        fi
    fi
    sleep "$INTERVAL"
done
