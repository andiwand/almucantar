# AGENTS.md

Orientation for agents: what this is, the layout, the build loop, and the
conventions. The design and the milestones are in [`PLAN.md`](PLAN.md). The
user documentation is in [`README.md`](README.md). A module with its own
`AGENTS.md` has the rules for that module. Read it before you change the module.

## What this is

sunradar shows the path of the sun on top of the camera image, so you can see when
the sun is at a given place in the sky. The repository is a monorepo:

| Path | What | Has `AGENTS.md` |
|---|---|---|
| `core/` | The C++ library with all its bindings (Python, Android, WASM, and Apple). | yes |
| `android/` | The Android app. It uses `core/android` as a composite build, and contains no C++. | yes |
| `pwa/` | The PWA. It uses `core/wasm`. | yes |
| `website/` | The website. The workflow `web.yml` deploys it with the PWA (under `/app/`) to GitHub Pages. | no |
| `assets/` | The logo. | no |
| `ios/` | The iOS app. It uses the Swift package of `core/apple`. | yes |
| `Package.swift` | The Swift package of the core. It must be at the root, because SwiftPM needs that for a package from a Git URL. | no |
| `scripts/` | Repository tools: `format`, `release.py`, and the pinned versions of the dev tools. | no |
| `.github/workflows/` | One workflow per project and per package, with path filters. | no |

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
  binding: `feat(core): ...`, `fix(python): ...`, `ci: ...`, `docs: ...`. The release
  calculates the next version from the commit messages, so write them carefully.
- A change that a user of a project notices gets an entry under `## Unreleased` in
  the `CHANGELOG.md` of that project, in the same commit or pull request.
- Commit messages, code comments, and documentation use Simplified Technical
  English: one idea per sentence, the active voice, the present tense, one word for
  one meaning, and short common words. Identifiers and error strings stay as they are.
- End commit messages with the `Co-Authored-By` line of the agent. Put the
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)` line at the top of
  a pull request body.

## Releasing

Each project has its own version and tag: `core-vX.Y.Z` (the core and all its packages),
`android-vX.Y.Z`, `pwa-vX.Y.Z`, and `ios-vX.Y.Z`. The flow is the one of OpenDocument.core.

1. Write the changes under `## Unreleased` of the changelog of the project, in the commit that
   makes them. An empty `## Unreleased` stops the release.
2. Run the workflow `release` (Actions → release → Run workflow) with the project. Keep
   `dry_run` on first: the summary shows the version, the stamp, and the release notes.
3. Run it again without `dry_run`. Set `prerelease` for a version that is not ready for all users. It commits `chore(release): <tag> [skip ci]` and creates a
   draft release.
4. Publish the draft on GitHub. The published release starts `publish-core.yml` (npm, PyPI,
   Maven Central, and the Swift package) or `android-release.yml` (the APK). The PWA needs no publish step, because
   GitHub Pages deploys `main`. The iOS app has no publish
   workflow yet, because the App Store needs an Apple developer account.

`scripts/release.py` runs the same steps by hand (`version`, `check`, `stamp --dry-run`,
`notes`). git cliff (`.github/cliff.toml`) calculates the version from the Conventional
Commits: `feat` gives a minor step, `fix` and the rest a patch step, `!` a major step.

A registry without credentials is skipped with a notice, and the files are still attached to
the release. The credentials are described at the top of each publish workflow.

