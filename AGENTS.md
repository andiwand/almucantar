# AGENTS.md

Orientation for agents: what this is, the layout, the build loop, and the
conventions. The design and the milestones are in [`PLAN.md`](PLAN.md). The
user documentation is in [`README.md`](README.md). A module with its own
`AGENTS.md` has the rules for that module. Read it before you change the module.

## What this is

almucantar is one C++ core that calculates the sky, and the apps that use it (PLAN.md 14). The
first app is sunradar: it shows the path of the sun on top of the camera image, so you can see
when the sun is at a given place in the sky. The second app is starradar, the sky map. The third
app is shotradar, the photo planner (on the web first).

The repository is a monorepo. The apps are grouped by platform: each platform is one build, with
a shared part and one part for each app.

| Path | What | Has `AGENTS.md` |
|---|---|---|
| `core/` | The C++ library almucantar with all its bindings (Python, Android, WASM, and Apple). There is one core for all apps. | yes |
| `android/` | The Android apps: one Gradle project with the module `shared/` and one module per app (`sunradar/`, `starradar/`). It uses `core/android` as a composite build, and contains no C++. | yes |
| `ios/` | The iOS apps: one XcodeGen project with `Shared/` and one folder per app (`Sunradar/`, `Starradar/`). It uses the Swift package of `core/apple`. | yes |
| `web/` | One npm workspace: `shared/` (`@almucantar/web-shared`), the page of the family (`home/`), and for each app its PWA and its website (`sunradar/app/`, `sunradar/site/`, `starradar/app/`, `starradar/site/`, `shotradar/app/`, `shotradar/site/`). It uses `core/wasm`. The workflow `web.yml` deploys them to GitHub Pages: `/`, `/sunradar/`, `/sunradar/app/`, `/starradar/`, `/starradar/app/`, `/shotradar/`, and `/shotradar/app/`. | yes |
| `assets/` | The logos (`logo.svg`, `starradar-logo.svg`, `shotradar-logo.svg`), the offline base map (`assets/basemap/`), and the star data (`assets/stars/`). | no |
| `Package.swift` | The Swift package of the core. It must be at the root, because SwiftPM needs that for a package from a Git URL. | no |
| `scripts/` | Repository tools: `format`, `release.py`, the base map, the star data, the horizon of a website chapter (`site/`), the store screenshots, and the pinned versions of the dev tools. | no |
| `screenshots/` | The design of the store screenshots and the description of the pipeline (`README.md`). The scripts are in `scripts/screenshots/`, the lanes in `android/fastlane/` and `ios/fastlane/`. | no |
| `.github/workflows/` | One workflow per platform and per package, with path filters. | no |

A new app gets a module or a folder on each platform next to `sunradar`, and uses the shared part
of the platform. The shared part never depends on an app. Code moves to the shared part when a
second app needs it.

The model is the one of OpenDocument.core: one core with a small public API, one
binding per language, and one package per binding. The apps use the packages and
contain no C++. `PLAN.md` section 3 explains why.

## Setup

1. Install CMake 3.24 or later, Conan 2, and a C++20 compiler.
2. Make the venv with the pinned dev tools:

   ```sh
   python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements-dev.txt
   ```

The build loop of the core is in [`core/AGENTS.md`](core/AGENTS.md). For Android, use the JDK
of Android Studio (`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`)
and set `ANDROID_HOME`. Install and test on an emulator only (`ANDROID_SERIAL`), because a
phone can be connected. For Apple, install Xcode and XcodeGen (`brew install xcodegen`).

## Conventions for all projects

- Format before each commit: `scripts/format`. CI runs `scripts/format --check`.
- Commit messages follow Conventional Commits. The scope is the project or the
  binding: `feat(core): ...`, `fix(python): ...`, `feat(android): ...`, `ci: ...`, `docs: ...`. The release
  calculates the next version from the commit messages, so write them carefully.
- A change that a user of a project notices gets an entry under `## Unreleased` in
  the `CHANGELOG.md` of that project (`core/`, or the folder of the app, for example
  `android/sunradar/`), in the same commit or pull request.
- Commit messages, code comments, and documentation use Simplified Technical
  English: one idea per sentence, the active voice, the present tense, one word for
  one meaning, and short common words. Identifiers and error strings stay as they are.
- End commit messages with the `Co-Authored-By` line of the agent. Put the
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)` line at the top of
  a pull request body.

## Releasing

Each project has its own version and tag: `core-vX.Y.Z` (the core and all its packages), and one
project for each app on each platform: `sunradar-android-vX.Y.Z`, `sunradar-ios-vX.Y.Z`,
`sunradar-pwa-vX.Y.Z`, the same three for starradar, and `shotradar-pwa-vX.Y.Z`. The paths of a project in `scripts/release.py` are the paths of its app and
the shared part of its platform. The flow is the one of OpenDocument.core.

1. Write the changes under `## Unreleased` of the changelog of the project, in the commit that
   makes them. An empty `## Unreleased` stops the release.
2. Run the workflow `release` (Actions → release → Run workflow) with the project. Keep
   `dry_run` on first: the summary shows the version, the stamp, and the release notes.
3. Run it again without `dry_run`. Set `prerelease` for a version that is not ready for all users. It commits `chore(release): <tag> [skip ci]` and creates a
   draft release.
4. Publish the draft on GitHub. The published release starts `publish-core.yml` (npm, PyPI,
   Maven Central, and the Swift package) or `android-release.yml` (the APK). The PWA needs no publish step, because
   GitHub Pages deploys `main`. The iOS apps have no publish
   workflow yet, because the App Store needs an Apple developer account.

`scripts/release.py` runs the same steps by hand (`version`, `check`, `stamp --dry-run`,
`notes`). git cliff (`.github/cliff.toml`) calculates the version from the Conventional
Commits: `feat` gives a minor step, `fix` and the rest a patch step, `!` a major step. Before
1.0.0, `!` gives a minor step, so a project leaves 0.x only with an explicit version.

A registry without credentials is skipped with a notice, and the files are still attached to
the release. The credentials are described at the top of each publish workflow.

