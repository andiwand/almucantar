# AGENTS.md — the website of shotradar

The website of shotradar on GitHub Pages (`/shotradar/`): a scroll story in the same form as the
websites of sunradar and starradar (`../../sunradar/site/`, `../../starradar/site/`), with its own
look. One print on a warm paper develops while you scroll: the full moon of 24 November 2026 rises
behind the summit of the Großglockner, seen from a ridge above Kals. The chapters follow the
workflow of the app (PLAN.md 14.1). Vite, TypeScript, `@almucantar/core`, and
`@almucantar/web-shared`. It is a package of the npm workspace `web/` (see
[`../../AGENTS.md`](../../AGENTS.md)).

## Layout

| Path | What |
|---|---|
| `index.html` | The page: the hero, the chapters (`data-chapter`: the subject, the landmark, the calendar, the spot, the check, and the shot), the playground, and the rest. |
| `src/main.ts` | The chapters: each chapter gives the time, the view, the picture (photo, map, or profile), and the overlays from the scroll progress (`chapters`). The searches of the core: the calendar (`findAlignments`), the subjects of the first chapter, the line of the spots (`alignmentLine` on the grid of `src/shot.json`), the clearance (`sight`, `lineOfSight`), and the darkness (`darkWindows`). The progress is smoothed inside a chapter; a jump of more than three hours fades like the next frame of a film. The rail of the chapters (`#rail`) and the readouts of the cards. |
| `src/scene.ts` | The drawing: the print with its mat (`printRect`, the same layout as the CSS), the photo (the sky with the belt of Venus, the stars and the Milky Way, the paths of the bodies, the moon in its phase, the terrain in layers, the label of the summit, the frame of the lens, and the marks of a viewfinder), the map (a hill shade, contour lines, the line of the spots), and the profile (the ground to the summit, the line of sight, and the light of the evening). It draws only; the core calculates. |
| `src/data.ts` | Decodes `src/shot.json`: the summit, the spot, the horizon layers, the map grid, and the profile. |
| `src/shot.json` | The terrain of the story. `scripts/site/build_shot.py` makes it with the Python binding and the Terrarium tiles (AWS Terrain Tiles), so the website downloads no elevation tiles. |
| `src/style.css` | The look of shotradar: paper with a grain, serif headings, monospace numbers, cards like prints with the corners of a viewfinder, the colors of the logo, and the rail as a strip of film. |
| `public/images/` | The logo. |

## Build

```sh
npm ci                          # in web/, the workspace
npm run build -w shotradar/site
npm run format:check -w shotradar/site
```

`npm run dev -w shotradar/site` serves the website. Build `core/wasm` first.

To make `src/shot.json` again (it needs Pillow and the Python build of the core):

```sh
PYTHONPATH=core/build/Debug/python python3 scripts/site/build_shot.py \
  web/shotradar/site/src/shot.json --cache /tmp/terrarium
npm run format -w shotradar/site
```

## Notes

- The page works without JavaScript: the text and the links are in the HTML. The print is
  decoration.
- A chapter is a section that is taller than the screen, with a sticky card. To add a chapter, add
  a section with `data-chapter` and a function in `chapters` in `src/main.ts`.
- The story is fixed: the spot, the peak, and the date do not change with the date of the visit.
  The search in `build_shot.py` (its docstring) explains how the spot was found.
- From this spot the summit is farther north than the sun and the planets go, so the subject
  chapter shows their nearest pass, and the playground offers only the moon (it lists the bodies
  that have a match). The Milky Way shows its clouds in Cygnus, because its center never comes this
  far north.
- The clearance of the check ignores the last 800 m before the summit: they are the flanks of the
  peak itself, and `lineOfSight` counts them as the highest terrain in front.
- On a wide screen the cards are in a column on the left and the print is on the right; on a narrow
  screen the print is at the top and the card at the bottom. `printRect` in `src/scene.ts` and
  `--margin` and `--column` in `src/style.css` must agree.
