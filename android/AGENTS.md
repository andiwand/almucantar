# AGENTS.md — the Android apps

One Gradle project for all Android apps of the repository (PLAN.md 14). The modules:

| Module | What |
|---|---|
| `shared/` | An Android library with the code that the apps share: the place and the offline base map, the maps, the terrain and its cache, the camera, the heading tracker, the moon phase, the sun detector, and the drawing helpers. Package `at.stefl.almucantar.shared`. |
| `sonnseite/` | The app sonnseite (`at.stefl.sonnseite`): the sky view, the settings, and the demo mode. |
| `sternstunde/` | The app sternstunde (`at.stefl.sternstunde`, PLAN.md 14.2): the stars, the constellations, the planets, the moon, and the Milky Way over the terrain. |
| `alidade/` | The app alidade (`at.stefl.alidade`, PLAN.md 14.1), the photo planner: the shot in first person from a spot, behind a landmark, from a place, or of an eclipse. The PWA `web/alidade/app/` is the reference. |

The modules use the AAR `at.stefl.almucantar:core-android` from source: `settings.gradle.kts`
includes `../core/android` as a composite build. The apps contain no C++ and no JNI code. Read
[`../core/android/AGENTS.md`](../core/android/AGENTS.md) for the API of the AAR.

A new app is a new application module next to `sonnseite/`, with
`implementation(project(":shared"))`. Code goes to `shared/` when a second app needs it, not
before.

## Layout

In each table, `.../` is the Kotlin package directory of the module, for example
`shared/src/main/kotlin/at/stefl/almucantar/shared/`.

### `shared/`

| Path | What |
|---|---|
| `.../HeadingTracker.kt` | The game rotation vector, the magnetometer (once per second), and the heading filter of the core. `apply()` takes a calibration, for example a sun tap. |
| `.../SkyDrawing.kt` | Drawing helpers: upright labels, the arrow at the screen border, dashed lines. |
| `.../MapPicker.kt` | A small OpenStreetMap tile map to pick a landmark. It follows the OSM tile policy: a User-Agent that names the app, the attribution, and tiles in memory only. It needs the network. `OsmTiles` (the tiles in memory of the process) and the Web Mercator functions are public, so the map of alidade uses them too. |
| `.../BaseMap.kt` | The offline base map (`assets/basemap/basemap.json`, Natural Earth): a small JSON reader for its format, the Web Mercator functions, the search of the places (without accents, the largest first), the nearest place, and the time zone of a dropped place. Pure Kotlin, tested in `shared/src/test/` with the real asset. |
| `.../PlacePanel.kt` | The place panel: the search and the Natural Earth map (move, zoom, tap to drop the place), and "Use my location". It works offline. `BaseMapStore` decodes the asset once, when the panel opens. |
| `.../Geo.kt` | Bearing and distance on a sphere. |
| `.../SunDetector.kt` | Finds saturated regions in the Y plane of a camera frame. Pure Kotlin, tested in `shared/src/test/`. `CameraPreview.kt` runs it in an `ImageAnalysis` and maps the regions to the view with sensorToView * (sensorToBuffer)^-1. |
| `.../Location.kt` | The place: the device location (coarse), a place that the user dropped (manual), the last place (`PlaceStore`, in the shared preferences), else the city of the time zone, else Vienna. The town name comes from the geocoder of the device. `deviceLocation()` gives one location of the device, for an app with its own place (alidade). |
| `.../TimeZonePlaces.kt` | Reads `assets/tzdata/zone.tab` (IANA, public domain). |
| `.../CameraPreview.kt` | The CameraX preview. It switches the distortion correction off, reads the Camera2 calibration, and gives the camera model after each change of the sensor-to-view transform. `tintable = true` draws the preview inside the view, so a tint over it (the red light of sternstunde) also tints the image. |
| `.../CameraCalibration.kt` | Converts the Camera2 calibration and `PreviewView.getSensorToViewTransform()` to a `Camera` in view pixels. Pure Kotlin, tested in `shared/src/test/`. |
| `.../MoonPhase.kt` | The lit part of the moon: the direction toward the sun and the outline of the phase. Tested in `shared/src/test/`. |
| `.../ElevationSource.kt` | The source of the elevation tiles: `ElevationSource` (name, attribution, `isSurfaceModel`, the coarse level, the full level, the distance of the full tiles, the tile URL, the size of the coarse part, and the reader), `Copernicus` (the only implementation), and `TERRAIN_SOURCE` (the source of the app). `horizonOptions(source)` gives the options of the profile: a surface model ignores the terrain nearer than `NEAR_CLEARANCE_M` (2 km), because buildings and forests give a false wall in a city. The coarse check, the profile, and "Keep offline" use these options. The names are those of `web/shared/src/terrain.ts`. A new source (for example a bare-earth model) changes only this file, and it needs a new directory of the cache. |
| `.../DemCache.kt` | The persistent cache of the tiles of an `ElevationSource` (Copernicus GLO-90 in the app) in `noBackupFilesDir/dem`: for each tile the full file (`N47_00_E011_00.tif`) or its coarse part (`N47_00_E011_00.l2.tif`: the first bytes up to the overview at level 2, from HTTP Range requests and `elevationGeotiffPrefixSize`), and `index.json` (ETag, Last-Modified, time of the check and of the last use, size, sea flag). It evicts the least recently used tiles above 400 MB, checks a tile with the server after 180 days, treats 404 as the sea, and downloads a file that the core cannot read again one time. The index also has the kept places (`KeptPlace`: id, name, latitude, longitude, tile names; index version 2, a version 1 index loads without them). The eviction never deletes a kept tile, and the kept tiles do not count toward the 400 MB. `fetch()` puts a full file on the disk without a read. `loadCoarse()` uses the full file if it is in the cache, else the coarse part. A tile has at most one of the two files: the cache deletes the coarse part when it stores the full file. All Range parts must have the same ETag; a server that sends the whole file (200) gives only the part to the cache. The lock covers only the entries in memory and the index: a download and the read or the write of a tile run without it, so a slow download does not block the other calls. Pure Kotlin with a replaceable HTTP layer (`TileHttp`), tested in `shared/src/test/`. |
| `.../TerrainLoader.kt` | The two steps of the terrain (PLAN.md 14.3): the coarse part of all tiles of `elevationTiles(place, 100 km)`, then, only if the coarse profile reaches `FULL_TERRAIN_MIN_ALTITUDE_DEG` (1°), the full files of the tiles nearer than `fullDistanceM` of the source (25 km). The levels, the distance, and the options of the profile (`options`, from `horizonOptions`) come from the source of the cache. Generic in the grid type, tested in `shared/src/test/` with a fake server and a fake bare-earth source (`FakeTileServer.kt`, `FakeSource`). |
| `.../OfflinePlaces.kt` | "Keep offline" (PLAN.md 13.3): keeps the same data as the terrain (`keepTerrain`, with a `TerrainLoader`): the coarse parts of `elevationTiles(place, 100 km)`, and the full files of the near tiles if the terrain matters. It runs in a scope of the process, so the download continues when the place panel closes. One download at a time. `PlacePanel` shows its progress, the kept places, and "Remove". The demo mode does not show it. |
| `.../Terrain.kt` | The terrain horizon: `rememberTerrain` loads the tiles with a `TerrainLoader` (the coarse profile first, at level 2; then level 0 nearer than 25 km if the terrain matters) and calculates the profile with `horizonOptions(TERRAIN_SOURCE)` in the background, again after a move of 500 m or a new tile. `TerrainLines` (the line and the fill) is the data of the drawing. `TerrainDay` of the app adds the sun. A device location, a manual place, and the last place get a terrain. The city of the time zone, Vienna, and the demo mode get none. `TerrainStore.cache()` (the cache of the tiles, one for the process) is public, so alidade reads its own bands from the same cache. |
| `.../MilkyWay.kt` | The points of the Milky Way in the galactic system, the same picture as `web/shared/src/milkyway.ts`. sternstunde and alidade use it. Tested in `shared/src/test/`. |
| `.../PlaceTime.kt` | The local time of a place: `wallClockTime`, `localDate`, and `dayBounds`. |
| `.../TimePanel.kt` | The date and time sliders of the apps. |
| `.../DeviceState.kt` | The rotation of the display and the time zone of the device, as Compose state. |
| `.../UserAgent.kt` | The User-Agent of the HTTP requests. Each app calls `UserAgent.init()` at the start, because the tile policy of OpenStreetMap asks that it names the app. |
| `shared/src/main/AndroidManifest.xml` | The permissions of the shared code (location, network, camera). The manifest merger gives them to each app. |
| `shared/src/main/assets/tzdata/` | The IANA `zone.tab`. |
| `shared/src/test/` | JVM unit tests of the shared code. |

### `sternstunde/`

`.../` is `sternstunde/src/main/kotlin/at/stefl/sternstunde/`.

| Path | What |
|---|---|
| `.../MainActivity.kt` | The activity: the User-Agent, the location permission, and the star data (`assets/stars/stars.json`, an assets directory in `sternstunde/build.gradle.kts`). |
| `.../StarScreen.kt` | The sky view: the Milky Way, the constellation lines, the stars (size from the magnitude, color from B−V, down to the limit of the twilight), the names, the planets, the moon, the sun, the horizon, the terrain, and the selection or the arrow to it. The tap with the info card (rise and set over the terrain, in the background), Find, Align (a tap on an object to magnitude 2.5), the time panel, the place panel, and the red light. |
| `.../Sky.kt` | `SkyAt`: the sky at one time (`starPositions` for the stars, the names of the constellations, and the Milky Way, once per second), and the targets (`Target`) with their name, direction, and rise and set (samples every 10 minutes). |
| `.../StarData.kt` | The decoder of `stars.json`, the planets with their colors, and `StarLook` (size, color, limiting magnitude, twilight), the same rules as `web/shared/src/stars.ts`. Tested in `sternstunde/src/test/`. |
| `.../Settings.kt` | The settings (constellation lines, terrain, red light) and the credits of the data. |

### `alidade/`

`.../` is `alidade/src/main/kotlin/at/stefl/alidade/`. The screens follow the PWA
(`web/alidade/app/src/main.ts`), and the rules of the search are the same as in its `search.ts`.

| Path | What |
|---|---|
| `.../MainActivity.kt` | The activity: the User-Agent, the light system bars, and the location permission. |
| `.../App.kt` | `Model` (the choices of the user in the shared preferences, the base map, the stars, the horizons once per place, and the stack of the screens with the back gesture), the bar with the strip of film, and the screen of each step. |
| `.../Search.kt` | The subjects and the searches: `datesFor` ("I know my spot"), `momentsFor` ("Find me a spot"), `peakTargets`, `shotsFromPlace`, and `skyFromPlace` ("From where I am"), `noMatchReason`, the eclipses (`upcomingEclipses`, `localEclipse`, `lunarUmbra`, `discCoverage`). The core finds the alignments and the eclipses; this file chooses and sorts. Tested in `alidade/src/test/`. |
| `.../Horizon.kt` | The horizon of a spot in four bands of distance (250 m to 90 km, at 0.2°): the full grids of GLO-90 for the near bands, the coarse grids for the far bands, from the shared `TerrainStore`. The skyline, `findPeaks`, the ground and the summit near a tap, the grids along a line, and the margin of the target for `lineOfSight`. Tested in `alidade/src/test/`. |
| `.../Photo.kt` | `PhotoPainter`: the photo of `web/shared/src/photo.ts` on the Android canvas, with the same colors: the sky, the stars and the Milky Way, the path, the sun, the moon, the eclipses, the terrain in layers, the label of the landmark, the frame of the lens, and the viewfinder. |
| `.../Frames.kt` | `frameOf`: the frame of a shot at a time, with a lens and a pan, and the formats of the dates and the numbers. |
| `.../ShotMap.kt` | The map of OpenStreetMap tiles (the shared `OsmTiles`) with a continuous zoom (a tile is 256 dp), the marks of the app, the lines (a band in meters, across the date line), and the gestures. |
| `.../Theme.kt` | The look of the website: the paper, the prints with the corners of a viewfinder, the step labels, the buttons, the chips, and the data rows. |
| `.../Screens.kt` | The pages: the start, the subject, the choices, the dates, the moments, the days, the shots of a place, the contact sheet, and the eclipses. |
| `.../MapScreens.kt` | The screens on the map: the landmark, the spot, the line of the spots, the map of a shot, and the path of an eclipse on the map of the Earth. |
| `.../ShotScreen.kt` | The shot: the photo with the pan and the zoom, the time slider, the lenses, the data, and the way to the spot. |

### `sonnseite/`

`.../` is `sonnseite/src/main/kotlin/at/stefl/sonnseite/`.

| Path | What |
|---|---|
| `.../MainActivity.kt` | The activity and the location permission. |
| `.../SkyScreen.kt` | The sky view (Compose): horizon, terrain line, cardinal points, path, hour marks, sun, crosshair with the auto-mode time, and the status text. |
| `.../SkyDay.kt` | The data of one local day, calculated once per day and place. |
| `.../Settings.kt` | The settings dialog (the switch "Terrain horizon", stored in the shared preferences) and the credits of the data (OpenStreetMap, Natural Earth, and the attribution of `TERRAIN_SOURCE`). |
| `.../ScreenshotScene.kt` | A fixed scene of the demo mode for the store screenshots (view, place, time, heading). |
| `.../TerrainDay.kt` | The sun path behind the terrain on one day, and the visible sunrise and sunset. |
| `.../DemoSky.kt` | The drawn sky of the demo mode, in place of the camera image: the sky of `web/sonnseite/site/src/sky.ts`. |
| `sonnseite/src/debug/.../ScreenshotMode.kt` | The demo mode: the scenes of `screenshots/README.md`, chosen with the intent extra `sonnseite.screenshot`. The release build has a no-op version in `sonnseite/src/release/`. |
| `sonnseite/src/test/` | JVM unit tests. |
| `sonnseite/src/androidTest/.../ScreenshotTests.kt` | Takes the store screenshots. It runs only with the instrumentation argument `device`. |
| `fastlane/` | The lanes `screenshots` and `upload_listing` (of sonnseite only). |

## The place

PLAN.md 13.3 (fully offline). The PWA has the same behavior (`web/shared/src/basemap.ts`, `web/shared/src/zone.ts`).

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
  `shared/build.gradle.kts`). Do not copy it into `android/`.
- The demo mode does not read or write the last place.

## Build and test

Run these commands in `android/`. The composite build builds the native library of the AAR
with Conan, unless `ALMUCANTAR_ABIS` is empty.

```sh
./gradlew :shared:testDebugUnitTest :shared:lintDebug :sonnseite:assembleDebug :sonnseite:testDebugUnitTest \
  :sonnseite:lintDebug :sternstunde:assembleDebug :sternstunde:testDebugUnitTest :sternstunde:lintDebug :alidade:assembleDebug :alidade:testDebugUnitTest :alidade:lintDebug \
  spotlessCheck
```

## Rules

- The apps call the core only through the Kotlin API of the AAR.
- `shared/` never depends on an app module. Data that only an app knows (for example the scene of
  its demo mode) goes in as a parameter, as `FixedHeading` does for the heading tracker.
- Calculate per day what does not change in a day (`SkyDay`). A frame only projects.
- Install on an emulator with `ANDROID_SERIAL=emulator-5554`, not on a connected phone,
  unless the user asks for it.
- Kotlin is formatted with ktfmt in the kotlinlang style (`./gradlew spotlessApply`).
- A change that a user notices gets an entry in the `CHANGELOG.md` of the app module.

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
