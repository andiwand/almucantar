# AGENTS.md — the iOS app

The iOS app in SwiftUI. It uses the Swift package `Sunradar` (`core/apple`, `Package.swift`
at the root) and contains no C++. The Android app (`android/`) is the reference for the
features and the behavior.

## Layout

| Path | What |
|---|---|
| `project.yml` | The XcodeGen project. `xcodegen generate` writes `Sunradar.xcodeproj`, which is not in Git. |
| `Sunradar/SunradarApp.swift` | The entry point. |
| `Sunradar/SkyScreen.swift` | The sky view: the drawing, the readout, the time panel, the align menu, and the detected sun. |
| `Sunradar/MapPicker.swift` | The MapKit map to pick a landmark. |
| `Sunradar/BaseMap.swift` | The offline base map (`assets/basemap/basemap.json`, Natural Earth, a resource of the app): the decode, the search, the nearest place and its time zone, and Web Mercator (`MapViewport`). `BaseMapStore` decodes it once, off the main thread. |
| `Sunradar/PlacePanel.swift` | The place panel: the search and the offline map, drawn with a `Canvas` (`BaseMapDrawing`), where a tap drops the place. |
| `Sunradar/PlaceTime.swift` | The calendar, the day, and the text of the times in the time zone of the place. |
| `Sunradar/SunDetector.swift` | The saturated regions of a luminance image (the same algorithm as on Android). |
| `Sunradar/Geo.swift` | The bearing, the distance, and the angle between two directions. |
| `Sunradar/MoonPhase.swift` | The lit part of the moon and its direction to the sun. |
| `Sunradar/ScreenshotMode.swift` | The demo mode of the store screenshots (debug builds only) and its scenes. |
| `Sunradar/DemoSky.swift` | The drawn sky of the demo mode: the sky of the website, in the projection of the app. |
| `Sunradar/DemCache.swift` | The persistent cache of the Copernicus GLO-90 tiles in Application Support/`dem`, with the JSON index, the revalidation after 180 days, and the eviction over 400 MB. `HTTPClient` is the HTTP layer that the tests replace. |
| `Sunradar/TerrainHorizon.swift` | The terrain horizon of the place: it loads the tiles, calculates the profile off the main thread, and derives the hidden parts of the path and the visible sunrise and sunset (`TerrainDay`). |
| `Sunradar/SettingsView.swift` | The settings (the terrain horizon) and the credits. |
| `SunradarTests/` | The unit tests of the detector, of `Geo`, of `MoonPhase`, of the DEM cache (with a fake HTTP layer), and of the base map, the search, and the time zones (`PlaceTests.swift`). |
| `SunradarUITests/` | The UI test that takes the store screenshots, and `Snapshots.swift`, the handoff with fastlane snapshot. |
| `fastlane/` | The lanes `screenshots` and `upload_listing`. |
| `Sunradar/MotionTracker.swift` | CoreMotion (`xArbitraryCorrectedZVertical`) and the heading filter of the core, with the magnetometer at 1 Hz. |
| `Sunradar/LocationProvider.swift` | The place: CoreLocation (an accuracy of a kilometer), a manual place, and the last place (`StoredPlace` in UserDefaults). |
| `Sunradar/CameraView.swift` | The AVFoundation preview, the camera model in the points of the view, and the frames for the sun detection. |

## Build and run

1. Build the xcframework: `python3 core/apple/build_xcframework.py` at the root.
2. Make the project: `xcodegen generate` in `ios/`.
3. Build for the simulator:

   ```sh
   xcodebuild -project Sunradar.xcodeproj -scheme SunradarApp \
     -destination 'generic/platform=iOS Simulator' -derivedDataPath build/dd build
   ```

4. Run the tests: replace `build` with `test`, and give a simulator as the destination, for example
   `-destination 'platform=iOS Simulator,name=iPhone 15'`.
5. Install it with `xcrun simctl install booted build/dd/Build/Products/Debug-iphonesimulator/Sunradar.app`.

The simulator has no motion sensors and no camera. The app then shows a sky view that you
move with a drag. Set the location with `xcrun simctl location booted set <lat>,<lon>`.

## Rules

- The module name of the app is `SunradarApp`, because the package module is `Sunradar`.
  `View` is in both SwiftUI and `Sunradar`, so write `SwiftUI.View` and `Sunradar.View`.
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
  every other place does. The PWA (`pwa/src/basemap.ts`) has the same behavior.
- All days and times use the time zone of the place, not of the device: use the `calendar` of
  `SkyScreen` and the functions of `PlaceTime`, never `Calendar.current` or `Date.formatted()`
  without a time zone.
- The demo mode does not read or write the stored place.
- `terrainAltitudeDeg()` converts the whole profile for each call. Call it when the profile or the
  day changes (`TerrainDayCache`), not for each frame.

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
