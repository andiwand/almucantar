# Changelog

The changes in the iOS app that a user notices. Each release has the tag `ios-vX.Y.Z`. The
rules are the same as in `core/CHANGELOG.md`.

## Unreleased

- The place works fully offline. The new button "Place" opens a search of 7342 places and a map
  (Natural Earth, in the app, no network needed). A tap on the map drops the place. The app keeps
  the last place and starts with it, also offline and without a location permission. A dropped
  place stays until you choose "Use my location".
- All days and times use the time zone of the place: the day of the path, the hours, the time
  panel, the dates of the crosshair, and the sunrise and sunset. A dropped place gets the time
  zone of the nearest place on the map.
- The terrain horizon also loads for a dropped place and for the last place.

- The terrain horizon: a line of the mountains around you, from the Copernicus DEM GLO-90. The
  parts of the sun path behind the terrain are dashed, and the status panel shows the visible
  sunrise and sunset. The app downloads the elevation tiles of the place and keeps them for
  offline use. A setting switches it off. The new menu button (⋮) at the end of the button row
  opens the settings and the credits.

- The hour marks on the path use 24 hours in every locale, as on Android.
- The moon shows its phase. The lit part faces the sun.
- A camera permission reply cannot start capture after the camera view closes.
- Dragging the sky view follows the drag distance, independent of gesture event frequency.
- A sky view that follows the orientation of the phone. It shows the horizon, the cardinal
  points, the path of the sun on the selected day with the full hours, the sun now, and the
  paths at the solstices.
- The camera image below the sky view, with the field of view of the camera.
- The time when the sun is in the direction of the crosshair, with a vertical line from the
  horizon to the sun, and the dates when the sun is in the crosshair.
- A date slider and a time slider.
- A heading filter with the magnetometer. It estimates the error that the OS calibration of
  the magnetometer leaves, so an alignment with the sun, the moon, or a landmark stays and does
  not drift back to the compass heading.
- The moon, and an arrow at the border of the screen that points to the sun when it is not on
  the screen.
- An align menu: with the sun, with the moon, or with a landmark that you pick on a map. Each
  item says why it is not possible now.
- Sun detection: a box around the sun in the camera image. Tap the box to align the heading.
- Without motion sensors (the simulator), drag to look around.
