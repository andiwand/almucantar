#!/usr/bin/env python3
"""Builds SunradarC.xcframework: the C API and the core as one static library per Apple slice.

    python3 core/apple/build_xcframework.py            all slices, then the xcframework
    python3 core/apple/build_xcframework.py --slice macos-arm64_x86_64
    python3 core/apple/build_xcframework.py --zip      also the zip of a release, and its checksum

The output is core/apple/build/SunradarC.xcframework. Package.swift (at the root of the
repository) uses it if it exists. Each Conan profile gets its own build; `libtool` merges
libsunradar_c.a and libsunradar.a, and `lipo` joins the architectures of one platform, because
an xcframework has one entry for each platform (device, simulator, macOS). The merge also takes
the static libraries of the Conan dependencies of the core (miniz), so the app links one library.
"""

import argparse
import json
import pathlib
import shutil
import subprocess

APPLE = pathlib.Path(__file__).resolve().parent
CORE = APPLE.parent
OUT = APPLE / "build"
NAME = "SunradarC"

SLICES = {
    "ios-arm64": ["apple-ios-armv8"],
    "ios-arm64_x86_64-simulator": ["apple-iossim-armv8", "apple-iossim-x86_64"],
    "macos-arm64_x86_64": ["apple-macos-armv8", "apple-macos-x86_64"],
}

# The build folder of each profile (the build_folder_vars of conan/profiles/apple.jinja).
FOLDERS = {
    "apple-ios-armv8": "ios-iphoneos-armv8-release",
    "apple-iossim-armv8": "ios-iphonesimulator-armv8-release",
    "apple-iossim-x86_64": "ios-iphonesimulator-x86_64-release",
    "apple-macos-armv8": "macos-armv8-release",
    "apple-macos-x86_64": "macos-x86_64-release",
}


def run(command):
    print("+", " ".join(str(c) for c in command), flush=True)
    subprocess.run([str(c) for c in command], cwd=CORE, check=True)


def dependency_archives(graph: dict) -> list[pathlib.Path]:
    """The static libraries of the host dependencies in the JSON graph of `conan install`."""
    archives = []
    for node in graph["graph"]["nodes"].values():
        folder = node.get("package_folder")
        if node.get("context") != "host" or not folder or node.get("ref", "").startswith("sunradar"):
            continue
        archives += sorted((pathlib.Path(folder) / "lib").glob("*.a"))
    return archives


def build(profile: str, conan: str) -> pathlib.Path:
    command = [
        conan,
        "install",
        ".",
        "--build=missing",
        f"-pr:h=conan/profiles/{profile}",
        "-pr:b=default",
        "-o",
        "&:with_tests=False",
        "-o",
        "&:with_apple=True",
        "--format=json",
    ]
    print("+", " ".join(command), flush=True)
    result = subprocess.run(command, cwd=CORE, check=True, stdout=subprocess.PIPE, text=True)
    dependencies = dependency_archives(json.loads(result.stdout))
    preset = f"conan-{FOLDERS[profile]}"
    run(["cmake", "--preset", preset, "-DSUNRADAR_WARNINGS_AS_ERRORS=ON"])
    run(["cmake", "--build", "--preset", preset, "--target", "sunradar_c"])
    build_dir = CORE / "build" / FOLDERS[profile]
    merged = build_dir / f"lib{NAME}.a"
    run(
        [
            "libtool",
            "-static",
            "-o",
            merged,
            build_dir / "apple" / "libsunradar_c.a",
            build_dir / "libsunradar.a",
            *dependencies,
        ]
    )
    return merged


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slice", action="append", choices=sorted(SLICES))
    parser.add_argument("--conan", default="conan")
    parser.add_argument(
        "--zip",
        action="store_true",
        help="also write the zip of a release and its checksum",
    )
    args = parser.parse_args()

    slices = args.slice or list(SLICES)
    headers = OUT / "headers"
    shutil.rmtree(headers, ignore_errors=True)
    headers.mkdir(parents=True)
    shutil.copy2(APPLE / "include" / "sunradar_c.h", headers)
    (headers / "module.modulemap").write_text(
        f'module {NAME} {{\n  header "sunradar_c.h"\n  link "c++"\n  export *\n}}\n'
    )

    libraries = []
    for name in slices:
        archives = [build(profile, args.conan) for profile in SLICES[name]]
        target = OUT / name / f"lib{NAME}.a"
        target.parent.mkdir(parents=True, exist_ok=True)
        run(["lipo", "-create", "-output", target, *archives])
        libraries += ["-library", target, "-headers", headers]

    xcframework = OUT / f"{NAME}.xcframework"
    shutil.rmtree(xcframework, ignore_errors=True)
    run(["xcodebuild", "-create-xcframework", *libraries, "-output", xcframework])
    print("wrote", xcframework)

    if args.zip:
        archive = OUT / f"{NAME}.xcframework.zip"
        archive.unlink(missing_ok=True)
        run(["ditto", "-c", "-k", "--keepParent", xcframework, archive])
        checksum = subprocess.run(
            ["swift", "package", "compute-checksum", str(archive)],
            cwd=CORE.parent,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        (OUT / f"{NAME}.xcframework.zip.sha256").write_text(checksum + "\n")
        print("wrote", archive, checksum)


if __name__ == "__main__":
    main()
