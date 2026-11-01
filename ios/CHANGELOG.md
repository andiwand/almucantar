# Changelog

The changes in the iOS app that a user notices. Each release has the tag `ios-vX.Y.Z`. The
rules are the same as in `core/CHANGELOG.md`.

## Unreleased

- A sky view that follows the orientation of the phone. It shows the horizon, the cardinal
  points, the path of the sun on the selected day with the full hours, the sun now, and the
  paths at the solstices.
- The camera image below the sky view, with the field of view of the camera.
- The time when the sun is in the direction of the crosshair, with a vertical line from the
  horizon to the sun, and the dates when the sun is in the crosshair.
- A date slider and a time slider.
- A heading filter with the magnetometer.
- The moon, and an arrow at the border of the screen that points to the sun when it is not on
  the screen.
- An align menu: with the sun, with the moon, or with a landmark that you pick on a map. Each
  item says why it is not possible now.
- Sun detection: a box around the sun in the camera image. Tap the box to align the heading.
- Without motion sensors (the simulator), drag to look around.
