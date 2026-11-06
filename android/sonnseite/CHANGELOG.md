# Changelog

The changes in the Android app Sonnseite (the name was Sunradar up to 0.2.0) that a user notices.
Each release has the tag `sonnseite-android-vX.Y.Z`. The rules are the same as in
`core/CHANGELOG.md`.

## Unreleased

- The name of your location comes from the offline map of the app ("near" the nearest town), not
  from the geocoder of the phone. So the app sends your location to no service.

## 0.3.0 - 2026-11-06

- Sonnseite has the bright look of the day of the website. Without the camera, the sky has the
  colors of the height of the sun, with a glow around the sun and a light ground. The path of the
  sun has a dark edge, and by day the labels and the crosshair are dark, so they are easy to see.
  The panels are warm white with dark text, and the main button is sun yellow.
- Sunradar has the new name Sonnseite, the Austrian word for the sunny side of a valley. The app
  has the new ID `at.stefl.sonnseite`, so Android installs it as a new app: remove Sunradar
  yourself. The stored place and the settings do not move to the new app.

## 0.2.0 - 2026-11-03

- The place panel looks like a map app: a rounded search bar with a back arrow, results with the
  country, round map buttons, a pin, and a card with the place, its time zone, and "Keep offline".
- The icons of the system bars are always light, also when the system uses its light theme.
- The terrain horizon ignores the terrain nearer than 2 km. The elevation data of Copernicus
  includes buildings and forests, so a city gave a false wall of several degrees around you (5.6°
  in the centre of Vienna). Now the centre of Vienna shows the Wienerwald in the northwest at 2.3°.
  The check that decides about the full data and "Keep offline" use the same rule.
- The terrain horizon downloads much less data where the terrain does not matter. First the app
  downloads only the first part of each tile (about 300 KB, with samples about 370 m apart) and
  shows this coarse terrain at once. Only if the terrain reaches 1° above the horizon somewhere, it
  downloads the full data of the tiles nearer than 25 km. A flat place needs about 3 MB in place of
  about 44 MB, Vienna about 12 MB, and Innsbruck about 7 MB. "Keep offline" keeps the same data.
- "Keep offline" in the place panel downloads the elevation data of the place (100 km around it),
  so you can prepare a trip. The panel shows the progress ("Downloading 3/9") and the result
  ("Ready offline: 9 tiles, 44 MB"). The download continues when you close the panel. The list of
  the kept places shows the name, the number of tiles, and the size. The app never removes the data
  of a kept place, and this data does not count toward the limit of 400 MB. "Remove" makes the data
  a normal part of the cache again.
- The alignment with a landmark also works with a dropped place, for example offline without GPS.
- The place works offline. The new button "Place" (or a tap on the place in the status) opens a
  search of 7342 places and a map of Natural Earth that is in the app. A tap on the map drops the
  place. The app keeps the last place and starts with it, also offline and without the location
  permission. A dropped place stays until you choose "Use my location".
- All days and times use the time zone of the place, not of the device: the path, the hour labels,
  the time panel, the crosshair dates, and the sunrise and sunset. The status shows the zone if it
  is not the zone of the device.
- The terrain horizon also works for a dropped place and for the last place.
- The settings button (⋮) is the last button of the bottom row.
- The terrain horizon: the app downloads the elevation data of Copernicus DEM GLO-90 for 100 km
  around the device location and draws the mountains as a line on the sky and on the camera
  image. The parts of the sun path behind the mountains are dim and dashed. The status shows the
  visible sunrise and sunset over the terrain. The app keeps the data (at most 400 MB) and checks
  it with the server after 180 days; it works offline with the data that it has. The new
  settings (⋮) can switch the terrain horizon off, and show the credits of the data.
- The moon shows its phase. The lit part faces the sun.
- The sun path, hour labels, and crosshair dates refresh when the device time zone changes.
- Alignment near the zenith or nadir shows a message instead of closing the app.
- The landmark map keeps polar locations within its bounds.
- Sun detection connects adjacent pixels in images with one sampled column.
- The heading filter estimates the bias of the magnetometer. After an alignment with the sun,
  the moon, or a landmark, the heading stays aligned and does not drift back to the compass
  heading. The app reads the raw magnetometer and calibrates it itself; the OS calibration is
  only the first guess.
- The heading accuracy is more realistic: the compass alone shows about ±5–10°, and better
  values need an alignment or some rotation of the phone.

## 0.1.0 - 2026-11-01

- A sky view without camera. It follows the orientation of the phone (rotation vector
  sensor) and shows the horizon, the cardinal points, the path of the sun today with the
  full hours, and the sun now.
- Auto mode: the crosshair shows the time when the sun is there today, or "The sun does not
  pass here today".
- The place comes from the coarse location. Without it, the app uses the main city of the
  time zone, and it says so. The app shows the nearest town and the error of the location
  as a power of ten, with the error that it causes on the sky.
- Camera mode: the "Camera" button shows the overlay on the image of the back camera. The
  overlay uses the lens calibration of the device (`LENS_INTRINSIC_CALIBRATION` and
  `LENS_DISTORTION`, with the distortion correction of the camera switched off), or else the
  focal length and the size of the sensor. The status shows which one.
- The app rotates with the screen, and the overlay stays correct in landscape. The activity
  handles the rotation itself, so the camera does not restart.
- Lines no longer flicker when the phone turns.
- The heading comes from a filter: the game rotation vector (no magnetometer, so steel does
  not disturb it), one magnetometer measurement per second, and an alignment with the sun:
  in camera mode, tap "Align with the sun" and then the sun. The status shows the heading
  accuracy in color.
- A new layout: readable panels on the camera image, labels that stay upright relative to
  the horizon, ticks on the horizon, sunrise and sunset marks, a dashed night path, and an
  arrow at the border of the screen when the sun is outside the view.
- A vertical line through the crosshair shows the direction, and the readout gives the time
  when the sun is in that direction, with its height. When the phone lies flat, the line
  is hidden. If the crosshair is in the band that the sun reaches in a year, the readout
  also lists the dates when the sun is in the crosshair.
- Time and date: the "Time" panel moves the view to another date (±6 months) and time.
  The paths of the solstices show as faint guide lines.
- Sun detection: in camera mode, a box marks the sun in the image ("Sun ✓" when the overlay
  agrees within 1°, else "Sun · tap to align"). A tap on the box aligns the heading.
- "Align" explains each option ("The sun is below the horizon", "Needs your location"), and
  the alignment with the sun or the moon switches the camera on by itself.
- The vertical line goes from the horizon to the crossing with the path.
- The camera preview now follows a rotation of the screen without a restart (the target
  rotation of the use cases was not set before).
- Align with the moon (tap it), or with a landmark: pick it on an OpenStreetMap map, point
  the crosshair at it, and press "Aligned". The moon shows on the overlay.
- The crosshair no longer shows "The sun does not pass here today". Instead, the app marks
  the point of today's path that is nearest to the crosshair and shows its time large, with
  the angle to the crosshair.
- The status shows the magnetic declination, the accuracy of the compass, and the times of
  sunrise and sunset.
