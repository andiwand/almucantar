# AGENTS.md — the Android app

The app `at.stefl.sunradar`. It uses the AAR `at.stefl.sunradar:core-android` from source:
`settings.gradle.kts` includes `../core/android` as a composite build. The app contains no
C++ and no JNI code. Read [`../core/android/AGENTS.md`](../core/android/AGENTS.md) for the
API of the AAR.

## Layout

| Path | What |
|---|---|
| `app/src/main/kotlin/at/stefl/sunradar/MainActivity.kt` | The activity and the location permission. |
| `.../SkyScreen.kt` | The sky view (Compose): horizon, terrain line, cardinal points, path, hour marks, sun, crosshair with the auto-mode time, and the status text. |
| `.../SkyDay.kt` | The data of one local day, calculated once per day and place. |
| `.../HeadingTracker.kt` | The game rotation vector, the magnetometer (once per second), and the heading filter of the core. `apply()` takes a calibration, for example a sun tap. |
| `.../SkyDrawing.kt` | Drawing helpers: upright labels, the arrow at the screen border, dashed lines. |
| `.../TimePanel.kt` | The date and time sliders. |
| `.../MapPicker.kt` | A small OpenStreetMap tile map to pick a landmark. It follows the OSM tile policy: a User-Agent that names the app, the attribution, and tiles in memory only. It needs the network. |
| `.../BaseMap.kt` | The offline base map (`assets/basemap/basemap.json`, Natural Earth): a small JSON reader for its format, the Web Mercator functions, the search of the places (without accents, the largest first), the nearest place, and the time zone of a dropped place. Pure Kotlin, tested in `app/src/test/` with the real asset. |
| `.../PlacePanel.kt` | The place panel: the search and the Natural Earth map (move, zoom, tap to drop the place), and "Use my location". It works offline. `BaseMapStore` decodes the asset once, when the panel opens. |
| `.../Geo.kt` | Bearing and distance on a sphere. |
| `.../SunDetector.kt` | Finds saturated regions in the Y plane of a camera frame. Pure Kotlin, tested in `app/src/test/`. `CameraPreview.kt` runs it in an `ImageAnalysis` and maps the regions to the view with sensorToView * (sensorToBuffer)^-1. |
| `.../Location.kt` | The place: the device location (coarse), a place that the user dropped (manual), the last place (`PlaceStore`, in the shared preferences), else the city of the time zone, else Vienna. The town name comes from the geocoder of the device. |
| `.../TimeZonePlaces.kt` | Reads `assets/tzdata/zone.tab` (IANA, public domain). |
| `.../CameraPreview.kt` | The CameraX preview. It switches the distortion correction off, reads the Camera2 calibration, and gives the camera model after each change of the sensor-to-view transform. |
| `.../CameraCalibration.kt` | Converts the Camera2 calibration and `PreviewView.getSensorToViewTransform()` to a `Camera` in view pixels. Pure Kotlin, tested in `app/src/test/`. |
| `.../MoonPhase.kt` | The lit part of the moon: the direction toward the sun and the outline of the phase. Tested in `app/src/test/`. |
| `.../ElevationSource.kt` | The source of the elevation tiles: `ElevationSource` (name, attribution, `isSurfaceModel`, the coarse level, the full level, the distance of the full tiles, the tile URL, the size of the coarse part, and the reader), `Copernicus` (the only implementation), and `TERRAIN_SOURCE` (the source of the app). `horizonOptions(source)` gives the options of the profile: a surface model ignores the terrain nearer than `NEAR_CLEARANCE_M` (2 km), because buildings and forests give a false wall in a city. The coarse check, the profile, and "Keep offline" use these options. The names are those of `pwa/src/terrain.ts`. A new source (for example a bare-earth model) changes only this file, and it needs a new directory of the cache. |
| `.../DemCache.kt` | The persistent cache of the tiles of an `ElevationSource` (Copernicus GLO-90 in the app) in `noBackupFilesDir/dem`: for each tile the full file (`N47_00_E011_00.tif`) or its coarse part (`N47_00_E011_00.l2.tif`: the first bytes up to the overview at level 2, from HTTP Range requests and `elevationGeotiffPrefixSize`), and `index.json` (ETag, Last-Modified, time of the check and of the last use, size, sea flag). It evicts the least recently used tiles above 400 MB, checks a tile with the server after 180 days, treats 404 as the sea, and downloads a file that the core cannot read again one time. The index also has the kept places (`KeptPlace`: id, name, latitude, longitude, tile names; index version 2, a version 1 index loads without them). The eviction never deletes a kept tile, and the kept tiles do not count toward the 400 MB. `fetch()` puts a full file on the disk without a read. `loadCoarse()` uses the full file if it is in the cache, else the coarse part. A tile has at most one of the two files: the cache deletes the coarse part when it stores the full file. All Range parts must have the same ETag; a server that sends the whole file (200) gives only the part to the cache. The lock covers only the entries in memory and the index: a download and the read or the write of a tile run without it, so a slow download does not block the other calls. Pure Kotlin with a replaceable HTTP layer (`TileHttp`), tested in `app/src/test/`. |
| `.../TerrainLoader.kt` | The two steps of the terrain (PLAN.md 14.3): the coarse part of all tiles of `elevationTiles(place, 100 km)`, then, only if the coarse profile reaches `FULL_TERRAIN_MIN_ALTITUDE_DEG` (1°), the full files of the tiles nearer than `fullDistanceM` of the source (25 km). The levels, the distance, and the options of the profile (`options`, from `horizonOptions`) come from the source of the cache. Generic in the grid type, tested in `app/src/test/` with a fake server and a fake bare-earth source (`FakeTileServer.kt`, `FakeSource`). |
| `.../OfflinePlaces.kt` | "Keep offline" (PLAN.md 13.3): keeps the same data as the terrain (`keepTerrain`, with a `TerrainLoader`): the coarse parts of `elevationTiles(place, 100 km)`, and the full files of the near tiles if the terrain matters. It runs in a scope of the process, so the download continues when the place panel closes. One download at a time. `PlacePanel` shows its progress, the kept places, and "Remove". The demo mode does not show it. |
| `.../Terrain.kt` | The terrain horizon: `rememberTerrain` loads the tiles with a `TerrainLoader` (the coarse profile first, at level 2; then level 0 nearer than 25 km if the terrain matters) and calculates the profile with `horizonOptions(TERRAIN_SOURCE)` in the background, again after a move of 500 m or a new tile. `TerrainLines` (the line and the fill) and `TerrainDay` (the path behind the terrain, the visible sunrise and sunset) are the data of the drawing. A device location, a manual place, and the last place get a terrain. The city of the time zone, Vienna, and the demo mode get none. |
| `.../Settings.kt` | The settings dialog (the switch "Terrain horizon", stored in the shared preferences) and the credits of the data (OpenStreetMap, Natural Earth, and the attribution of `TERRAIN_SOURCE`). |
| `.../ScreenshotScene.kt` | A fixed scene of the demo mode for the store screenshots (view, place, time, heading). |
| `.../DemoSky.kt` | The drawn sky of the demo mode, in place of the camera image: the sky of `website/src/sky.ts`. |
| `app/src/debug/.../ScreenshotMode.kt` | The demo mode: the scenes of `screenshots/README.md`, chosen with the intent extra `sunradar.screenshot`. The release build has a no-op version in `app/src/release/`. |
| `app/src/test/` | JVM unit tests. |
| `app/src/androidTest/.../ScreenshotTests.kt` | Takes the store screenshots. It runs only with the instrumentation argument `device`. |
| `fastlane/` | The lanes `screenshots` and `upload_listing`. |

## The place

PLAN.md 13.3 (fully offline). The PWA has the same behavior (`pwa/src/basemap.ts`, `pwa/src/zone.ts`).

- The place is the device location, a place that the user dropped (manual), or the last place. The
  app stores the last place (latitude, longitude, name, accuracy, time zone, manual flag) and starts
  with it, also offline and without the location permission.
- A manual place stays until the user chooses "Use my location". A new device location does not
  replace it.
- All days and times use the time zone of the place (`Place.zone`, null is the zone of the device):
  the day of the path, the hour labels, the time panel, the crosshair dates, and the sunrise and
  sunset. A dropped place gets the zone of the nearest place of the base map that has one, or the
  zone of the device if java.time does not know that zone.
- The app takes `basemap.json` from `../assets/basemap` (an assets directory in
  `app/build.gradle.kts`). Do not copy it into `android/`.
- The demo mode does not read or write the last place.

## Build and test

Run these commands in `android/`. The composite build builds the native library of the AAR
with Conan, unless `SUNRADAR_ABIS` is empty.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:spotlessCheck
```

## Rules

- The app calls the core only through the Kotlin API of the AAR.
- Calculate per day what does not change in a day (`SkyDay`). A frame only projects.
- Install on an emulator with `ANDROID_SERIAL=emulator-5554`, not on a connected phone,
  unless the user asks for it.
- Kotlin is formatted with ktfmt in the kotlinlang style (`./gradlew :app:spotlessApply`).
- A change that a user notices gets an entry in `CHANGELOG.md`.

## Screenshots

The store screenshots come from `ScreenshotTests` in the demo mode (see `screenshots/README.md` at
the root). Run them on an emulator with API 35 or later:

```sh
ANDROID_SERIAL=emulator-5554 bundle exec fastlane screenshots
ANDROID_SERIAL=emulator-5554 SUNRADAR_SCREENSHOT_DEVICE=tablet bundle exec fastlane screenshots
```

- The demo mode exists only in the debug build. It uses no sensors, no location, and no camera,
  and it draws the sky (`DemoSky`) in place of the camera image. Do not put screenshot code in the
  release behavior: a scene is null in a release build.
- The demo mode uses the screen rotation 0, because its view is a device held upright in
  portrait, also on a tablet whose natural orientation is landscape.
- The test enters the demo mode of the status bar again before each screen, because a slow
  emulator can restart the system UI, which ends the demo mode.
