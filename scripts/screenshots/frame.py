#!/usr/bin/env python3
"""Draws the store images from the raw captures.

Each image has a gradient background, the capture in a drawn device body, a headline of two
lines, and a dashed path of the sun. The path goes across the whole gallery: each image shows the
part of one large arc, so the gallery reads as one day from sunrise to the moon at night. The
device geometry comes from OpenDocument.droid and OpenDocument.ios.

Usage:
  frame.py --platform android|ios [--captured DIR] [--framed DIR] [--locale en-US]

See screenshots/README.md.
"""

import argparse
import bisect
import functools
import json
import math
import shutil
import sys
from pathlib import Path

import store

try:
    from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont
except ImportError:
    sys.exit("this needs Pillow: .venv/bin/pip install -r scripts/requirements-dev.txt")

FRAMES = store.ROOT / "screenshots" / "frames"

# All values are fractions: a position down the image is a fraction of the height, and a size or
# a position across is a fraction of the width. So one design fits all canvas sizes. The device is
# in the middle; `screen_width` is its largest width, and `foot` is the ground below it.
LAYOUT = {
    # A Pixel Pro: thin aluminium frame, a nearly circular screen corner, a camera hole, and the
    # keys on the right edge.
    "phone": {
        "headline_top": 0.045,
        "headline_size": 0.064,
        "headline_width": 0.86,
        "headline_leading": 1.08,
        "screen_top": 0.205,
        "screen_width": 0.640,
        "foot": 0.040,
        "bezel": 0.0268,
        "rim": 0.42,
        "corner": 0.122,
        "corner_easing": 2.2,
        "hole": 0.046,
        "hole_top": 0.028,
        "buttons": [],
        "buttons_right": [(0.175, 0.055), (0.250, 0.105)],
        "arc_peak": 0.165,
        "arc_base": 0.97,
        "dash_stroke": 0.0060,
        "dash_on": 0.0240,
        "dash_off": 0.0110,
        "body_radius": 0.030,
    },
    # A Pixel Tablet: a wide black border with a thin bright edge, and no keys in portrait.
    "tablet": {
        "headline_top": 0.055,
        "headline_size": 0.056,
        "headline_width": 0.80,
        "headline_leading": 1.08,
        "screen_top": 0.230,
        "screen_width": 0.760,
        "foot": 0.040,
        "bezel": 0.0470,
        "rim": 0.86,
        "corner": 0.030,
        "corner_easing": 2.2,
        "buttons": [],
        "arc_peak": 0.180,
        "arc_base": 0.97,
        "dash_stroke": 0.0045,
        "dash_on": 0.0170,
        "dash_off": 0.0075,
        "body_radius": 0.022,
    },
    # An iPhone Pro Max: a continuous corner, the Dynamic Island, and keys on both edges.
    "iphone": {
        "headline_top": 0.060,
        "headline_size": 0.072,
        "headline_width": 0.86,
        "headline_leading": 1.08,
        "screen_top": 0.235,
        "screen_width": 0.660,
        "foot": 0.045,
        "bezel": 0.0272,
        "rim": 0.57,
        "corner": 0.141,
        "corner_easing": 5.0,
        "island": (0.2826, 0.0811),
        "island_top": 0.0334,
        "buttons": [(0.189, 0.0423), (0.262, 0.0686), (0.349, 0.0686)],
        "buttons_right": [(0.286, 0.1082)],
        "arc_peak": 0.195,
        "arc_base": 0.97,
        "dash_stroke": 0.0060,
        "dash_on": 0.0240,
        "dash_off": 0.0110,
        "body_radius": 0.030,
    },
    # An iPad Pro: a uniform black border and a small corner.
    "ipad": {
        "headline_top": 0.060,
        "headline_size": 0.054,
        "headline_width": 0.80,
        "headline_leading": 1.08,
        "screen_top": 0.235,
        "screen_width": 0.760,
        "foot": 0.050,
        "bezel": 0.0350,
        "rim": 0.86,
        "corner": 0.0174,
        "corner_easing": 5.0,
        "buttons": [],
        "arc_peak": 0.190,
        "arc_base": 0.97,
        "dash_stroke": 0.0045,
        "dash_on": 0.0170,
        "dash_off": 0.0075,
        "body_radius": 0.022,
    },
}

# Which layout a device of store.DEVICES uses.
DEVICE_LAYOUT = {
    "phone": "phone",
    "tablet": "tablet",
    "iphone": "iphone",
    "ipad": "ipad",
}

# The size of the Android store image. iOS keeps the size of the capture.
CANVASES = {"phone": (1440, 2560), "tablet": (1600, 2560)}

BODY = "#08080a"
ALUMINIUM = (1.035, 1.0, 0.955)
BUTTON = ("#d8d8d6", "#a9a9a7", "#c4c4c2")
GLASS = 96
SHADOW = (0, 0, 0, 105)
SHADOW_OFFSET = (0.30, 0.65)
SHADOW_BLUR = 0.85
SUN = (255, 214, 102)
MOON = (232, 238, 250)


@functools.lru_cache(maxsize=None)
def design():
    text = json.loads((FRAMES / "frames.json").read_text())
    text.pop("_comment", None)
    return text


@functools.lru_cache(maxsize=None)
def font(size, weight):
    """Nunito at one weight, from its variable font file."""
    face = ImageFont.truetype(str(FRAMES / design()["font"]), size)
    face.set_variation_by_name(weight)
    return face


def gradient(size, top, bottom):
    width, height = size
    strip = Image.new("RGB", (1, height))
    start = Image.new("RGB", (1, 1), top).getpixel((0, 0))
    end = Image.new("RGB", (1, 1), bottom).getpixel((0, 0))
    for y in range(height):
        share = y / max(1, height - 1)
        strip.putpixel(
            (0, y),
            tuple(round(start[i] + (end[i] - start[i]) * share) for i in range(3)),
        )
    return strip.resize((width, height)).convert("RGBA")


def dashed(canvas, points, stroke, on, off, colour=(255, 255, 255, 220), phase=0.0):
    """Dashes along a polyline. The phase continues the pattern of the image before."""
    reached = [0.0]
    for before, after in zip(points, points[1:]):
        reached.append(
            reached[-1] + math.hypot(after[0] - before[0], after[1] - before[1])
        )
    total = reached[-1]
    if not total:
        return

    def at(distance):
        index = max(1, min(len(reached) - 1, bisect.bisect_left(reached, distance)))
        span = reached[index] - reached[index - 1]
        share = 0.0 if not span else (distance - reached[index - 1]) / span
        before, after = points[index - 1], points[index]
        return (
            before[0] + (after[0] - before[0]) * share,
            before[1] + (after[1] - before[1]) * share,
        )

    draw = ImageDraw.Draw(canvas)
    width = max(1, round(stroke))
    period = on + off
    phase = phase % period
    for number in range(int((total + phase) // period) + 2):
        start = number * period - phase
        end = min(start + on, total)
        if start >= total:
            break
        start = max(start, 0.0)
        if start >= end:
            continue
        run = [at(start)]
        run += [point for point, so_far in zip(points, reached) if start < so_far < end]
        run.append(at(end))
        draw.line(run, fill=colour, width=width, joint="curve")


def squircle(box, radius, exponent=2.2, per_corner=40):
    """A rounded rectangle with superellipse corners: about 2 for a Pixel, 5 for an iPhone."""
    x0, y0, x1, y1 = box
    r = min(radius, (x1 - x0) / 2, (y1 - y0) / 2)
    points = []
    for (cx, cy), sx, sy in (
        ((x1 - r, y1 - r), 1, 1),
        ((x0 + r, y1 - r), -1, 1),
        ((x0 + r, y0 + r), -1, -1),
        ((x1 - r, y0 + r), 1, -1),
    ):
        for step in range(per_corner + 1):
            share = step / per_corner if sx * sy > 0 else 1 - step / per_corner
            angle = math.pi / 2 * share
            points.append(
                (
                    cx + sx * r * math.cos(angle) ** (2 / exponent),
                    cy + sy * r * math.sin(angle) ** (2 / exponent),
                )
            )
    return points


def outset(points, distance):
    """The same outline, moved out along its normals, so a border has the same width everywhere."""
    pairs = list(zip(points, points[1:] + points[:1]))
    facing = 1.0 if sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in pairs) > 0 else -1.0
    moved = []
    for index, (x, y) in enumerate(points):
        (ax, ay), (bx, by) = points[index - 1], points[(index + 1) % len(points)]
        run, rise = bx - ax, by - ay
        length = math.hypot(run, rise) or 1.0
        moved.append(
            (
                x + facing * rise / length * distance,
                y - facing * run / length * distance,
            )
        )
    return moved


def stencil(size, points, supersample=3):
    """An antialiased mask of one shape."""
    big = Image.new("L", (size[0] * supersample, size[1] * supersample), 0)
    ImageDraw.Draw(big).polygon(
        [(x * supersample, y * supersample) for x, y in points], fill=255
    )
    return big.resize(size, Image.LANCZOS)


def chamfer(share):
    """The colour of the polished metal frame at a share of the band, from the outer edge in."""
    stops = (
        (0.00, 150),
        (0.10, 240),
        (0.22, 208),
        (0.42, 138),
        (0.66, 192),
        (0.85, 164),
        (1.00, 96),
    )
    place = bisect.bisect_right([at for at, _ in stops], share)
    if place == 0:
        level = stops[0][1]
    elif place == len(stops):
        level = stops[-1][1]
    else:
        (before, low), (after, high) = stops[place - 1], stops[place]
        level = low + (high - low) * (share - before) / (after - before)
    return tuple(min(255, round(level * warm)) for warm in ALUMINIUM)


def brushed(size, colours):
    width, height = size
    strip = Image.new("RGB", (len(colours), 1))
    for index, colour in enumerate(colours):
        strip.putpixel((index, 0), Image.new("RGB", (1, 1), colour).getpixel((0, 0)))
    return strip.resize((width, height), Image.BICUBIC)


def device_body(canvas, shot, layout):
    """The capture behind glass, in a drawn metal body, in the middle of the canvas."""
    width, height = canvas.size
    bezel = layout["bezel"] * width
    rim = bezel * layout["rim"]
    top = layout["screen_top"] * height

    # Two limits: the largest width, and the ground below the device.
    standing = (height - layout["foot"] * height) - top - bezel
    screen_width = min(
        layout["screen_width"] * width, standing * shot.width / shot.height
    )
    screen_height = screen_width * shot.height / shot.width
    left = (width - screen_width) / 2

    screen = (left, top, left + screen_width, top + screen_height)
    corner = layout["corner"] * screen_width
    body = (screen[0] - bezel, screen[1] - bezel, screen[2] + bezel, screen[3] + bezel)

    margin = round(bezel * 3)
    origin = (round(body[0]) - margin, round(body[1]) - margin)
    size = (round(body[2]) - origin[0] + margin, round(body[3]) - origin[1] + margin)

    def here(box):
        return tuple(v - origin[i % 2] for i, v in enumerate(box))

    device = Image.new("RGBA", size, (0, 0, 0, 0))

    # The keys first, so the edge of the body covers where they meet it.
    stand = screen_width * 0.0061
    tall_as = body[3] - body[1]
    keys = Image.new("L", size, 0)
    for edge, side in (
        (here(body)[0], layout["buttons"]),
        (here(body)[2], layout.get("buttons_right", [])),
    ):
        marks = Image.new("L", size, 0)
        draw = ImageDraw.Draw(marks)
        for at_height, tall in side:
            y = here(body)[1] + tall_as * at_height
            draw.rounded_rectangle(
                (edge - stand, y, edge + stand, y + tall_as * tall),
                radius=stand * 0.55,
                fill=255,
            )
        keys = ImageChops.lighter(keys, marks)
    device.paste(brushed(size, BUTTON), (0, 0), keys)

    face = squircle(here(screen), corner, layout["corner_easing"])
    outline = outset(face, bezel)

    band = bezel - rim
    lit = Image.new("RGB", size, chamfer(1.0))
    rings = ImageDraw.Draw(lit)
    steps = max(8, round(band))
    for step in range(steps + 1):
        share = step / steps
        rings.polygon(outset(face, bezel - band * share), fill=chamfer(share))
    device.paste(lit, (0, 0), stencil(size, outline))
    device.paste(Image.new("RGB", size, BODY), (0, 0), stencil(size, outset(face, rim)))

    fitted = shot.resize(
        (round(screen_width), round(screen_height)), Image.LANCZOS
    ).convert("RGBA")
    inside = here(screen)
    device.paste(
        fitted,
        (round(inside[0]), round(inside[1])),
        stencil(size, face).crop(
            (
                round(inside[0]),
                round(inside[1]),
                round(inside[0]) + fitted.width,
                round(inside[1]) + fitted.height,
            )
        ),
    )

    # The hairline where the glass meets the black border.
    hair = max(1.0, screen_width * 0.0012)
    halo = ImageChops.subtract(
        stencil(size, face), stencil(size, outset(face, -hair))
    ).point(lambda level: level * GLASS // 255)
    device.paste(Image.new("RGB", size, "white"), (0, 0), halo)

    middle = (inside[0] + inside[2]) / 2
    if layout.get("hole"):
        across = layout["hole"] * screen_width
        hole_top = inside[1] + layout["hole_top"] * screen_width
        ImageDraw.Draw(device).ellipse(
            (middle - across / 2, hole_top, middle + across / 2, hole_top + across),
            fill=BODY,
        )
    if layout.get("island"):
        island_width, island_height = (
            share * screen_width for share in layout["island"]
        )
        island_top = inside[1] + layout["island_top"] * screen_width
        ImageDraw.Draw(device).rounded_rectangle(
            (
                middle - island_width / 2,
                island_top,
                middle + island_width / 2,
                island_top + island_height,
            ),
            radius=island_height / 2,
            fill=BODY,
        )

    shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    shadow.paste(
        Image.new("RGB", size, SHADOW[:3]),
        (0, 0),
        stencil(size, outline).point(lambda v: v * SHADOW[3] // 255),
    )
    canvas.alpha_composite(
        shadow.filter(ImageFilter.GaussianBlur(bezel * SHADOW_BLUR)),
        (
            origin[0] + round(bezel * SHADOW_OFFSET[0]),
            origin[1] + round(bezel * SHADOW_OFFSET[1]),
        ),
    )
    canvas.alpha_composite(device, origin)


def headline(canvas, lines, layout):
    """Two lines, light over bold, in the middle, made smaller until they fit."""
    width, height = canvas.size
    size = round(layout["headline_size"] * width)
    allowed = layout["headline_width"] * width
    draw = ImageDraw.Draw(canvas)
    faces = [font(size, "Regular"), font(size, "Bold")]
    while (
        size > 8
        and max(draw.textlength(line, font=face) for line, face in zip(lines, faces))
        > allowed
    ):
        size -= 2
        faces = [font(size, "Regular"), font(size, "Bold")]
    y = layout["headline_top"] * height
    for line, face in zip(lines, faces):
        draw.text((width / 2, y), line, font=face, fill="white", anchor="ma")
        y += size * layout["headline_leading"]


def arc(layout, size, order, count, samples=240):
    """The part of the gallery arc in image `order` of `count`, as points in this image.

    The arc is a parabola across all images together: low at the outer edges of the first and the
    last image, and highest between the two middle images, in the band below the headline.
    """
    width, height = size
    gallery = width * count
    base, peak = layout["arc_base"] * height, layout["arc_peak"] * height
    points = []
    for step in range(samples + 1):
        x = order * width + width * step / samples
        u = (x - gallery / 2) / (gallery / 2)
        points.append((x - order * width, base - (base - peak) * (1 - u * u)))
    return points


def arc_length(points):
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(points, points[1:]))


def body(canvas, at, radius, colour, glow):
    """The sun or the moon on the arc, with a soft glow."""
    x, y = at
    halo = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(halo).ellipse(
        (x - radius * 2.6, y - radius * 2.6, x + radius * 2.6, y + radius * 2.6),
        fill=glow,
    )
    canvas.alpha_composite(halo.filter(ImageFilter.GaussianBlur(radius * 1.2)))
    ImageDraw.Draw(canvas).ellipse(
        (x - radius, y - radius, x + radius, y + radius), fill=colour
    )


def frame(shot, device, screen, locale, order, count):
    layout = LAYOUT[DEVICE_LAYOUT[device]]
    size = CANVASES.get(device, shot.size)
    width, _ = size
    spec = design()
    canvas = gradient(size, *spec["backgrounds"][screen["background"]])

    stroke = layout["dash_stroke"] * width
    on, off = layout["dash_on"] * width, layout["dash_off"] * width
    before = sum(arc_length(arc(layout, size, index, count)) for index in range(order))
    points = arc(layout, size, order, count)
    dashed(canvas, points, stroke, on, off, phase=before)

    # The sun rises in the first image, and the moon is up in the last one.
    radius = layout["body_radius"] * width
    if order == 0:
        body(canvas, points[len(points) * 8 // 100], radius, SUN, (255, 200, 80, 150))
    if order == count - 1:
        body(
            canvas,
            points[len(points) * 92 // 100],
            radius * 0.8,
            MOON,
            (210, 225, 255, 110),
        )

    device_body(canvas, shot, layout)
    headline(canvas, screen["headline"][locale], layout)
    return canvas.convert("RGB")


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--platform", choices=sorted(store.DEVICES), required=True)
    parser.add_argument(
        "--captured",
        type=Path,
        help="the raw captures (default: <app>/fastlane/screenshots)",
    )
    parser.add_argument(
        "--framed", type=Path, help="the output (default: <app>/fastlane/framed)"
    )
    parser.add_argument(
        "--locale", action="append", help="frame only this locale (repeatable)"
    )
    args = parser.parse_args(argv)

    captured = (
        args.captured or store.app_dir(args.platform) / "fastlane" / "screenshots"
    )
    framed = args.framed or store.app_dir(args.platform) / "fastlane" / "framed"
    screens = {screen["name"]: screen for screen in design()["screens"]}
    order = [name for name in store.SCREENS if name in screens]
    missing = [name for name in store.SCREENS if name not in screens]
    if missing:
        sys.exit(f"frames.json has no design for {', '.join(missing)}")

    made = 0
    for locale in args.locale or store.LOCALES:
        source = captured / locale
        if not source.is_dir():
            print(f"no captures for {locale} in {source}")
            continue
        target = framed / locale
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
        for path in sorted(source.glob("*.png")):
            name = store.screen_of(path)
            if name is None:
                continue
            shot = Image.open(path).convert("RGB")
            device = device_from_capture(args.platform, path, shot)
            framed_image = frame(
                shot, device, screens[name], locale, order.index(name), len(order)
            )
            framed_image.save(target / path.name, optimize=True)
            made += 1
    if not made:
        sys.exit(f"found no captures in {captured}")
    print(f"framed {made} {args.platform} screenshots in {framed}")


def device_from_capture(platform, path, shot):
    """The device of a raw capture: Android names it, iOS has the size of the simulator."""
    if platform == "android":
        device = path.stem.split("-", 1)[0]
        if device not in store.DEVICES["android"]:
            sys.exit(f"{path.name}: the name does not start with phone- or tablet-")
        return device
    return "ipad" if shot.width / shot.height > 0.6 else "iphone"


if __name__ == "__main__":
    main()
