# AGENTS.md — the PWA of sternstunde

The web app sternstunde (PLAN.md 14.2): the stars, the constellations, the planets, the moon, and
the Milky Way in the direction of the phone, over the terrain. Vite, TypeScript, the core
`@almucantar/core`, and the shared code `@almucantar/web-shared`. It is a package of the npm
workspace `web/` (see [`../../AGENTS.md`](../../AGENTS.md)). GitHub Pages serves it under
`/sternstunde/app/`.

## Layout

| Path | What |
|---|---|
| `src/main.ts` | The app: the star data, the sky at the selected time (`computeSky`, again once per second), the frame (projection and drawing), the tap and the info card (with the rise and set over the terrain), Find, the alignment with a bright object, sensors, location, camera, and the settings. |
| `src/style.css` | The layout, the Find panel, the info card, and the red light (`#night`). |
| `public/` | The manifest, the icon (`assets/sternstunde-logo.svg`), and the service worker (cache prefix `sternstunde:`). |
| `index.html` | The page, with the markup of the shared place panel. |
| `test/` | The start of the app with a mock of the core, and the offline cache of the service worker. |

The star data is `assets/stars/stars.json` (see `assets/stars/README.md`). `@almucantar/web-shared/stars`
decodes it, and gives the size, the color, and the faintest magnitude of the sky.

## Build and test

Run these commands in `web/` (the workspace):

```sh
npm ci
npm test -w sternstunde/app
npm run build -w sternstunde/app
npm run format:check -w sternstunde/app
```

`npm run dev -w sternstunde/app` serves the app. Build `core/wasm` first.

## Notes

- A frame calls `projectPoints()` once for all stars (about 0.6 ms) and draws the stars down to
  `limitingMagnitude()` of the altitude of the sun. `starPositions()` runs once per second (about
  1 ms), because the stars move 0.004° per second.
- A constellation segment needs both stars on the screen (a valid projection). This is simpler and
  faster than clipping each line, and the eye does not miss a cut segment at the border.
- An object below the horizon or behind the terrain is dim, not hidden, so the user can find it.
- The settings are in localStorage with the prefix `sternstunde-`, and the terrain cache has its own
  names (`sternstunde-terrain`), because the apps of the site share localStorage.
- The alignment takes only objects to magnitude 2.5, because the user must see them in the sky.
