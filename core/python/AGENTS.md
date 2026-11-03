# AGENTS.md — the Python binding

The pybind11 binding of the public C++ API (`src/almucantar/*.hpp`), packaged as
`almucantar`. The build and test loop is in [`README.md`](README.md).

## Layout

| Path | What |
|---|---|
| `CMakeLists.txt` | Builds the `almucantar/_core` extension (target `almucantar_python`). The core includes it if `ALMUCANTAR_PYTHON` is ON. |
| `src/module.cpp` | The binding. |
| `almucantar/` | The pure Python package. `__init__.py` re-exports `_core`. |
| `tests/` | The pytest suite. CTest runs it as `almucantar_python_pytest`. |

## Rules

- Bind the public API only. Never include `almucantar/internal/...` headers.
- Mirror the C++ names exactly: functions, types, fields, enumerators, and the
  argument names. Constants use upper case (`SUNRISE_ALTITUDE_DEG`).
- Every struct gets a constructor with keyword arguments. The defaults come from a
  default-constructed C++ struct, so they cannot drift from the C++ defaults.
- A `__repr__` formats each number with Python's `repr()`, so it shows the full
  value (a Unix time does not become `1.78e+09`).
- `std::invalid_argument` becomes `ValueError`. pybind11 does this.
- A new public C++ function: bind it here and add a pytest.
- The package has no type stubs yet, so it has no `py.typed` marker.
- Format Python with black (`scripts/format`).
