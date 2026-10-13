#!/usr/bin/env python3
"""Draws the Play feature graphic (1024x500, 24-bit, no alpha) from the launcher icon, the bundled IBM Plex Sans and the theme's colours, so it can be redrawn
instead of kept as an opaque file. It shows no screenshot and no claim beyond the listing's short description.

  tools/make-feature-graphic.py [output.png]      (default: play-listing/en-US/images/featureGraphic.png)

Needs Pillow (a build-time tool of this script only; nothing in the app or its Gradle build uses it).
"""
import os, sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "play-listing/en-US/images/featureGraphic.png")
W, H = 1024, 500
FIELD, GROUND, TITLE, TEXT = (0x29, 0x2E, 0x42), (0x16, 0x16, 0x1E), (0xE2, 0xE6, 0xFB), (0xC0, 0xCA, 0xF5)
font = lambda name, size: ImageFont.truetype(os.path.join(ROOT, "app/src/main/res/font", name), size)

img = Image.new("RGB", (W, H))
px = img.load()
for y in range(H):  # the launcher background's vertical gradient: field down to ground
    t = y / (H - 1)
    row = tuple(round(FIELD[i] + (GROUND[i] - FIELD[i]) * t) for i in range(3))
    for x in range(W): px[x, y] = row

icon = Image.open(os.path.join(ROOT, "fastlane/metadata/android/en-US/images/icon.png")).convert("RGBA").resize((300, 300), Image.LANCZOS)
mask = Image.new("L", (300, 300), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, 299, 299), radius=66, fill=255)
img.paste(icon, (92, 100), mask)

d = ImageDraw.Draw(img)
d.text((440, 206), "Paddock", font=font("ibm_plex_sans_semibold.ttf", 92), fill=TITLE, anchor="ls")
for i, line in enumerate(("Watch your herdr agents", "from your phone.")):
    d.text((443, 290 + i * 46), line, font=font("ibm_plex_sans_regular.ttf", 36), fill=TEXT, anchor="ls")
os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.save(OUT, "PNG", optimize=True)
print(OUT, img.size, img.mode)
