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
| `core/` | The C++ library with all its bindings (Python now; Android, WASM, and Apple later). | yes |
| `android/` | The Android app (M5). It uses `core/android`, and contains no C++. | later |
| `pwa/` | The PWA (M9). It uses `core/wasm`. | later |
| `ios/` | The iOS app, maybe later. It uses `core/apple`. | later |
| `scripts/` | Repository tools: `format`, and the pinned versions of the dev tools. | no |
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

The build loop of the core is in [`core/AGENTS.md`](core/AGENTS.md).

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
