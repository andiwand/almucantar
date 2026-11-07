# AGENTS.md — the web

One npm workspace for all web apps of the repository and their websites (PLAN.md 14). The packages:

| Package | What |
|---|---|
| `shared/` (`@almucantar/web-shared`) | The code that the web apps share. See below. |
| `home/` | The page of the family at the root of GitHub Pages: links to the apps. |
| `sonnseite/app/` | The PWA of sonnseite. See [`sonnseite/app/AGENTS.md`](sonnseite/app/AGENTS.md). |
| `sonnseite/site/` | The website of sonnseite. See [`sonnseite/site/AGENTS.md`](sonnseite/site/AGENTS.md). |
| `sternstunde/app/` | The PWA of sternstunde. See [`sternstunde/app/AGENTS.md`](sternstunde/app/AGENTS.md). |
| `sternstunde/site/` | The website of sternstunde. See [`sternstunde/site/AGENTS.md`](sternstunde/site/AGENTS.md). |
| `alidade/app/` | The PWA of alidade, the photo planner. See [`alidade/app/AGENTS.md`](alidade/app/AGENTS.md). |
| `alidade/site/` | The website of alidade. See [`alidade/site/AGENTS.md`](alidade/site/AGENTS.md). |

Each app has a folder with `app/` (the PWA) and `site/` (the website). GitHub Pages serves this
tree (PLAN.md 14.2): `/` is `home/`, `/<app>/` is the website of the app, and `/<app>/app/` is its
PWA. The step "Assemble the site" of `web.yml` makes the tree from the `dist/` folders.

All packages take the core `@almucantar/core` from the WASM build output (`file:` to
`core/build/emscripten-wasm-release/wasm/dist`), so build `core/wasm` first. The workflow `web.yml`
builds the workspace and deploys the tree of the sites to GitHub Pages.

## Build and test

Run these commands in `web/`:

```sh
npm ci
npm test
npm run build
npm run format:check
```

`-w <package>` runs a command in one package, for example `npm run dev -w sonnseite/app`.

## The shared package

`@almucantar/web-shared` has no build step. Its `package.json` exports the TypeScript sources
(`@almucantar/web-shared/terrain` is `shared/src/terrain.ts`), and Vite and Vitest of the apps
compile them. `npm run build -w shared` only type-checks it.

| Path | What |
|---|---|
| `shared/src/place.ts` | The place (`StoredPlace`): the last place in `localStorage` (each app has its own key), the most probable place without a location (`probablePlace`: the main city of the time zone, else of the country of the locale, from `assets/tzdata/zone.tab`, which each app imports with `?raw`), and the place from the location of the device. |
| `shared/src/placePanel.ts`, `placePanel.css` | The place panel: the offline map, the search, "Use my location", and "Keep offline". The markup is in the `index.html` of each app, with the ids that the module lists. The app gives its place and its terrain through `PlacePanelDeps` and calls `refresh()` after a change of the place. |
| `shared/src/terrain.ts` | The terrain horizon: the download of the Terrarium tiles (AWS Terrain Tiles), their cache (Cache Storage and an index in `localStorage`, with eviction above 100 MB and a check of the ETag after 180 days), the kept places ("Keep offline": their tiles are never evicted and do not count toward the limit), and the profile from the core. The profile uses coarse tiles (zoom 8) for 100 km, and fine tiles (zoom 10) for 25 km only if the coarse profile reaches `THRESHOLD_DEG` (1°). "Keep offline" keeps the same tiles. The tiles come from an `ElevationSource` (Terrarium now). A source that is a surface model (buildings and forests in the heights) clears the near path: the profile ignores the terrain nearer than `NEAR_CLEARANCE_M` (2 km), so a city gives no false wall. |
| `shared/src/basemap.ts` | The offline base map (`assets/basemap/basemap.json`, Natural Earth): the decode, the search, the time zone of a dropped place, and the drawing in Web Mercator. `BaseMapStyle` gives the colors: `DARK_MAP` (the default, the place panel) or a light style over a relief (alidade). |
| `shared/src/zone.ts` | The times in the time zone of the place (Intl), not of the browser. |
| `shared/src/orientation.ts` | The W3C device orientation (alpha, beta, gamma) and the desktop view as quaternions (device to ENU), and the screen rotation. |
| `shared/src/moon.ts` | The lit part of the moon and its direction to the sun. |
| `shared/src/milkyway.ts` | A picture of the Milky Way as soft points in the galactic system (brighter to the center, the star clouds, the Great Rift), as J2000 directions for `starPositions()`. A picture for the eye, not a measurement. |
| `shared/src/panorama.ts` | The panorama of the websites (azimuth to x, altitude to y, with a layout for each site) and the colors of the sky for an altitude of the sun. |
| `shared/src/photo.ts` | The first-person photo of alidade: the sky in the light of the sun, the stars and the Milky Way, the sun or a planet, the moon in its phase, the terrain in layers, the label of a landmark, the frame of a lens, and the viewfinder; for an eclipse, the sun and the moon at their radii (`discCoverage`) and the moon in the umbra. The website and the PWA of alidade draw with it. |
| `shared/src/stars.ts` | The star data of sternstunde (`assets/stars/stars.json`) as typed columns, and how a star looks: the size from the magnitude, the color from B−V, and the faintest magnitude of a sky (`limitingMagnitude`). |
| `shared/test/` | The Vitest tests of the shared code. |

## Rules

- `shared/` never imports from an app. Data that only an app knows goes in as a parameter, as
  `PlacePanelDeps` does.
- Code goes to `shared/` when a second app needs it, or when it is the same in all apps (the
  place, the terrain), not because it might be useful later.
- A new web app is a new folder next to `sonnseite/` with `app/` and `site/`, each with an entry in
  `workspaces` of `package.json`, and a line in the step "Assemble the site" of `web.yml`.
- Formatting: prettier (`npm run format`).
