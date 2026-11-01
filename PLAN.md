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
| NOAA solar equations (1901–2099) | about 0.01° | 2 s |
| Location error of 2 km (coarse location) | about 0.02° | 5 s |
| Phone clock error | < 1 s | < 1 s |
| Sun disc diameter, for comparison | 0.53° | 2 min |

### Conclusions

- The solar model is not the limit. A model with an error of 0.05° or better is
  sufficient, and NOAA (about 0.01°) is more than sufficient.
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
  M5 confirm it or not.

## 3. Architecture

```
┌──────────────────────────────┐   ┌──────────────────────────────┐
│ Android app (Kotlin/Compose) │   │ PWA (TypeScript)             │
│ CameraX, SensorManager,      │   │ getUserMedia,                │
│ location, UI, time zones     │   │ AbsoluteOrientationSensor,   │
│                              │   │ Geolocation, UI, Intl        │
└──────────────┬───────────────┘   └──────────────┬───────────────┘
               │ JNI                              │ embind
┌──────────────┴──────────────────────────────────┴───────────────┐
│ libsunradar (C++20)                                              │
│ solar models · magnetic model · orientation and calibration ·    │
│ camera model and projection · path, horizon, and search          │
└──────────────────────────────────────────────────────────────────┘
```

### Rules for the boundary

- The core uses UTC only, as Unix time in seconds (`double`). The platform converts
  local time and time zones, because `java.time` and `Intl` do this well. The support for
  `std::chrono` time zones in the libc++ of the NDK is not reliable.
- The core does not read sensors, files, or the camera. The platform gives it numbers.
- The core returns geometry in screen pixels (points and polylines). The platform draws
  them.
- The core does not allocate in the per-frame path. The platform calls it at 30–60 Hz.

## 4. Repository layout

The repository is a monorepo. Each platform has its own directory, and each
directory is a self-contained project with its own build system. All platforms use
the C++ core in `core/`.

```
sunradar/
├── core/                  C++ core, a self-contained CMake and Conan project
│   ├── include/sunradar/  public API
│   ├── src/
│   ├── tests/             Catch2 tests
│   │   └── data/          reference data for tests
│   ├── tools/             scripts to make reference data
│   ├── bindings/wasm/     embind wrapper for the PWA (M8)
│   ├── cmake/
│   ├── conan/profiles/    android-armv8, android-x86_64, emscripten
│   ├── conanfile.py
│   └── CMakeLists.txt
├── android/               Gradle project, with the JNI wrapper in app/src/main/cpp (M4)
├── pwa/                   the PWA (M8)
├── ios/                   the iOS app (later, if we need it)
├── .github/workflows/     CI for all projects
├── PLAN.md
└── README.md
```

The JNI wrapper belongs to `android/`, because it depends on the Android NDK and on
the Kotlin classes. The embind wrapper belongs to `core/`, because Emscripten builds
it as a target of the core.

## 5. C++ core

### 5.1 Solar models

An interface lets us use different models. The main reason is testing: we compare the
models with each other and with external reference data. Users do not need a choice.

```cpp
struct Observer { double lat_deg, lon_deg, height_m; };
struct Atmosphere { double pressure_hpa = 1010, temperature_c = 10; };
struct Horizontal { double azimuth_deg, altitude_deg; };  // azimuth from true north, clockwise

class SolarModel {
public:
  virtual ~SolarModel() = default;
  // Geometric position, without refraction.
  virtual Horizontal sun(double unix_utc, const Observer&) const = 0;
};

Horizontal apply_refraction(Horizontal, const Atmosphere&);  // separate step
```

Implementations:

| Model | Accuracy | Source | Use |
|---|---|---|---|
| `NoaaModel` | about 0.01° | handroll, about 100 lines | default in the app |
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
- Alternative: GeographicLib (`geographiclib/2.6` in Conan Center, MIT) contains
  `MagneticModel`. But it loads the coefficients from data files at run time, so Android
  must copy assets and the PWA must preload files. We use GeographicLib as a test oracle
  for our WMM code.

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

1. **Sun or moon tap.** The user taps the real sun or moon on the camera image. The core
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
- The app must convert the intrinsics from the sensor frame to the preview frame, because
  CameraX crops and scales the preview.
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

- `sun_path(date, observer)`: samples every 5 min from sunrise to sunset, with flags for
  full hours. It also handles polar day and polar night.
- `sun_events(date, observer)`: sunrise, sunset, solar noon, and the altitude at noon.
- `horizon_line(camera, orientation)`: points of altitude 0° at all azimuths, projected
  and clipped.
- `find_time(view_dir, date, observer)` for auto mode:
  1. Find the sample of the path with the smallest angle to `view_dir`.
  2. Refine the time with Brent's method between the two adjacent samples.
  3. Return the time and the angular distance. The UI shows "not today" if the distance
     is more than a threshold (for example 3°).
- `find_dates(view_dir, observer)` for the date slider: each point in the sky between the
  path of the winter solstice and the path of the summer solstice gets the sun on about
  two dates per year, one on each side of a solstice. A 2D search over the day of the
  year and the time of day finds them.

### 5.7 Public API and bindings

- A C++ API with value types for the tests and for embind.
- A thin JNI layer for Android. It gets primitive arrays and returns `FloatArray` for
  polylines, so there are no object allocations per frame.
- Later, we can generate the bindings (for example with djinni), if the API becomes large.

## 6. Dependencies

| Need | Choice | Source |
|---|---|---|
| Vectors, quaternions, least squares | Eigen | Conan Center, `eigen/3.4.x` or `5.0.x` |
| Tests | Catch2 | Conan Center, `catch2/3.x` |
| Second solar model, moon | Astronomy Engine | `FetchContent` or own recipe |
| Magnetic oracle for tests | GeographicLib | Conan Center, `geographiclib/2.6` |
| Precise reference (optional) | SOFA | Conan Center, `sofa/20231011` |
| NOAA solar model | handroll | — |
| WMM2025 | handroll | — |
| Root finding (Brent) | handroll | — |

Notes:

- Eigen is header-only, so it works with the NDK and with Emscripten without extra work.
  If we use Eigen only for small fixed-size types, we can replace it with a small
  handrolled `vec3` and `quat` later.
- Each test oracle (GeographicLib, SPA, SOFA) is a test dependency only. The app does not
  link it.

## 7. Build and tooling

- CMake ≥ 3.24, C++20, Conan 2.
- Conan profiles:
  - `host` (macOS) for development and tests.
  - `android-arm64` and `android-x86_64` (the x86_64 one is for the emulator), with
    `tools.android:ndk_path`.
  - `emscripten`, for the PWA.
- Gradle runs `conan install` for each ABI before `externalNativeBuild`, and gives the
  Conan toolchain file to CMake.
- CI (GitHub Actions): it builds and tests the core on Linux and macOS, it builds the
  Android AAR or APK, and later it builds the WASM.
- Static analysis: `clang-tidy` and the sanitizers (ASan, UBSan) on the host build.

## 8. Android app

- Kotlin, Jetpack Compose, `minSdk 28` (for `LENS_DISTORTION`).
- CameraX `PreviewView`, plus Camera2 interop to read the intrinsics.
- The overlay is a Compose `Canvas` on top of the preview.
- Sensors: `TYPE_ROTATION_VECTOR`, and `TYPE_GAME_ROTATION_VECTOR` for the gyro hold.
- Location: the fused location provider with coarse accuracy, and also a manual pick on
  a map. The manual pick lets the user plan a flat from home.
- Screens:
  1. Sky view: camera or no camera, path, horizon, sun marker, compass accuracy.
  2. Controls: mode switch (fixed time or auto), time slider, date slider.
  3. Calibration: sun or moon tap, map heading.

## 9. PWA (after Android)

- TypeScript, Vite, the core as WASM through Emscripten and embind.
- Camera: `getUserMedia`. Orientation: `AbsoluteOrientationSensor`, with
  `deviceorientation` as a fallback. iOS needs a permission prompt for the sensors.
- FOV: a slider, or calibration with two sun or moon taps.
- Expect a lower precision than on Android, because the PWA has no camera intrinsics and
  no compass accuracy status.

## 10. Milestones

| # | Content | Done when |
|---|---|---|
| M0 | Repository, CMake, Conan profiles, Catch2, CI | an empty test runs on the host and in CI |
| M1 | `NoaaModel`, refraction, sunrise and sunset, `sun_path` | tests agree with JPL Horizons to ≤ 0.05° |
| M2 | WMM2025, orientation, camera model, projection, horizon | tests agree with GeographicLib; projection round-trip ≤ 1 px |
| M3 | Search: `find_time`, `find_dates` | tests for auto mode and dates pass, including polar cases |
| M4 | Android shell without camera: sky view with sensors, JNI | the path moves correctly when you turn the phone |
| M5 | Camera overlay with intrinsics and distortion | outdoors, the real sun is on the predicted marker within 2° |
| M6 | Calibration: sun or moon tap, map heading, gyro hold | indoors, the error after calibration is ≤ 2° |
| M7 | Date slider, auto mode UI, polish | usable in a real flat visit |
| M8 | PWA | the same features, with the documented lower precision |

## 11. Risks

- **Compass indoors.** This is the largest risk. If the calibration in M6 does not get
  the error to about 2°, the app must make the map heading the main method.
- **Camera intrinsics.** Some devices give no intrinsics or wrong ones. The fallback from
  the focal length and the sensor size gives about 1–3° at the edges.
- **Preview crop.** The conversion from the sensor frame to the CameraX preview frame is
  a frequent source of offsets. Test it on two or three devices.
- **Licenses.** Check the NREL SPA license before we put the code in the repository.
  Astronomy Engine (MIT), GeographicLib (MIT), and Eigen (MPL 2.0) are not a problem.
- **WMM validity.** WMM2025 is valid until the end of 2029. Plan an update.

## 12. Open questions

- Do we need iOS later? If yes, the core already supports it, and the app can be native
  Swift or Kotlin Multiplatform.
- Do we show the moon as well? Astronomy Engine gives it at no extra cost, and the
  calibration needs it anyway.
- Do we want a shadow mode later (for example, which wall gets the sun)? This needs a
  3D model of the room, so it is out of scope now.
