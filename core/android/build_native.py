#!/usr/bin/env python3
"""Builds libsunradar_jni.so for each ABI into native/prebuilt/jniLibs/<abi>/.

    python3 build_native.py --abi armv8 --abi x86_64

Gradle runs this script in the task `buildNative`. CI runs it once for each ABI in a
separate job, and gives the results to the AAR job with `-Psunradar.abis=`.
"""

import argparse
import pathlib
import shutil
import subprocess

HERE = pathlib.Path(__file__).resolve().parent
CORE = HERE.parent
OUT = HERE / "native" / "prebuilt" / "jniLibs"

# Conan arch -> Android ABI directory.
ABIS = {"armv8": "arm64-v8a", "x86_64": "x86_64"}


def run(command):
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=CORE, check=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", action="append", choices=sorted(ABIS), required=True)
    parser.add_argument("--conan", default="conan")
    parser.add_argument("--build-profile", default="default")
    args = parser.parse_args()

    for abi in args.abi:
        run(
            [
                args.conan,
                "install",
                ".",
                "--build=missing",
                f"-pr:h=conan/profiles/android-{abi}",
                f"-pr:b={args.build_profile}",
                "-o",
                "&:with_tests=False",
                "-o",
                "&:with_jni=True",
            ]
        )
        preset = f"conan-android-{abi}-release"
        run(["cmake", "--preset", preset, "-DSUNRADAR_WARNINGS_AS_ERRORS=ON"])
        run(["cmake", "--build", "--preset", preset, "--target", "sunradar_jni"])

        library = (
            CORE
            / "build"
            / f"android-{abi}-release"
            / "android"
            / "jni"
            / "libsunradar_jni.so"
        )
        target = OUT / ABIS[abi]
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(library, target / library.name)
        print("copied", target / library.name)


if __name__ == "__main__":
    main()
