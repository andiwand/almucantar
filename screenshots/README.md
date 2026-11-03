# Store screenshots

The pipeline makes the screenshots for Google Play and the App Store. It is the same pipeline as
in OpenDocument.droid and OpenDocument.ios:

1. A UI test starts the real app in a demo mode for each screen and saves a raw PNG.
2. `scripts/screenshots/frame.py` draws the store image with Pillow: a gradient background, a
   drawn device body, a headline of two lines, and the dashed path of the sun across the gallery.
3. `scripts/screenshots/store.py` checks the complete set and stages it for fastlane.
4. fastlane runs all steps (`bundle exec fastlane screenshots` in `android/` or `ios/`). The
   workflow `screenshots.yml` runs them on CI, with one runner for each device.

## Setup

```sh
bundle install                                              # fastlane, from the Gemfile at the root
.venv/bin/pip install -r scripts/requirements-dev.txt       # Pillow
```

## Commands

```sh
cd android && bundle exec fastlane screenshots              # phone; one emulator with API 35 or later
cd android && SUNRADAR_SCREENSHOT_DEVICE=tablet bundle exec fastlane screenshots
cd ios && bundle exec fastlane screenshots                  # iPhone and iPad
cd ios && SUNRADAR_SCREENSHOT_DEVICE=ipad bundle exec fastlane screenshots
.venv/bin/python scripts/screenshots/frame.py --platform android   # frame again only
```

The raw captures go to `<app>/fastlane/screenshots/en-US/`, and the framed images go to
`<app>/fastlane/framed/en-US/`. Git ignores both directories.

## The demo mode

The demo mode exists only in debug builds. The UI test starts it with the name of the screen:

- Android: the intent extra `sunradar.screenshot` of `MainActivity`. A release build has a no-op
  version of the code in `src/release/`.
- iOS: the launch argument `-SunradarScreenshot <screen>`, read with `UserDefaults` inside
  `#if DEBUG`.

In the demo mode, the app:

- uses the place Vienna (48.2082° N, 16.3738° E) with the time zone `Europe/Vienna` and the place
  name "Vienna", and does not ask for a permission;
- uses no sensors: the view is the device held upright with the back camera to the azimuth and
  pitched up by the pitch of the table below (the same view as the drag view without sensors);
- uses the field of view 65° across the shorter side of the screen;
- shows a drawn sky in place of the camera image (see below);
- shows the heading as aligned, ±0.5°, except where the table says otherwise;
- shows the time of the table, as if the user had chosen it in the time panel;
- gives a signal when the screen is ready: on iOS the accessibility identifier
  `sunradar-screenshot-ready`, on Android an idle main thread after the first frame.

| Screen | Local time (Europe/Vienna) | View azimuth, pitch | State |
|---|---|---|---|
| `01-path` | 2026-08-20 19:00 | 255°, 20° | The sun path with the hours, the crosshair with the time when the sun is in its direction, and the dates. |
| `02-date` | 2026-12-21 12:00 | 180°, 30° | The time panel is open on this date. The dashed solstice paths show. |
| `03-align` | 2026-05-12 10:00 | 125°, 35° | The align menu is open (sun, moon, landmark). The heading is ±12°, not aligned. |
| `04-moon` | 2026-11-19 21:00 | 195°, 25° | Night. The moon (73 % lit) with its phase, 20° above the sun path of the day. |

The sun and the moon come from the core, so the values of the table give the same sky on both
platforms: on 2026-08-20 at 19:00 the sun is at 278.6° and 8.9° high, on 2026-11-19 at 21:00 the
moon is at 207.5° and 37.8° high. The moon of a night must not be on the sun path of its day, or it
covers the hour labels and the crosshair.

### The drawn sky

The drawn sky replaces the camera image. It is the sky of the website (`web/website/src/sky.ts`),
drawn with the projection of the app:

- The sky is a vertical gradient from the top of the screen to the horizon. The colors come from
  the altitude of the sun, with the stops of `SKY` in `web/website/src/sky.ts`.
- A glow surrounds the sun when it is higher than -12°.
- Stars show when the sun is lower than -4°, from the same generator (`random(7)`, 420 stars).
- The skyline: the buildings of `BUILDINGS` in `web/website/src/sky.ts` (`random(42)`). Each building
  is a polygon in sky directions, projected with the core. Below the horizon, the ground has the
  color of the skyline. At night the buildings have lit windows.

The two apps port the generator exactly, so the skyline is the same on all platforms.

## Devices and sizes

| Platform | Device | Capture | Store image |
|---|---|---|---|
| Android | `phone`: the newest Pixel Pro (XL) AVD | the screen of the AVD | 1440 × 2560 |
| Android | `tablet`: `pixel_tablet` | the screen of the AVD | 1600 × 2560 |
| iOS | `iphone`: the newest iPhone Pro Max simulator | 1320 × 2868 or 1290 × 2796 | the same size |
| iOS | `ipad`: the newest iPad Pro 13-inch simulator | 2064 × 2752 or 2048 × 2732 | the same size |

Google Play refuses a side that is more than two times the other side, so the Android store image
has its own size. The App Store wants the size of the device, so the iOS store image keeps it.

## Files

| Path | What |
|---|---|
| `screenshots/frames/frames.json` | The design: the backgrounds and the headline of each screen. |
| `screenshots/frames/Nunito[wght].ttf`, `OFL.txt` | The font of the headlines (SIL Open Font License). |
| `scripts/screenshots/store.py` | The locales, the screens, the sizes; checks and stages a set. |
| `scripts/screenshots/frame.py` | Draws the store images. |
| `Gemfile` | fastlane. |
| `android/fastlane/`, `ios/fastlane/` | The lanes `screenshots` and `upload_listing`. |
| `.github/workflows/screenshots.yml` | Captures on CI, and uploads if the store credentials exist. |
