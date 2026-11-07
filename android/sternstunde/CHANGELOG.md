# Changelog

The changes in the Android app Sternstunde that a user notices. Each release has the tag
`sternstunde-android-vX.Y.Z`. The rules are the same as in `core/CHANGELOG.md`.

## Unreleased

## 0.3.0 - 2026-11-07

- Without a location and without a last place, the app uses the most probable place: the main
  city of the time zone of the phone, else of the country of its language. Vienna is no longer the fixed
  default place.

## 0.2.0 - 2026-11-06

- The settings have a link to the privacy policy.
- The name of your location comes from the offline map of the app ("near" the nearest town), not
  from the geocoder of the phone. So the app sends your location to no service.

## 0.1.0 - 2026-11-06

- The first version: the stars to magnitude 6 (size from the brightness, color from the
  temperature), the 88 constellations with their lines and names, the planets, the moon with its
  phase, the sun, and the Milky Way, in the direction of the phone, also on the camera image. The
  sky gets dark with the twilight, so the stars come out in the order that the eye sees them.
- The real horizon: the terrain of the place hides the stars behind the mountains, and a tapped
  object shows its rise and set over the terrain.
- Tap a star, a planet, the moon, or a constellation for its name, brightness, height, and rise
  and set. "Find" searches the stars, the planets, and the constellations, and an arrow at the
  border of the screen leads to the object. The end of a pinch does not select an object.
- "Align": tap a bright star, a planet, or the moon in the camera image to correct the heading of
  the compass. "Align" switches the camera on, because the alignment compares the drawn object
  with the real one, and it sets the time back to now.
- A star without a proper name shows its number in the Bright Star Catalogue, for example
  "HR 4731".
- The time panel, the place panel with the offline map and "Keep offline", and a red light that
  keeps the eyes adapted to the dark.
