#!/usr/bin/env python3
"""The locales, the screens, and the sizes of the store screenshots; checks and stages a set.

Usage:
  store.py --platform android|ios --languages
  store.py --platform android|ios --screenshots DIR                 check a framed set
  store.py --platform android|ios --screenshots DIR --stage OUT     check, then stage for fastlane

See screenshots/README.md.
"""

import argparse
import shutil
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# The app is in English only, so the stores get English pictures only.
LOCALES = ("en-US",)

# What one device shows, in the order of the store. The UI tests write the same names.
SCREENS = ("01-path", "02-date", "03-align", "04-moon")

# The store image of each device. Android has its own canvas, because Google Play refuses a side
# that is more than two times the other side. iOS keeps the size of the capture, because the App
# Store wants the size of the device.
DEVICES = {
    "android": {
        "phone": {(1440, 2560)},
        "tablet": {(1600, 2560)},
    },
    "ios": {
        "iphone": {(1320, 2868), (1290, 2796), (1284, 2778), (1242, 2688)},
        "ipad": {(2064, 2752), (2048, 2732)},
    },
}

# Where supply wants each Android device. The tablet set fills the 10" and the 7" slots.
PLAY_DIRECTORIES = {
    "phone": ("phoneScreenshots",),
    "tablet": ("tenInchScreenshots", "sevenInchScreenshots"),
}

# The limits of the stores for each device.
LIMITS = {"android": (2, 8), "ios": (1, 10)}


def app_dir(platform):
    return ROOT / platform


def png_size(path):
    """The pixel size of a PNG, from its header."""
    with path.open("rb") as file:
        header = file.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")
    return struct.unpack(">II", header[16:24])


def screen_of(path):
    """The screen name at the end of a file name, or None."""
    return next((screen for screen in SCREENS if path.stem.endswith(screen)), None)


def device_of(platform, path):
    """The device of a framed image: from its size, so the name of the simulator does not matter."""
    found = png_size(path)
    for device, sizes in DEVICES[platform].items():
        if found in sizes:
            return device
    return None


def collect(platform, folder):
    """The framed images as {locale: {device: {screen: path}}}, and the problems found."""
    problems = []
    found = {}
    for locale in LOCALES:
        directory = folder / locale
        if not directory.is_dir():
            problems.append(f"{directory} does not exist")
            continue
        for path in sorted(directory.glob("*.png")):
            screen = screen_of(path)
            if screen is None:
                problems.append(
                    f"{path.name}: not one of the screens {', '.join(SCREENS)}"
                )
                continue
            device = device_of(platform, path)
            if device is None:
                problems.append(
                    f"{path.name}: the size {png_size(path)} is not a store size of {platform}"
                )
                continue
            per_device = found.setdefault(locale, {}).setdefault(device, {})
            if screen in per_device:
                problems.append(f"{path.name}: a second {device} picture of {screen}")
            per_device[screen] = path
    return found, problems


def check(platform, folder):
    """Fails if a locale or a device misses a screen, or has a wrong size."""
    found, problems = collect(platform, folder)
    least, most = LIMITS[platform]
    for locale in LOCALES:
        devices = found.get(locale, {})
        for device in DEVICES[platform]:
            screens = devices.get(device, {})
            missing = [screen for screen in SCREENS if screen not in screens]
            if missing:
                problems.append(f"{locale} {device}: no {', '.join(missing)}")
            if not least <= len(screens) <= most:
                problems.append(
                    f"{locale} {device}: {len(screens)} pictures, the store wants {least} to {most}"
                )
    if problems:
        sys.exit("the screenshot set is not complete:\n  " + "\n  ".join(problems))
    return found


def stage(platform, found, out):
    """Copies a checked set into the layout of supply (Android) or deliver (iOS)."""
    if out.exists():
        shutil.rmtree(out)
    for locale, devices in found.items():
        for device, screens in devices.items():
            for index, screen in enumerate(SCREENS, start=1):
                source = screens[screen]
                if platform == "android":
                    # supply sorts the pictures by name.
                    for directory in PLAY_DIRECTORIES[device]:
                        target = (
                            out
                            / locale
                            / "images"
                            / directory
                            / f"{index}_{screen}.png"
                        )
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(source, target)
                else:
                    # deliver finds the device from the size, and sorts by name.
                    target = out / locale / f"{device}-{index}_{screen}.png"
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, target)
    print(f"staged {platform} screenshots in {out}")


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--platform", choices=sorted(DEVICES), required=True)
    parser.add_argument(
        "--languages",
        action="store_true",
        help="print the locales, separated by commas",
    )
    parser.add_argument(
        "--screenshots",
        type=Path,
        help="a folder of framed images, one folder per locale",
    )
    parser.add_argument(
        "--stage",
        type=Path,
        help="copy the checked set here, in the layout of fastlane",
    )
    args = parser.parse_args(argv)

    if args.languages:
        print(",".join(LOCALES))
        return
    if args.screenshots is None:
        parser.error("give --languages or --screenshots")
    folder = (
        args.screenshots
        if args.screenshots.is_absolute()
        else Path.cwd() / args.screenshots
    )
    found = check(args.platform, folder)
    count = sum(
        len(screens) for devices in found.values() for screens in devices.values()
    )
    print(f"{count} {args.platform} screenshots are complete")
    if args.stage:
        stage(args.platform, found, args.stage)


if __name__ == "__main__":
    main()
