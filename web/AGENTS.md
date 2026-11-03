# AGENTS.md — the web

One npm workspace for all web apps of the repository and the website (PLAN.md 14). The packages:

| Package | What |
|---|---|
| `shared/` (`@almucantar/web-shared`) | The code that the web apps share. See below. |
| `sunradar/` | The PWA of sunradar. See [`sunradar/AGENTS.md`](sunradar/AGENTS.md). |
| `website/` | The website. See [`website/AGENTS.md`](website/AGENTS.md). |

All packages take the core `@almucantar/core` from the WASM build output (`file:` to
`core/build/emscripten-wasm-release/wasm/dist`), so build `core/wasm` first. The workflow `web.yml`
builds the workspace and puts the PWA under `/app/` of the website on GitHub Pages.

## Build and test

Run these commands in `web/`:

```sh
npm ci
npm test
npm run build
npm run format:check
```

`-w <package>` runs a command in one package, for example `npm run dev -w sunradar`.

## The shared package

`@almucantar/web-shared` has no build step. Its `package.json` exports the TypeScript sources
(`@almucantar/web-shared/terrain` is `shared/src/terrain.ts`), and Vite and Vitest of the apps
compile them. `npm run build -w shared` only type-checks it.

| Path | What |
|---|---|
| `shared/src/place.ts` | The place (`StoredPlace`): the last place in `localStorage` (each app has its own key), the default place (Vienna), and the place from the location of the device. |
| `shared/src/placePanel.ts`, `placePanel.css` | The place panel: the offline map, the search, "Use my location", and "Keep offline". The markup is in the `index.html` of each app, with the ids that the module lists. The app gives its place and its terrain through `PlacePanelDeps` and calls `refresh()` after a change of the place. |
| `shared/src/terrain.ts` | The terrain horizon: the download of the Terrarium tiles (AWS Terrain Tiles), their cache (Cache Storage and an index in `localStorage`, with eviction above 100 MB and a check of the ETag after 180 days), the kept places ("Keep offline": their tiles are never evicted and do not count toward the limit), and the profile from the core. The profile uses coarse tiles (zoom 8) for 100 km, and fine tiles (zoom 10) for 25 km only if the coarse profile reaches `THRESHOLD_DEG` (1°). "Keep offline" keeps the same tiles. The tiles come from an `ElevationSource` (Terrarium now). A source that is a surface model (buildings and forests in the heights) clears the near path: the profile ignores the terrain nearer than `NEAR_CLEARANCE_M` (2 km), so a city gives no false wall. |
| `shared/src/basemap.ts` | The offline base map (`assets/basemap/basemap.json`, Natural Earth): the decode, the search, the time zone of a dropped place, and the drawing in Web Mercator. |
| `shared/src/zone.ts` | The times in the time zone of the place (Intl), not of the browser. |
| `shared/src/orientation.ts` | The W3C device orientation (alpha, beta, gamma) and the desktop view as quaternions (device to ENU), and the screen rotation. |
| `shared/src/moon.ts` | The lit part of the moon and its direction to the sun. |
| `shared/test/` | The Vitest tests of the shared code. |

## Rules

- `shared/` never imports from an app. Data that only an app knows goes in as a parameter, as
  `PlacePanelDeps` does.
- Code goes to `shared/` when a second app needs it, or when it is the same in all apps (the
  place, the terrain), not because it might be useful later.
- A new web app is a new package next to `sunradar/`, with `@almucantar/web-shared` as a
  dependency, and an entry in `workspaces` of `package.json`.
- Formatting: prettier (`npm run format`).
