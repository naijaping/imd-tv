#!/usr/bin/env python3
"""Renders the app icon, TV banner and in-app logo from art/logo-a.png (needs Pillow).

The source's rounded tile is semi-transparent (alpha ~187), so its grey depends on what is behind it; here it is made
solid as it looks on white, with the corners outside the tile kept transparent.
Run from anywhere: python3 art/brand.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app/src/main/res")
src = Image.open(os.path.join(ROOT, "art/logo-a.png")).convert("RGBA")

# The tile: the opaque-ish rounded square (alpha > 128), cropped with its anti-aliased edge.
box = src.split()[3].point(lambda v: 255 if v > 128 else 0).getbbox()
tile = src.crop(box)
flat = Image.new("RGBA", tile.size, (255, 255, 255, 255))
flat.alpha_composite(tile)
a = tile.split()[3].point(lambda v: min(255, round(v * 255 / 187)))  # inside 187 → 255; the edge stays soft
flat.putalpha(a)
tile = flat


def fit(img, size, margin):
    """[img] centred on a transparent [size] square, [margin] of it left free on each side."""
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = round(size * (1 - 2 * margin))
    out.alpha_composite(img.resize((inner, inner), Image.LANCZOS), ((size - inner) // 2, (size - inner) // 2))
    return out


# Launcher icons (legacy, square): the tile with a small margin, as launchers expect.
for d, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    fit(tile, px, .04).save(os.path.join(RES, f"mipmap-{d}/ic_launcher.png"), optimize=True)

# In-app logo (guide top left, category panel, Settings): the tile at its own resolution; nodpi, scaled down sharply.
tile.save(os.path.join(RES, "drawable-nodpi/logo.png"), optimize=True)

# Android TV banner: 320×180dp at xhdpi. The tile on the left, "IMD TV" beside it, on charcoal like the tile.
W, H = 640, 360
banner = Image.new("RGBA", (W, H))
top, bottom = (40, 44, 52), (16, 18, 23)
d = ImageDraw.Draw(banner)
for y in range(H):
    t = y / (H - 1)
    d.line([(0, y), (W, y)], fill=tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
s = 210
font = ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-Bold.otf", 84)
gap = 36
text_w = d.textlength("IMD TV", font=font)
x0 = round((W - (s + gap + text_w)) / 2)  # tile and name centred as one group
banner.alpha_composite(tile.resize((s, s), Image.LANCZOS), (x0, (H - s) // 2))
x, y = x0 + s + gap, H // 2
d.text((x, y), "IMD", font=font, fill=(255, 255, 255), anchor="lm")
d.text((x + d.textlength("IMD ", font=font), y), "TV", font=font, fill=(46, 170, 112), anchor="lm")
banner.convert("RGB").save(os.path.join(RES, "drawable-xhdpi/banner.png"), optimize=True)
print("tile", tile.size, "from", box)
