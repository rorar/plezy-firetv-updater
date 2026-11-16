#!/usr/bin/env python3
"""The five verification flows, written the way a person with the remote does them.

Every step is "find the labelled button on screen, tap its centre, wait for the
next window" — no coordinates are hard-coded, so the same flows run against an
English or a German build (labels come from strings/app.<lang>.json) and against
a German or English Fire OS (strings/system.<lang>.json).

    install        pick the latest version in the picker and install it
    downgrade      pick an older version and drive uninstall-then-install
    upgrade        back to the Main View and install the latest again
    uninstall      Settings -> Advanced -> Uninstall Plezy…
    clean-install  install from the main screen when nothing is installed

The `all` sequence in run.py runs them in the order above, which starts from
"latest installed" and ends back on "latest installed".
"""

import re
import time

import harness
from harness import HarnessError


class Flows:
    def __init__(self, device, strings, config, log=print):
        self.d = device
        self.s = strings
        self.c = config
        self.log = log

    # ------------------------------------------------------------------ #
    # small helpers
    # ------------------------------------------------------------------ #

    def _tap(self, node, what):
        self.log(f"    tap {what!r} @ ({node.cx},{node.cy})")
        self.d.tap(node.cx, node.cy)

    def _require(self, screen, what, **kw):
        node = screen.find(**kw)
        if node is None:
            raise HarnessError(f"{what} not found on screen:\n{screen.pretty()}")
        return node

    def _wait_for(self, exact, what=None, timeout=40):
        deadline = time.time() + timeout
        while time.time() < deadline:
            screen = self.d.dump()
            node = screen.find(exact=exact)
            if node is not None:
                return screen, node
            time.sleep(1)
        raise HarnessError(f"{what or exact!r} never appeared within {timeout}s")

    def _space(self):
        """Free KB on /data, or None."""
        return self.d.free_data_kb()

    def _space_note(self, before_kb):
        """One honest line about the storage delta of a flow.

        The number is a real measurement, but it cannot isolate the update cache: installing
        Plezy itself costs ~87 MB, which dominates the delta. The cache is nevertheless cleared
        on success — ``InstallResultReceiver`` calls ``ApkInstaller.cleanCache`` when
        *delete after install* is on — but that is a code fact on this non-debuggable build, not
        something this delta (or the shell) can observe. Say so rather than imply the delta
        proves it.
        """
        after_kb = self.d.free_data_kb()
        if before_kb is None or after_kb is None:
            return "    /data free: ? (could not read)"
        used_mb = (before_kb - after_kb) / 1024.0
        return (f"    /data free: {before_kb} → {after_kb} KB (≈ {used_mb:.0f} MB used — the "
                f"~87 MB Plezy install dominates, so this does not isolate the update cache)")

    def _swipe_up(self, times=1):
        """Scroll a screen up (reveals content below the fold)."""
        for _ in range(times):
            self.d.shell("input swipe 960 900 960 420 200", check=False)
            time.sleep(0.6)

    def _expect_text(self, text, timeout=40, fatal=False):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.d.dump().has(text):
                self.log(f"    ✓ screen shows {text!r}")
                return True
            time.sleep(2)
        msg = f"screen never showed {text!r}"
        if fatal:
            raise HarnessError(msg)
        self.log(f"    (note: {msg})")
        return False

    # ------------------------------------------------------------------ #
    # screens
    # ------------------------------------------------------------------ #

    def open_updater(self):
        self.log("  open the updater")
        self.d.wake()
        self.d.shell(f"am force-stop {self.c['updater_package']}", check=False)
        self.d.shell(f"am start -n {self.c['main_activity']}", check=False)
        self.d.wait_focus("MainActivity", timeout=30)
        time.sleep(2)  # let the update check fill in the buttons

    def open_picker(self):
        self.open_updater()
        label = self.s.app("main.choose_version")
        screen, node = self._wait_for(label, timeout=45)
        self._tap(node, label)
        self.d.wait_focus("VersionListActivity", timeout=20)
        time.sleep(2)  # let the release list download

    def _picker_row(self, version, timeout=45):
        suffix = self.s.app("picker.installed_suffix")
        deadline = time.time() + timeout
        while time.time() < deadline:
            screen = self.d.dump()
            for n in screen.nodes:
                if not n.text:
                    continue
                if n.text == version or (n.text.startswith(version + " ") and suffix in n.text):
                    return screen, n
            time.sleep(1)
        raise HarnessError(f"picker never listed version {version}")

    # ------------------------------------------------------------------ #
    # language
    # ------------------------------------------------------------------ #

    def read_app_language(self):
        """The language the updater is in now, read off its own main screen.

        Every language names the Settings button differently, so whichever label is on
        screen tells us the current language — no stored state or per-run flag needed.
        """
        screen = self.d.dump()
        labels = set(screen.labels())
        for code in harness.APP_LANGS:
            if harness.load_app_strings(code)["main"]["settings"] in labels:
                return code
        raise HarnessError(
            f"could not tell the app's language from the main screen:\n{screen.pretty()}")

    def set_app_language(self, target, from_lang=None):
        """Set Settings → Language to ``target`` (a code, or ``'system'``) with the D-pad.

        The picker's rows are each language's *own* name (English, Deutsch, Français,
        Español), so once it is open we can step to the target without knowing the current
        language. The labels we click on the way there (Settings, Language) do depend on
        the current language, which we detect first.
        """
        rows = self.c["language_rows"]
        order, native = rows["order"], rows["native"]
        if target not in order:
            raise HarnessError(f"unknown language {target!r} (pick one of {order})")

        self.open_updater()
        current = from_lang or self.read_app_language()
        if current == target:
            self.log(f"  app language is already {target} — nothing to change")
            return target

        self.log(f"  app language {current} → {target} (Settings → Language)")
        src = harness.load_app_strings(current)

        settings_label = src["main"]["settings"]
        self._tap(self._require(self.d.dump(), f"Settings button {settings_label!r}",
                                exact=settings_label), settings_label)
        self.d.wait_focus("SettingsActivity", timeout=20)
        time.sleep(1)

        lang_label = src["lang"]["label"]
        node = self._scroll_to(lang_label, enabled=True, timeout=30)
        self._tap(node, lang_label)
        self.d.wait_focus("LanguageActivity", timeout=20)
        time.sleep(1)

        self._select_language_row(target, order, native)

        # The picker finishes back into the app; reopen the main screen and confirm.
        self.open_updater()
        got = self.read_app_language()
        if target != "system" and got != target:
            raise HarnessError(f"asked for language {target!r}, the app came back in {got!r}")
        self.log(f"    ✓ the app is now in {got} ({target})")
        return target

    def _select_language_row(self, target, order, native):
        """Step the open language picker to ``target`` and press OK."""
        target_index = order.index(target)
        for _ in range(len(order) + 2):
            screen = self.d.dump()
            current = self._focused_language_row(screen, order, native)
            if current == target_index:
                self.d.key("KEYCODE_DPAD_CENTER")
                time.sleep(2.5)
                return
            if current is None:
                raise HarnessError(
                    f"no language-picker row is focused:\n{screen.pretty()}")
            self.d.key("KEYCODE_DPAD_DOWN" if target_index > current else "KEYCODE_DPAD_UP")
            time.sleep(0.5)
        raise HarnessError(f"could not move the language picker to {target!r}")

    @staticmethod
    def _focused_language_row(screen, order, native):
        """Index into ``order`` of the focused picker row, or None if none is focused."""
        system_labels = {harness.load_app_strings(code)["lang"]["system"]
                         for code in harness.APP_LANGS}
        for label in screen.focused_texts():
            for i, code in enumerate(order):
                if code != "system" and native.get(code) == label:
                    return i
            if label in system_labels:
                return 0
        return None

    # ------------------------------------------------------------------ #
    # the install pipeline's dialogs
    # ------------------------------------------------------------------ #

    def _await_install_dialog(self, timeout=180):
        """Return what the pipeline raised: 'installer', 'uninstaller' or 'downgrade'."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            focus = self.d.current_focus()
            if "PackageInstallerActivity" in focus:
                return "installer"
            if "UninstallerActivity" in focus:
                return "uninstaller"
            if self.c["updater_package"] in focus:
                screen = self.d.dump()
                if screen.find(exact=self.s.app("dialog.downgrade_button")) is not None:
                    return "downgrade"
            time.sleep(2)
        raise HarnessError("no install dialog appeared (download still running?)")

    def _confirm_system_install(self, timeout=90):
        self.d.wait_focus("PackageInstallerActivity", timeout=timeout)
        time.sleep(1)
        label = self.s.sys("installer.confirm")
        screen = self.d.dump()
        node = self._require(screen, f"installer button {label!r}", exact=label)
        self._tap(node, label)

    def _confirm_system_uninstall(self, timeout=90):
        self.d.wait_focus("UninstallerActivity", timeout=timeout)
        time.sleep(1)
        label = self.s.sys("uninstaller.confirm")
        screen = self.d.dump()
        node = self._require(screen, f"uninstaller button {label!r}", exact=label)
        self._tap(node, label)

    def _confirm_downgrade(self, version):
        button = self.s.app("dialog.downgrade_button")
        title = self.s.app("dialog.downgrade_title", version=version)
        screen, node = self._wait_for(button, what=button, timeout=30)
        if screen.find(exact=title) is None:
            self.log(f"    (note: expected dialog title {title!r})")
        self._tap(node, button)

    def _dismiss_open_prompt(self, timeout=15):
        """Dismiss the "Open Plezy?" dialog that follows a successful install.

        Returns True when a dialog was dismissed. The prompt is on by default; a flow
        tolerates its absence (the setting can be off) but must clear it when present —
        the dialog is the *only* window in a `uiautomator dump`, so everything underneath
        is invisible until it is gone. Tapping "Not now" is the deterministic dismissal;
        the buttons take a tap, and the tap does not depend on where the focus landed.
        """
        title = self.s.app("open_prompt.title")
        not_now = self.s.app("open_prompt.not_now")
        deadline = time.time() + timeout
        while time.time() < deadline:
            screen = self.d.dump()
            if screen.find(exact=title) is not None:
                self.log(f"    dismiss the {title!r} prompt")
                node = screen.find(exact=not_now)
                if node is not None:
                    self._tap(node, not_now)
                else:
                    self.d.key("KEYCODE_DPAD_CENTER")
                time.sleep(1.5)
                return True
            time.sleep(1)
        return False

    def _back_to_main(self, timeout=25):
        """Walk back to the updater's Main View with BACK — never force-stops or relaunches.

        The upgrade flow must exercise the *Main View* path, so this is deliberately not
        ``open_updater()``. BACK is only sent while the updater itself is in front, so a
        mis-timed press cannot escape into the Fire TV launcher.
        """
        main = f"{self.c['updater_package']}.MainActivity"
        deadline = time.time() + timeout
        while time.time() < deadline:
            focus = self.d.current_focus()
            if main in focus:
                self.log("    ✓ back on the Main View")
                return
            if self.c["updater_package"] not in focus:
                raise HarnessError(
                    f"left the updater, cannot press BACK (focus: {focus!r})")
            self.d.key("KEYCODE_BACK")
            time.sleep(1.5)
        raise HarnessError(f"could not reach the Main View (focus: {self.d.current_focus()!r})")

    def check_quota(self):
        """Ask the updater for its GitHub quota over the diagnostics broadcast (A3).

        This is the "a script triggers a check and reads a report" path the feature was
        built for: the receiver answers in the ordered-broadcast result data, which adb
        prints as ``result=-1, data="status=ok;remaining=…;…"``. Returns the report as a
        dict, or None when the receiver did not answer.
        """
        action = self.c["diagnostics_action"]
        out = self.d.shell(f"am broadcast --include-stopped-packages -a {action}", check=False)
        last = out.strip().splitlines()[-1] if out.strip() else "(no output)"
        self.log(f"    quota broadcast: {last}")
        m = re.search(r'result=(-?\d+)(?:,\s*data="(.*)")?', out)
        if not m or not m.group(2):
            return None
        return dict(p.split("=", 1) for p in m.group(2).split(";") if "=" in p)

    # ------------------------------------------------------------------ #
    # assertions
    # ------------------------------------------------------------------ #

    def assert_installed(self, version, timeout=120):
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            last = self.d.installed_version(self.c["plezy_package"])
            if last == version:
                self.log(f"    ✓ Plezy is {version}")
                return
            time.sleep(3)
        raise HarnessError(f"expected Plezy {version}, still {last!r}")

    def assert_not_installed(self, timeout=45):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not self.d.is_installed(self.c["plezy_package"]):
                self.log("    ✓ Plezy is not installed")
                return
            time.sleep(2)
        raise HarnessError("Plezy is still installed")

    # ------------------------------------------------------------------ #
    # the flows
    # ------------------------------------------------------------------ #

    def install(self, version=None):
        """Install the latest version from the picker (the forward path)."""
        version = version or self.c["versions"]["latest"]
        self.log(f"[install] picker → install {version}")
        before = self._space()
        self.open_picker()
        _, row = self._picker_row(version)
        self._tap(row, f"version {version}")
        kind = self._await_install_dialog()
        if kind != "installer":
            raise HarnessError(f"installing {version} should open the installer, saw {kind!r}")
        self._confirm_system_install()
        self.assert_installed(version)
        # Clear the "Open Plezy?" dialog before reading the picker: while it is up it is the
        # only window in the dump, so the progress line underneath is invisible.
        self._dismiss_open_prompt()
        self._expect_text(self.s.app("picker.progress_installed", version=version), fatal=True)
        self.log(self._space_note(before))
        return version

    def downgrade(self, older=None):
        """Downgrade to an older version from the picker (uninstall-then-install)."""
        older = older or self.c["versions"]["older"]
        self.log(f"[downgrade] picker → downgrade to {older}")
        before = self._space()
        self.open_picker()
        _, row = self._picker_row(older)
        self._tap(row, f"version {older}")
        kind = self._await_install_dialog()
        if kind != "downgrade":
            raise HarnessError(f"expected the downgrade confirm, saw {kind!r}")
        self._confirm_downgrade(older)
        self._confirm_system_uninstall()
        self._confirm_system_install()
        self.assert_installed(older)
        self._dismiss_open_prompt()
        self._expect_text(self.s.app("picker.progress_installed", version=older), fatal=True)
        self.log(self._space_note(before))
        return older

    def upgrade(self, version=None, older=None):
        """Upgrade from the Main View — the path a user takes after a downgrade.

        Precondition: Plezy is installed and older than ``version``. If it is not on
        ``older`` already the flow downgrades first, so it also runs on its own. The
        install is started from the Main View's "Install <version>" button, *not* the
        picker: that is the whole point, it proves the dashboard path works. A replace is
        proven from the package identity — firstInstallTime must not move, lastUpdateTime
        must (a fresh install would move both).
        """
        version = version or self.c["versions"]["latest"]
        older = older or self.c["versions"]["older"]
        pkg = self.c["plezy_package"]

        installed = self.d.installed_version(pkg)
        if installed is None:
            raise HarnessError("upgrade needs Plezy installed; run install or clean-install first")
        if installed != older:
            self.log(f"[upgrade] Plezy is {installed}, not {older} — downgrading first")
            self.downgrade(older)
        # The picker is in front now; the upgrade itself must go through the Main View.

        before_first = self.d.first_install_time(pkg)
        before_last = self.d.last_update_time(pkg)
        self.log(f"  install identity before: first={before_first} last={before_last}")

        self.log(f"[upgrade] Main View → install {version}")
        self._back_to_main()
        label = self.s.app("main.install_version", version=version)
        screen, node = self._wait_for(label, what=f"Main View button {label!r}", timeout=60)
        if not node.enabled:
            raise HarnessError(f"the {label!r} button is disabled:\n{screen.pretty()}")
        self._tap(node, label)
        self._confirm_system_install(timeout=180)
        self.assert_installed(version)
        self._dismiss_open_prompt()

        after_first = self.d.first_install_time(pkg)
        after_last = self.d.last_update_time(pkg)
        if before_first and after_first and before_first != after_first:
            raise HarnessError(
                f"expected an in-place replace, but firstInstallTime moved: "
                f"{before_first!r} → {after_first!r}")
        if before_last and after_last and before_last == after_last:
            raise HarnessError(f"lastUpdateTime did not change: still {after_last!r}")
        self.log(f"    ✓ replaced in place — firstInstallTime {after_first}, "
                 f"lastUpdateTime {after_last}")
        return version

    def uninstall(self):
        """Uninstall Plezy from Settings → Advanced."""
        row_label = self.s.app("settings.uninstall_row")
        self.log("[uninstall] Settings → Advanced → Uninstall Plezy…")
        self.open_updater()
        self._tap(self._require(self.d.dump(), "Settings button",
                                exact=self.s.app("main.settings")),
                  self.s.app("main.settings"))
        self.d.wait_focus("SettingsActivity", timeout=20)
        time.sleep(1)

        # The row sits below the fold; walk the D-pad down until it is on screen.
        node = self._scroll_to(row_label, enabled=True, timeout=40)
        self._tap(node, row_label)

        button = self.s.app("settings.uninstall_confirm_button")
        _, node = self._wait_for(button, what=button, timeout=20)
        self._tap(node, button)
        self._confirm_system_uninstall()
        self.assert_not_installed()
        # The summary sits under the (now disabled) row; scroll it into view before reading it.
        self._swipe_up()
        self._expect_text(self.s.app("settings.not_installed_summary"))
        return None

    def clean_install(self, version=None):
        """Install from the main screen when Plezy is absent."""
        version = version or self.c["versions"]["latest"]
        self.log(f"[clean-install] main screen → Install {version}")
        before = self._space()
        self.open_updater()
        self._expect_text(self.s.app("main.not_installed_headline"), timeout=45, fatal=True)
        screen = self.d.dump()
        node = (screen.find(exact=self.s.app("main.install_version", version=version))
                or screen.find(exact=self.s.app("main.install_update")))
        if node is None:
            raise HarnessError(f"install button not found:\n{screen.pretty()}")
        self._tap(node, node.label)
        self._confirm_system_install(timeout=180)
        self.assert_installed(version)
        self._dismiss_open_prompt()
        self.log(self._space_note(before))
        return version

    # ------------------------------------------------------------------ #

    def _scroll_to(self, text, enabled=None, timeout=40, max_presses=15):
        deadline = time.time() + timeout
        for _ in range(max_presses):
            screen = self.d.dump()
            node = screen.find(exact=text)
            if node is not None and (enabled is None or node.enabled == enabled):
                return node
            self.d.key("KEYCODE_DPAD_DOWN")
            time.sleep(0.8)
            if time.time() > deadline:
                break
        raise HarnessError(f"could not reach {text!r} (enabled={enabled})")
