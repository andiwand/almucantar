# Changelog

The changes in the core, for the users of the core: the apps, the bindings, and
the packages on PyPI, npm, Maven Central, and SwiftPM. Each release of the core
has the tag `core-vX.Y.Z`.

An entry goes under `Unreleased` in the pull request that makes the change. Write
an entry only for a change that a user of the core notices: the API, the results,
the precision, or the packages. Refactoring, tests, and CI do not get an entry. A
change that breaks the API starts with **Breaking**.

The release puts the version and the date on the entries under `Unreleased`, and
starts a new empty `Unreleased`. The release refuses an empty `Unreleased`.

## Unreleased

### API

- The public API is `sunradar/sunradar.hpp`: `sun_position()`, `sun_events()`, and
  `sun_path()`, with the value types `Observer`, `Horizontal`, `Atmosphere`, and the
  option structs. `SolarModel` selects the model. The only model is `noaa`.
- `magnetic_declination_deg()` gives the magnetic declination from the World Magnetic
  Model 2025 (valid 2025 to 2029).
- `View` (orientation quaternion, screen rotation, heading offset, and `Camera`) with
  `project()`, `unproject()`, `project_line()`, `project_path()`, `horizon_line()`, and
  `camera_from_fov()`. The camera model is a pinhole with Brown–Conrady distortion
  (OpenCV convention). Lines are clipped at a cone a little larger than the screen.
- `find_time()` finds the time when the sun is nearest to a direction (auto mode), and
  `find_dates()` finds the dates when the sun goes through a direction (date slider).
  Both compare with the refracted position by default.
- `ScreenRotation` has the meaning of Android `Display.getRotation()`: `rotation_90` is a
  device turned 90° counterclockwise. The first version had `rotation_90` and
  `rotation_270` swapped, so a landscape view was upside down and mirrored.
- Clipped lines no longer get a wrong point at (0, 0) for single frames. A point on the
  border of the valid cone was sometimes rejected because of rounding, which made lines
  flicker.
- A heading filter (`heading.hpp`): `HeadingEstimate`, `heading_predict()`,
  `heading_update()` (a Kalman update on the circle), `heading_from_magnetometer()`, and
  `heading_from_direction()`. The variance of a magnetometer measurement grows with the
  difference between the measured and the WMM field strength and inclination, so a
  disturbance (for example steel) makes the filter trust the gyroscope. `Vector3` is new.
- `moon_position()` and `moon_illuminated_fraction()` from Astronomy Engine (vendored, MIT),
  and `SolarModel::astronomy_engine`, a second and more precise model of the sun.
- `find_azimuth_times()` finds the times when the sun has a given azimuth ("when is the sun
  in this direction?").
- An invalid argument throws `std::invalid_argument`: a value that is not finite, a
  latitude outside [-90, 90], an empty interval, a step that is not positive, or a
  path with more than 1,000,000 points.

### Precision

- The position of the sun agrees with JPL Horizons to 0.015° or better for 1950 to
  2100. Sunrise and sunset agree to 71 s or better (135 s in Tromsø), mostly
  because Horizons rounds its times to the minute.
- The magnetic field agrees with the 100 official WMM2025 test values to 0.001 nT,
  and the declination to their rounding of 0.005°.
- The moon agrees with JPL Horizons to 0.002°, and the sun of `astronomy_engine` to 0.002°
  (`noaa`: 0.015°).
- `project()` and `unproject()` are inverse to better than 1e-6 px, with distortion.

### Packages

- The Python package `sunradar` (pybind11, scikit-build-core). It has the same
  functions and types as the C++ API, including the view, the magnetic
  declination, the search, the heading filter, and the moon.
- The npm package `@sunradar/core` (WebAssembly, 68 KB gzipped) with the same functions.
  Values are plain objects, options are optional, and an invalid argument throws a
  `RangeError`.
- The Android AAR `at.stefl.sunradar:core-android` (JNI and a Kotlin API) for `arm64-v8a`
  and `x86_64`, with the same functions and types. An invalid argument throws
  `IllegalArgumentException`. `DirectionLine` and `Polylines` let an app project a path in
  each frame without conversions. An invalid argument raises `ValueError`.
