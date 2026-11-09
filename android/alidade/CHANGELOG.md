# Changelog

The changes in the Android app Alidade that a user notices. Each release has the tag
`alidade-android-vX.Y.Z`. The rules are the same as in `core/CHANGELOG.md`.

## Unreleased

- The live view no longer jumps once per second while the compass calibrates itself after the start.
  The heading now glides to each new estimate in less than a second. The view still follows a
  turn of the phone at once.

## 0.3.0 - 2026-11-07

- "Back" from a shot shows the dates, the moments, or the shots of a place at once, at the same
  scroll position. The app keeps the results of the searches and the small photos in memory.
- Keep a shot or a place with its star. The start page shows your shots and your places, and the
  map of the spot shows your places as chips.
- "Live view" shows the shot in first person, as Sonnseite and Sternstunde do: the terrain of the
  spot, the path of the subject with the hours, the landmark, and an arrow to the subject, on a
  drawn sky. The chip "Camera" puts it on the camera image.
- "Share" sends a card of the shot: the photo with the frame of the lens, and the time, the place,
  the elevation, and the direction of the subject.
- The buttons, the chips, and the back arrow are the ones of Android, and the logo in the bar is
  larger.
- The map starts at the location of the phone, else at the main city of its time zone or of the
  country of its language. It does not start at Innsbruck.
- The choices of the days are two rows that do not scroll.

## 0.2.0 - 2026-11-06

- The start page has a link to the privacy policy.
- The name of your location comes from the offline map of the app ("near" the nearest town), not
  from the geocoder of the phone. So the app sends your location to no service.

## 0.1.0 - 2026-11-06

- The first version, the photo planner of the PWA as an app. Every way ends in the shot: the photo
  from the spot in first person, with the real terrain, the landmark, the subject and its path, the
  stars, a time slider, and the frame of a lens, in the look of the website.
- "From where I am": the place on the map or the location of the phone, now or on some days. The
  app shows the moon, the Milky Way, the sun, and the planets while they are over the real horizon
  of the place, and the peaks of the horizon that they pass.
- "I have a landmark": the subject, the landmark on the map, then "I know my spot" (the dates of
  the next 12 months as a contact sheet of photos, with a check of the view) or "Find me a spot" (a
  moment, then the spots on the map; a full dot has a clear view).
- The eclipses: the next solar and lunar eclipses with what your place sees, the path of the
  shadow on the map of the Earth, the phases over the real terrain, and the eclipse in the shot.
- In the shot, drag to look around, pinch to zoom, and double tap to aim again. "Navigate" opens
  the spot in the map app of the phone.
