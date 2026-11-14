# design — where the artwork comes from

Everything the app draws is **original**: the launcher icon, the TV banner and the in-app
glyphs were made for this project. Nothing is copied from Plezy, from an icon pack, or from
AndroidX.

That matters because Plezy is **GPL-3.0**. The GPL covers its *code*; it does not grant any
right to its **logo or name**, and reusing Plezy's icon would also imply an affiliation with
Plezy that does not exist. So the updater has its own mark and its own visual language.

## What is in the app

| Asset | Where | Notes |
|---|---|---|
| Launcher icon | `app/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 48 / 72 / 96 / 144 / 192 px |
| TV banner | `app/res/drawable-{m,h,xh,xxh,xxxh}dpi/tv_banner.png` | 160×90 … 640×360 (320×180 at xhdpi, the Fire TV size) |
| In-app glyphs | `app/res/drawable/ic_*.xml` | hand-written `<vector>` drawables, 24 dp |

The palette is the one in [docs/10](../docs/10-tv-ui-design.md): accent blue `#3F86AD` →
deep slate `#16303E` for the tile, `#6EA8C8` for the wordmark.

## Regenerating

The PNGs are committed, so **building the APK needs neither Python nor librsvg**. These
scripts are only for changing the artwork.

```bash
python3 design/app_icon.py --preview    # redraw the icon + banner, print an ASCII proof
python3 design/preview_vectors.py       # rasterise app/res/drawable/ic_*.xml to a contact sheet
```

- `app_icon.py` draws the tile and the banner procedurally with Pillow, supersampled 4×.
- `preview_vectors.py` reads the **same** `ic_*.xml` files the app ships and renders them with
  `rsvg-convert`, so there is no second copy of the path data to keep in sync. The Android
  vector XML is the single source of truth for the glyphs.

`preview_vectors.py` needs `rsvg-convert` (librsvg). Neither script is part of `app/build.sh`.
