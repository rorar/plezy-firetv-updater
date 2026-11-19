#!/usr/bin/env python3
"""The eight verification flows, written the way a person with the remote does them.

Every step is "find the labelled button on screen, tap its centre, wait for the
next window" — no coordinates are hard-coded, so the same flows run against an
English or a German build (labels come from strings/app.<lang>.json) and against
a German or English Fire OS (strings/system.<lang>.json).

    install        pick the latest version in the picker and install it
    picker-notes   read a version's release notes (touch overlay / TV pane); no install
    interval       Settings -> Edit check interval… (touch grid+chips / TV two-pane)
    downgrade      pick an older version and drive uninstall-then-install
    upgrade        back to the Main View and install the latest again
    uninstall      Settings -> Advanced -> Uninstall Plezy…
    clean-install  install from the main screen when nothing is installed
    landscape      rotate a touch device and check each touch screen for clipping (TV: skips)

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

    def _safe_rect(self, screen=None):
        """The pixel rectangle a finger can actually touch, minus the system bars.

        With ``targetSdk`` 35 the window runs edge to edge: uiautomator still reports nodes whose
        centres sit *under* the gesture-nav bar, but ``input tap`` there goes to the bar instead of
        the app. The bar is a node in the app's own dump (``android:id/navigationBarBackground``),
        so use its bounds rather than guessing a margin. On a TV, or below 35 where the platform
        still reserves the bars, no such node is present and the whole display is safe.
        """
        screen = screen or self.d.dump()
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                     screen.root.attrib.get("bounds", ""))
        if m:
            width, height = int(m.group(3)), int(m.group(4))
        else:
            out = self.d.shell("wm size", check=False)
            size = re.search(r"(\d+)x(\d+)", out)
            if not size:
                raise HarnessError(f"cannot read the display size: {out!r}")
            width, height = int(size.group(1)), int(size.group(2))
        # A conservative bottom inset even when no bar is in the app dump: on API 34 the nav bar
        # belongs to SystemUI, so uiautomator does not show it, but a swipe starting in those
        # bottom pixels goes to the launcher/quick settings instead of the app's scroll view.
        left, top, right, bottom = 0, 0, width, height - max(24, height // 16)
        for node in screen.nodes:
            if node.rid == "android:id/navigationBarBackground":
                if node.x2 - node.x1 >= node.y2 - node.y1:  # horizontal bar
                    if node.y2 >= height - 2 and node.y1 > top:
                        bottom = node.y1
                    elif node.y1 <= 2 and node.y2 < bottom:
                        top = node.y2
                else:  # bar on a side (landscape phone)
                    if node.x2 >= width - 2 and node.x1 > left:
                        right = node.x1
                    elif node.x1 <= 2 and node.x2 < right:
                        left = node.x2
            elif node.rid == "android:id/statusBarBackground" and node.y2 > top:
                top = node.y2
        return left, top, right, bottom

    def _reveal(self, label, what=None, max_swipes=8):
        """Scroll a labelled node fully clear of the system bars, then return it.

        ``_scroll_to`` stops as soon as uiautomator can see a row. On an edge-to-edge API 35 phone
        that can be a row whose centre is under the nav bar, where a tap hits the bar instead. Keep
        swiping in the direction that brings the row into the safe rectangle, checking after every
        step so an overshoot (a row clamped against the *top* bar) is corrected too.
        """
        what = what or label
        margin = 8
        last = None
        for _ in range(max_swipes + 1):
            screen = self.d.dump()
            node = screen.find(exact=label)
            if node is not None:
                last = f"[{node.x1},{node.y1}][{node.x2},{node.y2}]"
                left, top, right, bottom = self._safe_rect(screen)
                if (node.x1 < left + margin or node.x2 > right - margin):
                    raise HarnessError(
                        f"{what!r} {last} is under a side system bar "
                        f"(safe x {left}..{right}); a vertical scroll cannot reveal it")
                if (node.y1 >= top + margin and node.y2 <= bottom - margin):
                    self.log(f"    ✓ {what!r} clear of system bars @ {last}")
                    return node
                too_high = node.y1 < top + margin
            else:
                too_high = False
                left, top, right, bottom = self._safe_rect(screen)
                last = "off screen"

            scroll = next((n for n in screen.nodes if "ScrollView" in n.cls), None)
            if scroll is None:
                raise HarnessError(f"{what!r} is not fully visible ({last}) and there is no scroll view")
            y_hi = min(scroll.y2 - 12, bottom - 12)
            y_lo = max(scroll.y1 + 12, top + 12)
            if y_hi <= y_lo:
                raise HarnessError(f"no room to scroll {what!r} into the safe rectangle")
            step = min(320, max(120, (y_hi - y_lo) // 4))
            y_start, y_end = (y_lo, min(y_lo + step, y_hi)) if too_high else \
                             (y_hi, max(y_hi - step, y_lo))
            self.log(f"    scroll {what!r} clear of system bars ({last})")
            self.d.shell(f"input swipe {scroll.cx} {y_start} {scroll.cx} {y_end} 250", check=False)
            time.sleep(0.6)
        raise HarnessError(f"could not reveal {what!r} clear of the system bars (last {last})")

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
        # Scroll before deciding the developer switch is off: on a short landscape screen the
        # button is below the fold, and toggling on a mere dump miss would switch it back OFF.
        try:
            self._scroll_to(label, enabled=True, timeout=15, max_presses=12)
        except HarnessError:
            # The picker is a developer option and is off by default, so a fresh install (or one
            # whose settings were wiped by an uninstall) has no "Choose version…" button.
            self._enable_allow_older()
            self.open_updater()
            self._scroll_to(label, enabled=True, timeout=30, max_presses=12)
        screen, node = self._wait_for(label, timeout=45)
        self._tap(node, label)
        self.d.wait_focus("VersionListActivity", timeout=20)
        time.sleep(2)  # let the release list download

    def _enable_allow_older(self):
        """Turn on Settings → Developer → "Allow older versions" (called with MainActivity up).

        Every picker flow needs it: "Choose version…" only exists while it is on. Idempotent.
        """
        self.log("  the picker is hidden — turning on Settings → Developer → Allow older versions")
        self.set_allow_older(True)

    def set_allow_older(self, enabled):
        """Turn Settings → Developer → "Allow older versions" on or off.

        "Choose version…" only exists while it is on, so the picker flows enable it; the
        screenshot run puts it back the way it found it. Idempotent — it reads the switch first —
        and returns the state *before* the change so a caller can restore it. It opens Settings
        itself, so it does not matter which screen is in front when it is called.
        """
        label = self.s.app("settings.allow_older_label")
        self.open_settings()
        node = self._scroll_to_row(label, timeout=120, max_presses=25)
        was = node.checked
        if was != enabled:
            self._tap(node, label)
            time.sleep(1)
        self.d.key("KEYCODE_BACK")
        self.d.wait_focus("MainActivity", timeout=20)
        time.sleep(1)
        return was

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

    def assert_insets(self, report):
        """API 35 must report the insets its UI applied; lower APIs must report none.

        targetSdk 35 makes the window edge-to-edge, so the app has to add the system-bar insets to
        its own padding. That is otherwise invisible to a script: it rides along on the diagnostics
        report, written by the insets listener the moment a screen was laid out. Below 35 the
        platform still reserves the bars, so the field must be absent — proving the guard leaves
        the API 25/34 layouts untouched. The values are px, and both ends must be non-zero on a
        phone, where a status bar and a gesture-nav bar are present. In landscape the nav bar sits
        on the right (and the cutout on the left), so only the top edge is fixed across rotations.
        """
        sdk = int(report["sdk"])
        got = report.get("insets")
        if sdk < 35:
            if got is not None:
                raise HarnessError(f"API {sdk} must not report insets, got {got!r}")
            self.log("    ✓ insets: none (the platform still reserves the bars)")
            return
        m = re.fullmatch(r"(\d+),(\d+),(\d+),(\d+)", got or "")
        if not m:
            raise HarnessError(f"API {sdk} insets report {got!r} is not left,top,right,bottom px")
        left, top, right, bottom = (int(x) for x in m.groups())
        if top <= 0 or left + right + bottom <= 0:
            raise HarnessError(f"API {sdk} insets report has no bars: {got}")
        self.log(f"    ✓ insets: {got} (status bar and one nav edge cleared)")

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
            notes = self._require(screen, "row notes icon",
                                  desc=self.s.app("picker.notes_button"))
            self._tap(notes, "notes icon")
            self._wait_for(self.s.app("picker.install_button"),
                           what="release-notes overlay", timeout=20)
            self._expect_text(self.s.app("picker.notes_heading"), fatal=True)
            self._tap(self._require(self.d.dump(), "overlay close button",
                                    exact=self.s.app("picker.close")), "✕")
            self._expect_gone(self.s.app("picker.install_button"),
                              what="release-notes overlay", timeout=15)
            # Back must close it too (the second way out of the overlay).
            self._tap(notes, "notes icon")
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

    def interval(self):
        """Settings → Edit check interval… — the check-interval picker (roadmap item 3 follow-up).

        A touch device gets a grid of number chips plus a 2×2 grid of unit chips; a TV keeps the
        two-pane VALUE|UNIT D-pad picker. On touch this taps a number and a unit chip, proves the
        live preview follows, saves, and proves the Settings summary picks the new value up — then
        puts the default (12 hours) back so a re-run starts level. Non-destructive: nothing is
        installed.
        """
        self.log("[interval] Settings → Edit check interval…")
        self.open_settings()
        self.open_interval()

        if self._touch_layout():
            self._interval_touch()
        else:
            self._interval_tv()
            self.d.key("KEYCODE_BACK")
            self.d.wait_focus("SettingsActivity", timeout=20)
            time.sleep(1)
        self.log("    ✓ the interval picker opened and returned to Settings")

    # -- landscape (touch layout on a short screen) ------------------------

    def _display_size(self, screen=None):
        """The live display size in px, read from the dump's own root bounds.

        ``wm size`` keeps reporting the physical portrait size even after a rotation on the real
        phone, so the dump is the more honest source. Falls back to ``wm size`` (swapped when
        ``user_rotation`` is 1) when there are no root bounds.
        """
        screen = screen or self.d.dump()
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", screen.root.attrib.get("bounds", ""))
        if m:
            return int(m.group(3)), int(m.group(4))
        out = self.d.shell("wm size", check=False)
        m = re.search(r"(\d+)x(\d+)", out)
        if not m:
            raise HarnessError(f"cannot read the display size: {out!r}")
        w, h = int(m.group(1)), int(m.group(2))
        return (h, w) if self.d.get_orientation()[1] == 1 else (w, h)

    def _check_bounds(self, node, what, size):
        """Assert a found node lies entirely inside the display; return it."""
        w, h = size
        if node.x1 < 0 or node.y1 < 0 or node.x2 > w or node.y2 > h:
            raise HarnessError(
                f"{what} is clipped: bounds [{node.x1},{node.y1}][{node.x2},{node.y2}] "
                f"outside {w}x{h}")
        self.log(f"    ✓ {what} fully on screen "
                 f"[{node.x1},{node.y1}][{node.x2},{node.y2}]")
        return node

    def _on_screen(self, screen, label, size, **kw):
        """Assert ``label`` is present *and entirely inside* the display; return its node.

        ``uiautomator dump`` omits off-screen nodes, so a missing label already fails a clipped
        button; the bounds check also catches a row that is only partly visible at an edge.
        """
        node = screen.find(exact=label, **kw)
        if node is None:
            raise HarnessError(f"{label!r} is not on screen:\n{screen.pretty()}")
        return self._check_bounds(node, repr(label), size)

    def _swipe_to(self, label, size, tries=12):
        """Scroll a page by finger swipes until ``label`` appears; return ``(screen, node)``.

        The interval and language pages are deliberately scrollable on a short landscape screen;
        DPAD_DOWN does not move among the touch chips, and after returning from a sub-page the
        wanted row can be *above* the current focus. So scroll down through the page first, then
        back up, keeping every swipe inside the display bounds.
        """
        w, h = size
        for y1, y2 in ((int(h * 0.82), int(h * 0.28)), (int(h * 0.28), int(h * 0.82))):
            for _ in range(tries):
                screen = self.d.dump()
                node = screen.find(exact=label)
                if node is not None:
                    return screen, node
                self.d.shell(f"input swipe {w // 2} {y1} {w // 2} {y2} 250", check=False)
                time.sleep(0.7)
        raise HarnessError(f"could not swipe {label!r} into view")

    def landscape(self):
        """Touch layouts on a short landscape screen — non-destructive.

        The TV layouts already live in landscape; this is the touch branch's turn. Two screens
        used to clip: the check-interval picker (its weight-1 number grid collapsed and pushed Save
        and Cancel off the bottom) and the language picker (no scroll view at all). Drive every
        touch screen and assert each expected label is present *and fully inside the display* —
        scrolling first where the page is meant to scroll. All rotation/toggles are restored.
        """
        if not self._touch_layout():
            self.log("[landscape] the TV layouts are already landscape — nothing to check")
            return None

        self.log("[landscape] rotate to landscape and check every touch screen")
        original = None
        allow_older_was = None
        try:
            original = self.d.set_orientation(1)
            self.open_updater()
            # A screen has now been laid out, so on API 35 the insets listener has fired. Prove the
            # edge-to-edge handling is live here (and absent on the API 34 phone) over the same
            # diagnostics channel the quota check already uses.
            insets_report = self.check_quota()
            if insets_report:
                self.assert_insets(insets_report)
            screen = self.d.dump()
            size = self._display_size(screen)
            self.log(f"    display: {size[0]}x{size[1]}")
            self._on_screen(screen, self.s.app("main.title"), size)

            # Main → Settings sits below the fold in landscape; open_settings scrolls to it.
            self.open_settings()
            self._on_screen(self.d.dump(), self.s.app("settings.title"), size)

            # Interval: header first, then scroll to the grid and the Save / Cancel pair. Before the
            # fix these were off-screen with no scroll view to reach them.
            self.open_interval()
            self._on_screen(self.d.dump(), self.s.app("interval.title"), size)
            screen, _ = self._swipe_to(self.s.app("interval.save"), size)
            self._on_screen(screen, self.s.app("interval.cancel"), size)
            chips = [n for n in screen.nodes
                     if n.cls.endswith("Button") and n.text.isdigit()
                     and n.y1 >= 0 and n.y2 <= size[1] and (n.y2 - n.y1) >= 24]
            if not chips:
                raise HarnessError(f"no full-size number chip on the interval screen:\n"
                                   f"{screen.pretty()}")
            self._check_bounds(max(chips, key=lambda n: n.y2 - n.y1), "a number chip", size)
            self.log("    ✓ the number grid and Save / Cancel are reachable in landscape")
            self.d.key("KEYCODE_BACK")
            self.d.wait_focus("SettingsActivity", timeout=20)
            time.sleep(1)

            # Language: every row, then Back — the last row used to be clipped and Back missing.
            lang_label = self.s.app("lang.label")
            _, lang_node = self._swipe_to(lang_label, size)
            self._tap(lang_node, lang_label)
            self.d.wait_focus("LanguageActivity", timeout=20)
            time.sleep(1)
            for code in self.c["language_rows"]["order"]:
                name = (self.s.app("lang.system") if code == "system"
                        else self.c["language_rows"]["native"][code])
                _, node = self._swipe_to(name, size)
                self._check_bounds(node, repr(name), size)
            screen, _ = self._swipe_to(self.s.app("action.back"), size)
            self.log("    ✓ all language rows and Back are reachable in landscape")
            self.d.key("KEYCODE_BACK")
            self.d.wait_focus("SettingsActivity", timeout=20)
            time.sleep(1)

            # Picker + notes overlay: the full-width rows and the fly-in must fit too.
            allow_older_was = self.set_allow_older(True)
            self.open_picker()
            screen = self.d.dump()
            self._on_screen(screen, self.s.app("picker.title"), size)
            screen, row = self._picker_row(self.c["versions"]["latest"])
            self._on_screen(screen, row.label, size)
            notes = self._require(screen, "notes icon", desc=self.s.app("picker.notes_button"))
            self._tap(notes, "notes icon")
            self._wait_for(self.s.app("picker.install_button"),
                           what="release-notes overlay", timeout=20)
            screen = self.d.dump()
            for key in ("picker.notes_heading", "picker.install_button", "picker.close"):
                self._on_screen(screen, self.s.app(key), size)
            self.log("    ✓ the picker and release-notes overlay fit in landscape")
            return True
        finally:
            if allow_older_was is not None:
                self.set_allow_older(allow_older_was)
            if original is not None:
                self.d.restore_orientation(original)
            self.open_updater()

    def _touch_layout(self):
        """True when the app itself picks the touch layout — the same signal Device.isTouch reads."""
        return self.c.get("expected_device", {}).get("touch") == "1"

    def open_settings(self):
        """Open Settings from the Main View (shared by the interval flow and the screenshots)."""
        self.open_updater()
        settings = self.s.app("main.settings")
        # In landscape the Settings button sits below the fold (the Main View scrolls), so scroll
        # to it instead of looking only at the nodes the first dump happens to show.
        node = self._scroll_to(settings, enabled=True, timeout=30)
        self._tap(node, settings)
        self.d.wait_focus("SettingsActivity", timeout=20)
        time.sleep(1)

    def open_interval(self):
        """Open Settings → Edit check interval… (the row only exists while *Check automatically*)."""
        row = self.s.app("settings.interval_label")
        node = self._scroll_to(row, timeout=60, max_presses=25)
        self._tap(node, row)
        self.d.wait_focus("IntervalActivity", timeout=20)
        time.sleep(1)

    def _interval_touch(self):
        self._expect_text(self.s.app("interval.title"), fatal=True)
        # On a short landscape screen Save/Cancel start below the fold. They are reachable through
        # the outer ScrollView, so scroll to them instead of assuming the portrait layout.
        self._scroll_list_to(self.s.app("interval.save"))
        self._scroll_list_to(self.s.app("interval.cancel"))

        days = self.s.app("interval.units.days")
        self._set_interval(3, "days")
        preview = self.s.app("interval.preview", n=3, unit=days)
        self._scroll_list_to(preview)
        self._expect_text(preview, fatal=True)
        self._save_interval()
        self._scroll_to(self.s.app("interval.now", n=3, unit=days), timeout=30, max_presses=8)
        self.log("    ✓ tapping a number and a unit chip set the interval; Settings followed")

        # A clean Settings, then put the default back so a re-run starts from the same place.
        self.open_settings()
        self.open_interval()
        hours = self.s.app("interval.units.hours")
        self._set_interval(12, "hours")
        preview = self.s.app("interval.preview", n=12, unit=hours)
        self._scroll_list_to(preview)
        self._expect_text(preview, fatal=True)
        self._save_interval()
        self._scroll_to(self.s.app("interval.now", n=12, unit=hours), timeout=30, max_presses=8)
        self.log("    ✓ restored the default interval (12 hours)")

    def _interval_tv(self):
        # The D-pad layout: two titled columns and the Back-to-cancel hint.
        self._expect_text(self.s.app("interval.value_heading"), fatal=True)
        self._expect_text(self.s.app("interval.unit_heading"), fatal=True)
        self._expect_text(self.s.app("interval.cancel_hint"), fatal=True)

        # Drive both columns. Up clamps at the first entry, so this is deterministic whatever the
        # stored interval was; the flow then presses Back to cancel, so nothing is persisted.
        for _ in range(30):
            self.d.key("KEYCODE_DPAD_UP")
        time.sleep(0.8)
        one = self._interval_preview_text()
        self.d.key("KEYCODE_DPAD_DOWN")
        time.sleep(0.8)
        two = self._interval_preview_text()
        if two == one:
            raise HarnessError(f"the D-pad did not change the value (still {one!r})")

        self.d.key("KEYCODE_DPAD_RIGHT")  # jump to the unit column
        time.sleep(0.8)
        for _ in range(6):
            self.d.key("KEYCODE_DPAD_UP")
        time.sleep(0.8)
        first_unit = self._interval_preview_text()
        self.d.key("KEYCODE_DPAD_DOWN")
        time.sleep(0.8)
        second_unit = self._interval_preview_text()
        if second_unit == first_unit:
            raise HarnessError(f"the D-pad did not change the unit (still {first_unit!r})")
        self.log(f"    ✓ the D-pad moves the value ({one!r} → {two!r}) "
                 f"and the unit ({first_unit!r} → {second_unit!r})")

    def _interval_preview_text(self):
        """The picker's live preview line ("Every 12 hours"), read off the screen.

        The prefix ("Every", "Alle", "Toutes les", …) is the localised preview label up to its
        first placeholder, so this works in any language without a second label key.
        """
        prefix = self.s.app("interval.preview").split("{n}")[0].strip().casefold()
        for n in self.d.dump().nodes:
            if n.label.casefold().startswith(prefix):
                return n.label
        raise HarnessError("the interval preview line is not on screen")

    def _set_interval(self, value, unit):
        """Touch picker: tap number ``value`` in the grid, then the chip for ``unit``.

        Units sit above the number grid and Save below it, so on a short screen each target has to
        be scrolled into view in turn — the dump after one tap may not contain the next one.
        """
        number = self._scroll_list_to(str(value))
        self._tap(number, f"interval {value}")
        time.sleep(0.6)
        label = self.s.app(f"interval.units.{unit}")
        chip = self._scroll_list_to(label)
        self._tap(chip, label)
        time.sleep(0.6)

    def _save_interval(self):
        save = self.s.app("interval.save")
        self._tap(self._scroll_list_to(save), save)
        self.d.wait_focus("SettingsActivity", timeout=20)
        time.sleep(1)

    def _scroll_list_to(self, text, tries=12):
        """Bring ``text`` into view inside the screen's scroll view, and return its node.

        The touch interval list is longer than a phone screen, and ``uiautomator dump`` omits
        off-screen nodes, so each target has to be scrolled to before it can be tapped. Swipe in
        both directions: units sit above the number grid while Save sits below it, so a target
        reached from one tap may be behind us for the next one. Swipes stay inside the scroll
        view's bounds and above any overlaid navigation bar.
        """
        screen = self.d.dump()
        node = screen.find(exact=text)
        if node is not None:
            return node
        # find() only matches labelled nodes and a ScrollView carries no text, so scan by class.
        scroll = next((n for n in screen.nodes if "ScrollView" in n.cls), None)
        if scroll is None:
            raise HarnessError(f"{text!r} is not on screen and there is no scroll view")
        left, top, right, bottom = self._safe_rect(screen)
        y_hi = min(scroll.y2 - 12, bottom - 12)
        y_lo = max(scroll.y1 + 12, top + 12)
        if y_hi <= y_lo:
            raise HarnessError(f"no room to scroll {text!r} into view")
        step = min(240, max(80, (y_hi - y_lo) // 3))
        for y_start, y_end in ((y_hi, max(y_lo, y_hi - step)),
                               (y_lo, min(y_hi, y_lo + step))):
            for _ in range(tries):
                self.d.shell(f"input swipe {scroll.cx} {y_start} "
                             f"{scroll.cx} {y_end} 300", check=False)
                time.sleep(0.5)
                node = self.d.dump().find(exact=text)
                if node is not None:
                    return node
        raise HarnessError(f"could not scroll {text!r} into view")

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

        # The row sits below the fold. On API 35 "on screen" is not enough: the nav bar overlays
        # the bottom edge, so reveal it fully before tapping (a D-pad-only scroll stops too early).
        node = self._reveal(row_label)
        if not node.enabled:
            raise HarnessError(f"the {row_label!r} row is disabled")
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
