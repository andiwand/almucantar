# AGENTS.md — the WebAssembly binding

The embind binding of the public C++ API (`core/src/almucantar/*.hpp`), packaged as the npm
package `@almucantar/core`. The rules are the ones of the WASM binding of OpenDocument.core.

## Layout

| Path | What |
|---|---|
| `CMakeLists.txt` | The `almucantar_wasm` target (`almucantar.mjs` and `almucantar.wasm` in `build/.../wasm/dist`). The core includes it if `ALMUCANTAR_WASM` is ON. It fails without Emscripten. |
| `src/bindings.cpp` | The binding. |
| `js/` | The hand-written half of the package: `package.json`, `index.js` (defaults, enums), `index.d.ts`, `README.md`. CMake copies them next to the generated module. |
| `tests/` | The `node --test` suite. CTest runs it as `almucantar_wasm_node`. |

## Build and test

Run these commands in `core/`. The tests need node 18 or later; emsdk brings an older one,
so give the path with `ALMUCANTAR_NODE`.

```sh
conan install . --build=missing -pr:h conan/profiles/emscripten -pr:b default -o "&:with_tests=False" -o "&:with_wasm=True"
cmake --preset conan-emscripten-wasm-release -DALMUCANTAR_NODE="$(which node)"
cmake --build --preset conan-emscripten-wasm-release
ctest --test-dir build/emscripten-wasm-release --output-on-failure
```

## Rules

- Bind the public API only. Never include `almucantar/internal/...` headers.
- Values cross as plain JS objects (`value_object`) or arrays built with `emscripten::val`, so
  they can go through `postMessage`. No embind class handles. A test checks that results
  survive `structuredClone`.
- Enums cross as numbers. The C++ enums are typed, so the binding has mirror structs with
  integer fields (`SunOptionsJs` and others). `index.js` has the named constants.
- The field names are camelCase (`latitudeDeg`).
- `std::invalid_argument` becomes a JS `RangeError` with the same message.
- The defaults of the options come from C++ (`defaults()`), and `index.js` merges them, so a
  caller gives only the fields that change. Do not write defaults in JS.
- A new public C++ function: bind it, add it to `index.js` and `index.d.ts`, and add a test.
