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
│ android/ apps     │ │ web/ apps         │ │ ios/ apps         │ │ scripts, notebooks│
│ Kotlin, Compose   │ │ TypeScript        │ │ Swift, SwiftUI    │ │ reference checks  │
└─────────┬─────────┘ └─────────┬─────────┘ └─────────┬─────────┘ └─────────┬─────────┘
          │ AAR                 │ npm                 │ SwiftPM             │ PyPI
┌─────────┴─────────┐ ┌─────────┴─────────┐ ┌─────────┴─────────┐ ┌─────────┴─────────┐
│ core/android      │ │ core/wasm         │ │ core/apple        │ │ core/python       │
│ JNI + Kotlin API  │ │ embind + JS/d.ts  │ │ Swift package     │ │ pybind11          │
└─────────┬─────────┘ └─────────┬─────────┘ └─────────┬─────────┘ └─────────┬─────────┘
          └─────────────────────┴──────────┬──────────┴─────────────────────┘
┌──────────────────────────────────────────┴──────────────────────────────────────────┐
│ core/src/almucantar/*.hpp: the public C++ API (small, value types, free functions)     │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ core/src/almucantar/internal/: solar models, refraction, WMM, orientation,             │
│ calibration, camera model, projection, search, numerics                              │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### Rules for the boundary

- Every binding wraps the public API only. A binding never includes
  `almucantar/internal/...`.
- The core uses UTC only, as Unix time in seconds (`double`). The platform converts
  local time and time zones, because `java.time`, `Intl`, and `Foundation` do this well.
  The support for `std::chrono` time zones in the libc++ of the NDK is not reliable.
- The core does not read sensors, files, or the camera. The platform gives it numbers.
- The core returns geometry in screen pixels (points and polylines). The platform draws
  them.
- The core allocates little in the per-frame path: a few vectors of a few hundred
  points. The platform calls it at 30–60 Hz.

## 4. Repository layout

The repository is a monorepo with the name of the core, almucantar (decided 2026-11-03), because
it holds more than one app (section 14). `core/` is the library with all its bindings, the same as
OpenDocument.core. The apps are grouped by platform: each platform is one build with a shared part
and one part for each app, because the apps on one platform share much code (the place, the maps,
the terrain, the camera, the heading). The shared part never depends on an app.

```
almucantar/
├── core/
│   ├── src/almucantar/        public API (*.hpp), installed
│   ├── src/almucantar/internal/ everything else, not installed
│   ├── test/                  Catch2 tests, reference data, and the scripts that make it
│   ├── python/                pybind11 binding, Python package `almucantar`
│   ├── android/               JNI binding and Kotlin API, AAR
│   ├── wasm/                  embind binding and JS API, npm package
│   ├── apple/                 C API and Swift package (Package.swift at the root)
│   ├── cmake/
│   ├── conan/profiles/
│   ├── third_party/           Astronomy Engine (a single C file)
│   ├── conanfile.py
│   ├── pyproject.toml         scikit-build-core, for the Python package
│   ├── CMakeLists.txt
│   ├── CHANGELOG.md
│   └── AGENTS.md
├── android/                   the Android apps: shared/, sunradar/ (one Gradle project)
├── ios/                       the iOS apps: Shared/, Sunradar/ (one XcodeGen project)
├── web/                       one npm workspace: shared/, sunradar/ (the PWA), website/
├── assets/                    the logo, and the offline base map (assets/basemap/)
├── scripts/                   format, release.py, the base map, the store screenshots
├── screenshots/               the design of the store screenshots
├── .github/workflows/         one workflow per project and per package
├── Package.swift              the Swift package of the core (SwiftPM needs it at the root)
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
  headers too. almucantar installs only `src/almucantar/*.hpp`, so a consumer cannot depend
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
// public: src/almucantar/sun.hpp
enum class SolarModel { noaa, astronomy_engine };

// internal: src/almucantar/internal/solar_model.hpp
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
| `SpaModel` | 0.0003° | NREL SPA | not done: the license does not allow it in the repository |

Astronomy Engine is not in Conan Center, so the core keeps a copy at a fixed commit in
`core/third_party/astronomy-engine/`.

Reference data for tests: tables from JPL Horizons, for some locations (equator, Vienna,
60° N, 70° S) and some dates (solstices, equinoxes, random).

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

- Android: `TYPE_GAME_ROTATION_VECTOR` for the orientation, and the heading filter of the
  core (5.4a) for the heading. A device without a game rotation vector uses
  `TYPE_ROTATION_VECTOR`, where the OS does the fusion and the calibration of the
  magnetometer.
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

1. **Sun or moon tap.** The user taps the real sun or moon on the camera image. Since M8b,
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
- One umbrella header, `almucantar/almucantar.hpp`, includes all public headers.

The public headers:

| Header | Content |
|---|---|
| `types.hpp` | `Observer`, `Horizontal`, `Atmosphere`, `Quaternion`, `Camera`, `ScreenPoint` |
| `sun.hpp` | `SolarModel`, `sun_position()`, `sun_events()`, `sun_path()` |
| `moon.hpp` | `moon_position()`, `moon_illuminated_fraction()` |
| `search.hpp` | `SearchOptions`, `TimeMatch`, `find_time()`, `find_dates()` |
| `magnetic.hpp` | `magnetic_declination_deg()` |
| `view.hpp` | `View` (camera, orientation, screen rotation, heading offset), `ScreenRotation`, `Camera`, `camera_from_fov()`, `project()`, `unproject()`, `project_line()`, `project_path()`, `horizon_line()`. The view direction for auto mode is `unproject()` of the screen center. |
| `heading.hpp` | `HeadingFilter` and its functions (5.4a), `heading_from_direction()`, `heading_from_magnetometer()` |
| `terrain.hpp` | `ElevationGrid`, `horizon_profile()`, `terrain_altitude_deg()`, `terrain_events()`, `elevation_tiles()`, `read_elevation_geotiff()`, `elevation_geotiff_prefix_size()`, `map_tiles()`, `terrarium_grid()` (13.1) |
| `sky.hpp` | `planet_position()`, `planet_magnitude()`, `galactic_position()`, `galactic_plane()` (13.4, 13.5; C++ and Python only) |
| `version.hpp` | `version()` |

Everything else is internal: the model classes, the refraction formula, the WMM
coefficients, the numerics, and the details of the projection. A function becomes public
only if an app needs it. The sun detection in the camera image (M8b) is in the apps, not
in the core.

### 5.8 Bindings and packages

| Binding | Directory | Technology | Package | Registry |
|---|---|---|---|---|
| Python | `core/python/` | pybind11, scikit-build-core | `almucantar` | PyPI |
| Android | `core/android/` | JNI by hand, Kotlin API | `at.stefl.almucantar:core-android` (AAR) | Maven Central |
| Web | `core/wasm/` | Emscripten, embind, hand-written `index.js` and `index.d.ts` | `@almucantar/core` | npm |
| Apple | `core/apple/` | C API and Swift package with a prebuilt xcframework | `Almucantar` | SwiftPM (GitHub release) |
| C++ | `core/` | Conan recipe | `almucantar` | own Conan remote, if anyone needs it |

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
| Vectors, quaternions | handroll (`internal/vector.hpp`, about 60 lines) | — |
| Tests | Catch2 | Conan Center, `catch2/3.x` |
| Second solar model, moon, planets | Astronomy Engine | a copy in `core/third_party/` |
| DEFLATE of the GeoTIFF and PNG tiles | miniz | Conan Center, `miniz/3.0.2` |
| Magnetic oracle for tests | the official WMM2025 test values | NOAA NCEI, in the coefficient download |
| NOAA solar model | handroll | — |
| WMM2025 | handroll | — |
| Root finding (Brent) | handroll | — |
| Python binding | pybind11 | Conan Center, `pybind11/3.0.x` |
| Python build | scikit-build-core | PyPI, build dependency only |

A test oracle is a test dependency only. The app does not link it.

## 7. Build, release, and conventions

### Build

- CMake ≥ 3.24, C++20, Conan 2.
- CMake options, one per binding, the same as `ODR_*` in OpenDocument.core:
  `ALMUCANTAR_TESTS`, `ALMUCANTAR_PYTHON`, `ALMUCANTAR_JNI`, `ALMUCANTAR_WASM`,
  `ALMUCANTAR_APPLE`. `conanfile.py` has the matching options (`with_python`, ...).
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
`web/` does not build the AAR:

| Workflow | Paths | Does |
|---|---|---|
| `core.yml` | `core/**` | builds and tests the core on Linux and macOS, runs clang-tidy |
| `python.yml` | `core/**` | builds wheels, runs pytest |
| `core-android.yml` | `core/**` | builds the AAR (the native libraries come from the reusable `native-android.yml`), runs the instrumented tests on an emulator |
| `android.yml` | `android/**`, `core/**` | builds and tests the shared module and the apps, runs lint and ktfmt |
| `web.yml` | `core/**`, `web/**` | builds the npm package (`node --test`), tests and builds the workspace `web/` (prettier, vitest), deploys GitHub Pages |
| `apple.yml` | `core/**`, `ios/**`, `Package.swift` | builds the xcframework, runs `swift test`, builds the shared framework alone and tests it, builds the iOS apps |
| `format.yml` | all | clang-format, black |
| `screenshots.yml` | manual | makes the store screenshots with the demo mode (M12) |
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
the core and all its packages, and one prefix for each app on each platform, for example
`sunradar-android-v1.0.0`, `sunradar-pwa-v1.0.0`, and `sunradar-ios-v1.0.0`.
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
- Sensors: `TYPE_GAME_ROTATION_VECTOR` and `TYPE_MAGNETIC_FIELD_UNCALIBRATED` for the
  heading filter (5.4a). `TYPE_ROTATION_VECTOR` is the fallback.
- Location: the coarse location (fused, network, or GPS provider). Without it, the main
  city of the device time zone (IANA `zone.tab`), and the app says so. The status shows
  the nearest town (geocoder) and the error as a power of ten, with its error on the sky.
  The user can also drop a place on the offline map, so they can plan a flat from home (13.3).
- Screens:
  1. Sky view: camera or no camera, path, horizon, terrain, sun and moon, compass accuracy.
  2. Time panel: mode switch (fixed time or auto), time slider, date slider.
  3. Alignment: sun or moon tap, sun detection, landmark on a map.
  4. Place panel: search, the offline map, and "Keep offline".

## 9. PWA (after Android)

- TypeScript, Vite. Uses `@almucantar/core` (`core/wasm`) from the WASM build output (a
  `file:` dependency). The core runs on the main thread, because a frame needs less than
  1 ms; the values are plain objects, so a Web Worker is possible later.
- GitHub Pages serves the website (`web/sunradar/site/`) and the PWA (`web/sunradar/`) under `/app/`.
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
| M11 | iOS app: `core/apple` as a Swift package (xcframework of the core, Swift API), and the app in `ios/` (SwiftUI, AVFoundation camera, CoreMotion, CoreLocation) with the same features as Android | implemented: a C API (`almucantar_c.h`, with a C test) below the Swift API, because the C API is stable and the Swift C++ interop is not; the xcframework for iOS, the simulator, and macOS; 8 XCTest tests; the app with the sky view, the camera, the heading filter, the alignment with the sun, the moon, or a landmark on a map (MapKit), the sun detection, the azimuth readout, the dates, and the time panel, checked in the simulator; 6 unit tests of the detector and the geodesy. |
| M12 | Moon phase in the apps; store screenshots (a demo mode in the debug builds, fastlane lanes, the workflow `screenshots.yml`) | implemented: the lit part of the moon faces the sun on Android, iOS, and the PWA. Open: the store accounts and secrets (see M10). |
| M13 | Terrain horizon (13.1) and the offline place (13.3) in the core and in all apps | implemented: the terrain line, the visible sunrise and sunset, the moon behind the terrain, the 2 km clearance for surface models, the coarse tiles first, the Place panel with the offline map, and "Keep offline". |

The user tested most features on real phones in November 2026. So the device checks of M5 to
M11 are closed.

**Releases:** all releases are pre-releases on GitHub: `core-v0.1.0` and
`sunradar-android-v0.1.0` (2026-11-01), and `core-v0.2.0`, `sunradar-android-v0.2.0`, and
`sunradar-pwa-v0.1.0` with the work of M12 and M13 (2026-11-03). These releases still use the old
name of the core (`sunradar`), and the tags of the apps had no app name before the split
(`android-v*`, `pwa-v*`); they were renamed on 2026-11-03. The registries skip the core, because no credentials are set, so the packages are
only attached to the GitHub release. iOS has no release yet.

### Next

1. Set up the accounts and secrets of M10 and M12: npm (the organization "almucantar"), PyPI
   (`almucantar`), Maven Central, the Play Store, and the Apple developer account. Then the iOS app
   can get a publish workflow.
2. starradar (14.2): the layout of the sites, the star data, the core, then the PWA and the
   website together.
3. The core parts of the photo planner (14.1), in this order: the windows of darkness, the line
   of sight over the DEM, and the inverse search.
4. A prototype of the photo planner as a web app (14.1).

Smaller items: the terrain line as a heading calibration (13.1), the Windows wheels (M10), and
WMM2030 before the end of 2029.

The Python binding comes early (M2), because it is the easiest binding to test. It
also checks that the public API is easy to bind before the API grows, and it gives a
fast way to plot paths and to compare models.

## 11. Risks

- **Compass indoors.** This is the largest risk. If the calibration in M7 does not get
  the error to about 2°, the app must make the map heading the main method. Status: the
  heading filter (5.4a) and the alignment with the sun, the moon, or a landmark are done, and
  a landmark also works from a dropped place. Keep this risk open until field tests indoors
  give a number for the error.
- **Camera intrinsics.** Some devices give no intrinsics or wrong ones. The fallback from
  the focal length and the sensor size gives about 1–3° at the edges.
- **Preview crop.** The conversion from the sensor frame to the CameraX preview frame is
  a frequent source of offsets. A JVM property test covers the conversion (M6), and the user
  checked it on real phones in November 2026.
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
- The names of the packages: decided (2026-11-03). The core is almucantar: `almucantar` on PyPI
  and `@almucantar/core` on npm.
  On 2026-11-03 both packages do not exist yet, but this does not reserve the names. The npm
  scope needs an npm organization "sunradar". The
  Maven group is `at.stefl.sunradar` (decided); Maven Central needs the verified namespace
  `at.stefl`.
- Do we publish the C++ package to a Conan remote? Only if someone outside this
  repository needs it.
- The moon: decided and done. The apps show the moon with its phase, and the alignment uses
  it (M7, M12).
- Do we want a shadow mode later (for example, which wall gets the sun)? This needs a
  3D model of the room, so it is out of scope now.

## 13. Features after the milestones

These started as proposals. The order was the priority. The terrain horizon came first,
because it changes the answer to the main question of the app: when does the sun really
appear or go away at this place? Each part has a status. 13.1 and 13.3 are done in all apps
(M13). The core parts of 13.4 and 13.5 are done, and these features go to the photo planner
(14.1), not to sunradar.

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

**Status:** the dropped place is done (13.3), and the sky view without the camera shows the
terrain. The search over places is part of the photo planner: it is item 1 of the core parts in
14.1.

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

**Decided:** the offline map is Natural Earth 1:10m (public domain), in the app as
`assets/basemap/basemap.json` (4.3 MB, 1.7 MB with gzip; `scripts/basemap/build_basemap.py`): the
coastlines, the borders, the larger lakes and rivers, and 7342 places with their IANA time
zones. A dropped place gets the time zone of the nearest place that has one.

**Status:** done in the PWA, on Android, and on iOS: the Place panel (search and tap on the
map, without a network), the last place, and the times in the time zone of the place. A dropped
place and the last place also get the terrain horizon. "Keep offline" in the Place panel keeps the
terrain tiles of a place for a trip.

### 13.4 Planets

Astronomy Engine (already in the core) gives the planets. Show the bright planets (Venus,
Mars, Jupiter, Saturn) as points with a name. A planet is also a target for the heading
calibration at night. The cost is small.

**Status:** the core part is done (`sky.hpp`, in C++ and Python): `planet_position()` agrees
with JPL Horizons to 0.006°, and `planet_magnitude()` gives the brightness, so an app can show
only the planets that the eye sees. The planets are for the photo planner and the sky map (14),
not for sunradar.

### 13.5 Milky Way

Show the galactic plane and the galactic center as a line and a point, from fixed galactic
coordinates converted to the horizontal frame with Astronomy Engine. It is useful only for
night photography, and only together with 13.2 (planning). It needs the darkness of the sky
(the sun more than 18° below the horizon, and the moon) to be useful. Decide how to integrate
it after 13.1 and 13.2.

**Status:** the core part is done (`sky.hpp`, in C++ and Python): `galactic_position()` for the
galactic center and `galactic_plane()` for the center line. The Milky Way is for the photo planner
and the sky map (14), not for sunradar.

## 14. The family of apps: sunradar, a sky map, and the photo planner

**Decided (2026-11-02, order changed 2026-11-03):** the core gives more than one app, and a new
app is mostly a new UI. The core has the name almucantar, and the repository holds all apps
(section 4). The order of the apps:

1. **sunradar** (this app) stays simple: "when is the sun at this place?", for a flat, a garden,
   or a window. It starts from the camera and from the place where the user is.
2. **starradar**, a sky map (an app like Sky Map or Stellarium), is the second app, and it comes
   next. It is the nearest to sunradar: the same camera view, heading filter, and projection, and
   the core already has the planets and the Milky Way (14.2).
3. **The photo planner** is the third app. It comes after the sky map.

### 14.1 The photo planner (the third app)

A friend of the user proposed the idea: photographers point at a place on a map and look for the
times when the sun, the moon, or the Milky Way is at a given place in the picture, for example the
moon that rises behind a peak or the Milky Way above a building. Apps for this exist (PhotoPills,
The Photographer's Ephemeris, PlanIt!), and photographers pay for them. So the niche is small, but
it has a market, and the strengths of the core match it.

**Not a mode of sunradar.** The planner starts from the map and a target ("from where and when is
the moon behind this peak?"). It needs other screens: a map with the observer and the target, a
calendar of results, the phases of the twilight and of the moon, and the darkness of the sky.

**Its main features** are the ones that sunradar needs only a little:

- The terrain horizon from a DEM, also offline (13.1, 13.3). The user plans a place where they
  are not, so they cannot see the horizon, and many tools use the horizon of 0°.
- The moon behind the terrain, the planets (13.4), and the Milky Way (13.5) as subjects of a photo.
- The camera view of sunradar, so the user can check a plan at the place, and the PWA, which needs
  no installation.

**The parts in the core first:**

1. The inverse search: the places from which a body (the sun, the moon, the galactic center) is in
   line with a target (a place with a height) at a given date, and the times for a given observer
   and target over a year. The second is `find_dates()` for any body and for a direction that
   comes from the target.
2. The rise and the set of the moon behind the terrain. **Done:** `terrain_events()` with
   `Body::moon`, in all bindings.
3. The windows of darkness: the astronomical twilight, the moon below the horizon or nearly new,
   and the galactic center above the terrain. These give the times for the Milky Way (13.5).
4. The line of sight between the observer and the target over the DEM: is the target visible?

**The order:** the core parts first, then a prototype of the planner as a second web app next to
the PWA (the cheapest UI to try), then a decision about a native app.

### 14.2 starradar: the sky map (the second app)

**Decided (2026-11-03):** the name is **starradar**, the pair of sunradar. A web search found no
app with this name. The PWA and the website come first and together, the native apps later.

**The promise:** point the phone at the night sky and see what is there: the stars, the
constellations, the planets, the moon, and the Milky Way. The market has strong free apps
(Stellarium, Sky Map, Star Walk), so starradar needs reasons to exist. These are the strengths of
the core and of sunradar:

- **The real horizon.** The terrain horizon (13.1) shows when a star, a planet, or the Milky Way
  really rises behind the mountain, not at 0°.
- **Offline.** The catalogue, the base map, and the terrain of a kept place are in the app, so it
  works on a mountain without a network (13.3).
- **An honest heading.** The heading filter shows its error, and the user aligns it with a bright
  star or a planet, as sunradar does with the sun.
- **No ads, open source, and a PWA without an installation.**

#### The data

| Data | Source | License | Size |
|---|---|---|---|
| Stars to magnitude 6.0 (about 5000) | Yale Bright Star Catalogue, 5th edition (VizieR V/50): position J2000, proper motion, V magnitude, B−V color | free use with the acknowledgement of the ADC and of Hoffleit & Warren (1991); **check** the ReadMe of V/50 before the first release | about 100 KB with gzip |
| Names of the bright stars | the IAU Catalog of Star Names (WGSN) | **check** | small |
| Lines of the 88 constellations | the stick figures of d3-celestial (BSD-3, Olaf Frohn), matched to the HR numbers of the catalogue | BSD-3 with attribution; **check** the sources that it names | small |
| Names of the constellations | IAU | public | small |

`scripts/stars/build_stars.py` makes `assets/stars/stars.json` from the sources, the same way as
`scripts/basemap/` makes the base map. The apps load it like the base map. The core gets the
catalogue as plain arrays, so the bindings stay simple.

#### The core

- `star_positions(observer, unix_utc, stars)`: the horizontal directions of many stars at once,
  from J2000 with the proper motion, the precession, the nutation, the aberration, and the
  refraction (Astronomy Engine). The input and the output are flat arrays (one call per second
  for all stars), and a frame only projects.
- `project_points(view, directions)`: the screen points of many directions in one call, for the
  per-frame path.
- The planets (`planet_position`, `planet_magnitude`) and the Milky Way (`galactic_plane`) exist in
  C++ and Python. They and the new functions get the WASM binding first, and the AAR and the Swift
  package when the native apps need them. This is a deviation from the rule "every binding",
  the same as for `sky.hpp` now.
- Precision: 0.01° is sufficient, because a star on the screen is several pixels large. Test
  against JPL Horizons for some bright stars, as for the sun.

#### The PWA (`web/starradar/app`)

1. **The sky view:** the stars as points (size from the magnitude, color from B−V), the
   constellation lines and names, the planets with their names, the moon with its phase, the
   Milky Way (the center line and the galactic center), the terrain horizon, and the cardinal
   points. The camera image is optional, the same as in sunradar.
2. **Tap an object:** its name, magnitude, altitude, and the time of its rise and set, also behind
   the terrain.
3. **Search:** "Saturn", "Polaris", "Orion": an arrow at the screen border leads to the object.
4. **The time panel:** another night or hour. The status shows the darkness: the twilight phases
   and the moon.
5. **The alignment:** tap a bright star or a planet, as the sun tap in sunradar.
6. **A red night mode**, so the eyes stay adapted to the dark.
7. From the shared package: the place panel, the terrain, the orientation, and the time zone.

#### The website (`web/starradar/site`)

The same form as the sunradar website: a scroll story with a sticky card for each chapter over a
sky that the core calculates, then a playground, then the facts.

| Chapter | Card | The sky |
|---|---|---|
| Hero | "Starradar: point your phone at the night sky." | the sky of now over Vienna; drag to change the time |
| Dusk | "The first stars" | the sky gets dark from sunset to the astronomical night; the planets and the brightest stars appear first |
| Constellations | "Lines between the stars" | the lines and the names fade in; Orion or the Big Dipper is highlighted |
| The sky turns | "Everything turns around one point" | hours in seconds: the stars turn around Polaris, rise in the east, and set in the west |
| Planets | "Wanderers" | weeks in seconds: the planets move against the stars, along the ecliptic |
| Behind the mountain | "The real horizon" | Innsbruck: the terrain line, and the Milky Way that rises late behind the Nordkette |
| Milky Way | "When the Milky Way is best" | the band and the center, and the dark hours without the moon |
| Compass | "Align with a star" | a tap on a bright star corrects the heading, the same story as in sunradar |
| Play | "Your sky" | the place, the date, and the time of the user |
| Rest | precise, offline, open source, and a link to sunradar | dim |

The drawing of the sky of the sunradar website (`web/sunradar/site/src/sky.ts`) is a panorama. The
star sky needs the same panorama plus the stars, so the common parts (the colors of the sky, the
projection of the panorama, the horizon, the terrain) move to `web/shared`.

#### The layout of the sites

**Decided (2026-11-03):** one folder for each app on GitHub Pages: `/` is a short page of the
family, `/sunradar/` and `/sunradar/app/` are sunradar, and `/starradar/` and `/starradar/app/`
are starradar. Each PWA has its own scope, so the two apps install and cache apart. In the
workspace: `web/home/`, `web/sunradar/{app,site}/`, `web/starradar/{app,site}/`, and
`web/shared/`.

#### The order of the work

**Status (2026-11-03):** steps 1 to 5 are done. The core has `star_positions()` and
`project_points()` (5080 stars in about 1 ms in WebAssembly, tested against an independent
calculation and for the aberration and the proper motion). The PWA (`web/starradar/app`) and the
website (`web/starradar/site`) are on GitHub Pages under `/starradar/`. The licenses of the star
data are in `assets/stars/README.md`; the one of the Bright Star Catalogue rests on an answer of
the ADC, not on a license text. The Android app (`android/starradar`) is done too, with the same
features as the PWA. Next: the iOS app, and the field test of the alignment with a star.

1. The layout: the folders of the workspace, the page of the family, and sunradar under
   `/sunradar/`.
2. The data: `scripts/stars/`, `assets/stars/`, and the licenses.
3. The core: `star_positions()` and `project_points()` in C++, Python, and WASM, with tests
   against JPL Horizons.
4. The PWA: the sky view first (stars, constellations, planets, moon, terrain), then tap, search,
   alignment, and the night mode.
5. The website, with the shared panorama.
6. The native apps (`android/starradar/`, `ios/Starradar/`), when the PWA is stable.

### 14.3 What this means for sunradar

- The terrain horizon stays in sunradar, but it is a second feature. It matters in the mountains
  (in Innsbruck it moves the sunrise in winter by more than one hour). In a city, buildings hide
  the sun, and the camera shows the real obstruction. Copernicus and Terrarium are surface models:
  their heights include buildings and forests, smeared over cells of about 90 m, which gives a
  false wall around the observer in a city (5.6° in the centre of Vienna). **Done:** the profile of
  a surface model ignores the terrain nearer than 2 km (`HorizonOptions.min_distance_m`; Vienna:
  2.3°, the Kahlenberg), and an `ElevationSource` in each app says if a source is a surface model,
  so a bare-earth source can use all terrain later. **Done:** the apps
  load the detailed terrain only where it matters (where the coarse profile is higher than 1°), so
  a flat place needs about 3 MB in the native apps, not 44 MB.
- The planets and the Milky Way do not come to sunradar. They are for the photo planner and the
  sky map.
