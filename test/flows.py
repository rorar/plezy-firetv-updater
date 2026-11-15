#!/usr/bin/env python3
"""The four verification flows, written the way a person with the remote does them.

Every step is "find the labelled button on screen, tap its centre, wait for the
next window" — no coordinates are hard-coded, so the same flows run against an
English or a German build (labels come from strings/app.<lang>.json) and against
a German or English Fire OS (strings/system.<lang>.json).

    install        pick the latest version in the picker and install it
    downgrade      pick an older version and drive uninstall-then-install
    uninstall      Settings -> Advanced -> Uninstall Plezy…
    clean-install  install from the main screen when nothing is installed

The `all` sequence in run.py runs them in the order above, which starts from
"latest installed" and ends back on "latest installed".
"""

import time

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
        self._expect_text(self.s.app("picker.progress_installed", version=version))
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
        self._expect_text(self.s.app("picker.progress_installed", version=older))
        self.log(self._space_note(before))
        return older

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
