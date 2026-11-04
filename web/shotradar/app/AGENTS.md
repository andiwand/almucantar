# AGENTS.md — the PWA of shotradar

The web app shotradar, the photo planner (PLAN.md 14.1): the moon, the Milky Way, the sun, or a
planet behind a landmark. Vite, TypeScript, the core `@almucantar/core`, and the shared code
`@almucantar/web-shared`. It is a package of the npm workspace `web/` (see
[`../../AGENTS.md`](../../AGENTS.md)). GitHub Pages serves it under `/shotradar/app/`.

## The workflow

1. **Subject**: the moon, the Milky Way (its center), the sun, Venus, Jupiter, Saturn, or Mars.
2. **Landmark**: a tap on the map. The landmark snaps to the highest point within 14 pixels (at
   most 300 m), because a tap is a few pixels off a summit. The height of the ground comes from the
   Terrarium tile of zoom 13; the user adds the height of a building.
3. **Plan**, in two modes:
   - "I stand here": the spot is a tap on the map or the location of the device. `sight()` gives the
     direction of the landmark, `findAlignments()` the dates and times of the next 12 months, and
     `lineOfSight()` checks the view. A match shows the phase of the moon, the light of the sky, and
     a button to the spots of its time. Without a match, the app says why, if the direction is out
     of the declinations of the subject.
   - "Where must I stand?": a date and a time (with buttons for the rise and the set of the
     subject), `alignmentLine()` up to 30 km, its spots, and `lineOfSight()` for each spot. A spot
     with a clear view is a full dot, a blocked one is a ring. "Stand here" makes a spot the spot of
     "I stand here".

## Layout

| Path | What |
|---|---|
| `src/main.ts` | The app: the state (in localStorage, `shotradar-state-v1`), the map (the shaded relief, the base map of `@almucantar/web-shared/basemap` with a light style, the gestures), the steps, the two modes, and the search of the places. |
| `src/relief.ts` | Pure functions: the Terrarium height of a pixel and between pixels, the latitude of a tile row, and the shaded relief of a tile (the color of the height, times the light from the north-west). |
| `src/style.css` | The look: warm paper, the dusk colors of the logo, monospace numbers, and the sheet (at the bottom on a phone, a column at the left on a desktop). |
| `public/` | The manifest, the icon (`assets/shotradar-logo.svg`), and the service worker (cache prefix `shotradar:`). |
| `index.html` | The page: the map, the top bar with the search, and the sheet with the steps. |
| `test/` | The relief functions, and the offline cache of the service worker. |

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
  the line of sight of "Where must I stand?" use the tiles within 30 km of the landmark; the line of
  sight of "I stand here" uses the tiles around the middle of the way, up to 40 km.
- Terrarium is a surface model in many places (buildings and forests), so a spot in a forest can be
  "blocked" by the trees of the model.
- All dates and times are in the time zone of the landmark (the nearest place of the base map).
