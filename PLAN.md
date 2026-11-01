# sunradar — plan

## 1. Goal

The app shows where the sun is in the sky at a given time, location, and date. It draws
the sun path on top of the camera image, or on an empty sky view without the camera. The
main use: you visit a flat and want to know when the sun shines through a window.

### Features

- The trajectory of the sun for the selected date, with hour labels.
- An artificial horizon: the line of altitude 0°.
- **Fixed-time mode:** the user selects a time. A sun marker stays at the sun position for
  that time, and the marker stays fixed in the world when the phone turns.
- **Auto mode:** the app finds the time when the sun path is nearest to the screen center.
  It shows this time as `hh:mm`, or "not today" if the path is too far away.
- **Date slider:** the user selects the date. Auto mode can also find the dates on which
  the sun reaches the screen center (see 5.6).
- **Heading calibration:** the user corrects the compass error indoors (see 5.4).

### Platforms

1. Android first (native, Kotlin).
2. PWA second, with the same C++ core compiled to WebAssembly.
3. iOS maybe later, with the same C++ core.

## 2. Precision budget

The user asks "when", so the important error is a time error. The sun moves 15° per hour
in hour angle, and at most about 0.25° per minute on the sky. So **1° of error is about
4 minutes**.

| Error source | Typical size | Time error |
|---|---|---|
| Compass heading indoors, no calibration | 10–30° | 40–120 min |
| Compass heading outdoors, or after calibration | 2–5° | 8–20 min |
| Pitch and roll (accelerometer) | 0.5–1° | 2–4 min |
| Lens distortion at the image edge, no correction | 1–2° | 4–8 min |
| Field of view from a wrong camera model | 1–5° at the edge | 4–20 min |
| Magnetic declination model (WMM), no local anomaly | about 0.5° | 2 min |
| Refraction near the horizon, no correction | 0.57° | 2 min |
| Refraction variation with temperature and pressure | about 0.1° near the horizon | < 1 min |
| One screen pixel (70° FOV on 1080 px) | 0.065° | 15 s |
| NOAA solar equations (1950–2100, measured against JPL Horizons) | ≤ 0.015° | 4 s |
| Location error of 2 km (coarse location) | about 0.02° | 5 s |
| Phone clock error | < 1 s | < 1 s |
| Sun disc diameter, for comparison | 0.53° | 2 min |

### Conclusions

- The solar model is not the limit. A model with an error of 0.05° or better is
  sufficient, and NOAA (≤ 0.015°, measured) is more than sufficient.
- The orientation of the phone is the limit, and the compass heading indoors is the main
  error. So most of the work goes into the orientation and the calibration, not into the
  astronomy.
- The camera model is the second limit. Use the intrinsics and the lens distortion from
  Camera2, and correct for the preview crop.
- Coarse location is sufficient, so the app needs only `ACCESS_COARSE_LOCATION`.
- Apply the refraction correction, because the interesting events (the sun above a roof,
  the sun behind a building) are near the horizon.

### Target values

- Solar model: ≤ 0.05° for 1950–2100.
- Projection math: ≤ 1 px error for a given orientation and camera model.
- Total, after calibration: ≤ 2° (about ±8 min). This is a goal, and the field tests in
  M6 and M7 confirm it or not.

## 3. Architecture

The model is the one of OpenDocument.core (`~/odr/OpenDocument.core`). One C++ core
has a small public API. Each language gets a binding of that API, and each binding is a
package. The apps use the packages and contain no C++.

```
┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────┐
│ android/ app      │ │ pwa/ app          │ │ ios/ app (later)  │ │ scripts, notebooks│
│ Kotlin, Compose   │ │ TypeScript        │ │ Swift, SwiftUI    │ │ reference checks  │
└─────────┬─────────┘ └─────────┬─────────┘ └─────────┬─────────┘ └─────────┬─────────┘
          │ AAR                 │ npm                 │ SwiftPM             │ PyPI
┌─────────┴─────────┐ ┌─────────┴─────────┐ ┌─────────┴─────────┐ ┌─────────┴─────────┐
│ core/android      │ │ core/wasm         │ │ core/apple        │ │ core/python       │
│ JNI + Kotlin API  │ │ embind + JS/d.ts  │ │ Swift package     │ │ pybind11          │
└─────────┬─────────┘ └─────────┬─────────┘ └─────────┬─────────┘ └─────────┬─────────┘
          └─────────────────────┴──────────┬──────────┴─────────────────────┘
┌──────────────────────────────────────────┴──────────────────────────────────────────┐
│ core/src/sunradar/*.hpp: the public C++ API (small, value types, free functions)     │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ core/src/sunradar/internal/: solar models, refraction, WMM, orientation,             │
│ calibration, camera model, projection, search, numerics                              │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### Rules for the boundary

- Every binding wraps the public API only. A binding never includes
  `sunradar/internal/...`.
- The core uses UTC only, as Unix time in seconds (`double`). The platform converts
  local time and time zones, because `java.time`, `Intl`, and `Foundation` do this well.
  The support for `std::chrono` time zones in the libc++ of the NDK is not reliable.
- The core does not read sensors, files, or the camera. The platform gives it numbers.
- The core returns geometry in screen pixels (points and polylines). The platform draws
  them.
- The core allocates little in the per-frame path: a few vectors of a few hundred
  points. The platform calls it at 30–60 Hz.

## 4. Repository layout

The repository is a monorepo. `core/` is the library with all its bindings, the same
as OpenDocument.core. Each app has its own directory. Each directory is a
self-contained project with its own build system.

```
sunradar/
├── core/
│   ├── src/sunradar/          public API (*.hpp), installed
│   ├── src/sunradar/internal/ everything else, not installed
│   ├── test/                  Catch2 tests, reference data, and the scripts that make it
│   ├── python/                pybind11 binding, Python package `sunradar`
│   ├── android/               JNI binding and Kotlin API, AAR
│   ├── wasm/                  embind binding and JS API, npm package
│   ├── apple/                 Swift package (later, with ios/)
│   ├── cmake/
│   ├── conan/profiles/
│   ├── conanfile.py
│   ├── pyproject.toml         scikit-build-core, for the Python package
│   ├── CMakeLists.txt
│   ├── CHANGELOG.md
│   └── AGENTS.md
├── android/                   the Android app
├── pwa/                       the PWA
├── ios/                       the iOS app (later, if we need it)
├── .github/workflows/         one workflow per project and per package
├── AGENTS.md                  orientation for agents; CLAUDE.md points to it
├── PLAN.md
└── README.md
```

Two differences from OpenDocument.core, because the situation is different:

- **No separate `jni/` and Maven jar.** OpenDocument.core has a Java API for desktop
  JVMs, so `jni/` builds a jar and `android/` reuses its Java sources. sunradar needs the
  JVM only on Android, so `core/android/` contains the JNI code and a Kotlin API
  directly.
- **Only the public headers are installed.** OpenDocument.core installs the internal
  headers too. sunradar installs only `src/sunradar/*.hpp`, so a consumer cannot depend
  on an internal header.

In the monorepo, an app uses the binding from source: the Android app includes
`core/android` as a Gradle composite build, the PWA uses `core/wasm` as an npm
workspace package, and the iOS app uses `core/apple` as a local Swift package. So a
change in the core and the app is one commit, and no app waits for a release. The
release still publishes the packages, for other users and for the Python package.

## 5. C++ core

### 5.1 Solar models

An internal interface lets us use different models. The main reason is testing: we
compare the models with each other and with external reference data. The public API
selects a model with an enum, so the bindings do not have to wrap a virtual class.

```cpp
// public: src/sunradar/sun.hpp
enum class SolarModel { noaa, astronomy_engine };

// internal: src/sunradar/internal/solar_model.hpp
class SolarModelImpl {
 public:
  virtual ~SolarModelImpl() = default;
  // Apparent position without refraction ("airless").
  virtual Horizontal sun(double unix_utc, const Observer&) const = 0;
};
const SolarModelImpl& solar_model(SolarModel);
```

Implementations:

| Model | Accuracy | Source | Use |
|---|---|---|---|
| `NoaaModel` | ≤ 0.015° (measured) | handroll, about 100 lines | default in the app (done in M1) |
| `AstronomyEngineModel` | about 1′ (0.017°) | Astronomy Engine, MIT, single C file | second model, and the **moon** for calibration |
| `SpaModel` | 0.0003° | NREL SPA | test oracle only; check the license before you ship it |

Astronomy Engine is not in Conan Center, so we fetch it with CMake `FetchContent` at a
fixed commit, or we write a small Conan recipe for it.

Reference data for tests: tables from JPL Horizons and from the NREL SPA, for some
locations (equator, Vienna, 60° N, 70° S) and some dates (solstices, equinoxes, random).

### 5.2 Magnetic declination

The compass gives magnetic north. The solar model gives true north. The core adds the
declination.

- Android gives the declination with `GeomagneticField`, but the PWA has nothing similar.
  So the core must contain a model, and Android can use it too.
- **Recommendation: handroll the WMM.** The WMM2025 has 90 coefficient pairs and
  degree 12, and the code for the spherical harmonics is about 200 lines. We compile the
  coefficients into the library, so the core does no file IO. WMM2025 is valid from 2025
  to 2030, so we must update the coefficients for WMM2030.
- Done in M3. The tests use the 100 official test values of WMM2025, which come with
  the coefficients. They are a better oracle than GeographicLib, because they come from
  the authors of the model.

### 5.3 Orientation

The platform gives the core a quaternion (device frame to East-North-Up).

- Android: `TYPE_ROTATION_VECTOR`. The OS does the fusion and the hard-iron and
  soft-iron calibration of the magnetometer.
- PWA: `AbsoluteOrientationSensor` (Chrome on Android), or `deviceorientation` with
  `webkitCompassHeading` (iOS Safari).

The core does:

1. Remap the axes for the screen rotation (portrait or landscape).
2. Convert magnetic north to true north (5.2).
3. Apply the heading offset from the calibration (5.4).
4. Smooth the orientation with slerp, so the overlay does not shake.

We do not write our own sensor fusion at first, because the platforms already do it. If
the fusion of the platform is not good enough, we can add a Madgwick or Mahony filter in
the core later (about 150 lines).

### 5.4 Calibration

The heading indoors is the main error (section 2). The core supports these methods:

1. **Sun or moon tap.** The user taps the real sun or moon on the camera image. Later (M8b)
   the app detects the sun itself, highlights it with a box, and the user only confirms. The core
   calculates the direction of the body, compares it with the tapped ray, and stores the
   heading offset. This method is exact to about 0.5°.
2. **Map heading.** The user marks the direction of a window or a façade on a map. The
   core calculates the offset from the difference to the current heading.
3. **Gyro hold.** The user calibrates outdoors or near a window, then the app changes to
   `TYPE_GAME_ROTATION_VECTOR`. This sensor does not use the magnetometer, so steel in
   the building does not disturb it. But it drifts slowly, so the app shows the age of
   the calibration.

The platform also gives the accuracy status of the magnetometer
(`SENSOR_STATUS_ACCURACY_*`), and the UI shows a warning.

### 5.4a The heading filter, from the thesis on magnetometer calibration (done in M7)

Source: <https://github.com/andiwand/magnetic-calibration-thesis> (particle filter for
the online calibration of the magnetometer in smartphones). Its pipeline is:

1. Madgwick IMU filter (accelerometer and gyroscope, no magnetometer). It gives the
   tilt exactly and the yaw with slow drift.
2. A particle filter for the hard iron: each particle has a hard-iron bias and an
   external field in the world frame. The predicted measurement is
   `bias + R(q)ᵀ · external`. It updates only after enough rotation.
3. A particle filter for the heading: it combines the yaw changes of the gyroscope with
   the heading of the calibrated magnetometer. The variance of the magnetometer heading
   grows when the measured field differs from the expected field of the Earth.

Step 3 is the answer to our largest risk (the compass indoors, section 11): near steel,
the field changes, so the filter trusts the gyroscope and not the magnetometer. The
proposal for sunradar:

- The orientation comes from `TYPE_GAME_ROTATION_VECTOR` (accelerometer and gyroscope,
  no magnetometer). This is step 1, done by the OS. The core does not need its own
  Madgwick filter.
- The core gets a **heading filter** with one state: the heading offset of the game
  frame to true north (and its variance). A one-dimensional Kalman filter is
  sufficient, because the state is one angle and the measurements are angles. It has
  these measurements:
  - the heading of the calibrated magnetometer, with a variance from the difference
    between the measured field and the expected field. The expected field is now known
    from the WMM in the core (intensity and inclination at the place and the date). This
    replaces the fixed field `{0, 20, −44} µT` of the thesis.
  - a sun or moon tap: a very small variance.
  - a map bearing: a small variance.
  - the process noise is the yaw drift of the game rotation vector.
- So the three calibration methods of section 5.4 become measurements of one filter,
  and the app shows the variance as the accuracy of the heading.
- The hard-iron calibration (step 2) stays with the OS at first. If the OS calibration is
  bad on some devices, a Kalman filter can replace the particle filter: given the
  orientation, the measurement is linear in the state (bias and external field).
- The public API stays without state: the filter state is a value type, and an update
  is a pure function, for example `HeadingState update(HeadingState, Measurement)`.

### 5.5 Camera model and projection

```cpp
struct CameraModel {
  double fx, fy, cx, cy;        // pixels, in the preview frame
  std::array<double, 5> dist;   // k1, k2, k3, p1, p2 (Brown–Conrady, same as Camera2)
  int width, height;
};
```

- Android fills the values from `LENS_INTRINSIC_CALIBRATION` and `LENS_DISTORTION`
  (API 28). If the device does not give them, the app calculates `fx` from
  `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` and `SENSOR_INFO_PHYSICAL_SIZE`, without
  distortion.
- The app converts the intrinsics from the sensor frame to the preview frame with
  `PreviewView.getSensorToViewTransform()`, which maps the active array to the view
  (crop, scale, and rotation). The intrinsics are in the pre-correction array, so the app
  subtracts the offset to the active array. The preview switches the distortion correction
  off (`DISTORTION_CORRECTION_MODE_OFF`), so the image has the distortion of the model.
- The PWA has no intrinsics. So it uses a FOV slider, or a table of devices.
- If the platform shows no camera image, the core uses a virtual camera with a FOV that
  the user selects.

Projection steps:

1. Convert altitude and azimuth to a unit vector in ENU.
2. Rotate the vector into the camera frame with the inverse orientation.
3. If `z ≤ 0`, the point is behind the camera. Clip the polyline, and return the
   direction for an arrow at the screen edge.
4. Apply the pinhole model and the lens distortion.

### 5.6 Path, horizon, and search

- `sun_path(model, observer, start_utc, end_utc)`: samples at a fixed step (default
  5 min), with or without refraction. It includes the points below the horizon. If
  the interval starts at local midnight, the points fall on the full hours.
- `sun_events(model, observer, start_utc, end_utc)`: all crossings of a threshold
  altitude (sunrise, sunset, or twilight), the culmination, and the polar day or the
  polar night. The platform gives the start and the end of the local day in UTC,
  because it knows the time zone.
- `horizon_line(camera, orientation)`: points of altitude 0° at all azimuths, projected
  and clipped.
- `find_time(observer, direction, start_utc, end_utc)` for auto mode (done in M4):
  1. Find the sample of the path with the smallest angle to the direction.
  2. Refine the time with a golden-section search between the two adjacent samples.
  3. Return the time, the angular distance, and the position of the sun. The UI shows
     "not today" if the distance is more than a threshold (for example 3°).
- `find_dates(observer, direction, start_utc, end_utc)` for the date slider (done in M4):
  each point in the sky between the path of the winter solstice and the path of the
  summer solstice gets the sun on about two dates per year, one on each side of a
  solstice. The function runs `find_time()` for each 24-hour window and returns the
  local minima of the distance below a threshold.

### 5.7 Public API

The public API is small, and its form makes every binding easy:

- Value types (plain structs) and free functions. No class hierarchies, no templates,
  no callbacks, no handles, and no state in the core. The app stores state, for example
  the heading offset of the calibration, and passes it in.
- So a binding only converts structs and arrays. It does not manage the lifetime of
  objects, which is the hard part of the JNI and Apple bindings of OpenDocument.core.
- Invalid arguments give `std::invalid_argument`. Each binding maps it to the native
  exception of its language (`IllegalArgumentException`, `ValueError`, a rejected JS
  promise or a thrown `Error`, a Swift `throws`).
- The public headers stay C++20. The internal code can use C++23 later, if the NDK and
  emsdk support what we need.
- One umbrella header, `sunradar/sunradar.hpp`, includes all public headers.

The planned public headers:

| Header | Content |
|---|---|
| `types.hpp` | `Observer`, `Horizontal`, `Atmosphere`, `Quaternion`, `Camera`, `ScreenPoint` |
| `sun.hpp` | `SolarModel`, `sun_position()`, `sun_events()`, `sun_path()` |
| `search.hpp` | `SearchOptions`, `TimeMatch`, `find_time()`, `find_dates()` (done in M4) |
| `magnetic.hpp` | `magnetic_declination_deg()` (done in M3) |
| `view.hpp` | `View` (camera, orientation, screen rotation, heading offset), `ScreenRotation`, `Camera`, `camera_from_fov()`, `project()`, `unproject()`, `project_line()`, `project_path()`, `horizon_line()` (done in M3). The view direction for auto mode is `unproject()` of the screen center. |
| `calibration.hpp` | `heading_offset_from_tap()`, `heading_offset_from_bearing()` |
| `version.hpp` | `version()` |

That is about 7 headers and 15 functions. Everything else is internal: the model
classes, the refraction formula, the WMM coefficients, the numerics, and the details of
the projection. A function becomes public only if an app needs it.

### 5.8 Bindings and packages

| Binding | Directory | Technology | Package | Registry |
|---|---|---|---|---|
| Python | `core/python/` | pybind11, scikit-build-core | `sunradar` | PyPI |
| Android | `core/android/` | JNI by hand, Kotlin API | `at.stefl.sunradar:core-android` (AAR) | Maven Central |
| Web | `core/wasm/` | Emscripten, embind, hand-written `index.js` and `index.d.ts` | `@sunradar/core` | npm |
| Apple (later) | `core/apple/` | Swift package with a prebuilt xcframework | `SunradarCore` | SwiftPM (GitHub release) |
| C++ | `core/` | Conan recipe | `sunradar` | own Conan remote, if anyone needs it |

Rules for all bindings, from OpenDocument.core:

- Mirror the C++ names, in the naming style of the language (`sun_events` in Python,
  `sunEvents` in Kotlin, JS, and Swift).
- Each binding has its own `AGENTS.md` and a test suite in its language: pytest,
  instrumented tests on an emulator, `node --test`, and XCTest.
- A new public function needs a binding and a test in every binding.
- JNI: enums cross as ordinals, so the Kotlin order must match the C++ order. Polylines
  cross as `FloatArray`, so the per-frame path allocates no objects.
- WASM: the binding runs in a Web Worker, so every value must be structured-clonable.
  Plain objects only, no embind class handles.
- Apple: SwiftPM cannot build a C++ project with Conan dependencies, so the package
  ships a prebuilt xcframework, the same as OpenDocument.core. Swift C++ interop (Swift
  5.9 and later) can replace the Objective-C layer of OpenDocument.core. We decide
  that when we start iOS.
- A SwiftPM package for external users must have `Package.swift` at the root of the
  repository. In the monorepo, the iOS app does not need this, because it uses a
  local package.

## 6. Dependencies

| Need | Choice | Source |
|---|---|---|
| Vectors, quaternions | handroll (`internal/vector.hpp`, about 60 lines) | — (Eigen only if the calibration needs least squares) |
| Tests | Catch2 | Conan Center, `catch2/3.x` |
| Second solar model, moon | Astronomy Engine | `FetchContent` or own recipe |
| Magnetic oracle for tests | the official WMM2025 test values | NOAA NCEI, in the coefficient download |
| Precise reference (optional) | SOFA | Conan Center, `sofa/20231011` |
| NOAA solar model | handroll | — |
| WMM2025 | handroll | — |
| Root finding (Brent) | handroll | — |
| Python binding | pybind11 | Conan Center, `pybind11/2.13.x` |
| Python build | scikit-build-core | PyPI, build dependency only |

Notes:

- Eigen is header-only, so it works with the NDK and with Emscripten without extra work.
  If we use Eigen only for small fixed-size types, we can replace it with a small
  handrolled `vec3` and `quat` later.
- Each test oracle (GeographicLib, SPA, SOFA) is a test dependency only. The app does not
  link it.

## 7. Build, release, and conventions

### Build

- CMake ≥ 3.24, C++20, Conan 2.
- CMake options, one per binding, the same as `ODR_*` in OpenDocument.core:
  `SUNRADAR_TESTS`, `SUNRADAR_PYTHON`, `SUNRADAR_JNI`, `SUNRADAR_WASM`,
  `SUNRADAR_APPLE`. `conanfile.py` has the matching options (`with_python`, ...).
- Conan profiles in `core/conan/profiles/`: `android-armv8`, `android-x86_64`, and
  `emscripten`. Later `apple-*` for the xcframework slices.
- The AAR: `core/android/build_native.py` runs Conan and CMake for each ABI into
  `native/prebuilt/jniLibs`, and Gradle packs the AAR. This is the model of
  OpenDocument.core. CI builds each ABI in its own job and gives the libraries to the
  AAR job.
- Static analysis: `clang-tidy` and `clang-format` with a CI check, and the sanitizers
  (ASan, UBSan) on the host build.

### CI

One workflow per project and per package, each with a path filter, so a change in
`pwa/` does not build the AAR:

| Workflow | Paths | Does |
|---|---|---|
| `core.yml` | `core/**` | builds and tests the core on Linux and macOS, cross-builds Android |
| `python.yml` | `core/**` | builds wheels, runs pytest |
| `core-android.yml` | `core/**` | builds the AAR, runs the instrumented tests on an emulator |
| `wasm.yml` | `core/**` | builds the npm package, runs `node --test` |
| `android.yml` | `android/**`, `core/**` | builds and tests the app |
| `pwa.yml` | `pwa/**`, `core/**` | builds and tests the PWA |
| `format.yml` | all | clang-format, ktfmt, black, prettier |

### Release

The same flow as OpenDocument.core:

1. Commit messages follow Conventional Commits (`feat(core): ...`, `fix(android): ...`).
   The scope is the project. `git cliff --bumped-version` calculates the next version
   from them.
2. Each change that a user notices gets an entry under `## Unreleased` in the
   `CHANGELOG.md` of its project, in the same pull request.
3. A manual dispatch of `release.yml` makes a draft release. A human publishes it.
4. The published release starts the publish workflows: PyPI (trusted publishing), npm
   (with provenance), Maven Central, and later the xcframework for SwiftPM.

A monorepo has more than one version, so the tags have a prefix: `core-v1.2.0` for
the core and all its packages, and `android-v1.0.0` and `pwa-v1.0.0` for the apps.
The core packages share one version, so the AAR, the npm package, and the wheel always
contain the same core.

### Conventions

- `AGENTS.md` at the root and in each module, with the architecture, the build loop, and
  the rules. `CLAUDE.md` only points to `AGENTS.md`. The same as OpenDocument.core.
- The code conventions of OpenDocument.core apply where they fit: `std::array` instead
  of C arrays, `const` where possible, `std::ranges`, fixed-width integers, the
  qualified definition of free functions, and short doc comments (`///`, `///<`).
- Commit messages, code comments, and documentation use Simplified Technical English.

## 8. Android app

- Uses `core/android` (the AAR) as a Gradle composite build. The app contains no C++
  and no JNI code.
- Kotlin, Jetpack Compose, `minSdk 28` (for `LENS_DISTORTION`).
- CameraX `PreviewView`, plus Camera2 interop to read the intrinsics.
- The overlay is a Compose `Canvas` on top of the preview.
- Sensors: `TYPE_ROTATION_VECTOR`, and `TYPE_GAME_ROTATION_VECTOR` for the gyro hold.
- Location: the coarse location (fused, network, or GPS provider). Without it, the main
  city of the device time zone (IANA `zone.tab`), and the app says so. The status shows
  the nearest town (geocoder) and the error as a power of ten, with its error on the sky.
  Later also a manual pick on a map, so the user can plan a flat from home.
- Screens:
  1. Sky view: camera or no camera, path, horizon, sun marker, compass accuracy.
  2. Controls: mode switch (fixed time or auto), time slider, date slider.
  3. Calibration: sun or moon tap, map heading.

## 9. PWA (after Android)

- TypeScript, Vite. Uses `@sunradar/core` (`core/wasm`) as an npm workspace package,
  in a Web Worker.
- Camera: `getUserMedia`. Orientation: `AbsoluteOrientationSensor`, with
  `deviceorientation` as a fallback. iOS needs a permission prompt for the sensors.
- FOV: a slider, or calibration with two sun or moon taps.
- Expect a lower precision than on Android, because the PWA has no camera intrinsics and
  no compass accuracy status.

## 10. Milestones

| # | Content | Done when |
|---|---|---|
| M0 | Repository, CMake, Conan profiles, Catch2, CI | done |
| M1 | `NoaaModel`, refraction, sunrise and sunset, `sun_path` | done: ≤ 0.015° against JPL Horizons |
| M2 | Public and internal split, `SolarModel` enum, umbrella header, install rules, `AGENTS.md`, clang-format and clang-tidy in CI, Conventional Commits, `CHANGELOG.md`, Python binding of the M1 API | done |
| M3 | WMM2025, orientation, camera model, projection, horizon | done: WMM agrees with the official test values to 0.001 nT; projection round-trip < 1e-6 px |
| M4 | Search: `find_time`, `find_dates` | done: exact on the path (< 1 s), two dates per direction, one at the solstice, polar cases; a year takes about 50 ms |
| M5 | `core/android` AAR (JNI and Kotlin API), Android app shell without camera | done: 11 instrumented tests pass on an emulator; on the emulator, the projection of path, horizon, and sun agrees with a hand calculation for set sensor values. Not yet checked on a real phone. |
| M6 | Camera overlay with intrinsics and distortion | implemented: Camera2 calibration and the CameraX sensor-to-view transform, with a JVM property test of the conversion. Open: the outdoor check of 2° on a real phone. |
| M7 | Heading filter (core) with game rotation vector, magnetometer, sun tap, moon tap, and landmark on a map; UI rework | implemented and tested in C++, Python, and on an emulator. Open: the indoor check of 2° on a real phone. |
| M8 | Date slider, time slider, azimuth readout (vertical line through the crosshair), dates for the crosshair, solstice guides | implemented; checked on an emulator. Open: a real flat visit. |
| M8b | Sun detection in the camera image: find the brightest saturated blob near the predicted position of the sun, draw a box around it, and wait for the user to confirm it with a tap. Then the alignment needs no exact tap, and the app can propose it. | planned (proposal of the user after the first trials) |
| M9 | `core/wasm` npm package, PWA | the same features, with the documented lower precision |
| M10 | `release.yml`, publishing to PyPI, npm, and Maven Central | a release publishes all three packages with the same version |
| later | `core/apple` Swift package, iOS app | if we decide to do iOS |

The Python binding comes early (M2), because it is the easiest binding to test. It
also checks that the public API is easy to bind before the API grows, and it gives a
fast way to plot paths and to compare models.

## 11. Risks

- **Compass indoors.** This is the largest risk. If the calibration in M7 does not get
  the error to about 2°, the app must make the map heading the main method.
- **Camera intrinsics.** Some devices give no intrinsics or wrong ones. The fallback from
  the focal length and the sensor size gives about 1–3° at the edges.
- **Preview crop.** The conversion from the sensor frame to the CameraX preview frame is
  a frequent source of offsets. Test it on two or three devices.
- **Direction of the distortion.** Resolved in M6: the AOSP definition of
  `LENS_DISTORTION` says `correctedImage(x_i, y_i) = sample_at(x_c, y_c, inputImage)`, so
  it maps the ideal point to the distorted point, the same direction as OpenCV and the
  core. kappa_4 and kappa_5 are p1 and p2.
- **Licenses.** Check the NREL SPA license before we put the code in the repository.
  Astronomy Engine (MIT), GeographicLib (MIT), and Eigen (MPL 2.0) are not a problem.
- **WMM validity.** WMM2025 is valid until the end of 2029. Plan an update.

## 12. Open questions

- Do we need iOS later? If yes, `core/apple` gives a Swift package, and the app is
  native Swift. If we want external users of the Swift package, `Package.swift` must
  be at the root of the monorepo.
- The names of the packages: `sunradar` on PyPI and `@sunradar/core` on npm are proposals.
  Check that the names are free. The Maven group is `at.stefl.sunradar` (decided).
- Do we publish the C++ package to a Conan remote? Only if someone outside this
  repository needs it.
- Do we show the moon as well? Astronomy Engine gives it at no extra cost, and the
  calibration needs it anyway.
- Do we want a shadow mode later (for example, which wall gets the sun)? This needs a
  3D model of the room, so it is out of scope now.
