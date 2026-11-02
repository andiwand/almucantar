# AGENTS.md — the Apple binding

The Swift package `Sunradar` of the public C++ API (`core/src/sunradar/*.hpp`). It has two
layers:

1. A C API (`include/sunradar_c.h`). It is plain C, so Swift imports it without C++ interop.
2. A Swift API (`swift/Sources/Sunradar`) on top of the C API, with Swift structs, enums, and
   throwing functions.

`Package.swift` is at the root of the repository, because SwiftPM needs that for a package
from a Git URL.

## Layout

| Path | What |
|---|---|
| `include/sunradar_c.h` | The C API: `sr_*` structs, `sr_default_*()`, and functions that return a status. |
| `src/sunradar_c.cpp` | The implementation of the C API on top of the public C++ API. |
| `CMakeLists.txt` | The `sunradar_c` static library and the C test. The core includes it if `SUNRADAR_APPLE` is ON. |
| `test/sunradar_c_test.c` | A test of the C API in C. CTest runs it if `SUNRADAR_TESTS` is ON. |
| `build_xcframework.py` | Builds `build/SunradarC.xcframework` (iOS, the simulator, macOS). `--zip` also writes the zip of a release and its checksum. |
| `swift/Sources/Sunradar` | The Swift API: `Types.swift` and `Sunradar.swift`. |
| `swift/Tests/SunradarTests` | The XCTest tests. |

## Build and test

Run these commands at the root of the repository.

```sh
python3 core/apple/build_xcframework.py        # all slices; --slice macos-arm64_x86_64 is faster
swift test
```

`Package.swift` uses the local xcframework if it exists. Else it uses the zip of a release.
On `main`, that zip is a placeholder, so build the xcframework first.

## Rules

- Bind the public API only. Never include `sunradar/internal/...` headers.
- The C API catches all C++ exceptions. A function returns `SR_OK`, `SR_INVALID_ARGUMENT`, or
  `SR_ERROR`, and `sr_last_error()` gives the message (one per thread).
- A function that returns a list allocates it. The caller frees it with `sr_free`,
  `sr_free_polylines`, `sr_free_elevation_grid`, or `sr_free_horizon_profile`. The Swift layer
  does that, so a Swift user never sees C memory. An array in an input struct is only borrowed.
- The heights of a DEM tile are large. `ElevationHeights` keeps the C buffer of
  `sr_read_elevation_geotiff` and does not copy it, and `horizonProfile()` gives the buffers to C
  without a copy. C++ copies them one time per call, because its API takes a `std::vector`.
- A C function has the name of the C++ function with `sr_`, but the name of a struct wins if they
  are the same: `sr_calculate_horizon_profile` and `sr_find_terrain_events`.
- The defaults of the options come from C++ (`sr_default_*()`). Do not write defaults in Swift.
- An invalid argument becomes `SunradarError` with `kind == .invalidArgument`.
- The field names are camelCase (`latitudeDeg`), the same as in the other bindings.
- A new public C++ function: add it to the C API, to the C test, to the Swift API, and to the
  XCTest tests.
