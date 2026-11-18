#!/usr/bin/env python3
"""The six verification flows, written the way a person with the remote does them.

Every step is "find the labelled button on screen, tap its centre, wait for the
next window" — no coordinates are hard-coded, so the same flows run against an
English or a German build (labels come from strings/app.<lang>.json) and against
a German or English Fire OS (strings/system.<lang>.json).

    install        pick the latest version in the picker and install it
    picker-notes   read a version's release notes (touch overlay / TV pane); no install
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

    def _expect_gone(self, text, what=None, timeout=15):
        """Wait for ``text`` to disappear — an overlay that should have closed."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not self.d.dump().has(text):
                self.log(f"    ✓ {what or text!r} is gone")
                return
            time.sleep(1)
        raise HarnessError(f"{what or text!r} is still on screen after {timeout}s")

    # ------------------------------------------------------------------ #
    # screens
    # ------------------------------------------------------------------ #

    #: Focus substrings that mean a stock installer/uninstaller is (still) in front.
    _SYSTEM_DIALOGS = ("packageinstaller", "InstallAppProgress",
                       "UninstallerActivity", "UninstallAppProgress")

    def _system_dialog_focus(self):
        """The focused window when a stock installer/uninstaller is in front, else ''."""
        focus = self.d.current_focus()
        return focus if any(t in focus for t in self._SYSTEM_DIALOGS) else ""

    def _clear_stray_system_window(self, tries=2):
        """Cancel a stock installer/uninstaller window left over from an earlier run.

        Every flow starts from the updater's Main View. If a *system* dialog is in front instead,
        the device is dirty — a previous run (or a hand test) left the stock installer up and its
        window sits in the activity stack. Proceeding would drive the wrong window: the incident
        this guards against was a stale updater-confirmation dialog that made a whole 0.6.0 stick
        run look like an app regression. BACK cancels the stock installer/uninstaller, so clear it
        here — loudly — and fail if it will not go away, rather than corrupt the run silently.
        """
        for _ in range(tries):
            focus = self._system_dialog_focus()
            if not focus:
                return
            self.log(f"    ! stray system dialog in front ({focus!r}) — cancelling it with BACK")
            self.d.key("KEYCODE_BACK")
            time.sleep(1.5)
        focus = self._system_dialog_focus()
        if focus:
            raise HarnessError(
                f"a stock installer/uninstaller window is stuck in front ({focus!r}); "
                "dismiss it on the device and re-run")

    def open_updater(self):
        self.log("  open the updater")
        self.d.wake()
        self._clear_stray_system_window()
        self.d.shell(f"am force-stop {self.c['updater_package']}", check=False)
        self.d.shell(f"am start -n {self.c['main_activity']}", check=False)
        self._dismiss_deprecation_warning()
        self.d.wait_focus("MainActivity", timeout=30)
        time.sleep(2)  # let the update check fill in the buttons

    def _dismiss_deprecation_warning(self, timeout=15):
        """Clear Android's "built for an older version of Android" warning, if it shows.

        From API 34 the platform raises ``DeprecatedTargetSdkVersionDialog`` when an app targets
        below ``Build.VERSION.MIN_SUPPORTED_TARGET_SDK_INT`` (28 on API 34) — a *system* window,
        so it hides the updater and ``wait_focus("MainActivity")`` would otherwise time out.

        Press **OK**, not BACK: OK is what makes ``AppWarnings`` record the per-package
        ``FLAG_HIDE_DEPRECATED_SDK`` (persisted in ``/data/system/appwarnings.xml``), so the
        warning is not raised again on the next activity start — BACK only closes it once, and it
        pops up again on every activity the app opens. The dialog's positive button is the
        platform's "OK" in every locale. Once the updater targets 31+ (roadmap item 5) the dialog
        stops appearing and this returns False as soon as the Main View is in front.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            focus = self.d.current_focus()
            if "DeprecatedTargetSdkVersionDialog" in focus:
                self.log('    dismiss the "built for an older version of Android" warning (OK)')
                screen = self.d.dump()
                node = screen.find(exact="OK") or screen.find(exact="Ok")
                if node is not None:
                    self._tap(node, "OK")
                else:
                    self.d.key("KEYCODE_DPAD_CENTER")
                time.sleep(1.5)
                return True
            if "MainActivity" in focus:
                return False
            time.sleep(0.5)
        return False

    def open_picker(self):
        label = self.s.app("main.choose_version")
        self.open_updater()
        if self.d.dump().find(exact=label) is None:
            # The picker is a developer option and is off by default, so a fresh install (or one
            # whose settings were wiped by an uninstall) has no "Choose version…" button.
            self._enable_allow_older()
            self.open_updater()
        screen, node = self._wait_for(label, timeout=45)
        self._tap(node, label)
        self.d.wait_focus("VersionListActivity", timeout=20)
        time.sleep(2)  # let the release list download

    def _enable_allow_older(self):
        """Turn on Settings → Developer → "Allow older versions" (called with MainActivity up).

        Every picker flow needs it: "Choose version…" only exists while it is on. Idempotent.
        """
        label = self.s.app("settings.allow_older_label")
        self.log("  the picker is hidden — turning on Settings → Developer → Allow older versions")
        settings = self.s.app("main.settings")
        self._tap(self._require(self.d.dump(), f"Settings button {settings!r}", exact=settings),
                  settings)
        self.d.wait_focus("SettingsActivity", timeout=20)
        time.sleep(1)
        node = self._scroll_to_row(label, timeout=120, max_presses=25)
        if not node.checked:
            self._tap(node, label)
            time.sleep(1)
        self.d.key("KEYCODE_BACK")
        self.d.wait_focus("MainActivity", timeout=20)
        time.sleep(1)

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
        labels = {label.casefold() for label in screen.labels()}
        for code in harness.APP_LANGS:
            if harness.load_app_strings(code)["main"]["settings"].casefold() in labels:
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
        """Step the open language picker to ``target`` and press OK.

        A TV list has a row focused the moment it opens; a *touch* device (the phone lane) often
        has no focus at all until a D-pad event arrives, so nudge it once with DOWN to establish
        focus rather than giving up.
        """
        target_index = order.index(target)
        for _ in range(len(order) + 3):
            screen = self.d.dump()
            current = self._focused_language_row(screen, order, native)
            if current == target_index:
                self.d.key("KEYCODE_DPAD_CENTER")
                time.sleep(2.5)
                return
            if current is None:
                self.d.key("KEYCODE_DPAD_DOWN")  # establish focus on a touch device
                time.sleep(0.5)
                continue
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

    def _await_install_dialog(self, timeout=None):
        """Return what the pipeline raised: 'installer', 'uninstaller' or 'downgrade'."""
        if timeout is None:
            timeout = harness.INSTALL_TIMEOUT
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

    def _confirm_system_install(self, timeout=None):
        """Tap the stock installer's positive button, once or twice as the platform needs.

        The label depends on the platform *and* on the situation: the AOSP installer (API 25) says
        "INSTALL" ("INSTALLIEREN") for both a first install and an update; the Material installer
        (API 31+) says "Install" for a first install but "Update" when replacing; and the AOSP
        installer on API 31+ adds a *second* confirmation — "Update anyway" ("Trotzdem
        aktualisieren") — when the app was installed by a different installer than this one (the
        state a sideloaded Plezy is in). ``installer.confirm`` and ``installer.update_confirm`` may
        each be one label or a list of alternatives; the loop taps whichever is on screen and stops
        once the installer window is gone, so a two-dialog platform needs no special case.
        """
        if timeout is None:
            timeout = harness.INSTALL_TIMEOUT
        self.d.wait_focus("PackageInstallerActivity", timeout=timeout)
        time.sleep(1)
        candidates = (self.s.sys_candidates("installer.confirm")
                      + self.s.sys_candidates_optional("installer.update_confirm"))
        tapped = False
        for _ in range(3):
            if "PackageInstallerActivity" not in self.d.current_focus():
                break
            screen = self.d.dump()
            node = None
            for label in candidates:
                node = screen.find(exact=label)
                if node is not None:
                    break
            if node is None:
                break
            self._tap(node, node.label)
            tapped = True
            time.sleep(2)
        if not tapped:
            raise HarnessError(
                f"installer confirm button not found (tried {candidates}):\n{self.d.dump().pretty()}")

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

    def assert_open_prompt_opens_plezy(self, timeout=25):
        """Tap **Open** on the "Open Plezy?" prompt and prove it starts Plezy (roadmap item 6).

        Every install flow *dismisses* the prompt (``_dismiss_open_prompt`` taps "Not now"), so
        only the negative path was covered — the dialog merely appearing proved nothing about its
        **Open** button, which had never been pressed by the harness. This presses it and asserts
        Plezy's MainActivity comes to the front, then leaves the updater in front again so the run
        ends on a known screen.
        """
        title = self.s.app("open_prompt.title")
        open_label = self.s.app("open_prompt.open")
        pkg = self.c["plezy_package"]
        screen, _ = self._wait_for(title, what=f"{title!r} prompt", timeout=timeout)
        node = self._require(screen, f"prompt button {open_label!r}", exact=open_label)
        self._tap(node, open_label)
        deadline = time.time() + timeout
        last = ""
        while time.time() < deadline:
            last = self.d.current_focus()
            if pkg in last:
                self.log(f"    ✓ Open launched Plezy ({last})")
                self.d.shell(f"am force-stop {pkg}", check=False)
                self.open_updater()
                return
            time.sleep(1)
        raise HarnessError(f"tapping {open_label!r} did not launch Plezy (focus: {last!r})")

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
        # -p <package> makes the broadcast explicit. Since targetSdk 26 the platform refuses to
        # deliver an *implicit* broadcast to a manifest receiver ("Background execution not
        # allowed" in logcat), so the bare action works on the API 25 stick but is dropped on
        # API 26+. Naming the package is what the docs and the shell example must show too.
        out = self.d.shell(
            f"am broadcast -p {self.c['updater_package']} --include-stopped-packages -a {action}",
            check=False)
        last = out.strip().splitlines()[-1] if out.strip() else "(no output)"
        self.log(f"    quota broadcast: {last}")
        m = re.search(r'result=(-?\d+)(?:,\s*data="(.*)")?', out)
        if not m or not m.group(2):
            return None
        return dict(p.split("=", 1) for p in m.group(2).split(";") if "=" in p)

    def assert_device(self, report):
        """The broadcast's device=…;tv=…;touch=… must match this stick (roadmap item 2).

        Device detection is otherwise invisible from the outside, so it rides along on the
        diagnostics report. Expected values live in config.json's "expected_device".
        """
        want = self.c["expected_device"]
        got = {k: report.get(k) for k in ("device", "tv", "touch")}
        expect = {"device": want["kind"], "tv": want["tv"], "touch": want["touch"]}
        if got != expect:
            raise HarnessError(f"device report {got} != expected {expect}")
        self.log(f"    ✓ device: {got['device']} (tv={got['tv']}, touch={got['touch']})")

    def assert_notifications(self, report):
        """The broadcast's notif=… must be ``allowed`` (roadmap item 5, step 33).

        On API 25 there is no ``POST_NOTIFICATIONS`` permission, so ``allowed`` proves the guard
        leaves the legacy path untouched. On API 33+ the harness grants the permission before the
        flows, so ``allowed`` proves the new permission path is satisfied. Expected value lives in
        config.json's "expected_notif" (default ``allowed``).
        """
        want = self.c.get("expected_notif", "allowed")
        got = report.get("notif")
        if got != want:
            raise HarnessError(f"notification report {got!r} != expected {want!r}")
        self.log(f"    ✓ notifications: {got}")

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

    def picker_notes(self):
        """The release-notes view (roadmap item 3: the touch layout).

        Touch devices get a single-pane picker where each row carries a *Notes* button; tapping it
        flies the release notes in from the right, with a close button and an "Install this version"
        action. TV devices keep the two-pane layout, where the notes pane follows the highlighted
        row — this proves the pane fills in when a version is selected.

        Non-destructive: it opens the notes and closes them again, never starting an install.
        """
        self.log("[picker-notes] open the picker and read a version's release notes")
        self.open_picker()
        version = self.c["versions"]["latest"]
        screen, row = self._picker_row(version)

        if self.c.get("navigation") == "touch":
            notes = self._require(screen, "row Notes button",
                                  exact=self.s.app("picker.notes_button"))
            self._tap(notes, "Notes")
            self._wait_for(self.s.app("picker.install_button"),
                           what="release-notes overlay", timeout=20)
            self._expect_text(self.s.app("picker.notes_heading"), fatal=True)
            self._tap(self._require(self.d.dump(), "overlay close button",
                                    exact=self.s.app("picker.close")), "✕")
            self._expect_gone(self.s.app("picker.install_button"),
                              what="release-notes overlay", timeout=15)
            # Back must close it too (the second way out of the overlay).
            self._tap(notes, "Notes")
            self._wait_for(self.s.app("picker.install_button"),
                           what="release-notes overlay", timeout=20)
            self.d.key("KEYCODE_BACK")
            self._expect_gone(self.s.app("picker.install_button"),
                              what="release-notes overlay", timeout=15)
        else:
            # TV: the notes pane starts on the placeholder and fills in when a row is highlighted.
            self._expect_text(self.s.app("picker.select"), fatal=True)
            deadline = time.time() + 20
            while time.time() < deadline:
                self.d.key("KEYCODE_DPAD_DOWN")  # the first event after the window opens can drop
                time.sleep(1)
                if not self.d.dump().has(self.s.app("picker.select")):
                    break
            else:
                raise HarnessError("the notes pane never filled in after selecting a version")
            self.log("    ✓ the notes pane follows the highlighted version")
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
        self._confirm_system_install()
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
        self._confirm_system_install()
        self.assert_installed(version)
        # Prove the "Open Plezy?" prompt's positive button really launches Plezy (item 6), then
        # leave the updater in front. The other flows only ever dismiss this prompt.
        self.assert_open_prompt_opens_plezy()
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

    def _scroll_to_row(self, label, timeout=40, max_presses=15):
        """Like ``_scroll_to``, but matches a label *prefix*.

        A Settings switch row carries the platform's on/off word after its label
        ("Allow older versions OFF"), so an exact match never finds one.
        """
        deadline = time.time() + timeout
        for _ in range(max_presses):
            screen = self.d.dump()
            node = next((n for n in screen.nodes if n.label.startswith(label)), None)
            if node is not None:
                return node
            self.d.key("KEYCODE_DPAD_DOWN")
            time.sleep(0.8)
            if time.time() > deadline:
                break
        raise HarnessError(f"could not reach the row starting with {label!r}")
