#!/usr/bin/env python3
"""The release steps of a project of the monorepo. release.yml runs them; you can run them by hand.

    scripts/release.py version core          the next version (git cliff), for example 0.2.0
    scripts/release.py check core            fail if "## Unreleased" of the changelog is empty
    scripts/release.py stamp core 0.2.0      put the version into the changelog (and the files)
    scripts/release.py notes core 0.2.0      the release notes: the changelog section and the commits

`stamp --dry-run` prints the changes and writes nothing. The tag of a release is
<project>-v<version>, for example core-v0.2.0.
"""

import argparse
import datetime
import os
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CLIFF_CONFIG = ROOT / ".github" / "cliff.toml"

# Each project: its paths, its changelog, and the files that carry its version.
PROJECTS = {
    "core": {
        "paths": ["core/**"],
        "changelog": "core/CHANGELOG.md",
        "stamps": [
            (
                "core/CMakeLists.txt",
                r"(project\(almucantar\s+VERSION )[0-9][^\s)]*",
                r"\g<1>{version}",
            ),
            ("core/conanfile.py", r'(version = ")[^"]*(")', r"\g<1>{version}\g<2>"),
        ],
    },
    # An app on one platform: its own paths and the shared code of the platform. The paths of
    # another app on the same platform do not change the version of this app.
    "sonnseite-android": {
        "paths": [
            "android/sonnseite/**",
            "android/shared/**",
            "android/gradle/**",
            "android/*.kts",
            "android/gradle.properties",
            "android/fastlane/**",
            "assets/basemap/**",
            "assets/tzdata/**",
        ],
        "changelog": "android/sonnseite/CHANGELOG.md",
        "stamps": [],
    },
    "sonnseite-ios": {
        "paths": [
            "ios/Sonnseite/**",
            "ios/SonnseiteUITests/**",
            "ios/Shared/**",
            "ios/SharedTests/**",
            "ios/project.yml",
            "ios/fastlane/**",
            "assets/basemap/**",
        ],
        "changelog": "ios/Sonnseite/CHANGELOG.md",
        "stamps": [
            (
                "ios/project.yml",
                r'(PRODUCT_BUNDLE_IDENTIFIER: at\.stefl\.sonnseite\n(?:.*\n)*?\s*MARKETING_VERSION: ")[^"]*(")',
                r"\g<1>{version}\g<2>",
            )
        ],
    },
    "sonnseite-pwa": {
        "paths": [
            "web/sonnseite/app/**",
            "web/shared/**",
            "web/package.json",
            "web/package-lock.json",
            "assets/basemap/**",
            "assets/tzdata/**",
        ],
        "changelog": "web/sonnseite/app/CHANGELOG.md",
        "stamps": [
            (
                "web/sonnseite/app/package.json",
                r'("version": ")[^"]*(")',
                r"\g<1>{version}\g<2>",
            )
        ],
    },
    "sternstunde-android": {
        "paths": [
            "android/sternstunde/**",
            "android/shared/**",
            "android/gradle/**",
            "android/*.kts",
            "android/gradle.properties",
            "assets/basemap/**",
            "assets/tzdata/**",
            "assets/stars/**",
        ],
        "changelog": "android/sternstunde/CHANGELOG.md",
        "stamps": [],
    },
    "sternstunde-ios": {
        "paths": [
            "ios/Sternstunde/**",
            "ios/SternstundeTests/**",
            "ios/Shared/**",
            "ios/SharedTests/**",
            "ios/project.yml",
            "assets/basemap/**",
            "assets/stars/**",
        ],
        "changelog": "ios/Sternstunde/CHANGELOG.md",
        "stamps": [
            (
                "ios/project.yml",
                r'(PRODUCT_BUNDLE_IDENTIFIER: at\.stefl\.sternstunde\n(?:.*\n)*?\s*MARKETING_VERSION: ")[^"]*(")',
                r"\g<1>{version}\g<2>",
            )
        ],
    },
    "sternstunde-pwa": {
        "paths": [
            "web/sternstunde/app/**",
            "web/shared/**",
            "web/package.json",
            "web/package-lock.json",
            "assets/basemap/**",
            "assets/tzdata/**",
            "assets/stars/**",
        ],
        "changelog": "web/sternstunde/app/CHANGELOG.md",
        "stamps": [
            (
                "web/sternstunde/app/package.json",
                r'("version": ")[^"]*(")',
                r"\g<1>{version}\g<2>",
            )
        ],
    },
    "alidade-android": {
        "paths": [
            "android/alidade/**",
            "android/shared/**",
            "android/gradle/**",
            "android/*.kts",
            "android/gradle.properties",
            "assets/basemap/**",
            "assets/tzdata/**",
            "assets/stars/**",
        ],
        "changelog": "android/alidade/CHANGELOG.md",
        "stamps": [],
    },
    "alidade-pwa": {
        "paths": [
            "web/alidade/app/**",
            "web/shared/**",
            "web/package.json",
            "web/package-lock.json",
            "assets/basemap/**",
            "assets/tzdata/**",
            "assets/stars/**",
        ],
        "changelog": "web/alidade/app/CHANGELOG.md",
        "stamps": [
            (
                "web/alidade/app/package.json",
                r'("version": ")[^"]*(")',
                r"\g<1>{version}\g<2>",
            )
        ],
    },
}

SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def git_cliff(project: str, *arguments: str) -> str:
    cliff = shutil.which("git-cliff") or str(ROOT / ".venv" / "bin" / "git-cliff")
    # The first version must match the tag pattern, so it has the prefix of the project.
    env = {**os.environ, "GIT_CLIFF__BUMP__INITIAL_TAG": f"{project}-v0.1.0"}
    command = [cliff, "--config", str(CLIFF_CONFIG), "--tag-pattern", f"^{project}-v"]
    for path in PROJECTS[project]["paths"]:
        command += ["--include-path", path]
    result = subprocess.run(
        command + list(arguments),
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        env=env,
    )
    return result.stdout.strip()


def unreleased(project: str) -> str:
    text = (ROOT / PROJECTS[project]["changelog"]).read_text()
    match = re.search(r"^## Unreleased\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    if not match:
        sys.exit(f"{PROJECTS[project]['changelog']} has no '## Unreleased' section")
    return match.group(1).strip()


def version(project: str) -> str:
    bumped = git_cliff(project, "--bumped-version")
    value = re.sub(rf"^{project}-v", "", bumped).lstrip("v")
    if not SEMVER.fullmatch(value):
        sys.exit(f"git cliff gave an unexpected version: {bumped!r}")
    tags = subprocess.run(
        ["git", "tag", "--list", f"{project}-v{value}"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if tags.stdout.strip():
        sys.exit(f"the tag {project}-v{value} exists already; give an unused version")
    return value


def check(project: str) -> None:
    if not unreleased(project):
        sys.exit(
            f"'## Unreleased' of {PROJECTS[project]['changelog']} is empty. Write the changes, or "
            "'No changes that a user notices.'"
        )


def stamp(project: str, value: str, dry_run: bool) -> None:
    if not SEMVER.fullmatch(value):
        sys.exit(f"not a version: {value!r}")
    check(project)
    changes: dict[pathlib.Path, str] = {}
    changelog = ROOT / PROJECTS[project]["changelog"]
    date = datetime.date.today().isoformat()
    changes[changelog] = changelog.read_text().replace(
        "## Unreleased\n", f"## Unreleased\n\n## {value} - {date}\n", 1
    )
    for path, pattern, replacement in PROJECTS[project]["stamps"]:
        file = ROOT / path
        text, count = re.subn(
            pattern, replacement.format(version=value), file.read_text(), count=1
        )
        if count != 1:
            sys.exit(f"{path}: the version pattern did not match")
        changes[file] = text
    for file, text in changes.items():
        if dry_run:
            print(f"--- {file.relative_to(ROOT)} (dry run, not written)")
            diff = subprocess.run(
                ["diff", "-u", str(file), "-"],
                input=text,
                capture_output=True,
                text=True,
            )
            print(diff.stdout)
        else:
            file.write_text(text)
            print("stamped", file.relative_to(ROOT))


def notes(project: str, value: str) -> str:
    text = (ROOT / PROJECTS[project]["changelog"]).read_text()
    match = re.search(
        rf"^## {re.escape(value)} - .*?\n(.*?)(?=^## |\Z)", text, re.M | re.S
    )
    section = (match.group(1) if match else unreleased(project)).strip()
    commits = git_cliff(
        project, "--unreleased", "--tag", f"{project}-v{value}", "--strip", "all"
    )
    return f"{section}\n\n## Commits\n\n{commits}\n"


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("command", choices=["version", "check", "stamp", "notes"])
    parser.add_argument("project", choices=sorted(PROJECTS))
    parser.add_argument("version", nargs="?")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.command == "version":
        print(version(args.project))
    elif args.command == "check":
        check(args.project)
        print("ok")
    elif args.command == "stamp":
        stamp(args.project, args.version or version(args.project), args.dry_run)
    else:
        print(notes(args.project, args.version or version(args.project)))


if __name__ == "__main__":
    main()
