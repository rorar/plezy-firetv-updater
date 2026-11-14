#!/usr/bin/env python3
"""Render the bundled Android vector drawables to a PNG contact sheet.

The vector XML under ``app/res/drawable/ic_*.xml`` is the single source of truth for the
main-screen glyphs; this script only exists so a human can *look* at them without a device.
It translates each ``<vector>`` into an SVG (the two formats share the same path syntax) and
lets ``rsvg-convert`` rasterise the sheet.

    python3 design/preview_vectors.py [out.png]

Requires ``rsvg-convert`` (librsvg). Nothing here is needed to build the app.
"""
import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLE = os.path.join(ROOT, "app", "res", "drawable")

CELL = 132         # px per cell in the sheet
COLS = 6
BG = "#101820"     # the app background, so the glyphs are judged in context
INK = "#E2E9F0"
LABEL = 18         # px reserved under each glyph for its file name


def to_svg(vector_path, size, x, y):
    """One <vector> document -> a nested <svg> element at ``size`` px, placed at x,y."""
    root = ET.parse(vector_path).getroot()
    vw = float(root.get(ANDROID + "viewportWidth", 24))
    vh = float(root.get(ANDROID + "viewportHeight", 24))
    out = ['<svg x="%d" y="%d" width="%d" height="%d" viewBox="0 0 %g %g">'
           % (x, y, size, size, vw, vh)]
    for path in root.findall("path"):
        fill = path.get(ANDROID + "fillColor") or "none"
        if fill.upper() in ("#00000000", "NONE"):
            fill = "none"
        out.append('<path d="%s" fill="%s"' % (path.get(ANDROID + "pathData", ""), fill))
        stroke = path.get(ANDROID + "strokeColor")
        if stroke:
            out.append(' stroke="%s"' % stroke)
        width = path.get(ANDROID + "strokeWidth")
        if width:
            out.append(' stroke-width="%s"' % width)
        cap = path.get(ANDROID + "strokeLineCap")
        if cap:
            out.append(' stroke-linecap="%s"' % cap)
        join = path.get(ANDROID + "strokeLineJoin")
        if join:
            out.append(' stroke-linejoin="%s"' % join)
        out.append("/>")
    out.append("</svg>")
    return "".join(out)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/opencode/icons-preview.png"
    files = sorted(glob.glob(os.path.join(DRAWABLE, "ic_*.xml")))
    if not files:
        sys.exit("no ic_*.xml found under %s" % DRAWABLE)

    rows = (len(files) + COLS - 1) // COLS
    w, h = COLS * CELL, rows * CELL
    sheet = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
             'viewBox="0 0 %d %d">' % (w, h, w, h)]
    sheet.append('<rect width="%d" height="%d" fill="%s"/>' % (w, h, BG))
    for i, f in enumerate(files):
        cx, cy = (i % COLS) * CELL, (i // COLS) * CELL
        pad = (CELL - LABEL) // 8
        glyph = CELL - LABEL - 2 * pad
        sheet.append(to_svg(f, glyph, cx + pad, cy + pad))
        sheet.append('<text x="%d" y="%d" font-family="DejaVu Sans" font-size="11" '
                     'fill="%s">%s</text>' % (cx + 6, cy + CELL - 5, INK,
                                              os.path.basename(f)[:-4]))
    sheet.append("</svg>")

    svg = "/tmp/opencode/icons-preview.svg"
    with open(svg, "w") as fh:
        fh.write("\n".join(sheet))
    subprocess.run(["rsvg-convert", "-o", out, svg], check=True)
    print("wrote", out, "(%d icons)" % len(files))


if __name__ == "__main__":
    main()
