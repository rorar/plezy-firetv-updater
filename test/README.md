# `test/` — on-device flow tests

The five verification flows, written down as code. Each flow does exactly what a person with the
remote does — find the labelled button on screen, tap its centre, wait for the next window — so
they are both a test and a record of *what to click*.

| Flow | What it drives |
|---|---|
| `install` | open **Choose version…**, install the highlighted (latest) release |
| `downgrade` | pick an older release and drive the uninstall-then-install handshake |
| `upgrade` | go back to the **Main View** and install the latest again (in-place replace) |
| `uninstall` | **Settings → Advanced → Uninstall Plezy…** plus the stock uninstaller |
| `clean-install` | install from the main screen when Plezy is not installed |

`all` runs them in that order: it starts from "latest installed" and ends back on "latest
installed". Every run also fires the diagnostics broadcast first (see
[Quota + device check](#quota--device-check-a3--item-2)), so A3 is exercised on each invocation.

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
```

The updater is **runtime-switchable** (Settings → Language), so `--app-lang` is the language the
run *sets* before the flows — not a build variant. There is one build; the run drives
**Settings → Language** with the D-pad first, then verifies the app came back in that language.

Options: `--serial` (defaults to `$FIRE_TV_SERIAL` — copy `local.env.example` to `local.env`),
`--latest` / `--older` (override the versions),
`--system-lang` (default: auto-detected from `persist.sys.locale`), `--from-lang` (the app's
language now, if it should not be auto-detected), `--no-set-language` (skip the switch),
`-v` (echo every adb command).

`downgrade`, `upgrade`, `uninstall`, `clean-install` and `all` **erase Plezy's data** (the upgrade
does not erase data itself, but it starts by downgrading if Plezy is not already on the older
release), so they need `--yes`.

The harness leaves Plezy on the **latest** release; the last flow in `all` is the clean install.

## Strings are configuration, not code

Nothing in `harness.py` or `flows.py` contains a user-visible label. Two dimensions are loaded
from JSON, so the same flows run against any app language and any device locale:

- `strings/app.<lang>.json` — the **updater app's** own labels, for `en` / `de` / `fr` / `es`.
  Chosen with `--app-lang`; the run sets the app to that language first. Each file mirrors the
  `res/values*/strings.xml` it stands for — keep them in step.
- `strings/system.<lang>.json` — the **stock installer / uninstaller** labels (`INSTALLIEREN`,
  `ABBRECHEN`, `OK`, …). These follow the *device* locale, not the app's, and are auto-detected
  (`persist.sys.locale`), overridable with `--system-lang`. Only the device's language is needed;
  this stick is `de-DE`.

The **Settings → Language** rows are language-independent: each language is listed by its own name
(English, Deutsch, Français, Español). Those names live in `config.json` (`language_rows`), which is
what lets `set_app_language()` pick a row without knowing the current language.

To add a language, copy a pair of files and translate the values — no code changes.

One dimension the JSON labels do **not** carry is the **touch wording**: the app picks `press OK`
vs `tap` from Android's own touchscreen resource qualifier (`res/values-finger/`), so there is
nothing for the harness to set. `check_strings.py` keeps those files in parity.

`config.json` holds the non-localised bits: package/activity names, the language-picker rows, the
diagnostics broadcast action, the expected device classification, and the two versions the flows use
(`latest`, `older`). Bump the versions when upstream moves on, or pass `--latest` / `--older`.

## Quota + device check (A3 + item 2)

`run.py` exercises the diagnostics feature the way it is meant to be used — a script triggers the
check and reads the report back, with no screen involved. `Flows.check_quota()` sends

```bash
adb shell am broadcast --include-stopped-packages -a dev.rorar.plezyupdater.action.DIAGNOSTICS
```

and parses the ordered-broadcast result, which `am broadcast` prints as
`Broadcast completed: result=-1, data="status=ok;remaining=59;limit=60;reset_ms=…;checked_ms=…;device=fire_tv;tv=1;touch=0"`.
The preamble prints `GitHub API quota: 59/60 requests left`. The action lives in
`config.json` (`diagnostics_action`); the report is a quote-free `key=value;…` string by design, so
it survives that channel. The same report is written to the app's `quota.txt` (external cache dir)
as a second way in — and it carries the same suffix, so the two channels cannot drift.

The trailing `device=…;tv=…;touch=…` is `Device.report()`: the device classification (roadmap item
2) rides along on the check that was already happening, because it is otherwise invisible from the
outside. `Flows.assert_device()` compares it against `config.json`'s `expected_device`
(`fire_tv` / `1` / `0` for this stick) and the run **fails** if the receiver does not answer or the
values differ.

## String parity

`test/check_strings.py` is a static check over `app/res/values*/strings.xml` — no adb:

```bash
python3 test/check_strings.py
```

It fails when a locale is missing a key that `values/` has (bar the handful that are deliberately
the same in every language), when a `values-finger/` file invents a key instead of overriding one,
when a translation drops or renumbers a `%1$s` placeholder, or when the `test/strings/app.<lang>.json`
labels disagree on their keys. It runs as the pre-flight step of `run.py`.

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
  run.py                 CLI: pick flows + languages, print a summary
  harness.py             Device (adb + uiautomator) and the parsed Screen/Node model
  flows.py               the five flows, in remote-press terms
  check_strings.py       static parity check for res/values*/strings.xml
  config.json            package/activity names, the diagnostics action, the versions,
                         and the expected device classification
  jvm/DeviceKindTest.java  plain-Java tests for DeviceKind
  jvm/run.sh             compile + run them (no Android, no device)
  strings/app.<lang>.json      app labels      (per app language)
  strings/system.<lang>.json   system labels   (per device locale)
```

## Notes

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
