# Changelog

The changes in the Android app that a user notices. Each release has the tag
`android-vX.Y.Z`. The rules are the same as in `core/CHANGELOG.md`.

## Unreleased

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
- The crosshair no longer shows "The sun does not pass here today". Instead, the app marks
  the point of today's path that is nearest to the crosshair and shows its time large, with
  the angle to the crosshair.
- The status shows the magnetic declination, the accuracy of the compass, and the times of
  sunrise and sunset.
