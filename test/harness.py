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


def _load_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def load_strings(app_lang, system_lang):
    app = _load_json(os.path.join(HERE, "strings", f"app.{app_lang}.json"))
    system = _load_json(os.path.join(HERE, "strings", f"system.{system_lang}.json"))
    return Strings(app, system)


# The languages the updater ships (LocaleHelper.CODES). Kept here so the harness can
# recognise the app's current language without a per-run flag.
APP_LANGS = ("en", "de", "fr", "es")


def load_app_strings(lang):
    """The app-side labels for one language, without the system dialogs."""
    return _load_json(os.path.join(HERE, "strings", f"app.{lang}.json"))


def load_config():
    return _load_json(os.path.join(HERE, "config.json"))


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
             cls=None, enabled=None, x_max=None, x_min=None):
        for n in self.nodes:
            label = n.label
            if not label:
                continue
            if exact is not None and n.text != exact:
                continue
            if startswith is not None and not label.startswith(startswith):
                continue
            if contains is not None and contains not in label:
                continue
            if regex is not None and not re.search(regex, label):
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
        return any(text in n.label for n in self.nodes)

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

    # -- windows ----------------------------------------------------------

    def current_focus(self):
        out = self.shell("dumpsys window windows", check=False)
        # mCurrentFocus=Window{abc123 u0 pkg/pkg.Activity} — the value contains spaces.
        m = re.search(r"mCurrentFocus=(.*)", out)
        return m.group(1).strip() if m else ""

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

    def free_data_kb(self):
        out = self.shell("df /data", check=False)
        for line in out.splitlines():
            m = re.search(r"\s(\d+)\s+\d+%\s*/data\b", line)
            if m:
                return int(m.group(1))
        return None

    def detect_system_lang(self):
        loc = self.shell("getprop persist.sys.locale", check=False).strip()
        if not loc:
            loc = self.shell("getprop ro.product.locale", check=False).strip()
        return loc.split("-")[0].lower() if loc else "en"
