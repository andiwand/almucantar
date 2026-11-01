# AGENTS.md — the Android app

The app `at.stefl.sunradar`. It uses the AAR `at.stefl.sunradar:core-android` from source:
`settings.gradle.kts` includes `../core/android` as a composite build. The app contains no
C++ and no JNI code. Read [`../core/android/AGENTS.md`](../core/android/AGENTS.md) for the
API of the AAR.

## Layout

| Path | What |
|---|---|
| `app/src/main/kotlin/at/stefl/sunradar/MainActivity.kt` | The activity and the location permission. |
| `.../SkyScreen.kt` | The sky view (Compose): horizon, cardinal points, path, hour marks, sun, crosshair with the auto-mode time, and the status text. |
| `.../SkyDay.kt` | The data of one local day, calculated once per day and place. |
| `.../HeadingTracker.kt` | The game rotation vector, the magnetometer (once per second), and the heading filter of the core. `apply()` takes a calibration, for example a sun tap. |
| `.../SkyDrawing.kt` | Drawing helpers: upright labels, the arrow at the screen border, dashed lines. |
| `.../TimePanel.kt` | The date and time sliders. |
| `.../MapPicker.kt` | A small OpenStreetMap tile map to pick a landmark. It follows the OSM tile policy: a User-Agent that names the app, the attribution, and tiles in memory only. |
| `.../Geo.kt` | Bearing and distance on a sphere. |
| `.../SunDetector.kt` | Finds saturated regions in the Y plane of a camera frame. Pure Kotlin, tested in `app/src/test/`. `CameraPreview.kt` runs it in an `ImageAnalysis` and maps the regions to the view with sensorToView * (sensorToBuffer)^-1. |
| `.../Location.kt` | The place: the device location (coarse), else the city of the time zone, else Vienna. The town name comes from the geocoder of the device. |
| `.../TimeZonePlaces.kt` | Reads `assets/tzdata/zone.tab` (IANA, public domain). |
| `.../CameraPreview.kt` | The CameraX preview. It switches the distortion correction off, reads the Camera2 calibration, and gives the camera model after each change of the sensor-to-view transform. |
| `.../CameraCalibration.kt` | Converts the Camera2 calibration and `PreviewView.getSensorToViewTransform()` to a `Camera` in view pixels. Pure Kotlin, tested in `app/src/test/`. |
| `app/src/test/` | JVM unit tests. |

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
