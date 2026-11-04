# AGENTS.md — the PWA of shotradar

The web app shotradar, the photo planner (PLAN.md 14.1): the moon, the Milky Way, the sun, or a
planet behind a landmark. Vite, TypeScript, the core `@almucantar/core`, and the shared code
`@almucantar/web-shared`. It is a package of the npm workspace `web/` (see
[`../../AGENTS.md`](../../AGENTS.md)). GitHub Pages serves it under `/shotradar/app/`.

## The workflow

Every way through the app ends in **the shot**: the first-person photo from the spot
(`@almucantar/web-shared/photo`, the same drawing as the website), with the terrain of the spot in
layers, the landmark, the subject and its path with the hours, the stars and the Milky Way at night,
the frame of a lens (24 to 600 mm), and a time slider.

- **A: I am here, or I will be.** See "From where I am" below; it is the first way on the start
  page.
- **B: I have a landmark.** The subject (seven boxes: the moon, the Milky Way, the sun, and four
  planets), then the landmark on the map (a tap snaps to the highest point within 14 pixels, at
  most 300 m), then one of two ways:
  - **I know my spot**: the spot on the map or the location of the device. `findAlignments()` gives
    the dates of the next 12 months as a contact sheet of photos; `lineOfSight()` checks the view.
    Without a match, the app compares the declination of the landmark with the range of the subject
    in that year (`noMatchReason`).
  - **Find me a spot**: a moment (the next times when the subject is low over the horizon, near the
    full moon or in a dark sky, or a time of the user), then the line of `alignmentLine()` on the
    map, with the spots (a full dot has a clear view). A spot opens its shot.
- **From where I am** (way A). The place, then the days (now: 14 days; or a first day and a number of
  days). The app calculates the horizon of the place and gives two contact sheets:
  - **The sky from here**: each subject while it is over the real terrain (`terrainEvents()` with
    the skyline) and the sky is dark enough for it (`darkWindows()`), without a landmark. With all
    subjects, the sheet has the first night of each subject and "+ N more nights".
  - **Behind a peak**: the prominent peaks of the skyline (`findPeaks`) and the subjects that pass
    them (`findAlignments()`).

- **C: An eclipse.** The next solar eclipses (10 years) and lunar eclipses (5 years), with what the
  place sees of each. An eclipse opens the map of the Earth: the central line and the band of the
  umbra of a solar eclipse (`eclipseCentralLine()`), or the line inside which the moon is up at the
  peak of a lunar eclipse; a tap on the map changes the place. The card gives the phases at the
  place and whether they are over the real terrain. The shot shows the sun and the moon at their
  radii (from the contacts), the covered part of the sun (`discCoverage`), the corona and the dark
  sky at totality, and the moon red in the umbra (`lunarUmbra`).

In the shot, a drag turns the view, a pinch or the wheel zooms it, and a double tap aims it again.
The lens buttons are five equal columns on one line, and the time is next to the slider.

A moon below 10 % lit is not a shot, and each subject has a limit of the light of the sky (the
Milky Way at −15°, the planets at −4° to −8°).

## Layout

| Path | What |
|---|---|
| `src/main.ts` | The screens and the navigation (a stack of screens with the history of the browser, and the strip of film of the steps), the contact sheets, and the shot. |
| `src/search.ts` | The searches without the screens: the subjects, `datesFor`, `momentsFor`, `peakTargets`, `shotsFromPlace`, `skyFromPlace`, `noMatchReason`, and the eclipses (`upcomingEclipses`, `localEclipse`, `lunarUmbra`). |
| `src/horizon.ts` | The horizon of a spot: four bands of distance (0.25 to 2.5 km, to 9 km, to 30 km, to 90 km) of `horizonProfile()` with their own tiles, the skyline, and the peaks of a skyline. |
| `src/tiles.ts` | The Terrarium tiles: the cache, the decoded tiles, the height of the ground, the summit near a tap, and the grids of the core. |
| `src/mapview.ts` | The map: the shaded relief, the base map with a light style, the gestures, and the marks (the landmark, the spot, lines, and dots). |
| `src/relief.ts` | Pure functions: the Terrarium height, the latitude of a tile row, and the shaded relief of a tile. |
| `src/style.css` | The look of the website: paper with a grain, serif titles, monospace numbers, cards with the corners of a viewfinder, and the strip of film. |
| `public/` | The manifest, the icon (`assets/shotradar-logo.svg`), and the service worker (cache prefix `shotradar:`). |
| `index.html` | The bar, the page of the screens, and the map screen. |
| `test/` | The relief, the horizon and the peaks, the umbra of a lunar eclipse, and the offline cache of the service worker. |

## Build and test

Run these commands in `web/` (the workspace):

```sh
npm ci
npm test -w shotradar/app
npm run build -w shotradar/app
npm run format:check -w shotradar/app
```

`npm run dev -w shotradar/app` serves the app. Build `core/wasm` first.

## Notes

- The map tiles and the grids of the core are Terrarium tiles in the cache of
  `@almucantar/web-shared/terrain` (`shotradar-terrain`), so a second visit works offline. The map
  draws the tiles of the nearest whole zoom (2 to 13) and scales them; while a tile loads, a part of
  a tile of a lower zoom stands in.
- The grids of the core have zoom 11 (about 50 m per pixel in the Alps). The line, the spots, and
  the line of sight of "Find me a spot" use the tiles within 30 km of the landmark; the line of
  sight of "I know my spot" uses the tiles around the middle of the way, up to 40 km. The line of
  sight ignores the ground just before the landmark (`targetMarginM`, 8 % of the distance, 300 m
  to 1.5 km), because a DEM smooths a summit.
- The horizon of a spot takes about a second in WebAssembly, so the app keeps it for each spot and
  shows its progress. The photos of a contact sheet are drawn one after the other.
- Terrarium is a surface model in many places (buildings and forests), so a spot in a forest can be
  "blocked" by the trees of the model.
- All dates and times of a shot are in the time zone of its spot (the nearest place of the base
  map).
