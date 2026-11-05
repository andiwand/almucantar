# almucantar

An almucantar is a circle of equal altitude in the sky. This repository has one C++ core that
calculates the sky (the sun, the moon, the planets, the Milky Way, the terrain horizon, and the
projection on a camera image), and the apps that use it. See [PLAN.md](PLAN.md) for the design,
the milestones, and the plan of the apps (section 14).

| App | What | Status |
|---|---|---|
| **sunradar** | The path of the sun on the camera image: when is the sun at this place in the sky? | pre-release |
| **starradar** | The stars, the constellations, the planets, and the Milky Way in the direction of the phone, over the real horizon. | web app: <https://andiwand.github.io/almucantar/starradar/>; the Android and iOS apps build from `android/` and `ios/` |
| **shotradar** | The photo planner: from where and when is the moon, the Milky Way, or the sun behind a landmark? | prototype: <https://andiwand.github.io/almucantar/shotradar/>; the Android app builds from `android/` |

## sunradar

<p align="center">
  <img src="assets/logo.svg" alt="Sunradar" width="128" height="128">
</p>

**Website and web app: <https://andiwand.github.io/almucantar/sunradar/>**

sunradar shows the path of the sun on top of the camera image. You can see when the sun is at a
given place in the sky, for example when you visit a flat.

- The path of the sun with the hours, the horizon, the terrain, and the summer and winter paths,
  on the camera image or on a sky view.
- Point the crosshair at a window or a gap between roofs: the app shows the time when the sun is
  in that direction, and the days of the year when the sun is in the crosshair.
- Any date, time, and place, also offline, with the sunrise and the sunset, also behind the
  terrain.
- A heading filter for indoor use. Align the heading with the sun, the moon, or a landmark on a
  map. The Android and iOS apps find the sun in the camera image.
- The sun to 0.015° or better, measured against NASA JPL Horizons.

### Download

sunradar is a pre-release. It is not ready for all users yet.

| What | Where |
|---|---|
| Android app | the APK of [sunradar-android-v0.2.0](https://github.com/andiwand/almucantar/releases/tag/sunradar-android-v0.2.0) (a debug build: allow the installation from this source) |
| Web app | <https://andiwand.github.io/almucantar/sunradar/app/> (also offline after the first visit) |
| iOS app | build it from `ios/` (see [`ios/AGENTS.md`](ios/AGENTS.md)); it is not in the App Store |

## The core packages

The packages are not on PyPI, npm, or Maven Central yet. The release
[core-v0.2.0](https://github.com/andiwand/almucantar/releases/tag/core-v0.2.0) has the wheels, the
npm package, the Android AAR, and the xcframework, still with the old name `sunradar`. The next
release of the core has the name `almucantar`: the Python package `almucantar`, the npm package
`@almucantar/core`, the AAR `at.stefl.almucantar:core-android`, and the Swift package
`Almucantar`.

```sh
# Wheels for Linux x86_64 and macOS arm64, Python 3.10 to 3.14 (core-v0.2.0, old name).
pip install sunradar --find-links https://github.com/andiwand/almucantar/releases/expanded_assets/core-v0.2.0
```

## Repository layout

This repository is a monorepo. The core is one library with one binding per language. The apps
are grouped by platform: each platform is one build with a shared part and one part per app.

| Path | Content |
|---|---|
| `core/` | the C++20 core and its bindings: `core/python/` (PyPI `almucantar`), `core/android/` (AAR), `core/wasm/` (npm `@almucantar/core`), `core/apple/` (Swift package `Almucantar`, `Package.swift` at the root) |
| `android/` | the Android apps (Kotlin, Jetpack Compose): `shared/`, `sunradar/`, `starradar/`, and `shotradar/` |
| `ios/` | the iOS apps (SwiftUI): `Shared/`, `Sunradar/`, and `Starradar/` |
| `web/` | one npm workspace: `shared/`, `home/` (the page of the family), and for each app `app/` and `site/` (on GitHub Pages under `/sunradar/`, `/starradar/`, and `/shotradar/`) |
| `assets/` | the logos, the offline base map, and the star data |

## License

[MPL-2.0](LICENSE).

## Development

[`AGENTS.md`](AGENTS.md) has the setup, the layout, the conventions, and the release flow. Each
project has its own build and test steps, for example [`core/AGENTS.md`](core/AGENTS.md) for the
C++ core.
