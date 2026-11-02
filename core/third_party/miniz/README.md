# miniz

miniz by Rich Geldreich, MIT license (see `LICENSE`). The core uses only its inflate code
(`tinfl`), to read DEFLATE-compressed GeoTIFF files (the elevation tiles).

- Source: <https://github.com/richgel999/miniz>, release `3.0.2`, file `miniz-3.0.2.zip`, files
  `miniz.c`, `miniz.h`, and `LICENSE`
- SHA-256:
  - `miniz.c`: `0fcdc9888cb3a29ca8f176bac087e5fe6c7258a6ab06b1c271c1e109a11d3740`
  - `miniz.h`: `295d1a0041aea09609598c0f1f35c1977ca05ad662acbadcfdaac44c140af37b`
  - `LICENSE`: `0115478d567121238cf6cc1c0c361926cf07a49d9e4c9e66da97fac6a01646b3`

The files are in the repository and not fetched at build time, so the Android, WASM, and
Python builds need no network. Do not edit them. `CMakeLists.txt` switches off the parts that
the core does not use (`MINIZ_NO_*`). To update, replace the files and the checksums, and run
the tests.
