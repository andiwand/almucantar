# AGENTS.md — the PWA of sunradar

The web app sunradar: Vite, TypeScript, the core as the npm package `@almucantar/core`
(`core/wasm`), and the shared code `@almucantar/web-shared` (`../shared`). It is a package of the
npm workspace `web/` (see [`../AGENTS.md`](../../AGENTS.md)). GitHub Pages serves the app under
`/app/` of the website.

## Layout

| Path | What |
|---|---|
| `src/main.ts` | The app: state, the frame (projection and drawing), sensors, location, camera, input. |
| `src/main.ts` (end) | The place panel comes from `@almucantar/web-shared/placePanel`; the app gives it its place and calls `refresh()` after a change of the place. |
| `src/style.css` | The layout. |
| `public/` | The manifest, the icon (`assets/logo.svg`), and the service worker. |
| `index.html` | The page. |

## Build and test

Run these commands in `web/` (the workspace), not here:

```sh
npm ci
npm test -w sunradar/app
npm run build -w sunradar/app
npm run format:check -w sunradar/app
```

`npm run dev -w sunradar/app` serves the app. The camera and the sensors need HTTPS (or `localhost`).

## Notes

- The orientation comes from `deviceorientationabsolute` (Chrome on Android) or from
  `deviceorientation` with `webkitCompassHeading` (iOS, after a permission prompt). Both are
  magnetic, so the heading offset starts at the declination, and an alignment with the sun
  corrects it through the heading filter of the core.
- The browser gives no camera calibration, so the field of view is a slider.
- Without sensors (a computer), the user drags to look around.
- The core runs on the main thread. A frame needs less than a millisecond; only the search
  for the dates of the crosshair is slower, and it runs debounced. The values are plain
  objects, so a Web Worker is possible later.
- The terrain uses the Terrarium tiles, not the Copernicus tiles of the native apps, because
  the Copernicus bucket sends no CORS headers. The tile cache has its own Cache Storage name, so
  the service worker does not delete it when a new build activates.
- The place is the location of the device, a place that the user dropped on the map (the Place
  panel), or the last place (in `localStorage`). A dropped place stays until the user presses
  "Use my location". All days and times use the time zone of the place (`zone.ts` of the shared package); a dropped
  place gets the time zone of the nearest place of the base map.
- `vite.config.ts` allows the repository root, because the app reads the WASM build of the core,
  `assets/basemap/`, and `web/shared/`.
- Formatting: prettier (`npm run format`).
