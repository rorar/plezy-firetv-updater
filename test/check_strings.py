#!/usr/bin/env python3
"""Static parity check for the updater's string resources.

No device, no adb: this reads the XML directly and fails when a locale or a touch variant
drifts from the base. Run it after touching ``app/res/values*/strings.xml``:

    python3 test/check_strings.py

Three invariants, all of which have bitten this app at least once:

1. **Locale completeness.** Every key in ``values/strings.xml`` also exists in
   ``values-<lang>/``, except a short allow-list of strings that are the same in every
   language by design (the app name, each language written in itself, the notification title).
2. **Touch variants are deltas.** ``values-finger/`` and its per-locale siblings may only
   *override* keys that already exist in the matching base file. A ``-finger`` file that
   invents a key is almost always a typo, and it would ship a string no other config has.
3. **Format specifiers survive translation.** A translated string must carry the same
   ``%1$s`` / ``%2$d`` placeholders as the base; a dropped or renumbered one is a crash at
   format time, not a cosmetic bug.

It also checks that the harness labels in ``test/strings/app.<lang>.json`` agree on their keys.
"""

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
RES = os.path.join(REPO, "app", "res")
LABELS = os.path.join(HERE, "strings")

LANGS = ["de", "fr", "es"]

# Deliberately the same in every language: the app name, the language names (each written in
# itself, so the picker reads the same whatever the app is set to), and the notification title.
UNTRANSLATED = {"app_name", "lang_de", "lang_en", "lang_es", "lang_fr", "notifications_title"}

# %1$s, %2$d, %s, %d … and the literal %%.
FORMAT = re.compile(r"%(?:\d+\$)?[a-zA-Z%]")


def read_strings(path):
    """{name: text} for every <string> and <plurals> in one resource file."""
    out = {}
    for el in ET.parse(path).getroot():
        name = el.get("name")
        if not name:
            continue
        if el.tag == "string":
            out[name] = "".join(el.itertext())
        elif el.tag == "plurals":
            # Every <item> of a plurals block carries the same placeholders; join them so the
            # comparison sees all of them.
            out[name] = " ".join("".join(item.itertext()) for item in el)
    return out


def placeholders(text):
    return sorted(FORMAT.findall(text))


def resource(lang, finger=False):
    name = f"values-{lang}" if lang else "values"
    if finger:
        name += "-finger"
    return os.path.join(RES, name, "strings.xml")


def check_resources(problems):
    base = read_strings(resource(""))

    for lang in LANGS:
        path = resource(lang)
        if not os.path.exists(path):
            problems.append(f"values-{lang}: missing")
            continue
        loc = read_strings(path)
        for key in sorted(set(base) - set(loc) - UNTRANSLATED):
            problems.append(f"values-{lang}: missing key {key!r}")
        for key in sorted(set(loc) - set(base)):
            problems.append(f"values-{lang}: unknown key {key!r} (not in values/)")
        for key in sorted(set(loc) & set(base)):
            if placeholders(loc[key]) != placeholders(base[key]):
                problems.append(
                    f"values-{lang}: {key!r} has {placeholders(loc[key])}, "
                    f"values/ has {placeholders(base[key])}")

    for lang in [""] + LANGS:
        path = resource(lang, finger=True)
        label = f"values-{lang + '-' if lang else ''}finger"
        if not os.path.exists(path):
            problems.append(f"{label}: missing")
            continue
        finger = read_strings(path)
        if not finger:
            problems.append(f"{label}: defines no strings")
        allowed = read_strings(resource(lang))
        base_label = f"values{'-' + lang if lang else ''}/"
        for key in sorted(set(finger) - set(allowed)):
            problems.append(f"{label}: {key!r} is not in {base_label} — "
                            f"a -finger file may only override")
        for key in sorted(set(finger) & set(base)):
            if placeholders(finger[key]) != placeholders(base[key]):
                problems.append(
                    f"{label}: {key!r} has {placeholders(finger[key])}, "
                    f"values/ has {placeholders(base[key])}")
    return base


def check_labels(problems):
    def leaves(node, prefix=""):
        if not isinstance(node, dict):
            return {prefix}
        out = set()
        for key, value in node.items():
            if key.startswith("_"):
                continue
            out |= leaves(value, f"{prefix}.{key}" if prefix else key)
        return out

    en = leaves(json.load(open(os.path.join(LABELS, "app.en.json"))))
    for lang in LANGS:
        path = os.path.join(LABELS, f"app.{lang}.json")
        if not os.path.exists(path):
            problems.append(f"app.{lang}.json: missing")
            continue
        keys = leaves(json.load(open(path)))
        for key in sorted(en - keys):
            problems.append(f"app.{lang}.json: missing {key!r} (present in app.en.json)")
        for key in sorted(keys - en):
            problems.append(f"app.{lang}.json: extra {key!r}")


def main():
    problems = []
    base = check_resources(problems)
    check_labels(problems)

    counts = "   ".join(
        f"values-{lang}: {len(read_strings(resource(lang)))}" for lang in LANGS)
    print(f"values/: {len(base)}   {counts}   "
          f"+finger: {len(read_strings(resource('', finger=True)))} keys per locale")

    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print(f"  ✗ {problem}")
        return 1
    print("✓ strings are in parity")
    return 0


if __name__ == "__main__":
    sys.exit(main())
