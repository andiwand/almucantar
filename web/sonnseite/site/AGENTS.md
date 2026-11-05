# AGENTS.md — the website

The website on GitHub Pages: Vite, TypeScript, and the core as the npm package `@almucantar/core`
(`core/wasm`), the same as the PWA. Build `core/wasm` first. The workflow `web.yml` builds the
website and puts the PWA under `/app/`.

## Layout

| Path             | What                                                                                                                                                                                                          |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `index.html`     | The page: the hero, the chapters (`data-chapter`), the playground, and the rest.                                                                                                                              |
| `src/main.ts`    | The chapters: each chapter sets the time and the parts of the sky from the scroll progress. The input and the readouts.                                                                                       |
| `src/sky.ts`     | The drawing of the sky: a panorama (azimuth to x, altitude to y, from `@almucantar/web-shared/panorama`), so a whole summer path fits on the screen. It draws only; the core calculates the sun and the moon. |
| `src/style.css`  | The layout.                                                                                                                                                                                                   |
| `public/images/` | The logo and the image for link previews.                                                                                                                                                                     |

## Build

```sh
npm ci                          # in web/, the workspace
npm run build -w sonnseite/site
npm run format:check -w sonnseite/site
```

`npm run dev -w sonnseite/site` serves the website.

## Notes

- The page works without JavaScript: the text and the links are in the HTML. The sky is decoration.
- A chapter is a section that is taller than the screen, with a sticky card. The progress of a
  chapter goes from 0 to 1 while its card is on the screen. To add a chapter, add a section with
  `data-chapter` and a function in `chapters` in `src/main.ts`.
- The default place is Vienna, with its time zone. "Use my location" changes both.
