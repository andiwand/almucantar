# AGENTS.md — the PWA

The web app: Vite, TypeScript, and the core as the npm package `@sunradar/core`
(`core/wasm`). `package.json` takes the package from the WASM build output (`file:`), so
build `core/wasm` first. GitHub Pages serves the app under `/app/` of the website.

## Layout

| Path | What |
|---|---|
| `src/main.ts` | The app: state, the frame (projection and drawing), sensors, location, camera, input. |
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
- Formatting: prettier (`npm run format`).
