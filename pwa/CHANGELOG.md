# Changelog

The changes in the PWA that a user notices. Each release has the tag `pwa-vX.Y.Z`. The rules
are the same as in `core/CHANGELOG.md`.

## Unreleased

- The sky view shows the moon with its phase. The lit part faces the sun.
- The offline cache includes the scripts and WebAssembly on the first visit. A new version
  activates only when no page of the old version is open, so an open page keeps its cached
  assets. Cache cleanup removes the cache of earlier versions and preserves other apps on the
  same site.
- Location access works without orientation sensors and after a sensor permission denial.
- An uncalibrated compass on iOS (heading -1) no longer gives a wrong heading.
- Camera requests cannot overlap. A failed video start releases the camera.
- Alignment near the zenith or nadir shows a message. Crosshair dates refresh at midnight.
- The first version: the sky view with the path of the sun, the hours, the horizon, the
  solstice paths, and the time when the sun is in the direction of the crosshair, with the
  dates for the crosshair. A time panel for another date and time.
- Sensors: the absolute device orientation (Chrome on Android) or the compass heading (iOS).
  On a computer, drag to look around.
- The camera with a slider for the field of view, and the alignment of the heading with the
  sun.
- Works offline after the first visit.
