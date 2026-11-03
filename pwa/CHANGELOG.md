# Changelog

The changes in the PWA that a user notices. Each release has the tag `pwa-vX.Y.Z`. The rules
are the same as in `core/CHANGELOG.md`.

## Unreleased

## 0.1.0 - 2026-11-03

- The Place panel looks like a map app: a full-screen map, a rounded search bar with a back arrow,
  results with the country, round map buttons, a pin, and a card with the place, its time zone, and
  "Keep offline".
- The terrain loads much less data: first coarse tiles (about 400 KB), and the detailed tiles near
  the place only where the terrain is higher than 1°. Berlin needs 0.4 MB, Vienna 1.4 MB, and no
  place needs the 9 MB of before.
- "Keep offline" in the Place panel: the app downloads the terrain of the place for a trip and
  keeps it. The cache does not remove these tiles, and "Remove" releases them.
- The Place panel: search a place or tap it on a map, also offline. The map is in the app
  (Natural Earth). The app keeps the last place, and the times use the time zone of the place.
- The terrain horizon: the sky view shows the mountains around the place, and the sun path is
  dimmer where the terrain hides the sun. The status shows when the sun comes over the terrain
  and goes behind it. The app downloads the elevation tiles (AWS Terrain Tiles) once and keeps
  them, also offline. A setting in the time panel switches the terrain off.
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
