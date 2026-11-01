# core-android

The AAR `at.stefl.sunradar:core-android`: the Kotlin API of the sunradar core and the
native library `libsunradar_jni.so` for `arm64-v8a` and `x86_64`.

```kotlin
import at.stefl.sunradar.core.*

val vienna = Observer(latitudeDeg = 48.2082, longitudeDeg = 16.3738)
val sun = sunPosition(System.currentTimeMillis() / 1000.0, vienna)
```

## Requirements

- JDK 17 or later (Android Studio has one in `Contents/jbr/Contents/Home`)
- The Android SDK (`ANDROID_HOME`) with the NDK `28.2.13676358`
- Conan 2, CMake 3.24 or later, Python 3

## Build

Run these commands in `core/android/`. The Gradle task `buildNative` runs
`build_native.py`, which builds the native library for each ABI with Conan and CMake.

1. Build the AAR, run lint, and check the format:

   ```sh
   ./gradlew assembleRelease lintDebug spotlessCheck
   ```

2. Run the instrumented tests on an emulator. Set `ANDROID_SERIAL`, so the tests do not
   install on a connected phone:

   ```sh
   ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
   ```

Gradle properties and environment variables:

| Property | Variable | Meaning |
|---|---|---|
| `sunradar.abis` | `SUNRADAR_ABIS` | The ABIs that `build_native.py` builds, default `armv8,x86_64`. Empty: use the libraries that are already in `native/prebuilt/jniLibs`. |
| `sunradar.python` | `SUNRADAR_PYTHON` | The Python that runs `build_native.py`, default `python3`. It must have Conan. |
