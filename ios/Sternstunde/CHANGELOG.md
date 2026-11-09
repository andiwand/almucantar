# Changelog

The changes in the iOS app Sternstunde (starradar before) that a user notices. Each release has
the tag `sternstunde-ios-vX.Y.Z`. The rules are the same as in `core/CHANGELOG.md`.

## Unreleased

- Tap the sun, the moon, or a planet, and choose "Show path (24 h)" to see its path from 12 hours
  before to 12 hours after the selected time, with the full hours. The parts behind the terrain or
  below the horizon are dashed. The settings have the same switch.
- The sky no longer jumps once per second while the compass calibrates itself after the start.
  The heading now glides to each new estimate in less than a second. The view still follows a
  turn of the phone at once.
- Without a location and without a last place, the app uses the most probable place: the main
  city of the time zone of the phone, else of the country of its region.
- The first version, with the features of the Android app: the stars to magnitude 6 (size from
  the brightness, color from the temperature), the 88 constellations with their lines and names,
  the planets, the moon with its phase, the sun, and the Milky Way, in the direction of the phone,
  also on the camera image. The sky gets dark with the twilight, so the stars come out in the order
  that the eye sees them.
- The real horizon: the terrain of the place hides the stars behind the mountains, and a tapped
  object shows its rise and set over the terrain.
- Tap a star, a planet, the moon, or a constellation for its name, brightness, height, and rise
  and set. "Find" searches the stars, the planets, and the constellations, and an arrow at the
  border of the screen leads to the object.
- "Align": tap a bright star, a planet, or the moon to correct the heading of the compass.
- Pinch to zoom the sky view, and tap two times to reset the zoom.
- The time panel, the place panel with the offline map and "Keep offline", and a red light that
  keeps the eyes adapted to the dark.
