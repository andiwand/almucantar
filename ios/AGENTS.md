# AGENTS.md — the iOS app

The iOS app in SwiftUI. It uses the Swift package `Sunradar` (`core/apple`, `Package.swift`
at the root) and contains no C++. The Android app (`android/`) is the reference for the
features and the behavior.

## Layout

| Path | What |
|---|---|
| `project.yml` | The XcodeGen project. `xcodegen generate` writes `Sunradar.xcodeproj`, which is not in Git. |
| `Sunradar/SunradarApp.swift` | The entry point. |
| `Sunradar/SkyScreen.swift` | The sky view: the drawing, the readout, the time panel, the buttons, and the align with the sun. |
| `Sunradar/MotionTracker.swift` | CoreMotion (`xArbitraryCorrectedZVertical`) and the heading filter of the core, with the magnetometer at 1 Hz. |
| `Sunradar/LocationProvider.swift` | CoreLocation, with an accuracy of a kilometer. |
| `Sunradar/CameraView.swift` | The AVFoundation preview and the camera model in the points of the view. |

## Build and run

1. Build the xcframework: `python3 core/apple/build_xcframework.py` at the root.
2. Make the project: `xcodegen generate` in `ios/`.
3. Build for the simulator:

   ```sh
   xcodebuild -project Sunradar.xcodeproj -scheme SunradarApp \
     -destination 'generic/platform=iOS Simulator' -derivedDataPath build/dd build
   ```

4. Install it with `xcrun simctl install booted build/dd/Build/Products/Debug-iphonesimulator/Sunradar.app`.

The simulator has no motion sensors and no camera. The app then shows a sky view that you
move with a drag. Set the location with `xcrun simctl location booted set <lat>,<lon>`.

## Rules

- The module name of the app is `SunradarApp`, because the package module is `Sunradar`.
  `View` is in both SwiftUI and `Sunradar`, so write `SwiftUI.View` and `Sunradar.View`.
- The interface orientation gives the `ScreenRotation`: `landscapeRight` is `rotation90` (the
  top of the device is on the left), and `landscapeLeft` is `rotation270`. This is the same
  convention as `Display.getRotation()` on Android.
- Labels stay upright relative to the horizon, the same as on Android.
- The camera model has no distortion, because AVFoundation gives no lens distortion for the
  preview.
