# AGENTS.md — the PWA

The web app: Vite, TypeScript, and the core as the npm package `@sunradar/core`
(`core/wasm`). `package.json` takes the package from the WASM build output (`file:`), so
build `core/wasm` first. GitHub Pages serves the app under `/app/` of the website.

## Layout

| Path | What |
|---|---|
| `src/main.ts` | The app: state, the frame (projection and drawing), sensors, location, camera, input. |
| `src/terrain.ts` | The terrain horizon: the download of the Terrarium tiles (AWS Terrain Tiles), their cache (Cache Storage and an index in `localStorage`, with eviction above 100 MB and a check of the ETag after 180 days), and the profile from the core. Tested in `test/`. |
| `src/orientation.ts` | The W3C device orientation (alpha, beta, gamma) and the desktop view as quaternions (device to ENU), and the screen rotation. Tested in `test/`. |
| `src/style.css` | The layout. |
| `public/` | The manifest, the icon (`assets/logo.svg`), and the service worker. |
| `index.html` | The page. |

## Build and test

```sh
npm ci
npm test
npm run build
npm run format:check
```

`npm run dev` serves the app. The camera and the sensors need HTTPS (or `localhost`).

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
- `npm run dev` does not serve the WASM file of the core. Use `npm run build` and
  `npx vite preview` to try the app in a browser.
- Formatting: prettier (`npm run format`).
