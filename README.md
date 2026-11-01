<p align="center">
  <img src="assets/logo.svg" alt="Sunradar" width="128" height="128">
</p>

# sunradar

**Website and web app: <https://andiwand.github.io/sunradar/>**

sunradar shows the path of the sun on top of the camera image. You can see when the
sun is at a given place in the sky, for example when you visit a flat. See
[PLAN.md](PLAN.md) for the design and the milestones.

## Repository layout

This repository is a monorepo. Each directory is a self-contained project.

| Path | Content | Status |
|---|---|---|
| `core/` | the C++20 core: solar models, projection, search | in progress |
| `core/python/` | the Python package `sunradar` | in progress |
| `core/android/` | the Android AAR `at.stefl.sunradar:core-android` | in progress |
| `android/` | the Android app (Kotlin, Jetpack Compose) | in progress |
| `core/apple/` | the Swift package `Sunradar` (`Package.swift` at the root) | in progress |
| `core/wasm/` | the npm package `@sunradar/core` (WebAssembly) | in progress |
| `pwa/` | the PWA (TypeScript, the core as WebAssembly) | in progress |
| `website/` | the website on GitHub Pages; the PWA is under `/app/` | in progress |
| `ios/` | the iOS app (SwiftUI) | in progress |

Agents: read [`AGENTS.md`](AGENTS.md) first.

## License

[MPL-2.0](LICENSE).

## Development

[`AGENTS.md`](AGENTS.md) has the setup, the layout, the conventions, and the release flow.
Each project has its own build and test steps, for example
[`core/AGENTS.md`](core/AGENTS.md) for the C++ core.
