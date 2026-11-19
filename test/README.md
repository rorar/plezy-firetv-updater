# `test/` — on-device flow tests

The seven verification flows, written down as code. Each flow does exactly what a person with the
remote does — find the labelled button on screen, tap its centre, wait for the next window — so
they are both a test and a record of *what to click*.

| Flow | What it drives |
|---|---|
| `install` | open **Choose version…**, install the highlighted (latest) release |
| `picker-notes` | open a version's release notes and close them again (touch overlay / TV pane); no install |
| `interval` | open **Settings → Edit check interval…** and drive the picker (tap on touch, D-pad on TV); no install |
| `downgrade` | pick an older release and drive the uninstall-then-install handshake |
| `upgrade` | go back to the **Main View** and install the latest again (in-place replace) |
| `uninstall` | **Settings → Advanced → Uninstall Plezy…** plus the stock uninstaller |
| `clean-install` | install from the main screen when Plezy is not installed |

`all` runs them in that order: it starts from "latest installed" and ends back on "latest
installed". Every run also fires the diagnostics broadcast first (see
[Quota + device + notification check](#quota--device--notification-check-a3--item-2--item-5)), so A3 is exercised on each invocation.

`picker-notes` and `interval` are **non-destructive**: `picker-notes` opens the release notes
(the touch picker's fly-in overlay, or the TV picker's notes pane) and closes them again without
tapping a version label, so it installs nothing — on touch it also proves both ways out of the
overlay work, the `✕` button and the remote/gesture **Back**. `interval` opens the picker, changes
the interval and saves it, then restores the default `12 hours`, so the stored value is unchanged
when the flow ends.

Before any of that, `run.py` runs the static string-parity check (`test/check_strings.py`) — no
device needed, and a locale that drifted from `values/` would otherwise make a flow look for a
label that is no longer there.

## Running

```bash
python3 test/run.py install                     # forward path (non-destructive)
python3 test/run.py all --yes                    # the whole sequence
python3 test/run.py upgrade --yes                # downgrade → Main View → upgrade
python3 test/run.py install --app-lang de        # set the app to German first, then run
python3 test/run.py all --yes --build            # build + install the updater first
python3 test/run.py all --yes --profile phone34 --serial emulator-5554   # emulator lane
python3 test/run.py all --yes --profile phone35   # real phone (PHONE_SERIAL in local.env)
```

The updater is **runtime-switchable** (Settings → Language), so `--app-lang` is the language the
run *sets* before the flows — not a build variant. There is one build; the run drives
**Settings → Language** with the D-pad first, then verifies the app came back in that language.

Options: `--serial` (explicit address; else the profile's `serial_env` variable, else
`$FIRE_TV_SERIAL` — copy `local.env.example` to `local.env`),
`--profile` (which device the run expects — see [Device profiles](#device-profiles)),
`--latest` / `--older` (override the versions),
`--system-lang` (default: auto-detected from `persist.sys.locale`), `--from-lang` (the app's
language now, if it should not be auto-detected), `--no-set-language` (skip the switch),
`-v` (echo every adb command).

The environment variable **`PLEZY_INSTALL_TIMEOUT`** (default `180`) is how long a flow waits for
the install/uninstall/downgrade dialog to appear. The ~50 MB Plezy asset downloads *before* the
dialog, so on a slow link — or a throttled emulator, where the host's GitHub throughput can drop
below 250 KB/s — a download can take minutes; raise it (e.g. `PLEZY_INSTALL_TIMEOUT=420`) instead
of watching a flow fail on a network wait. `PLEZY_TEST_TMP` (default `/tmp/opencode/plezy-test`)
relocates the scratch dir so concurrent runs do not collide.

`downgrade`, `upgrade`, `uninstall`, `clean-install` and `all` **erase Plezy's data** (the upgrade
does not erase data itself, but it starts by downgrading if Plezy is not already on the older
release), so they need `--yes`.

The harness leaves Plezy on the **latest** release; the last flow in `all` is the clean install.

## README screenshots

`test/screenshots.py` generates the images in [`../docs/screenshots/`](../docs/screenshots/). They
are captured by script, not by hand, so they can be refreshed after any UI change:

```bash
source local.env
python3 test/screenshots.py                        # the stick → docs/screenshots/tv-*.png
python3 test/screenshots.py --profile phone34 --serial emulator-5554
                                                   # the phone lane → docs/screenshots/phone-*.png
```

It drives the app to each screen the README shows — the Main View, Settings, the Choose-Version
picker (filling the TV notes pane, or opening the touch release-notes overlay), and the
check-interval picker — `screencap`s it, and, because the harness cannot see images, verifies the
capture **structurally**: an expected label must be present in the same screen's `uiautomator dump`,
so a mis-navigated shot fails loudly instead of being committed. Files are named
`<prefix>-<screen>.png`, the prefix defaulting to `phone` on a touch device and `tv` on a TV.

It is non-destructive — nothing is installed — and it restores the app language and the developer
*Allow older versions* switch (which the picker shots need) to the state it found them in. On the
`phone34` AVD the shots are taken at a phone resolution set up front — `adb shell wm size 1080x2400`
clamps to the panel's `1080x1920`, then `adb shell wm density 440`; the committed phone shots are
`1080x1920`.

## Device profiles

One run targets one device, and the things that differ between devices are not in the flows. They
live in `config.json` → `profiles`, and `--profile <name>` selects one (default:
`config.json`'s `default_profile`, the stick). A profile flattens onto the config top level:

| Field | Meaning |
|---|---|
| `installer_activity` / `uninstaller_activity` | the stock installer's package + activity |
| `expected_device` | what `device=…;tv=…;touch=…` must report (`kind` / `tv` / `touch`) |
| `expected_notif` | what `notif=…` must report (default `allowed`) |
| `system_lang` | the locale to use when `--system-lang auto` cannot read one |
| `system_strings` | (optional) the exact stock-installer string file to load |
| `navigation` | `dpad` or `touch` — how the app's own screens are driven |
| `serial_env` | (optional) the environment variable holding the device's address |
| `avd` / `image` | the emulator to boot (emulator profiles only) |

Five profiles ship: `stick` (the Fire TV, the regression target), `tv25` and `tv30` (Android TV
emulators), `phone34` (an Android 14 phone emulator), and `phone35` (a **real** Android 15 / API 35
arm64 phone). The emulators are why this exists: the stick is API 25, so the notification-channel,
background-limit, and `targetSdk` 31/34 branches cannot be reached on it at all; the real phone adds
arm64 and API 35.

A profile may name the environment variable that holds its address (`serial_env`), so
`--profile phone35` needs no `--serial` — `run.py` reads the profile first and resolves the serial
from the flag, then `serial_env`, then `$FIRE_TV_SERIAL`. The **real address stays out of the repo**:
it lives only in the gitignored `local.env` (`PHONE_SERIAL`; copy `local.env.example`).

## Emulator lane

`test/emulator.sh` creates, boots, and provisions the AVDs named by the profiles:

```bash
export ANDROID_HOME=~/Android/Sdk        # with emulator + platform-tools + the system images
test/emulator.sh create    tv30          # make the AVD from the profile's system image
test/emulator.sh start     tv30          # boot it headless, wait for adb, print the serial
test/emulator.sh provision tv30          # install the updater + grant "install unknown apps"
test/emulator.sh stop      tv30
```

`provision` does the two things a test device needs that a real user does by hand, so a lane needs
no manual setup: install `app/build/plezy-updater.apk`, and on API 26+ grant the
`REQUEST_INSTALL_PACKAGES` **appop** — from Android 8 that is a per-app permission, denied by
default, and without it the stock installer aborts the session with
`INSTALL_FAILED_ABORTED: User rejected permissions`. (`run.py --build` grants it too, after
installing.) Pass `--serial <emulator-…>` to `run.py` as well: the stick may be connected over TCP
at the same time, and adb would otherwise not know which device to use.

`phone35` is not an emulator — it is a real phone over wireless adb, so there is no `create`/`start`
for it. Source `local.env` (which sets `PHONE_SERIAL`) and run `--profile phone35`; the profile's
`serial_env` resolves the address, and its `system_strings` (`system.de.api34.json`) supplies the
German AOSP installer's labels.

What each lane is for:

| Profile | API | ABI | Runs |
|---|---|---|---|
| `tv25` | 25 | x86 | updater-side checks (device classification, diagnostics, jobs) |
| `tv30` | 30 | x86 | the API 26+ channel / background-limit / implicit-broadcast behaviour |
| `phone34` | 34 | x86_64 | the **full Plezy flows**, and the only *emulator* here that enforces the `targetSdk` 31/34 rules |
| `phone35` | 35 | arm64-v8a | a **real phone** (not an emulator): the full flows over touch on the newest platform here, and the only arm64 device |

Plezy itself ships `arm64-v8a` / `armeabi-v7a` / `x86_64` only, so the 32-bit x86 TV images cannot
install it — they exercise the updater, not a Plezy install. `phone34` (x86_64) is the emulator that
runs everything end to end, and it is also the lane that proves the `targetSdk` 31/34 work (mutable vs
immutable `PendingIntent`s, the notification trampoline rule, the receiver-export flag and the
`ACCESS_NETWORK_STATE` job permission). `phone35` repeats that on real
hardware and closes the arm64 gap. The emulators boot headless; `phone35` is the user's own phone,
reachable over wireless adb (`PHONE_SERIAL` in `local.env`).

**Keeping the phone reachable.** Wireless-debugging adbd picks a new port whenever it restarts
(`adb root` / `unroot`, a reboot, toggling Wireless debugging), so a `PHONE_SERIAL` that worked a
minute ago can suddenly refuse. [`test/adb-keepalive.sh`](adb-keepalive.sh) watches the device and
re-discovers the port over mDNS, reconnecting as needed; the address stays in the gitignored
`local.env`, never in the repo:

```bash
source local.env
test/adb-keepalive.sh --daemon      # keep $PHONE_SERIAL reachable (writes a pidfile + a log)
test/adb-keepalive.sh --once        # reconnect once, print the working address
test/adb-keepalive.sh --stop        # stop the daemon
```

If the phone has lost its *pairing* (a fresh pairing code is on screen), pair once with
`adb pair <ip>:<pair-port> <code>` — the keepalive only re-connects, it does not pair.

## Strings are configuration, not code

Nothing in `harness.py` or `flows.py` contains a user-visible label. Two dimensions are loaded
from JSON, so the same flows run against any app language and any device locale:

- `strings/app.<lang>.json` — the **updater app's** own labels, for `en` / `de` / `fr` / `es`.
  Chosen with `--app-lang`; the run sets the app to that language first. Each file mirrors the
  `res/values*/strings.xml` it stands for — keep them in step.
- `strings/system.<lang>.json` — the **stock installer / uninstaller** labels (`INSTALLIEREN`,
  `ABBRECHEN`, `OK`, …). These follow the *device* locale, not the app's, and are auto-detected
  (`persist.sys.locale`), overridable with `--system-lang`. Only the device's language is needed;
  this stick is `de-DE`. A platform whose installer is worded differently gets an optional
  `strings/system.<lang>.<variant>.json` (e.g. `system.en.api34.json`, `system.de.api34.json`), named
  by a profile's `system_strings`: the Google installer on Android TV (API 25/30) says `Install` /
  `Cancel` and reuses `Install` for an update, the Material installer on API 31+ says `Install` but
  `Update` when replacing, and the AOSP installer on Android 15 (German) says `Installieren` /
  `Aktualisieren` / `Abbrechen`. `check_strings.py` keeps a variant's key set identical to its base
  file. Labels are matched **case-insensitively**: `Theme.Material` upper-cases dialog buttons on
  API 31+ (`Uninstall & install` → `UNINSTALL & INSTALL`) while Fire OS renders them as written.
- A confirm label may be a **list of alternatives**, not just one string. `installer.confirm` and
  `installer.update_confirm` are tried in order and `_confirm_system_install` taps whichever is on
  screen, up to three dialogs — because an update from a *different* installer than the app's original
  one adds a second dialog on the API 31+ AOSP installer: **`Trotzdem aktualisieren`** ("Update
  anyway") before the plain **`Aktualisieren`**. A two-dialog platform needs no special case; a
  single-dialog one just matches its one label.

The **Settings → Language** rows are language-independent: each language is listed by its own name
(English, Deutsch, Français, Español). Those names live in `config.json` (`language_rows`), which is
what lets `set_app_language()` pick a row without knowing the current language.

To add a language, copy a pair of files and translate the values — no code changes.

One dimension the JSON labels do **not** carry is the **touch wording**: the app picks `press OK`
vs `tap` from Android's own touchscreen resource qualifier (`res/values-finger/`), so there is
nothing for the harness to set. `check_strings.py` keeps those files in parity.

`config.json` holds the non-localised bits: package/activity names, the language-picker rows, the
diagnostics broadcast action, the device profiles, and the two versions the flows use (`latest`,
`older`). Bump the versions when upstream moves on, or pass `--latest` / `--older`.

## Quota + device + notification check (A3 + item 2 + item 5)

`run.py` exercises the diagnostics feature the way it is meant to be used — a script triggers the
check and reads the report back, with no screen involved. `Flows.check_quota()` sends

```bash
adb shell am broadcast -p dev.rorar.plezyupdater --include-stopped-packages \
    -a dev.rorar.plezyupdater.action.DIAGNOSTICS
```

and parses the ordered-broadcast result, which `am broadcast` prints as
`Broadcast completed: result=-1, data="status=ok;remaining=59;limit=60;reset_ms=…;checked_ms=…;device=fire_tv;tv=1;touch=0;notif=allowed"`.
The preamble prints `GitHub API quota: 59/60 requests left`. The action lives in
`config.json` (`diagnostics_action`); the report is a quote-free `key=value;…` string by design, so
it survives that channel.

**`-p <package>` is required on API 26+.** Since Android 8 the platform will not deliver an
*implicit* broadcast to a manifest receiver (`Background execution not allowed` in logcat), so the
bare `-a <action>` form works on the API 25 stick but is silently dropped on newer Android. Naming
the package makes it explicit — this was one of the first real findings of the emulator lane. The same report is written to the app's `quota.txt` (external cache dir)
as a second way in — and it carries the same suffix, so the two channels cannot drift.

The trailing `device=…;tv=…;touch=…` is `Device.report()`: the device classification (roadmap item
2) rides along on the check that was already happening, because it is otherwise invisible from the
outside. `Flows.assert_device()` compares it against `config.json`'s `expected_device`
(`fire_tv` / `1` / `0` for this stick) and the run **fails** if the receiver does not answer or the
values differ. The last field, `notif=allowed|blocked`, is `Notifier.state()` — whether Android will
actually deliver a notification (item 5, step 33: the API 33 `POST_NOTIFICATIONS` grant plus
`areNotificationsEnabled`) — asserted by `Flows.assert_notifications()` against `expected_notif`.
On API 33+ `run.py` also grants `POST_NOTIFICATIONS` before the flows (`Device.grant_notification_permission`,
a no-op below 33) so the one-time request dialog never lands in the middle of a flow; the dialog
itself is verified by hand.

## String parity

`test/check_strings.py` is a static check over `app/res/values*/strings.xml` — no adb:

```bash
python3 test/check_strings.py
```

It fails when a locale is missing a key that `values/` has (bar the handful that are deliberately
the same in every language), when a `values-finger/` file invents a key instead of overriding one,
when a translation drops or renumbers a `%1$s` placeholder, when the `test/strings/app.<lang>.json`
labels disagree on their keys, or when a `test/strings/system.<lang>.<variant>.json` carries a
different key set than its base file. It runs as the pre-flight step of `run.py`.

## JVM tests

`test/jvm/run.sh` compiles and runs `DeviceKindTest` against the plain JDK — no Android SDK, no
device:

```bash
test/jvm/run.sh
```

`DeviceKind` takes a plain `Signals` bundle instead of reaching into the platform, which is what
makes this possible: the Fire TV / Android TV / touch / other branches (and the precedence between
them) are all exercised here, including the ones this single stick cannot produce. `Device` is the
thin Android wrapper that only reads the platform.

## Layout

```
test/
  run.py                 CLI: pick flows + languages + profile, print a summary
  screenshots.py         capture the README images (Main/Settings/pickers) from a device
  harness.py             Device (adb + uiautomator) and the parsed Screen/Node model
  flows.py               the seven flows, in remote-press terms
  check_strings.py       static parity check for res/values*/strings.xml
  emulator.sh            create / start / provision / stop the AVDs (the emulator lane)
  config.json            package/activity names, the diagnostics action, the versions,
                         and the device profiles (stick, tv25, tv30, phone34, phone35)
  jvm/DeviceKindTest.java  plain-Java tests for DeviceKind
  jvm/run.sh             compile + run them (no Android, no device)
  strings/app.<lang>.json            app labels    (per app language)
  strings/system.<lang>.json         system labels (per device locale)
  strings/system.<lang>.<variant>.json  a platform whose installer is worded differently
```

## Notes

- **Every flow starts by clearing a stray system window.** If a previous run (or a hand test) left the
  stock installer or uninstaller in front, `Flows.open_updater()` BACK-cancels it before it starts —
  loudly — and fails if it will not go away. A leftover dialog otherwise makes the run drive the wrong
  window and look like an app regression (that is exactly how a stale updater-confirmation dialog once
  made a whole `0.6.0` stick run look broken). The list of system windows lives in
  `Flows._SYSTEM_DIALOGS`.
- **The "Open Plezy?" prompt is asserted, not just dismissed.** `clean-install` presses **Open** and
  proves Plezy's `MainActivity` comes to the front (`Flows.assert_open_prompt_opens_plezy`), then
  leaves the updater in front again. The other flows only ever dismiss the prompt (tap **Not now**),
  so before this the positive button had never been exercised.
- **"Choose version…" is a developer option and off by default**, so it is absent after a fresh
  install or an uninstall (which wipes the prefs). Every picker flow therefore opens the updater
  first and, if the button is missing, `Flows.open_picker()` turns on **Settings → Developer →
  Allow older versions** itself (scrolling to the row, flipping the switch, going back) before
  opening the picker. No manual setup is needed — but it does mean the flows touch that setting.
- The updater is **not debuggable** and the harness cannot see images, so everything is verified
  through `uiautomator dump` (labels + bounds) and `dumpsys package` (version, `firstInstallTime`,
  `lastUpdateTime`). The `upgrade` flow uses the two timestamps to tell a **replace** from a fresh
  install: `firstInstallTime` must not move, `lastUpdateTime` must.
- A dump can come back empty if the screen is asleep; `Device.dump()` wakes the stick and retries.
- On API 34 an app that targets below `Build.VERSION.MIN_SUPPORTED_TARGET_SDK_INT` (28 there) gets a
  system `DeprecatedTargetSdkVersionDialog` on every activity start, which hides the app and would
  time out `wait_focus("MainActivity")`. `Flows._dismiss_deprecation_warning()` presses **OK**, not
  BACK: OK is what records the per-package `FLAG_HIDE_DEPRECATED_SDK` in `/data/system/appwarnings.xml`,
  so the warning is not raised again — BACK only closes it once. A build targeting 31+ does not
  trigger it at all.
- Which `dumpsys` prints the focused window moved: `dumpsys window windows` up to ~API 30,
  `dumpsys window displays` on API 34 (the former no longer contains `mCurrentFocus`).
  `Device.current_focus()` tries the one that worked last, then the other.
- The **GitHub API rate-limits** unauthenticated clients to 60 requests/hour. The updater no longer
  spends that budget: the latest check uses the unmetered web feed and the picker's API call is
  conditional, so a run should not hit 403. The strongest test is therefore to run **while the
  quota is spent** — check `curl -s https://api.github.com/rate_limit`, confirm `remaining` is `0`,
  then run the flows; they must still pass. If they fail with *"GitHub API returned HTTP 403"* or
  the calm rate-limit wording, something regressed in the fallback, not in the harness.
- The flows log `/data` free space before and after. Treat it as a measurement, not as proof that
  the downloaded APK was deleted: installing Plezy itself costs ~87 MB and dominates the delta.
  The deletion is a code path (`InstallResultReceiver` → `ApkInstaller.cleanCache` when *delete
  after install* is on), which a non-debuggable build gives no direct way to observe.
- See [`../AGENTS.md`](../AGENTS.md) for the device facts and the gotchas (e.g. the first D-pad
  event after a dialog closes is swallowed).
