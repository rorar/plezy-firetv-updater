# `test/` — on-device flow tests

The four verification flows, written down as code. Each flow does exactly what a person with the
remote does — find the labelled button on screen, tap its centre, wait for the next window — so
they are both a test and a record of *what to click*.

| Flow | What it drives |
|---|---|
| `install` | open **Choose version…**, install the highlighted (latest) release |
| `downgrade` | pick an older release and drive the uninstall-then-install handshake |
| `uninstall` | **Settings → Advanced → Uninstall Plezy…** plus the stock uninstaller |
| `clean-install` | install from the main screen when Plezy is not installed |

`all` runs them in that order: it starts from "latest installed" and ends back on "latest
installed".

## Running

```bash
python3 test/run.py install                     # forward path (non-destructive)
python3 test/run.py all --yes                    # the whole sequence
python3 test/run.py install --app-lang de        # against a German build
python3 test/run.py all --yes --build            # build + install the updater first
```

Options: `--serial 192.168.1.50:5555`, `--latest` / `--older` (override the versions),
`--system-lang` (default: auto-detected from `persist.sys.locale`), `-v` (echo every adb command).

`downgrade`, `uninstall`, `clean-install` and `all` **erase Plezy's data**, so they need `--yes`.

The harness leaves Plezy on the **latest** release; the last flow in `all` is the clean install.

## Strings are configuration, not code

Nothing in `harness.py` or `flows.py` contains a user-visible label. Two dimensions are loaded
from JSON, so the same flows run against any app language and any device locale:

- `strings/app.<lang>.json` — the **updater app's** own labels. Chosen with `--app-lang`.
  - `app.en.json` matches the build in `app/` today.
  - `app.de.json` is the **target** for the pending German translation; a `--app-lang de` run will
    fail until the Java strings are translated to match it. Keep the two in step.
- `strings/system.<lang>.json` — the **stock installer / uninstaller** labels (`INSTALLIEREN`,
  `ABBRECHEN`, `OK`, …). These follow the *device* locale, not the app's, and are auto-detected
  (`persist.sys.locale`), overridable with `--system-lang`.

To add a language, copy a pair of files and translate the values — no code changes.

`config.json` holds the non-localised bits: package/activity names and the two versions the flows
use (`latest`, `older`). Bump them when upstream moves on, or pass `--latest` / `--older`.

## Layout

```
test/
  run.py                 CLI: pick flows + languages, print a summary
  harness.py             Device (adb + uiautomator) and the parsed Screen/Node model
  flows.py               the four flows, in remote-press terms
  config.json            package/activity names and the versions
  strings/app.<lang>.json      app labels      (per app language)
  strings/system.<lang>.json   system labels   (per device locale)
```

## Notes

- The updater is **not debuggable** and the harness cannot see images, so everything is verified
  through `uiautomator dump` (labels + bounds) and `dumpsys package` (version, `lastUpdateTime`).
- A dump can come back empty if the screen is asleep; `Device.dump()` wakes the stick and retries.
- The flows log `/data` free space before and after. Treat it as a measurement, not as proof that
  the downloaded APK was deleted: installing Plezy itself costs ~87 MB and dominates the delta.
  The deletion is a code path (`InstallResultReceiver` → `ApkInstaller.cleanCache` when *delete
  after install* is on), which a non-debuggable build gives no direct way to observe.
- See [`../AGENTS.md`](../AGENTS.md) for the device facts and the gotchas (e.g. the first D-pad
  event after a dialog closes is swallowed).
