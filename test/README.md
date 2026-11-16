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
[Quota check](#quota-check-a3)), so A3 is exercised on each invocation.

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

Options: `--serial 192.168.1.50:5555`, `--latest` / `--older` (override the versions),
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

`config.json` holds the non-localised bits: package/activity names, the language-picker rows, the
diagnostics broadcast action, and the two versions the flows use (`latest`, `older`). Bump them when
upstream moves on, or pass `--latest` / `--older`.

## Quota check (A3)

`run.py` exercises the diagnostics feature the way it is meant to be used — a script triggers the
check and reads the report back, with no screen involved. `Flows.check_quota()` sends

```bash
adb shell am broadcast --include-stopped-packages -a dev.rorar.plezyupdater.action.DIAGNOSTICS
```

and parses the ordered-broadcast result, which `am broadcast` prints as
`Broadcast completed: result=-1, data="status=ok;remaining=59;limit=60;reset_ms=…;checked_ms=…"`.
The preamble prints `GitHub API quota: 59/60 requests left`. The action lives in
`config.json` (`diagnostics_action`); the report is a quote-free `key=value;…` string by design, so
it survives that channel. The same report is written to the app's `quota.txt` (external cache dir)
as a second way in.

## Layout

```
test/
  run.py                 CLI: pick flows + languages, print a summary
  harness.py             Device (adb + uiautomator) and the parsed Screen/Node model
  flows.py               the five flows, in remote-press terms
  config.json            package/activity names, the diagnostics action, and the versions
  strings/app.<lang>.json      app labels      (per app language)
  strings/system.<lang>.json   system labels   (per device locale)
```

## Notes

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
