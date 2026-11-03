# AGENTS.md — the website of starradar

The website of starradar on GitHub Pages (`/starradar/`): a scroll story over a night sky that the
core calculates, in the same form as the website of sunradar (`../../sunradar/site/`). Vite,
TypeScript, `@almucantar/core`, and `@almucantar/web-shared`. It is a package of the npm workspace
`web/` (see [`../../AGENTS.md`](../../AGENTS.md)).

## Layout

| Path                         | What                                                                                                                                                                                                                                                                                                                      |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `index.html`                 | The page: the hero, the chapters (`data-chapter`), the playground, and the rest.                                                                                                                                                                                                                                          |
| `src/main.ts`                | The chapters: each chapter gives the place, the time, the center of the view, and the parts of the sky from the scroll progress (`chapters`). The values on the screen follow the chapter smoothly; a jump of more than three hours or a new place fades. The readouts of the cards.                                      |
| `src/sky.ts`                 | The drawing: the panorama of `@almucantar/web-shared/panorama` with the layout of this site, the stars, the constellation lines and names, the planets and their tracks, the star tracks, the moon, the Milky Way, the ground or the terrain, and the ghost lines of a wrong compass. It draws only; the core calculates. |
| `src/horizon-innsbruck.json` | The terrain horizon of Innsbruck for the chapter "Behind the mountain". `scripts/site/build_horizon.py` makes it with the Python binding and Copernicus DEM GLO-90, so the website downloads no elevation tiles.                                                                                                          |
| `src/style.css`              | The layout (from the website of sunradar, with the colors of the night).                                                                                                                                                                                                                                                  |
| `public/images/`             | The logo.                                                                                                                                                                                                                                                                                                                 |

## Build

```sh
npm ci                          # in web/, the workspace
npm run build -w starradar/site
npm run format:check -w starradar/site
```

`npm run dev -w starradar/site` serves the website. Build `core/wasm` first.

## Notes

- The page works without JavaScript: the text and the links are in the HTML. The sky is decoration.
- A chapter is a section that is taller than the screen, with a sticky card. To add a chapter, add
  a section with `data-chapter` and a function in `chapters` in `src/main.ts`.
- The default place is Vienna. The chapter "Behind the mountain" is always in Innsbruck, and it
  picks the bright star or planet that the mountains hide the longest tonight.
- The chapter "Planets" keeps the sidereal time and forces a dark sky, because a time lapse of
  weeks at a fixed sidereal time moves into the day.
- The panorama breaks a line above 80° of altitude, because the azimuth changes fast near the
  zenith.
