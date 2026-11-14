#!/usr/bin/env python3
"""Draw the updater's launcher icon and TV banner — original artwork, no third-party assets.

Plezy is GPL-3.0, which covers its code, not its logo or name; reusing its icon would also
imply an affiliation that does not exist. So the updater gets its own mark, drawn here from
scratch in the app's own accent palette (see ``docs/10-tv-ui-design.md``):

    a bold white "up + tray" glyph (an update landing) on a rounded tile that runs from the
    accent blue to a deep slate, with a faint inner highlight.

Everything is rasterised at 4x and downsampled, so the edges stay clean at 48 px.

    python3 design/app_icon.py            # writes the PNGs under app/res/
    python3 design/app_icon.py --preview  # also dumps an ASCII proof of the banner

Requires Pillow. The generated PNGs are committed, so building the APK does not need Python.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "res")

FONT_BOLD = "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf"

# The palette (docs/10): accent blue -> deep slate, plus the accent tints used in-app.
TILE_TOP = (74, 147, 184)      # #4A93B8
TILE_BOTTOM = (22, 48, 62)     # #16303E
MARK = (244, 249, 252)         # near-white
BANNER_BG = (20, 31, 42)       # #141F2A
BANNER_ACCENT = (110, 168, 200)  # #6EA8C8, the section-heading tint
BANNER_TEXT = (232, 241, 248)

SS = 4                          # supersampling factor
ICON_DP = 48                    # launcher icon size at mdpi
BANNER_DP = (160, 90)           # TV banner at mdpi; 320x180 at xhdpi

DENSITIES = {                   # qualifier -> scale (mdpi is 1x)
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def _gradient(w, h, top, bottom):
    """A diagonal gradient from ``top`` (top-left) to ``bottom`` (bottom-right)."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x + y) / max(1, (w - 1) + (h - 1))
            px[x, y] = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return img


def _vgradient(w, h, top, bottom):
    """A vertical gradient from ``top`` to ``bottom``."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        row = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = row
    return img


def draw_mark(d, size, color, lw, cx=0.5):
    """The up-arrow-over-a-tray glyph, in normalised coordinates scaled to ``size``."""
    S = size
    r = lw / 2
    def L(x1, y1, x2, y2):
        d.line([(x1 * S, y1 * S), (x2 * S, y2 * S)], fill=color, width=round(lw))
        for (x, y) in ((x1, y1), (x2, y2)):          # round caps
            d.ellipse([x * S - r, y * S - r, x * S + r, y * S + r], fill=color)
    # shaft + chevron head + tray
    L(cx, 0.685, cx, 0.275)
    L(cx - 0.225, 0.455, cx, 0.275)
    L(cx + 0.225, 0.455, cx, 0.275)
    L(cx - 0.225, 0.795, cx + 0.225, 0.795)


def app_icon(px):
    """One square launcher icon at ``px`` device pixels."""
    S = px * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    tile = _gradient(S, S, TILE_TOP, TILE_BOTTOM).convert("RGBA")
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=round(0.225 * S), fill=255)
    img.paste(tile, (0, 0), mask)

    d = ImageDraw.Draw(img)
    # A faint inner highlight just inside the edge gives the tile a little depth.
    inset = round(0.035 * S)
    d.rounded_rectangle([inset, inset, S - 1 - inset, S - 1 - inset],
                        radius=round(0.19 * S), outline=(255, 255, 255, 46),
                        width=max(1, round(0.012 * S)))

    draw_mark(d, S, MARK, lw=0.088 * S)
    return img.resize((px, px), Image.LANCZOS)


def tv_banner(px_w, px_h):
    """One 16:9 TV banner: the app tile on the left, the name stacked to its right.

    The tile + wordmark are measured and the whole group is centred, so the banner reads the
    same whether the device picks the mdpi or xxhdpi copy. No tagline: at TV distance the name
    is the message.
    """
    W, H = px_w * SS, px_h * SS
    img = Image.new("RGB", (W, H), BANNER_BG)

    # A soft vertical lift so the banner is not a flat block.
    img.paste(_vgradient(W, H, (28, 43, 58), (14, 22, 31)), (0, 0))

    tile = round(0.40 * H)
    font = ImageFont.truetype(FONT_BOLD, round(0.205 * H))
    line1, line2 = "Plezy", "Updater"
    text_w = max(font.getlength(line1), font.getlength(line2))
    gap = round(0.06 * H)

    x0 = round((W - (tile + gap + text_w)) / 2)
    icon = app_icon(tile)
    img.paste(icon, (x0, (H - tile) // 2), icon)

    d = ImageDraw.Draw(img)
    spacing = round(0.245 * H)
    tx = round(x0 + tile + gap)
    d.text((tx, round(H / 2 - spacing / 2)), line1, font=font, fill=BANNER_TEXT, anchor="lm")
    d.text((tx, round(H / 2 + spacing / 2)), line2, font=font, fill=BANNER_ACCENT, anchor="lm")

    return img.resize((px_w, px_h), Image.LANCZOS)


def write(path, image):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path)
    return path


def ascii_proof(image, cols=110):
    """Print a banner/icon as characters, so a human (or an agent without image input) can see it."""
    small = image.convert("L").resize((cols, max(1, round(cols * image.height / image.width * 0.5))))
    ramp = " .:-=+*#%@"
    for row in range(small.height):
        print("  " + "".join(ramp[min(9, small.getpixel((c, row)) * 10 // 256)] for c in range(cols)))


def main():
    made = []
    for qual, scale in DENSITIES.items():
        px = round(ICON_DP * scale)
        made.append(write(os.path.join(RES, "mipmap-" + qual, "ic_launcher.png"), app_icon(px)))
    for qual, scale in DENSITIES.items():
        w = round(BANNER_DP[0] * scale)
        h = round(BANNER_DP[1] * scale)
        made.append(write(os.path.join(RES, "drawable-" + qual, "tv_banner.png"), tv_banner(w, h)))
    for p in made:
        print("wrote", os.path.relpath(p, ROOT))

    if "--preview" in sys.argv:
        print("\n== ic_launcher (192 px) ==")
        ascii_proof(app_icon(192), cols=48)
        print("\n== tv_banner (320x180) ==")
        ascii_proof(tv_banner(320, 180), cols=110)


if __name__ == "__main__":
    main()
