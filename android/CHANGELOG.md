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
- The status shows the magnetic declination, the accuracy of the compass, and the times of
  sunrise and sunset.
