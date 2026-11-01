# Astronomy Engine

The C version of Astronomy Engine by Don Cross, MIT license (see `LICENSE`). The core uses
it for the moon and as a second model of the sun.

- Source: <https://github.com/cosinekitty/astronomy>, tag `v2.1.19`, files
  `source/c/astronomy.c`, `source/c/astronomy.h`, and `LICENSE`
- SHA-256:
  - `astronomy.c`: `388920479d819713963ffd0f8ab0677944f66c6c94239ceddddd3b0d5d064432`
  - `astronomy.h`: `83a31011957c2b87e22f0828820f370f1a495aa4d51357bb57d714cde839dc00`
  - `LICENSE`: `690dd98cb13ba4db77c6327deea852a816892bb9debbad5943405c66972f8023`

The files are in the repository and not fetched at build time, so the Android, WASM, and
Python builds need no network. Do not edit them. To update, replace the files and the
checksums, and run the tests.
