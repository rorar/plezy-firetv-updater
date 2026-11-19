#!/usr/bin/env python3
"""ADB + uiautomator plumbing for the plezy-firetv-updater flow tests.

Nothing here knows about Plezy or about any particular language: it can drive
the device, read the screen as a tree of nodes, and look up a label by key. The
flows (flows.py) and the labels (strings/*.json) supply the rest.
"""

import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
TMP = os.environ.get("PLEZY_TEST_TMP", "/tmp/opencode/plezy-test")
# How long to wait for the install/uninstall/downgrade dialog to appear. The download runs before
# the dialog, so on a slow link (or a throttled emulator) the ~50 MB asset can take minutes. Raise
# it with PLEZY_INSTALL_TIMEOUT=<seconds> instead of failing a flow that is only waiting on network.
INSTALL_TIMEOUT = int(os.environ.get("PLEZY_INSTALL_TIMEOUT", "180"))


class HarnessError(RuntimeError):
    """A flow could not proceed (button missing, window never appeared, …)."""


# --------------------------------------------------------------------------- #
# Strings
# --------------------------------------------------------------------------- #

class Strings:
    """App labels (per app language) and system-dialog labels (per device locale)."""

    def __init__(self, app, system):
        self._app = app
        self._system = system

    @property
    def app_language(self):
        return self._app.get("language", "?")

    @property
    def system_language(self):
        return self._system.get("language", "?")

    @staticmethod
    def _lookup(tree, key):
        cur = tree
        for part in key.split("."):
            if not isinstance(cur, dict) or part not in cur:
                raise HarnessError(f"missing string key: {key}")
            cur = cur[part]
        if not isinstance(cur, str):
            raise HarnessError(f"string key {key} is not a string")
        return cur

    def app(self, key, **fmt):
        text = self._lookup(self._app, key)
        return text.format(**fmt) if fmt else text

    def sys(self, key, **fmt):
        text = self._lookup(self._system, key)
        return text.format(**fmt) if fmt else text

    def sys_optional(self, key, default=None):
        """Like :meth:`sys`, but a key the system file does not define returns ``default``."""
        try:
            return self.sys(key)
        except HarnessError:
            return default

    def _lookup_raw(self, tree, key):
        cur = tree
        for part in key.split("."):
            if not isinstance(cur, dict) or part not in cur:
                raise HarnessError(f"missing string key: {key}")
            cur = cur[part]
        return cur

    def sys_candidates(self, key):
        """A system label as a list: a string is one label, a list is alternative wordings.

        The stock installer words one button differently by platform *and* by situation — on the
        AOSP installer of API 31+ an update that comes from a *different* installer than the app's
        original one first asks "Update anyway" ("Trotzdem aktualisieren") before the plain
        "Update" ("Aktualisieren"). A label may therefore be a string or a list of strings.
        """
        value = self._lookup_raw(self._system, key)
        if isinstance(value, str):
            return [value]
        if isinstance(value, list) and all(isinstance(x, str) for x in value):
            return list(value)
        raise HarnessError(f"string key {key} is neither a string nor a list of strings")

    def sys_candidates_optional(self, key):
        """Like :meth:`sys_candidates`, but a missing key yields ``[]``."""
        try:
            return self.sys_candidates(key)
        except HarnessError:
            return []


def _load_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def load_strings(app_lang, system_lang, system_strings=None):
    """App labels for ``app_lang`` and stock-installer labels for ``system_lang``.

    ``system_strings`` names the system file directly (e.g. ``system.en.api34.json``) for a
    device whose stock installer is worded differently from the default — the Material installer
    on Android 12+ says "Install" where the AOSP one says "INSTALL". A profile sets it; without
    it the file is ``system.<system_lang>.json``.
    """
    app = _load_json(os.path.join(HERE, "strings", f"app.{app_lang}.json"))
    name = system_strings or f"system.{system_lang}.json"
    system = _load_json(os.path.join(HERE, "strings", name))
    return Strings(app, system)


# The languages the updater ships (LocaleHelper.CODES). Kept here so the harness can
# recognise the app's current language without a per-run flag.
APP_LANGS = ("en", "de", "fr", "es")


def load_app_strings(lang):
    """The app-side labels for one language, without the system dialogs."""
    return _load_json(os.path.join(HERE, "strings", f"app.{lang}.json"))


def load_config():
    return _load_json(os.path.join(HERE, "config.json"))


def profile_names(config):
    """The device profiles declared in config.json, in file order, without the comment."""
    return [k for k in config.get("profiles", {}) if not k.startswith("_")]


def apply_profile(config, name=None):
    """Flatten a device profile (config.json → "profiles") onto the config and return it.

    The flows read ``installer_activity`` / ``uninstaller_activity`` / ``expected_device`` off
    the top level, so the selected profile's fields are copied there. ``name=None`` picks the
    config's ``default_profile`` (the stick), which keeps a run without ``--profile`` behaving
    exactly as before. Raises :class:`HarnessError` for an unknown name.
    """
    profiles = config.get("profiles", {})
    name = name or config.get("default_profile")
    if name not in profiles or name.startswith("_"):
        raise HarnessError(
            f"unknown profile {name!r} (have: {', '.join(profile_names(config))})")
    profile = profiles[name]
    config["profile"] = name
    for key, value in profile.items():
        if not key.startswith("_"):
            config[key] = value
    return profile


# --------------------------------------------------------------------------- #
# Screen model
# --------------------------------------------------------------------------- #

class Node:
    def __init__(self, attrib):
        self.text = attrib.get("text", "")
        self.desc = attrib.get("content-desc", "")
        self.cls = attrib.get("class", "")
        self.pkg = attrib.get("package", "")
        self.rid = attrib.get("resource-id", "")
        self.enabled = attrib.get("enabled", "true") == "true"
        self.focused = attrib.get("focused", "false") == "true"
        self.clickable = attrib.get("clickable", "false") == "true"
        self.selected = attrib.get("selected", "false") == "true"
        self.checked = attrib.get("checked", "false") == "true"
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attrib.get("bounds", ""))
        self.x1, self.y1, self.x2, self.y2 = (
            (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
            if m else (0, 0, 0, 0)
        )

    @property
    def cx(self):
        return (self.x1 + self.x2) // 2

    @property
    def cy(self):
        return (self.y1 + self.y2) // 2

    @property
    def label(self):
        return self.text or self.desc

    def __repr__(self):
        return f"<Node {self.label!r} @{self.cx},{self.cy} cls={self.cls.split('.')[-1]}>"


class Screen:
    """A parsed `uiautomator dump`."""

    def __init__(self, xml):
        root = ET.fromstring(xml)
        self.root = root
        self.nodes = [Node(el.attrib) for el in root.iter("node")]

    def find(self, exact=None, startswith=None, contains=None, regex=None,
             cls=None, enabled=None, x_max=None, x_min=None, desc=None):
        """Find the first node matching every given filter.

        Text is compared **case-insensitively**. The same app string is rendered differently by
        the platform theme: ``Theme.Material`` — which this app uses — sets ``textAllCaps`` on
        ``AlertDialog`` buttons, so "Uninstall & install" shows up as "UNINSTALL & INSTALL" on
        API 31+ (Android 12) while Fire OS 6 renders it as written. The label in the JSON is the
        app's string; the platform's casing is presentation, not a translation.

        ``desc`` matches the node's ``content-desc`` instead of its text, for icon-only controls
        (the touch picker's notes glyph) that carry no visible label.
        """
        for n in self.nodes:
            label = n.label
            if not label:
                continue
            if desc is not None and n.desc.casefold() != desc.casefold():
                continue
            if exact is not None and n.text.casefold() != exact.casefold():
                continue
            if startswith is not None and not label.casefold().startswith(startswith.casefold()):
                continue
            if contains is not None and contains.casefold() not in label.casefold():
                continue
            if regex is not None and not re.search(regex, label, re.IGNORECASE):
                continue
            if cls is not None and cls not in n.cls:
                continue
            if enabled is not None and n.enabled != enabled:
                continue
            if x_max is not None and n.cx > x_max:
                continue
            if x_min is not None and n.cx < x_min:
                continue
            return n
        return None

    def has(self, text):
        """True when some node's label contains ``text`` (case-insensitive; see :meth:`find`)."""
        return any(text.casefold() in n.label.casefold() for n in self.nodes)

    def labels(self):
        return [n.label for n in self.nodes if n.label]

    def focused_labels(self):
        return [n.label for n in self.nodes if n.focused and n.label]

    def focused_texts(self):
        """Text of each focused node, falling back to the first labelled descendant.

        A TV row often has focus on a container while the language name sits on a child,
        so reading only the focused node's own text can come back empty.
        """
        out = []
        for el in self.root.iter("node"):
            if el.attrib.get("focused") != "true":
                continue
            own = el.attrib.get("text", "")
            if own:
                out.append(own)
                continue
            for child in el.iter("node"):
                text = child.attrib.get("text", "")
                if text:
                    out.append(text)
                    break
        return out

    def pretty(self):
        return "\n".join(
            f"    {'*' if n.focused else ' '} {n.label[:90]!r}" for n in self.nodes if n.label
        )


# --------------------------------------------------------------------------- #
# Device
# --------------------------------------------------------------------------- #

class Device:
    def __init__(self, serial=None, adb="adb", verbose=False):
        self.adb = adb
        self.serial = serial
        self.verbose = verbose
        os.makedirs(TMP, exist_ok=True)
        self._seq = 0

    # -- raw adb ----------------------------------------------------------

    def _base(self):
        return [self.adb] + (["-s", self.serial] if self.serial else [])

    def sh(self, *args, timeout=60, check=True):
        cmd = self._base() + [str(a) for a in args]
        if self.verbose:
            print("    $ " + " ".join(cmd))
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise HarnessError(f"timed out: {' '.join(cmd)}")
        out = (p.stdout or "") + (p.stderr or "")
        if check and p.returncode != 0:
            raise HarnessError(f"{' '.join(cmd)} exited {p.returncode}\n{out.strip()}")
        return out

    def shell(self, cmd, timeout=60, check=True):
        return self.sh("shell", cmd, timeout=timeout, check=check)

    def require_connected(self):
        out = self.sh("get-state", timeout=20, check=False).strip()
        if out != "device":
            raise HarnessError(
                f"no device ready (adb get-state = {out!r}). "
                "Connect the stick with `adb connect <ip>:5555` first."
            )

    # -- input ------------------------------------------------------------

    def wake(self):
        self.shell("input keyevent KEYCODE_WAKEUP", check=False)
        self.shell("wm dismiss-keyguard", check=False)
        time.sleep(0.5)

    def key(self, code):
        self.shell(f"input keyevent {code}")

    def tap(self, x, y):
        self.shell(f"input tap {int(x)} {int(y)}")

    # -- orientation ------------------------------------------------------

    def get_orientation(self):
        """``(accelerometer_rotation, user_rotation)`` as ints (auto-rotate may be on)."""
        def get(key):
            raw = self.shell(f"settings get system {key}", check=False).strip()
            try:
                return int(raw)
            except ValueError:
                return 0
        return get("accelerometer_rotation"), get("user_rotation")

    def set_orientation(self, rotation):
        """Lock to ``0`` (portrait) or ``1`` (landscape), turning auto-rotate off.

        Returns the previous ``(accelerometer_rotation, user_rotation)`` so a caller can restore
        it in a ``finally`` — rotating a device for a test must not leave it sideways. TVs ignore
        ``user_rotation``; the landscape flow skips those profiles.
        """
        if rotation not in (0, 1):
            raise HarnessError(f"rotation must be 0 (portrait) or 1 (landscape): {rotation!r}")
        previous = self.get_orientation()
        self.shell("settings put system accelerometer_rotation 0", check=False)
        self.shell(f"settings put system user_rotation {rotation}", check=False)
        time.sleep(2.5)  # let the window recreate at the new size before dumping
        return previous

    def restore_orientation(self, previous):
        """Undo :meth:`set_orientation` with the tuple it returned."""
        accel, user = previous
        self.shell(f"settings put system user_rotation {user}", check=False)
        self.shell(f"settings put system accelerometer_rotation {accel}", check=False)
        time.sleep(2.5)

    # -- windows ----------------------------------------------------------

    #: Which `dumpsys window …` variant prints mCurrentFocus, learned on first use.
    _focus_cmd = None

    def current_focus(self):
        """The focused window, e.g. ``Window{abc u0 pkg/pkg.Activity}`` (contains spaces).

        The subcommand that carries ``mCurrentFocus`` moved: up to about API 30 it is
        ``dumpsys window windows``, on API 34 that no longer prints it and ``dumpsys window
        displays`` does. Both are small; try the one that worked last, then the other.
        """
        order = ["dumpsys window windows", "dumpsys window displays"]
        if self._focus_cmd in order:
            order.remove(self._focus_cmd)
            order.insert(0, self._focus_cmd)
        for cmd in order:
            m = re.search(r"mCurrentFocus=(.*)", self.shell(cmd, check=False))
            if m:
                Device._focus_cmd = cmd
                return m.group(1).strip()
        return ""

    def wait_focus(self, needle, timeout=90, interval=2):
        deadline = time.time() + timeout
        last = ""
        while time.time() < deadline:
            last = self.current_focus()
            if needle in last:
                return last
            time.sleep(interval)
        raise HarnessError(
            f"window containing {needle!r} did not appear within {timeout}s (focus: {last!r})"
        )

    # -- uiautomator ------------------------------------------------------

    def dump(self, retries=5):
        remote = "/sdcard/plezy_test_dump.xml"
        for _ in range(retries):
            self.shell(f"rm -f {remote}", check=False)
            out = self.shell(f"uiautomator dump {remote}", timeout=60, check=False)
            if "dumped to" in out:
                self._seq += 1
                local = os.path.join(TMP, f"dump-{self._seq}.xml")
                self.sh("pull", remote, local, check=False)
                if os.path.exists(local) and os.path.getsize(local) > 0:
                    with open(local, encoding="utf-8") as fh:
                        return Screen(fh.read())
            time.sleep(1.5)
            self.wake()
        raise HarnessError("uiautomator dump failed — is the screen asleep?")

    # -- package facts ----------------------------------------------------

    def installed_version(self, pkg):
        out = self.shell(f"dumpsys package {pkg}", check=False)
        m = re.search(r"versionName=(\S+)", out)
        return m.group(1) if m else None

    def version_code(self, pkg):
        out = self.shell(f"dumpsys package {pkg}", check=False)
        m = re.search(r"versionCode=(\d+)", out)
        return int(m.group(1)) if m else None

    def first_install_time(self, pkg):
        out = self.shell(f"dumpsys package {pkg}", check=False)
        m = re.search(r"firstInstallTime=(.+)", out)
        return m.group(1).strip() if m else None

    def last_update_time(self, pkg):
        out = self.shell(f"dumpsys package {pkg}", check=False)
        m = re.search(r"lastUpdateTime=(.+)", out)
        return m.group(1).strip() if m else None

    def is_installed(self, pkg):
        return "package:" in self.shell(f"pm path {pkg}", check=False)

    def api_level(self):
        return int(self.shell("getprop ro.build.version.sdk", check=False).strip() or 0)

    def grant_install_permission(self, pkg):
        """Grant "install unknown apps" for ``pkg``; no-op (returns None) below API 26.

        On API 25 ``REQUEST_INSTALL_PACKAGES`` is an ordinary manifest permission the install just
        uses. From API 26 it is a per-app *appop* whose default is **deny**, and the stock
        installer then aborts the session with ``INSTALL_FAILED_ABORTED: User rejected
        permissions``. A real user flips Settings → Apps → Special access; a test device (the
        emulator lane) can grant it directly. Returns the resulting appops line for the log.
        """
        if self.api_level() < 26:
            return None
        self.shell(f"appops set {pkg} REQUEST_INSTALL_PACKAGES allow", check=False)
        return self.shell(f"appops get {pkg} REQUEST_INSTALL_PACKAGES", check=False).strip()

    def grant_notification_permission(self, pkg):
        """Grant ``POST_NOTIFICATIONS`` for ``pkg``; no-op (returns None) below API 33.

        The permission does not exist before API 33, so the manifest entry is inert on the stick
        and on the API 30 emulator. From API 33 on the updater asks for it on first launch; a run
        grants it up front so the one-time dialog never lands in the middle of a flow. The request
        path itself is verified by hand (see docs/08). Returns the appop line for the log.
        """
        if self.api_level() < 33:
            return None
        self.shell(f"pm grant {pkg} android.permission.POST_NOTIFICATIONS", check=False)
        return self.shell(f"appops get {pkg} POST_NOTIFICATION", check=False).strip()

    def free_data_kb(self):
        """Free KB on the data partition, or None when ``df`` output cannot be parsed.

        The mount point the shell sees for ``/data`` differs by platform — on the API 35 phone the
        shell's ``/data`` shows as ``/storage/emulated/0/Android/obb`` — so match the
        Available/Use% columns rather than the mount-point name.
        """
        out = self.shell("df /data", check=False)
        for line in out.splitlines():
            m = re.search(r"\s(\d+)\s+\d+%\s+\S+", line)
            if m:
                return int(m.group(1))
        return None

    def detect_system_lang(self):
        """The device locale's language, e.g. 'de' for de-DE, or None when it cannot be read."""
        loc = self.shell("getprop persist.sys.locale", check=False).strip()
        if not loc:
            loc = self.shell("getprop ro.product.locale", check=False).strip()
        return loc.split("-")[0].lower() if loc else None
