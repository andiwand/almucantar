# Changelog

The changes in the core, for the users of the core: the apps, the bindings, and
the packages on PyPI, npm, Maven Central, and SwiftPM. Each release of the core
has the tag `core-vX.Y.Z`.

An entry goes under `Unreleased` in the pull request that makes the change. Write
an entry only for a change that a user of the core notices: the API, the results,
the precision, or the packages. Refactoring, tests, and CI do not get an entry. A
change that breaks the API starts with **Breaking**.

The release puts the version and the date on the entries under `Unreleased`, and
starts a new empty `Unreleased`. The release refuses an empty `Unreleased`.

## Unreleased

### API

- The public API is `sunradar/sunradar.hpp`: `sun_position()`, `sun_events()`, and
  `sun_path()`, with the value types `Observer`, `Horizontal`, `Atmosphere`, and the
  option structs. `SolarModel` selects the model. The only model is `noaa`.
- An invalid argument throws `std::invalid_argument`: a value that is not finite, a
  latitude outside [-90, 90], an empty interval, a step that is not positive, or a
  path with more than 1,000,000 points.

### Precision

- The position of the sun agrees with JPL Horizons to 0.015° or better for 1950 to
  2100. Sunrise and sunset agree to 71 s or better (135 s in Tromsø), mostly
  because Horizons rounds its times to the minute.

### Packages

- The Python package `sunradar` (pybind11, scikit-build-core). It has the same
  functions and types as the C++ API. An invalid argument raises `ValueError`.
