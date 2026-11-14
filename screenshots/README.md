# Store screenshots

The pipeline makes the screenshots for Google Play and the App Store. It is the same pipeline as
in OpenDocument.droid and OpenDocument.ios. It has the screenshots of Sonnseite (Android and iOS),
Sternstunde (Android), and Alidade (Android). The scripts and the lanes take the app as an option
(`--app` and `app:`), and Sonnseite is the default.

1. A UI test starts the real app in a demo mode for each screen and saves a raw PNG.
2. [storeframe](https://github.com/andiwand/storeframe) draws the store image: a gradient background, a
   drawn device body, a headline of two lines, and the dashed path of the sun across the gallery.
3. `scripts/screenshots/store.py` checks the complete set and stages it for fastlane.
4. fastlane runs all steps (`bundle exec fastlane screenshots` in `android/` or `ios/`). The
   workflow `screenshots.yml` runs them on CI, with one runner for each device.

## Setup

```sh
bundle install                                              # fastlane, from the Gemfile at the root
.venv/bin/pip install -r scripts/requirements-dev.txt       # storeframe and Pillow
```

## Commands

```sh
cd android && bundle exec fastlane screenshots              # phone; one emulator with API 35 or later
cd android && SONNSEITE_SCREENSHOT_DEVICE=tablet bundle exec fastlane screenshots
cd android && bundle exec fastlane screenshots app:sternstunde   # the same for Sternstunde or Alidade
cd ios && bundle exec fastlane screenshots                  # iPhone and iPad
cd ios && SONNSEITE_SCREENSHOT_DEVICE=ipad bundle exec fastlane screenshots
.venv/bin/python -m storeframe frame screenshots/frames/sonnseite.json --platform android \
  --captured android/fastlane/screenshots --out android/fastlane/framed --locale en-US   # frame again only
```

The raw captures of Sonnseite go to `<platform>/fastlane/screenshots/en-US/`, and the framed
images go to `<platform>/fastlane/framed/en-US/`. Each other app has a folder below them, for
example `android/fastlane/framed/sternstunde/en-US/`. Git ignores all these folders.

## The demo mode

The demo mode exists only in debug builds. The UI test starts it with the name of the screen:

- Android: the intent extra `sonnseite.screenshot` of `MainActivity`. A release build has a no-op
  version of the code in `src/release/`.
- iOS: the launch argument `-SonnseiteScreenshot <screen>`, read with `UserDefaults` inside
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
  `sonnseite-screenshot-ready`, on Android an idle main thread after the first frame.

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

The drawn sky replaces the camera image. It is the sky of the website (`web/sonnseite/site/src/sky.ts`),
drawn with the projection of the app:

- The sky is a vertical gradient from the top of the screen to the horizon. The colors come from
  the altitude of the sun, with the stops of `SKY` in `web/sonnseite/site/src/sky.ts`.
- A glow surrounds the sun when it is higher than -12°.
- Stars show when the sun is lower than -4°, from the same generator (`random(7)`, 420 stars).
- The skyline: the buildings of `BUILDINGS` in `web/sonnseite/site/src/sky.ts` (`random(42)`). Each building
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
| `screenshots/frames/<app>.json` | The design of an app: the backgrounds and the headline of each screen, and `arc` (the dashed path of the sun of Sonnseite). The screens of the design are the screens of the app. |
| `screenshots/frames/Nunito[wght].ttf`, `OFL.txt` | The font of the headlines (SIL Open Font License). storeframe and `scripts/screenshots/listing.py` use it. |
| `scripts/screenshots/store.py` | The locales, the screens, the sizes; checks and stages a set. |
| `scripts/requirements-dev.txt` | storeframe, which draws the store images, and its version. |
| `Gemfile` | fastlane. |
| `android/fastlane/`, `ios/fastlane/` | The lanes `screenshots` and `upload_listing` (Android: with `app:`). |
| `.github/workflows/screenshots.yml` | Captures on CI for the input `app`, and uploads if the store credentials exist. iOS: Sonnseite only. |

## Sternstunde

The demo mode of Sternstunde is the intent extra `sternstunde.screenshot` (`ScreenshotMode` in
`android/sternstunde/src/debug/`). It uses the place Vienna, no sensors, no camera, no terrain,
and no network. The settings are fixed: the constellation lines on, the terrain off.

| Screen | Local time (Europe/Vienna) | View azimuth, pitch | State |
|---|---|---|---|
| `01-night` | 2026-12-18 21:30 | 140°, 28° | Orion, Taurus, and Sirius in the southeast. |
| `02-tap` | 2026-12-18 21:30 | 140°, 28° | The same sky, with Betelgeuse selected and its info card. |
| `03-milkyway` | 2026-08-12 22:30 | 190°, 18° | The center of the Milky Way selected, at new moon. |
| `04-red` | 2026-10-10 21:00 | 0°, 35° | The north sky with the red light. |

## Alidade

The demo mode of Alidade is the intent extra `alidade.screenshot` (`ScreenshotMode` in
`android/alidade/src/debug/`). It is the example of the website: the full moon behind the
Großglockner, seen from a ridge above Kals (`web/alidade/site/src/shot.json`). The time of the app
is 2026-11-06 12:00 (Europe/Vienna). The scene sets the choices and the stack of screens, and the
model does not read or write the stored choices. The terrain comes from the network, so each screen
waits 40 s before the capture.

| Screen | State |
|---|---|
| `01-home` | The start page. |
| `02-dates` | "I know my spot": the contact sheet of the dates of the next 12 months. |
| `03-shot` | The shot of 24 November 2026, 17:08: the center of the moon on the summit. |
| `04-eclipses` | The next eclipses, seen from the spot. |
