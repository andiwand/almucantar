# Changelog

The changes in the Android app that a user notices. Each release has the tag
`android-vX.Y.Z`. The rules are the same as in `core/CHANGELOG.md`.

## Unreleased

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
