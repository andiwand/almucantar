# AGENTS.md — the Android AAR

The JNI binding of the public C++ API (`core/src/sunradar/*.hpp`) and its Kotlin API,
packaged as the AAR `at.stefl.sunradar:core-android`. The build loop is in
[`README.md`](README.md).

## Layout

| Path | What |
|---|---|
| `jni/sunradar_jni.cpp` | The JNI functions (`libsunradar_jni.so`). The core builds them if `SUNRADAR_JNI` is ON. The comment at the top documents the array layouts. |
| `jni/CMakeLists.txt` | The `sunradar_jni` target: hidden visibility, so only the JNI functions are exported. |
| `build_native.py` | Conan and CMake for each ABI into `native/prebuilt/jniLibs/<abi>/`. The Gradle task `buildNative` and CI run it. |
| `src/main/kotlin/` | The Kotlin API: `Types.kt` (value types), `Sunradar.kt` (functions), `Lines.kt` (packed lines), `Pack.kt` (array layouts), `SunradarNative.kt` (the `external` functions). |
| `src/androidTest/` | Instrumented tests. They run on a device or an emulator. Their assets are the GeoTIFF test files of the core (`../test/data/geotiff/`). |

## Design

- Every value crosses JNI as a flat `DoubleArray`, and every result comes back as a
  `DoubleArray`. So the C++ code needs no `FindClass` or `GetFieldID`, and a frame allocates
  only a few arrays. The layouts are in `sunradar_jni.cpp` and in `Pack.kt`. Change both, or
  neither.
- Bulk data is the exception: the GeoTIFF file crosses as a `ByteArray`, and the heights of an
  `ElevationGrid` cross as a `FloatArray` (one per tile, in an `Array<FloatArray>`). A tile has
  more than a million heights, so a `DoubleArray` or a list of boxed values costs too much.
  `readElevationGeotiff` returns the heights and writes the other values into a `DoubleArray`
  that the caller gives.
- `HorizonProfile` keeps its packed form after the first native call. So do not change its arrays
  after the first use.
- Enums cross as ordinals. The order of the Kotlin constants must be the same as in C++.
- The Kotlin defaults must be the same as the C++ defaults. The test
  `defaultsAreTheSameAsInCpp` compares them with the native function `defaults()`.
- `std::invalid_argument` becomes `IllegalArgumentException`, other C++ exceptions become
  `RuntimeException`.
- `DirectionLine` and `Polylines` hold lines in packed form. An app packs the path once per
  day and projects it in each frame without conversion.
- The native library links libc++ statically (`c++_static` in the Conan profile), because the
  AAR has only one native library. So the AAR does not ship `libc++_shared.so`.

## Rules

- Bind the public API only. Never include `sunradar/internal/...` headers.
- A new public C++ function needs: a JNI function, an `external` declaration, a Kotlin
  function with the C++ name in camel case, and an instrumented test.
- Kotlin is formatted with ktfmt in the kotlinlang style (`./gradlew spotlessApply`).
- Run the instrumented tests on an emulator only: set `ANDROID_SERIAL`, because
  `connectedAndroidTest` installs on all connected devices.
