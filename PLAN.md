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
3. iOS (M11), with the same C++ core.

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
│   ├── apple/                 C API and Swift package (Package.swift at the root)
│   ├── cmake/
│   ├── conan/profiles/
│   ├── conanfile.py
│   ├── pyproject.toml         scikit-build-core, for the Python package
│   ├── CMakeLists.txt
│   ├── CHANGELOG.md
│   └── AGENTS.md
├── android/                   the Android app
├── pwa/                       the PWA
├── ios/                       the iOS app
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
`core/android` as a Gradle composite build, the PWA uses the built `core/wasm`
package as a `file:` dependency, and the iOS app uses `core/apple` as a local Swift package. So a
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

**Revision: the bias in the state (`HeadingFilter`).** The first filter had one state (the
offset) and took the magnetometer as a white measurement with σ = 2°. In a field test, a sun
tap was gone after about a minute: the magnetometer has a constant error (residual hard iron),
and the filter averaged that error back in each second. The filter now has 7 states:

- the offset θ (deg), the hard-iron bias **b** in the device frame (3, µT), and a local
  disturbance **d** in East-North-Up (3, µT), with a full covariance.
- The magnetometer model is `m = R(q)ᵀ · Rz(−θ) · (F_WMM + d) + b + noise`. The update is an
  iterated EKF (5 iterations), with the start of the linearization at the offset of the sample
  when θ is not known (σ > 30°). A sample far from the prediction (NIS > 16.3) gets a larger
  noise.
- **b** is a random walk with a very small growth (1 µT² in 10 min). The rotations and the
  WMM field make it observable, so the filter calibrates the hard iron itself (step 2 of the
  thesis, with a Kalman filter in place of the particle filter). The Android app gives it the
  raw field (`TYPE_MAGNETIC_FIELD_UNCALIBRATED`), and the OS bias is only the prior. Without
  the raw field (iOS, or an old Android device), the filter takes the calibrated field with a
  prior of 0 and estimates the error that is left.
- **d** is a first-order Gauss–Markov process (σ = 3 µT, τ = 120 s). It models that the error
  of the magnetometer is correlated in time. So the magnetometer alone gives about
  σ_d / H ≈ 8° per τ, and not 2° per second.
- A sun tap is a scalar update of θ. Through the covariance between θ and **b**, it also
  corrects **b**: true north gives the expected field in the device frame, and the difference
  to the measured field is the bias. The magnetometer then agrees with the tap.
- Not modeled: the soft iron. If the error changes with the orientation after a tap, this is
  the next step (a 3 × 3 matrix in the state, or a calibration with many orientations).

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
| Apple | `core/apple/` | C API and Swift package with a prebuilt xcframework | `Sunradar` | SwiftPM (GitHub release) |
| C++ | `core/` | Conan recipe | `sunradar` | own Conan remote, if anyone needs it |

Rules for all bindings, from OpenDocument.core:

- Mirror the C++ names, in the naming style of the language (`sun_events` in Python,
  `sunEvents` in Kotlin, JS, and Swift).
- Each binding has its own `AGENTS.md` and a test suite in its language: pytest,
  instrumented tests on an emulator, `node --test`, and XCTest.
- A new public function needs a binding and a test in every binding.
- JNI: enums cross as ordinals, so the Kotlin order must match the C++ order. Polylines
  cross as `FloatArray`, so the per-frame path allocates no objects.
- WASM: every value must be structured-clonable, so the binding can move to a Web Worker.
  Plain objects only, no embind class handles. The PWA calls it on the main thread (M9).
- Apple: SwiftPM cannot build a C++ project with Conan dependencies, so the package
  ships a prebuilt xcframework, the same as OpenDocument.core. The Swift API is on top
  of a plain C API (M11), not on Swift C++ interop or an Objective-C layer.
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
  `emscripten`, and `apple-*` for the xcframework slices.
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
| `core.yml` | `core/**` | builds and tests the core on Linux and macOS, runs clang-tidy |
| `python.yml` | `core/**` | builds wheels, runs pytest |
| `core-android.yml` | `core/**` | builds the AAR (with `native-android.yml`), runs the instrumented tests on an emulator |
| `android.yml` | `android/**`, `core/**` | builds and tests the app, runs lint and ktfmt |
| `web.yml` | `core/**`, `pwa/**`, `website/**` | builds the npm package (`node --test`), tests and builds the PWA (prettier, vitest), deploys GitHub Pages |
| `apple.yml` | `core/**`, `ios/**`, `Package.swift` | builds the xcframework, runs `swift test`, builds and tests the iOS app |
| `format.yml` | all | clang-format, black |
| `release.yml`, `publish-core.yml`, `android-release.yml` | manual, release | the release flow (see below) |

### Release

The same flow as OpenDocument.core:

1. Commit messages follow Conventional Commits (`feat(core): ...`, `fix(android): ...`).
   The scope is the project. `git cliff --bumped-version` calculates the next version
   from them.
2. Each change that a user notices gets an entry under `## Unreleased` in the
   `CHANGELOG.md` of its project, in the same pull request.
3. A manual dispatch of `release.yml` makes a draft release. A human publishes it.
4. The published release starts the publish workflows: PyPI (trusted publishing), npm
   (with provenance), Maven Central, and the xcframework for SwiftPM (with a tag `X.Y.Z`).

A monorepo has more than one version, so the tags have a prefix: `core-v1.2.0` for
the core and all its packages, and `android-v1.0.0`, `pwa-v1.0.0`, and `ios-v1.0.0` for the apps.
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

- TypeScript, Vite. Uses `@sunradar/core` (`core/wasm`) from the WASM build output (a
  `file:` dependency). The core runs on the main thread, because a frame needs less than
  1 ms; the values are plain objects, so a Web Worker is possible later.
- GitHub Pages serves the website (`website/`) and the PWA under `/app/`.
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
| M5 | `core/android` AAR (JNI and Kotlin API), Android app shell without camera | done: 11 instrumented tests pass on an emulator; on the emulator, the projection of path, horizon, and sun agrees with a hand calculation for set sensor values. |
| M6 | Camera overlay with intrinsics and distortion | implemented: Camera2 calibration and the CameraX sensor-to-view transform, with a JVM property test of the conversion. |
| M7 | Heading filter (core) with game rotation vector, magnetometer, sun tap, moon tap, and landmark on a map; UI rework | implemented and tested in C++, Python, and on an emulator. |
| M8 | Date slider, time slider, azimuth readout (vertical line through the crosshair), dates for the crosshair, solstice guides | implemented; checked on an emulator. |
| M8b | Sun detection in the camera image: the saturated region nearest to the predicted sun (within max(10°, 3σ) and at most 3° in radius) gets a box; a tap on the box aligns the heading | implemented; the detector is tested on synthetic images. |
| M9 | `core/wasm` npm package, PWA, website on GitHub Pages | implemented: 10 node tests of the package, 4 vitest tests, and a check in Chrome. Deviation: the core runs on the main thread, not in a Web Worker (a frame needs less than 1 ms). |
| M10 | `release.yml`, publishing to PyPI, npm, and Maven Central, Android APK | implemented: `scripts/release.py` and git cliff, the release workflow with a dry run, `publish-core.yml` (npm, wheels for Linux and macOS, sdist, AAR), `android-release.yml`. Open: the accounts and secrets of the registries, Windows wheels. |
| M11 | iOS app: `core/apple` as a Swift package (xcframework of the core, Swift API), and the app in `ios/` (SwiftUI, AVFoundation camera, CoreMotion, CoreLocation) with the same features as Android | implemented: a C API (`sunradar_c.h`, with a C test) below the Swift API, because the C API is stable and the Swift C++ interop is not; the xcframework for iOS, the simulator, and macOS; 8 XCTest tests; the app with the sky view, the camera, the heading filter, the alignment with the sun, the moon, or a landmark on a map (MapKit), the sun detection, the azimuth readout, the dates, and the time panel, checked in the simulator; 6 unit tests of the detector and the geodesy. |
| M12 | Moon phase in the apps; store screenshots (a demo mode in the debug builds, fastlane lanes, the workflow `screenshots.yml`) | implemented: the lit part of the moon faces the sun on Android, iOS, and the PWA. Open: the store accounts and secrets (see M10). |

The user tested most features on real phones in November 2026. So the device checks of M5 to
M11 are closed.

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
- **Licenses.** Resolved: the NREL SPA is not in the repository. The tests use JPL Horizons.
  Astronomy Engine (MIT), GeographicLib (MIT), and Eigen (MPL 2.0) are not a problem.
- **WMM validity.** WMM2025 is valid until the end of 2029. Plan an update.

## 12. Open questions

- iOS (M11): decided. `Package.swift` is at the root of the monorepo. The Swift API is on
  top of a C API, not on Swift C++ interop and not on the Objective-C layer of
  OpenDocument.core. A release attaches the xcframework and tags `X.Y.Z` for SwiftPM.
- The names of the packages: `sunradar` on PyPI and `@sunradar/core` on npm are proposals.
  Check that the names are free (the npm scope needs an npm organization "sunradar"). The
  Maven group is `at.stefl.sunradar` (decided); Maven Central needs the verified namespace
  `at.stefl`.
- Do we publish the C++ package to a Conan remote? Only if someone outside this
  repository needs it.
- The moon: decided and done. The apps show the moon with its phase, and the alignment uses
  it (M7, M12).
- Do we want a shadow mode later (for example, which wall gets the sun)? This needs a
  3D model of the room, so it is out of scope now.

## 13. Future features (proposals, not planned in detail)

The order is the priority. The terrain horizon comes first, because it changes the answer to
the main question of the app: when does the sun really appear or go away at this place?

### 13.1 Terrain horizon (most important)

The horizon of altitude 0° is wrong in the mountains and in a valley. The real horizon is the
terrain around the place. It changes the visible sunrise and sunset by many minutes.

- **Data:** a digital elevation model (DEM), for example Copernicus GLO-30 (30 m, free) or
  SRTM. The app downloads the tiles around the place (a radius of about 50–100 km) and keeps
  them in a cache for offline use.
- **Core:** a pure function that calculates the horizon profile: the altitude of the terrain
  for each azimuth (for example every 0.25°). It must include the curvature of the Earth and
  the refraction of the terrestrial line of sight. The DEM crosses the boundary as a plain
  grid (an array of heights with an origin and a step), so the bindings stay simple.
- **Use:** draw the profile as the horizon line, cut the sun path where it is below the
  terrain, and give the visible sunrise and sunset (the crossings of the sun path with the
  profile, with `find_azimuth_times()` and a search on the profile).
- **Check:** the camera image shows the real terrain line, so the user can see the error. A
  terrain line that matches the image is also a heading calibration (a measurement for the
  heading filter), like a landmark.
- **Open:** the height of the observer (GPS altitude is bad; the DEM height plus the eye
  height is better), near objects (buildings and trees are not in the DEM), and the size of
  the download.
- **Decided:** the data is Copernicus GLO-90 from AWS Open Data
  (`https://copernicus-dem-90m.s3.amazonaws.com/`), tiles of 1° in latitude and longitude as
  GeoTIFF (float32, DEFLATE, predictor 3). A tile is about 2–5 MB. The sea has no tile (HTTP 404).
  The license allows free use, also commercial, with the attribution "© DLR e.V. 2010-2014 and ©
  Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA;
  all rights reserved". The bucket sends no CORS headers, so the PWA cannot download the tiles
  directly. The PWA uses the AWS Terrain Tiles instead (Terrarium PNG, Web Mercator, zoom 10,
  `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png`, CORS allowed). The
  core converts them with `terrarium_grid()`. Near Innsbruck the two sources agree to 8 m on
  average.
- **Status:** the core part is done (`terrain.hpp`): `elevation_tiles()` lists the tiles,
  `read_elevation_geotiff()` reads one, and `horizon_profile()` takes all of them. A grid of
  about 90 m within 100 km and a step of 0.25° takes about 70 ms on a laptop.
- **Cache in the apps:** the apps keep the tiles on the disk. The cache must not grow without
  limit and must not keep old data forever: it removes the tiles that were used least recently
  when it is larger than its limit, and it checks a tile again (with its ETag) when the tile is
  older than its maximum age.

### 13.2 Virtual place: find photo spots

Drop the observer anywhere on the map, not only at the device location. Without the camera,
the sky view shows the terrain horizon (13.1) as a synthetic view. Then the user can plan:

- When does the sun or the moon rise or set behind a given peak or landmark?
- From which place is the sun or the moon in line with a landmark on a given date? This is a
  search over places, the inverse of `find_dates()`. A first version: the user picks the
  landmark and the date, and the app draws the line of places on the map.

### 13.3 Fully offline

The app must work without a network and without GPS. The user drops the place on the map, and
the device clock, the time panel, and the alignment do the rest. Most of the app is offline
already: the core needs no data from the network (the WMM and the time zones are in the app),
and the alignment with the sun or the moon needs only the camera and the orientation sensors.

What is not offline today:

- **The map.** `MapPicker` loads the OpenStreetMap tiles over the network. The tile usage
  policy of OSM does not allow a bulk download for offline use, so the app needs a map that it
  can ship or download once, for example a small vector base map (coastlines, borders, large
  roads, and place names) at low zoom levels. This is sufficient to drop the place to about
  1 km, which gives an error of the sun position of less than 0.01°.
- **The landmark alignment.** It needs a map at a high zoom level near the place. Offline, the
  alignment with the sun or the moon is the main method, and the landmark works only where
  the user downloaded a region before.
- **The terrain (13.1).** The DEM tiles must be downloaded before the trip, together with the
  map of the region.

Design points:

- A manual place is a normal source of the place, next to the device location, and the app
  keeps the last place between starts.
- The time zone of a dropped place comes from the time zones in the app (`zone.tab`) and the
  nearest place; the user can correct it in the time panel.
- A "download this region" action fetches the map, the DEM, and (later) the landmarks for one
  area, so the user can prepare a trip at home.
- The network stays optional: no feature may wait for it or fail without it. The app shows
  clearly when it is offline, so the user knows which features need a download.

This is also the base of 13.2: a virtual place is the same as a dropped place, without the
camera.

### 13.4 Planets

Astronomy Engine (already in the core) gives the planets. Show the bright planets (Venus,
Mars, Jupiter, Saturn) as points with a name. A planet is also a target for the heading
calibration at night. The cost is small.

**Status:** the core part is done (`sky.hpp`, in C++ and Python): `planet_position()` agrees
with JPL Horizons to 0.006°, and `planet_magnitude()` gives the brightness, so an app can show
only the planets that the eye sees. No app uses it yet.

### 13.5 Milky Way

Show the galactic plane and the galactic center as a line and a point, from fixed galactic
coordinates converted to the horizontal frame with Astronomy Engine. It is useful only for
night photography, and only together with 13.2 (planning). It needs the darkness of the sky
(the sun more than 18° below the horizon, and the moon) to be useful. Decide how to integrate
it after 13.1 and 13.2.

**Status:** the core part is done (`sky.hpp`, in C++ and Python): `galactic_position()` for the
galactic center and `galactic_plane()` for the center line. No app uses it yet.
