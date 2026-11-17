# 01 — ADB access

## Host

| Component | Value |
|---|---|
| `adb` | 1.0.41, platform-tools `37.0.0p1-android-tools` (`/usr/bin/adb`) |
| Host OS | Linux (any recent distro; only `adb` and `curl` matter) |
| Host LAN address | `192.168.1.10/24`, gateway `192.168.1.1` (example values) |
| Device key | `~/.android/adbkey` (persisted, so authorisation survives reboots) |

> **Addresses in this repo are examples.** The stick's real LAN address is deliberately not
> published. Substitute your own wherever you see `192.168.1.50`. The test harness reads it from
> the `FIRE_TV_SERIAL` environment variable instead (copy `local.env.example` to `local.env`).

## Connecting

```bash
adb connect 192.168.1.50:5555
adb devices -l
```

Expected when healthy:

```
List of devices attached
192.168.1.50:5555    device product:mantis model:AFTMM device:mantis transport_id:1
```

### First-time authorisation

ADB debugging is enabled on the stick, so port 5555 is open, but the host key must be
accepted **on the TV**:

```
Allow USB debugging?
The computer's RSA key fingerprint is ...
[ ] Always allow from this computer     [Cancel] [OK]
```

Tick *Always allow* and accept. Until then `adb devices` reports `unauthorized` and every
command fails with:

```
adb: device unauthorized.
This adb server's $ADB_VENDOR_KEYS is not set
Try 'adb kill-server' if that seems wrong.
```

Version note: `failed to authenticate to 192.168.1.50:5555` is printed on connect while the
device is still `unauthorized` — that is the normal pre-acceptance message, not a failure.

If no dialog appears, the TV screen must be awake; re-run `adb kill-server && adb connect …`
with the device on and in the foreground.

## Finding the device on the LAN

```bash
for ip in $(seq 1 254); do
  (timeout 1 bash -c "echo > /dev/tcp/192.168.1.$ip/5555" 2>/dev/null && echo "OPEN $ip:5555") &
done; wait
```

The device also answers mDNS as `Android.local`.

## State model

| State | Meaning | Fix |
|---|---|---|
| `device` | Ready | — |
| `unauthorized` | RSA dialog not accepted | Accept on TV |
| `offline` | Stale transport / device asleep | `adb disconnect && adb connect` |
| (absent) | ADB debugging off, wrong IP, or Wi-Fi off | Check *Settings → My Fire TV → Developer Options → ADB debugging* |

## The ADB daemon stops on its own — toggle it off and on

Fire OS 6 on the stick periodically stops answering ADB even though the device is fine: it still
pings, and port 5555 may still look open, but `adb devices` shows `offline` and reconnecting does
not help. This happened repeatedly during development (the summary note "adb link is flaky" is
this bug). `adb disconnect` → `adb kill-server` → `adb start-server` → `adb connect` sometimes
recovers it; when it does not, the reliable fix is on the TV:

> **Settings → My Fire TV → Developer Options → ADB debugging: switch it Off, then On again.**

The port reopens a few seconds later and `adb connect 192.168.1.50:5555` succeeds. Treat "toggle
ADB debugging" as the standard first step whenever the connection goes stale for more than a
minute — it is faster than fighting the server-side reconnect loop. The persisted host key
(`~/.android/adbkey`) means no new authorisation prompt appears after the toggle.

## Cheat-sheet

Read-only inspection:

```bash
adb shell 'getprop | grep -E "ro.product|ro.build.version"'   # identity
adb shell 'df -h /data'                                       # storage
adb shell 'pm list packages | grep -i plezy'                  # installed?
adb shell 'dumpsys package com.edde746.plezy | grep -E "versionName|versionCode|firstInstallTime"'
adb shell 'dumpsys package com.edde746.plezy | grep -c LEANBACK_LAUNCHER'   # launcher entries
adb shell 'dumpsys activity activities | grep mResumedActivity'             # foreground app
adb shell 'pidof com.edde746.plezy'
adb exec-out screencap -p > screen.png                        # 1920x1080 PNG
adb logcat -d -t 300 | grep -iE 'plezy|AndroidRuntime|FATAL'
```

Acting on the device:

```bash
adb install -r app.apk
adb shell am start -a android.intent.action.MAIN \
  -c android.intent.category.LEANBACK_LAUNCHER -n com.edde746.plezy/.MainActivity
adb shell am force-stop com.edde746.plezy
adb uninstall com.edde746.plezy
adb shell input text "https://github.com/derFrisson/plezy-apks"   # "type" without a keyboard
adb shell input keyevent 66                                        # ENTER
```

`input text` is the practical way to fill text fields on a TV: it avoids D-pad typing
entirely. Note it does not handle all characters (spaces need `%s`); URLs are fine.

## Notes / caveats

- Streaming installs work fine over Wi-Fi (`Performing Streamed Install`, ~15 s for 87 MB).
- The stick must be on the same L2 network; there is no cloud relay.
- The RSA key lives in `~/.android/adbkey`. Deleting that file forces a new prompt on every
  device you have authorised.
