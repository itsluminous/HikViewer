#!/usr/bin/env python3
"""Generate the Android TV launcher banner (320 x 180 at xhdpi) and prove the text fits.

The banner is the tile Android TV's home screen shows for the app. It has to be exactly
320 x 180 px in `drawable-xhdpi` (Lint checks the size). Earlier versions drew "HikViewer" too
large and the right end of the word was cut off, so this script sizes the text from the
available width and asserts, in pixels, that every glyph lies inside the padding before writing.

Design: the app's near-black background, the launcher's lens glyph (ring + pupil) on the left,
the name to its right in a bold sans-serif, a small accent rule under the name.

Usage: scripts/gen-banner.py [--out app/src/main/res/drawable-xhdpi/banner.webp]
Needs Pillow (python3 -m pip install pillow) and cwebp (brew install webp).
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 320, 180
PADDING = 20  # px kept clear on every side
BACKGROUND = (0x10, 0x14, 0x18)
ACCENT = (0x4F, 0xC3, 0xF7)
FOREGROUND = (0xF2, 0xF4, 0xF7)
TEXT = "HikViewer"
FONT_CANDIDATES = [
    "/System/Library/Fonts/Supplemental/Arial Rounded Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    sys.exit("no bold TrueType font found; add one to FONT_CANDIDATES")


def render() -> tuple[Image.Image, tuple[int, int, int, int]]:
    scale = 4  # draw at 4x and downsample for clean edges
    w, h, pad = WIDTH * scale, HEIGHT * scale, PADDING * scale
    img = Image.new("RGB", (w, h), BACKGROUND)
    draw = ImageDraw.Draw(img)

    # Lens glyph: ring + pupil, 72 px tall at 1x, vertically centred.
    glyph = 72 * scale
    gx, gy = pad, (h - glyph) // 2
    ring = 6 * scale
    draw.ellipse([gx, gy, gx + glyph, gy + glyph], outline=ACCENT, width=ring)
    pupil = glyph * 0.42
    cx, cy = gx + glyph / 2, gy + glyph / 2
    draw.ellipse([cx - pupil / 2, cy - pupil / 2, cx + pupil / 2, cy + pupil / 2], fill=FOREGROUND)

    # The name: the largest size whose ink fits between the glyph and the right padding.
    text_left = gx + glyph + 12 * scale
    max_width = w - pad - text_left
    size = 60 * scale
    while size > 8 * scale:
        font = load_font(size)
        left, top, right, bottom = font.getbbox(TEXT)
        if right - left <= max_width and bottom - top <= h - 2 * pad:
            break
        size -= scale
    font = load_font(size)
    left, top, right, bottom = font.getbbox(TEXT)
    text_h = bottom - top
    ty = (h - text_h) // 2 - top - 6 * scale
    draw.text((text_left - left, ty), TEXT, font=font, fill=FOREGROUND)
    ink = (text_left, ty + top, text_left + (right - left), ty + bottom)

    # Accent rule under the name, as wide as the ink.
    rule_y = ink[3] + 8 * scale
    draw.rounded_rectangle([ink[0], rule_y, ink[2], rule_y + 4 * scale], radius=2 * scale, fill=ACCENT)

    out = img.resize((WIDTH, HEIGHT), Image.LANCZOS)
    bounds = tuple(round(v / scale) for v in ink)
    return out, bounds  # type: ignore[return-value]


def ink_bounds(img: Image.Image) -> tuple[int, int, int, int]:
    """Bounding box of every pixel that is not the background colour (glyph, text and rule)."""
    bg = Image.new("RGB", img.size, BACKGROUND)
    from PIL import ImageChops

    diff = ImageChops.difference(img, bg).convert("L").point(lambda v: 255 if v > 24 else 0)
    box = diff.getbbox()
    assert box is not None, "the banner is blank"
    return box


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", default="app/src/main/res/drawable-xhdpi/banner.webp")
    args = parser.parse_args()

    img, text = render()
    assert img.size == (WIDTH, HEIGHT), img.size
    ink = ink_bounds(img)
    print(f"text ink   : left={text[0]} top={text[1]} right={text[2]} bottom={text[3]}")
    print(f"all ink    : left={ink[0]} top={ink[1]} right={ink[2]} bottom={ink[3]}")
    assert ink[0] >= PADDING and ink[1] >= PADDING, f"ink starts inside the padding: {ink}"
    assert ink[2] <= WIDTH - PADDING and ink[3] <= HEIGHT - PADDING, f"ink runs past the padding: {ink}"
    assert text[2] <= WIDTH - PADDING, f"the name is cut off: {text}"

    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
        img.save(tmp.name, "PNG")
        png = tmp.name
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["cwebp", "-quiet", "-lossless", "-z", "9", png, "-o", str(out)], check=True)
    Path(png).unlink()
    print(f"wrote {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
