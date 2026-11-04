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

- The eclipses (`almucantar/eclipse.hpp`): `solar_eclipses()` and `lunar_eclipses()` give the
  eclipses of an interval with their kind, peak, and coverage; `local_solar_eclipses()` gives the
  phases of a solar eclipse at a place, with the altitude of the sun; `eclipse_central_line()` gives
  the central line of a total or annular eclipse with the radius of the shadow, for a map. From
  Astronomy Engine; in C++, Python, and WASM.
- The photo planner (`almucantar/plan.hpp`), for shotradar: `body_position()` gives the position
  of any body. `sight()` gives the direction to a landmark with the curvature of the Earth.
  `find_alignments()` finds the times when a body is behind a landmark ("I stand here"), one
  year of the moon in a few milliseconds. `alignment_line()` gives the places from which a body
  is behind a landmark at a time, and on a DEM the spots where the ground has the right height
  ("where must I stand?"). `line_of_sight()` checks the view over the DEM, and `dark_windows()`
  finds the dark hours without the sun and the moon, also with the galactic center up. In C++,
  Python, and WASM. `SightOptions.target_margin_m` makes `line_of_sight()` ignore the flanks of a
  summit just below its top.
- `Body` has the galactic center and the planets after the sun and the moon, and
  `terrain_events()` takes all of them. `Body` is in `types.hpp` now.
- The stars: `star_positions()` gives the apparent positions of all stars of a `StarCatalog`
  (J2000 positions and proper motions, as columns) in one call, with the precession, the
  nutation, the annual aberration, and the refraction. 5080 stars take about 1 ms in WebAssembly.
  `project_points()` projects many directions in one call. In C++, Python, WASM, and the AAR.
- The AAR has the planets, the Milky Way, and the stars: `planetPosition()`, `planetMagnitude()`,
  `galacticPosition()`, `galacticPlane()`, `starPositions()`, and `projectPoints()`, with
  `Planet`, `SkyOptions`, and `StarCatalog`.
- The Swift package has the planets, the Milky Way, and the stars: `planetPosition()`,
  `planetMagnitude()`, `galacticPosition()`, `galacticPlane()`, `starPositions()`, and
  `projectPoints()`, with `Planet`, `SkyOptions`, `StarCatalog`, and the galactic center. The C API
  has the same functions with the prefix `sr_`.
- The WASM package has the planets and the Milky Way: `planetPosition()`, `planetMagnitude()`,
  `galacticPosition()`, and `galacticPlane()`, with `Planet` and `GALACTIC_CENTER`. In
  `starPositions()` and `projectPoints()`, the stars and the points cross as flat `Float64Array`s.

- **Breaking:** the core has the new name almucantar, because it is the core of more than one
  app. The C++ headers are in `almucantar/` (`almucantar/almucantar.hpp`), and the namespace is
  `almucantar`. The Python package is `almucantar`, the npm package is `@almucantar/core`, the
  AAR is `at.stefl.almucantar:core-android` with the Kotlin package `at.stefl.almucantar.core`,
  and the Swift package is `Almucantar` (`AlmucantarC.xcframework`, `almucantar_c.h`). The
  CMake options start with `ALMUCANTAR_`. The repository is
  <https://github.com/andiwand/almucantar>.

## 0.2.0 - 2026-11-03

- The terrain horizon: `horizon_profile()` calculates the altitude of the terrain for each
  azimuth from a list of `ElevationGrid` tiles (a DEM), with the curvature of the Earth and the
  terrestrial refraction. `terrain_altitude_deg()` interpolates the profile, and
  `terrain_events()` finds the visible sunrise and sunset behind the terrain.
  `read_elevation_geotiff()` reads a GeoTIFF tile, for example of Copernicus GLO-90, and
  `elevation_tiles()` lists the tiles of 1° that a profile needs. For a web app, `map_tiles()` and
  `terrarium_grid()` read the Web Mercator tiles of the AWS Terrain Tiles (Terrarium PNG), which
  allow CORS. The WASM package has all terrain functions. The AAR and the Swift package have all
  except `map_tiles()` and `terrarium_grid()`.
- `terrain_events()` also finds the moon behind the terrain: `TerrainEventOptions.body` is
  `Body::sun` (the default) or `Body::moon`, in all bindings.
- `elevation_geotiff_prefix_size()` gives the bytes from the start of a GeoTIFF that a level needs.
  So an app can download only the directories and an overview of a Cloud Optimized GeoTIFF with an
  HTTP Range request: about 300 KB of a Copernicus GLO-90 tile of 2–5 MB for level 2. In all
  bindings.
- `HorizonOptions.min_distance_m` ignores the terrain nearer than this distance. A surface model
  such as Copernicus has buildings and forests in its heights, so a city gives a false wall of
  several degrees around the observer; a clearance of about 2 km removes it. The default 0 uses
  all terrain. In all bindings.
- `horizon_profile()` does not depend on the order of the tiles, and where tiles overlap, the tile
  with the smaller cells wins.
- The planets and the Milky Way: `planet_position()` and `planet_magnitude()` for Mercury to
  Neptune (within 0.006° of JPL Horizons), and `galactic_position()` and `galactic_plane()` for
  the galactic center and the center line of the Milky Way. In C++ and Python only.
- `find_time()` refines each minimum, so a search across many days finds the nearest match.
- `find_dates()` includes matches in the final partial day.
- Searches keep exact crossings at sample times and at the ends of an azimuth interval.
- A WebAssembly build copies changes to the JavaScript wrapper and types without a native rebuild.
- `HeadingFilter` with `heading_filter_start()`, `heading_filter_predict()`,
  `heading_filter_update()`, and `heading_filter_update_magnetometer()`, in all bindings. The
  filter estimates the heading offset, the hard-iron bias of the magnetometer, and a local
  disturbance of the field together. A sun tap or a landmark therefore also corrects the bias,
  and the magnetometer does not pull the offset back to its own wrong heading. The filter
  accepts the raw field (Android `TYPE_MAGNETIC_FIELD_UNCALIBRATED`), so it can calibrate the
  hard iron without the OS. `heading_from_magnetometer()` stays, but the apps do not use it.

## 0.1.0 - 2026-11-01

### API

- `kDefaultHeadingDriftDeg2PerS` is the default drift of `heading_predict()`. All bindings
  expose it and take their defaults from C++.
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
- The Swift package `Sunradar` for iOS 16 and macOS 13 (`Package.swift` at the root of the
  repository). It has the same API as the other bindings. Below it is the C API
  `sunradar_c.h` in the binary target `SunradarC.xcframework`. A release of the core
  attaches the xcframework and tags `X.Y.Z` for SwiftPM.
