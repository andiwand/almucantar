#!/usr/bin/env python3
"""The images of the Google Play listings: the icon (512 x 512) and the feature graphic (1024 x 500).

Usage:
  listing.py            write the images of all apps
  listing.py sonnseite  write the images of one app

The script renders the logo of the app (assets/<app>-logo.svg) with rsvg-convert. Google Play
rounds the corners of the icon itself, so the icon has square corners. The feature graphic has
the logo, the name, and the line of the website on the colors of the logo. The images go to
android/fastlane/metadata/<app>/en-US/images/, where supply finds them.
"""

import re
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1].parent
FONT = ROOT / "screenshots" / "frames" / "Nunito[wght].ttf"
METADATA = ROOT / "android" / "fastlane" / "metadata"

# The name, the line of the website, and the colors of the background (top, bottom) of each app.
# The colors are the gradient of the logo.
APPS = {
    "sonnseite": ("Sonnseite", "See when the sun is where.", ("#16325a", "#0b1a30")),
    "sternstunde": ("Sternstunde", "The night sky over the real horizon.", ("#0d1430", "#050916")),
    "alidade": ("Alidade", "Plan the shot.", ("#2a1f3d", "#8a4a55")),
}

FEATURE = (1024, 500)
ICON = 512


def render_logo(app, size, square):
    """The logo as an RGBA image. With square, the logo has no rounded corners."""
    svg = (ROOT / "assets" / f"{app}-logo.svg").read_text()
    if square:
        # The background of the logo (or its clip path) is the only rect with rx="112".
        svg = re.sub(r'(<rect width="512" height="512") rx="112"', r"\1", svg)
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "logo.svg"
        target = Path(tmp) / "logo.png"
        source.write_text(svg)
        subprocess.run(
            ["rsvg-convert", "-w", str(size), "-h", str(size), "-o", str(target), str(source)],
            check=True,
        )
        return Image.open(target).convert("RGBA")


def font(size, weight):
    face = ImageFont.truetype(str(FONT), size)
    face.set_variation_by_name(weight)
    return face


def gradient(size, top, bottom):
    width, height = size
    image = Image.new("RGB", size)
    draw = ImageDraw.Draw(image)
    a = tuple(int(top[i : i + 2], 16) for i in (1, 3, 5))
    b = tuple(int(bottom[i : i + 2], 16) for i in (1, 3, 5))
    for y in range(height):
        t = y / (height - 1)
        draw.line([(0, y), (width, y)], fill=tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)))
    return image


def feature_graphic(app):
    name, line, (top, bottom) = APPS[app]
    width, height = FEATURE
    image = gradient(FEATURE, top, bottom)
    logo_size = 300
    logo = render_logo(app, logo_size, square=False)
    left = 90
    image.paste(logo, (left, (height - logo_size) // 2), logo)

    draw = ImageDraw.Draw(image)
    text_left = left + logo_size + 60
    text_width = width - text_left - 60
    # The largest sizes that fit, so a long name or line stays inside the image.
    name_size = 96
    while draw.textlength(name, font=font(name_size, "Bold")) > text_width:
        name_size -= 2
    line_size = 40
    while draw.textlength(line, font=font(line_size, "Regular")) > text_width:
        line_size -= 1
    gap = 18
    block = name_size + gap + line_size
    y = (height - block) // 2
    draw.text((text_left, y), name, font=font(name_size, "Bold"), fill="white", anchor="lt")
    draw.text(
        (text_left, y + name_size + gap),
        line,
        font=font(line_size, "Regular"),
        fill=(255, 255, 255, 220),
        anchor="lt",
    )
    return image


def main(argv):
    apps = argv[1:] or list(APPS)
    for app in apps:
        if app not in APPS:
            sys.exit(f"unknown app {app}; the apps are {', '.join(APPS)}")
        images = METADATA / app / "en-US" / "images"
        images.mkdir(parents=True, exist_ok=True)
        # Google Play wants a 32-bit PNG for the icon and no transparency in the feature graphic.
        render_logo(app, ICON, square=True).save(images / "icon.png")
        feature_graphic(app).save(images / "featureGraphic.png")
        print(f"wrote {images / 'icon.png'} and {images / 'featureGraphic.png'}")


if __name__ == "__main__":
    main(sys.argv)
