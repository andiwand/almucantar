# Sunradar for Android

The Android app. It shows the path of the sun, the horizon, and the sun on a sky view that
follows the orientation of the phone. The crosshair shows when the sun is at that place
today.

## Build

Requirements: JDK 17 or later, the Android SDK with the NDK `28.2.13676358`, Conan 2,
CMake, and Python 3. Run in `android/`:

```sh
./gradlew :sunradar:assembleDebug
```

The build includes `../core/android` from source. See [`AGENTS.md`](AGENTS.md) for the
tests and the rules.
