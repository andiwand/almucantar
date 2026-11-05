# AGENTS.md — the iOS apps

One XcodeGen project (`project.yml`, `Almucantar.xcodeproj`) for all iOS apps of the repository
(PLAN.md 14). The apps are in SwiftUI. They use the Swift package `Almucantar` (`core/apple`,
`Package.swift` at the root) and contain no C++. The Android apps (`android/`) are the reference
for the features and the behavior.

- `Shared/` is the code that the apps share. Each app target compiles these files, so the shared
  code needs no `public`. The framework target `AlmucantarShared` builds `Shared/` alone. It fails
  if the shared code uses a type of an app, and `AlmucantarSharedTests` tests it with
  `@testable import AlmucantarShared`.
- `Sunradar/` is the app sunradar (target `SunradarApp`, bundle `at.stefl.sunradar`).
- `Starradar/` is the app starradar (target `StarradarApp`, bundle `at.stefl.starradar`, PLAN.md
  14.2): the stars, the constellations, the planets, the moon, and the Milky Way over the terrain.
  `StarradarTests` tests its data code with `@testable import StarradarApp`.
- A new app is a new application target with `sources: [Shared, <App>]`. Code goes to `Shared/`
  when a second app needs it, not before. `Shared/` never uses a type of an app: data that only an
  app knows goes in as a parameter, as `LocationProvider(loadsStoredPlace:)` does.

## Layout

| Path | What |
|---|---|
| `project.yml` | The XcodeGen project. `xcodegen generate` writes `Almucantar.xcodeproj`, which is not in Git. |
| `Sunradar/SunradarApp.swift` | The entry point. |
| `Sunradar/SkyScreen.swift` | The sky view: the drawing, the readout, the time panel, the align menu, and the detected sun. |
| `Shared/SkyDrawing.swift` | The drawing helpers of the sky views (`SkyDrawing`): the point and the lines of a projection, upright labels, the arrow at the border of the screen, the terrain fill, and the moon with its phase. `SkyOrientation` gives the screen rotation and the upright device of the drag view. `skyPanel()` is the dark panel over the sky. |
| `Shared/TimePanel.swift` | The date and time sliders, and `TimePanel.time`, the selected time on the wall clock of the place. |
| `Shared/MapPicker.swift` | The MapKit map to pick a landmark. |
| `Shared/BaseMap.swift` | The offline base map (`assets/basemap/basemap.json`, Natural Earth, a resource of the app): the decode, the search, the nearest place and its time zone, and Web Mercator (`MapViewport`). `BaseMapStore` decodes it once, off the main thread. |
| `Shared/PlacePanel.swift` | The place panel: the search and the offline map, drawn with a `Canvas` (`BaseMapDrawing`), where a tap drops the place. It also has "Keep offline" and the list of the kept places. |
| `Shared/KeptPlaces.swift` | "Keep offline" (`PLAN.md` 13.3): it downloads the same data as the terrain (the prefix files of the tiles of `elevationTiles(place, 100 km)`, and the full near tiles if the threshold applies) and keeps the state and the list of the kept places. `SkyScreen` owns it, so the download continues when the panel closes. |
| `Shared/PlaceTime.swift` | The calendar, the day, and the text of the times in the time zone of the place. |
| `Shared/SunDetector.swift` | The saturated regions of a luminance image (the same algorithm as on Android). |
| `Shared/Geo.swift` | The bearing, the distance, and the angle between two directions. |
| `Shared/MoonPhase.swift` | The lit part of the moon and its direction to the sun. |
| `Sunradar/ScreenshotMode.swift` | The demo mode of the store screenshots (debug builds only) and its scenes. |
| `Sunradar/DemoSky.swift` | The drawn sky of the demo mode: the sky of the website, in the projection of the app. |
| `Shared/ElevationSource.swift` | The source of the elevation tiles (`ElevationSource`): the name, the attribution, `isSurfaceModel`, the URL of a tile, the coarse level, the full level, and the distance of the full tiles. `CopernicusSource` (Copernicus GLO-90) is the only source now. `horizonOptions` gives the options of the profile: `nearClearanceM` (2000 m) for a surface model, else 0. The PWA has the same idea (`web/shared/src/terrain.ts`). |
| `Shared/DemCache.swift` | The persistent cache of the tiles of an `ElevationSource` (`DemCache.defaultSource`, Copernicus GLO-90) in Application Support/`dem`: the full files (`<tile>.tif`) and the prefix files (`<tile>.l2.tif`, HTTP Range), with the JSON index, the revalidation after 180 days, the eviction over 400 MB, and the kept places (`KeptPlace`). `HTTPClient` is the HTTP layer that the tests replace. |
| `Shared/TerrainHorizon.swift` | The terrain horizon of the place: it loads the tiles in two steps (the coarse check, then the full near tiles if the terrain is high), calculates the profile off the main thread, and gives the lines of the terrain. |
| `Sunradar/TerrainDay.swift` | The hidden parts of the sun path and the visible sunrise and sunset (`TerrainDay`, `TerrainDayCache`). |
| `Shared/Settings.swift` | The keys of the settings in UserDefaults. |
| `Sunradar/SettingsView.swift` | The settings (the terrain horizon) and the credits. |
| `Starradar/StarradarApp.swift` | The entry point of starradar: it reads `stars.json` (`assets/stars/`, a resource of the app) and keeps the screen on. |
| `Starradar/StarScreen.swift` | The sky view of starradar: the Milky Way, the constellation lines, the stars (size from the magnitude, color from B−V, down to the limit of the twilight), the names, the planets, the moon, the sun, the horizon, the terrain, and the selection or the arrow to it. The tap with the info card (rise and set over the terrain, in the background), Find (`FindPanel`), Align (a tap on an object to magnitude 2.5), the pinch zoom, the time panel, the place panel, and the red light. |
| `Starradar/Sky.swift` | `SkyAt`: the sky at one time (`starPositions` for the stars, the names of the constellations, and the Milky Way, once per second in `SkyCache`), and the targets (`Target`) with their name, direction, and rise and set (samples every 10 minutes). `horizonAt` is the terrain altitude for a frame. |
| `Starradar/StarData.swift` | The decoder of `stars.json`, the planets with their colors (`planetStyles`), and `StarLook` (size, color, limiting magnitude, twilight), the same rules as Android and `web/shared/src/stars.ts`. |
| `Starradar/MilkyWay.swift` | The points of the Milky Way in the galactic system, with `JavaRandom`, so the points are the same as on Android. |
| `Starradar/SettingsView.swift` | The settings (constellation lines, terrain, red light) and the credits of the data. |
| `StarradarTests/` | The tests of the star data, the look of the stars, the Milky Way, the random numbers, the rise and set with its text, `horizonAt`, the tap, the sky color, and the search. |
| `SharedTests/` | The unit tests of the detector, of `Geo`, of `MoonPhase`, of the DEM cache (with a fake HTTP layer), of the two steps of the terrain and "Keep offline" (`TerrainLoadTests.swift`), and of the base map, the search, and the time zones (`PlaceTests.swift`). `TestServer.swift` has a fake server with Range requests and a writer of small GeoTIFF tiles. |
| `SunradarUITests/` | The UI test that takes the store screenshots, and `Snapshots.swift`, the handoff with fastlane snapshot. |
| `fastlane/` | The lanes `screenshots` and `upload_listing`. |
| `Shared/MotionTracker.swift` | CoreMotion (`xArbitraryCorrectedZVertical`) and the heading filter of the core, with the magnetometer at 1 Hz. |
| `Shared/LocationProvider.swift` | The place: CoreLocation (an accuracy of a kilometer), a manual place, and the last place (`StoredPlace` in UserDefaults). |
| `Shared/CameraView.swift` | The AVFoundation preview, the camera model in the points of the view, and the frames for the sun detection. |

## Build and run

1. Build the xcframework: `python3 core/apple/build_xcframework.py` at the root.
2. Make the project: `xcodegen generate` in `ios/`.
3. Build for the simulator. For starradar, use the scheme `StarradarApp`:

   ```sh
   xcodebuild -project Almucantar.xcodeproj -scheme SunradarApp \
     -destination 'generic/platform=iOS Simulator' -derivedDataPath build/dd build
   ```

4. Run the tests: use the scheme `AlmucantarShared` (or `StarradarApp` for the tests of
   starradar), replace `build` with `test`, and give a simulator as the destination, for example
   `-destination 'platform=iOS Simulator,name=iPhone 15'`.
5. Install it with `xcrun simctl install booted build/dd/Build/Products/Debug-iphonesimulator/Sunradar.app`
   (or `Starradar.app`).

The simulator has no motion sensors and no camera. The app then shows a sky view that you
move with a drag. Set the location with `xcrun simctl location booted set <lat>,<lon>`.

## Rules

- The module names of the apps are `SunradarApp` and `StarradarApp`. `View` is in both SwiftUI and the package
  `Almucantar`, so write `SwiftUI.View` and `Almucantar.View`.
- The interface orientation gives the `ScreenRotation`: `landscapeRight` is `rotation90` (the
  top of the device is on the left), and `landscapeLeft` is `rotation270`. This is the same
  convention as `Display.getRotation()` on Android.
- Labels stay upright relative to the horizon, the same as on Android.
- The frames for the sun detection stay in the orientation of the sensor, so a point of a frame,
  divided by the size of the frame, is a capture device point. `layerPointConverted` maps it to the view.
- The camera model has no distortion, because AVFoundation gives no lens distortion for the
  preview.
- The demo mode makes `TerrainHorizon` without a cache, so it never downloads a tile and shows no
  terrain.
- The place (`PLAN.md` 13.3) is the device location, a manual place, or the last place, in
  `LocationProvider`. A device location does not replace a manual place; "Use my location" ends it.
  The app stores the last place and starts with it. The default place (Vienna) gets no terrain;
  every other place does. The PWA (`web/shared/src/basemap.ts`) has the same behavior.
- All days and times use the time zone of the place, not of the device: use the `calendar` of
  `SkyScreen` and the functions of `PlaceTime`, never `Calendar.current` or `Date.formatted()`
  without a time zone.
- The demo mode does not read or write the stored place.
- The index of `DemCache` is `{"tiles": {...}, "kept": [...]}`. An index of an older version is only
  the dictionary of the tiles; the cache still reads it. The eviction never deletes a tile of a kept
  place, and these tiles do not count toward the limit of 400 MB. The revalidation after 180 days
  applies to all tiles. "Remove" only deletes the record, so its tiles become normal cache tiles.
- The terrain loads in two steps (`PLAN.md` 14.3). The PWA and Android have the same steps:
  1. The coarse check: for each tile, the cache downloads only the prefix that level 2 needs
     (`elevationGeotiffPrefixSize`): first `bytes=0-16383`, then the rest of the prefix. A size of 0
     gets a header that is 4 times longer. 404 is the sea. A 200 for a Range request is the whole
     file; the cache keeps only the prefix. The app shows the coarse profile at once.
  2. If the coarse profile reaches `TerrainHorizon.fullTerrainMinDeg` (1°), the full files of the
     tiles nearer than `ElevationSource.fullResolutionM` (25 km), and the profile again with
     `fullLevel` (0) for these tiles. Below 1°, the app downloads nothing more.
- The facts of the data come from the `ElevationSource` of the cache (`cache.source`), never from
  constants in the terrain logic. Copernicus is a surface model: its heights include buildings and
  forests, so a city gives a false wall of several degrees around the observer. Both steps and
  "Keep offline" use `source.horizonOptions`, which ignores the terrain nearer than
  `nearClearanceM` (2000 m) for a surface model. The coarse check against 1° must use the same
  options as the full profile, or a city downloads the full tiles for a wall that the full profile
  does not show. The PWA uses the same clearance (`NEAR_CLEARANCE_M`).
- The cache keeps the tiles by their name, not by their source. A new source needs a new directory
  of the cache, or the cache gives the files of the old source.
- A full file also serves level 2, so the cache downloads no prefix if it has the full file, and it
  deletes the prefix file when it stores the full file. The revalidation of a prefix file is a
  conditional Range request (If-None-Match and Range): 304 keeps the file, 206 or 200 replaces it.
- A kept place keeps the tile names. The eviction protects both entries of each kept tile (the full
  file and the prefix file), because the terrain uses the one that the cache has.
- `calculateProfile` sorts the grids, because the core takes the first tile with data at the edge
  of two tiles. Without a fixed order, the same grids can give a slightly different profile.
- `TerrainHorizon` and `KeptPlaces` use the same actor, `DemCache.shared`, because both write the
  index. Do not make a second `DemCache` for the same directory.
- `TerrainHorizon` tries the missing tiles of the place again every 60 s (`retryInterval`), the same
  as Android. A new place or the setting off stops it. A move of more than 5 km (`keepProfileM`)
  removes the old profile at once. If the tile at the observer is the sea, or the core has no data at
  the observer, the profile is at sea level (`useGridElevation` false, `observerElevationM` 0).
- `DemCache` downloads an entry only once at the same time: a second request waits for the running
  download (`pending`) and gets its result.
- `terrainAltitudeDeg()` converts the whole profile for each call. Call it when the profile or the
  day changes (`TerrainDayCache`), not for each frame. A frame of starradar needs the terrain for
  each star, so it uses `horizonAt`, the same interpolation in Swift. A test compares the two.
- The sky of starradar changes once per second (`SkyCache`). A frame only projects it with
  `projectPoints()`. The stars of the same brightness and color go into one path, because one fill
  for each of 5000 stars is slow.
- The alignment of starradar sets the time to now and takes the direction of the object now,
  because the user points at the real sky.

## Screenshots

The store screenshots follow [`../screenshots/README.md`](../screenshots/README.md). The short
version:

1. Build the xcframework, and install the gems (`bundle install` at the root) and the `.venv`.
2. Run `bundle exec fastlane screenshots` in `ios/`. Set `SUNRADAR_SCREENSHOT_DEVICE=iphone` or
   `ipad` for one device.
3. The lane runs `xcodegen generate`, builds the scheme `Sunradar Screenshots` one time, and runs
   snapshot for each device on the newest runtime that has the device. Then it frames the images
   with `scripts/screenshots/frame.py`.

The UI test starts the app with `-SunradarScreenshot <screen>` and `TZ=Europe/Vienna`. The demo
mode fixes the place, the time, the view, and the heading, and draws the sky in place of the camera.
It sets the accessibility identifier `sunradar-screenshot-ready` when the dates of the crosshair
are ready. A SwiftUI menu does not open from code, so the test taps "Align" for `03-align`.
