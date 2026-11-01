# sunradar

sunradar shows the path of the sun on top of the camera image. You can see when the
sun is at a given place in the sky, for example when you visit a flat. See
[PLAN.md](PLAN.md) for the design and the milestones.

## Repository layout

This repository is a monorepo. Each directory is a self-contained project.

| Path | Content | Status |
|---|---|---|
| `core/` | the C++20 core: solar models, projection, search | in progress |
| `android/` | the Android app (Kotlin, Jetpack Compose) | planned (M4) |
| `pwa/` | the PWA (TypeScript, the core as WebAssembly) | planned (M8) |
| `ios/` | the iOS app | maybe later |

## Core

### Requirements

- CMake 3.24 or later
- Conan 2
- A C++20 compiler (Apple Clang, Clang, or GCC)
- For Android: the NDK `28.2.13676358`

### Build and test on the host

Run these commands in `core/`.

1. Install the dependencies:

   ```sh
   conan install . --build=missing -s build_type=Debug
   ```

2. Configure:

   ```sh
   cmake --preset conan-debug
   ```

3. Build:

   ```sh
   cmake --build --preset conan-debug
   ```

4. Run the tests:

   ```sh
   ctest --preset conan-debug --output-on-failure
   ```

To use the sanitizers, add `-DSUNRADAR_SANITIZE=ON` to step 2.

### Build for Android

Run these commands in `core/`. The profile finds the NDK through `ANDROID_NDK_HOME`. If
that variable is not set, it uses `ANDROID_HOME`, or `~/Library/Android/sdk` on macOS.

1. Install the dependencies (use `android-x86_64` for the emulator):

   ```sh
   conan install . --build=missing -pr:h conan/profiles/android-armv8 -pr:b default -o "&:with_tests=False"
   ```

2. Configure:

   ```sh
   cmake --preset conan-android-armv8-release
   ```

3. Build:

   ```sh
   cmake --build --preset conan-android-armv8-release
   ```
