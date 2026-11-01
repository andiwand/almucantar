# sunradar (Python)

The Python binding of the sunradar core. It calculates the position, the path,
and the rise and set times of the sun.

```python
import sunradar

vienna = sunradar.Observer(latitude_deg=48.2082, longitude_deg=16.3738)

# The position at 2026-06-21T10:57Z, with refraction.
print(sunradar.sun_position(1782039420, vienna))

# The sunrise and the sunset of the local day 2026-06-21 (CEST).
day = sunradar.sun_events(vienna, 1781992800, 1781992800 + 86400)
for crossing in day.crossings:
    print("rise" if crossing.rising else "set", crossing.unix_utc)

# The path in steps of 10 minutes.
path = sunradar.sun_path(vienna, 1781992800, 1781992800 + 86400, sunradar.PathOptions(step_s=600))
```

All times are Unix time in seconds (UTC). An invalid argument raises `ValueError`.

## Build and test

Run these commands in `core/`.

1. Install the C++ dependencies:

   ```sh
   conan install . --build=missing -s build_type=Debug -o "&:with_python=True"
   ```

2. Configure and build. CMake uses the first Python on the `PATH`, or the one in
   `Python_EXECUTABLE`:

   ```sh
   cmake --preset conan-debug
   cmake --build --preset conan-debug
   ```

3. Run the tests. CTest runs pytest on the build tree:

   ```sh
   ctest --preset conan-debug -R python --output-on-failure
   ```

   To run pytest directly:

   ```sh
   PYTHONPATH=build/Debug/python python -m pytest python/tests
   ```

## Build a wheel

`pip` builds the wheel with scikit-build-core (`core/pyproject.toml`). Give it the
Conan toolchain file:

```sh
conan install . --build=missing -s build_type=Release -o "&:with_python=True" -o "&:with_tests=False"
CMAKE_ARGS="-DCMAKE_TOOLCHAIN_FILE=$PWD/build/Release/generators/conan_toolchain.cmake" pip wheel . -w dist
```

The version comes from the latest `core-v*` git tag (setuptools-scm).
