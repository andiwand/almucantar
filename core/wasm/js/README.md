# @almucantar/core

The almucantar core as WebAssembly: the position of the sun and the moon, the path of the
sun, sunrise and sunset, the magnetic declination (WMM2025), the projection to a camera
image, the search for times and dates, and a heading filter.

```js
import { load } from "@almucantar/core";

const almucantar = await load();
const vienna = { latitudeDeg: 48.2082, longitudeDeg: 16.3738 };
const sun = almucantar.sunPosition(Date.now() / 1000, vienna);
console.log(sun.azimuthDeg, sun.altitudeDeg);
```

All times are Unix time in seconds (UTC), and all angles are in degrees. Options are
optional: give only the fields that you want to change. All values are plain objects, so
they can go to and from a Web Worker. An invalid argument throws a `RangeError`.

License: MPL-2.0. Astronomy Engine (MIT) and the WMM2025 coefficients (public domain) are
part of the module.
