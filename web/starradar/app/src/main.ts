// The Starradar PWA: the stars, the constellations, the planets, the moon, and the Milky Way in the
// direction of the phone, over the real horizon of the terrain. The core comes from
// @almucantar/core (WebAssembly); the place, the terrain, and the orientation come from
// @almucantar/web-shared.

import { GALACTIC_CENTER, Planet, load } from "@almucantar/core";
import type {
  HeadingEstimate,
  HorizonProfile,
  Horizontal,
  Quaternion,
  View,
} from "@almucantar/core";
import { litOutline, towardSun } from "@almucantar/web-shared/moon";
import type { MoonAt } from "@almucantar/web-shared/moon";
import {
  fromDeviceOrientation,
  screenRotation,
  upright,
  withCompass,
} from "@almucantar/web-shared/orientation";
import { DEFAULT_PLACE, gpsPlace, loadPlace } from "@almucantar/web-shared/place";
import type { StoredPlace } from "@almucantar/web-shared/place";
import { setupPlacePanel } from "@almucantar/web-shared/placePanel";
import {
  darkness,
  decodeStars,
  limitingMagnitude,
  starColor,
  starRadius,
} from "@almucantar/web-shared/stars";
import type { StarsJson } from "@almucantar/web-shared/stars";
import {
  Terrain,
  TileCache,
  cacheStorageStore,
  decodePng,
  localStorageIndex,
} from "@almucantar/web-shared/terrain";
import type { TerrainState } from "@almucantar/web-shared/terrain";
import { fromWallTime, wallTime } from "@almucantar/web-shared/zone";
import baseMapUrl from "../../../../assets/basemap/basemap.json?url";
import starsUrl from "../../../../assets/stars/stars.json?url";

const almucantar = await load();

// --- Elements -----------------------------------------------------------------------------------

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const canvas = $<HTMLCanvasElement>("sky");
const video = $<HTMLVideoElement>("camera");
const status = $("status");
const message = $("message");
const info = $("info");
const ctx = canvas.getContext("2d")!;

function showMessage(text: string | null) {
  message.textContent = text ?? "";
  message.classList.toggle("hidden", text === null);
}

// --- The star data ------------------------------------------------------------------------------

const stars = decodeStars((await (await fetch(starsUrl)).json()) as StarsJson);
const starCount = stars.catalog.raDeg.length;
// The labels of the constellations as a small catalogue, so the core moves them with the stars.
const labelCatalog = {
  raDeg: stars.constellations.map((c) => c.labelRaDeg),
  decDeg: stars.constellations.map((c) => c.labelDecDeg),
};

const PLANETS: { planet: Planet; name: string; color: string }[] = [
  { planet: Planet.mercury, name: "Mercury", color: "255, 236, 210" },
  { planet: Planet.venus, name: "Venus", color: "255, 252, 236" },
  { planet: Planet.mars, name: "Mars", color: "255, 170, 120" },
  { planet: Planet.jupiter, name: "Jupiter", color: "255, 240, 214" },
  { planet: Planet.saturn, name: "Saturn", color: "255, 230, 170" },
  { planet: Planet.uranus, name: "Uranus", color: "200, 240, 255" },
  { planet: Planet.neptune, name: "Neptune", color: "170, 200, 255" },
];

// --- State --------------------------------------------------------------------------------------

const PLACE_KEY = "starradar-place";
let place = loadPlace(PLACE_KEY);
let observer = place.observer;
let placeText = place.name;
let zone = place.timeZone;

let deviceToEnu: Quaternion | null = null;
let sensorsActive = false;
// The view without sensors: the user drags it, and the wheel zooms.
let dragAzimuth = 180;
let dragPitch = 30;
let skyFovDeg = 0; // 0: the default for the shape of the screen

// The browser gives a magnetic heading, so the offset starts at the declination. An alignment
// with a bright star corrects it.
let heading: HeadingEstimate = { offsetDeg: 0, varianceDeg2: 15 * 15 };

let dayOffset = 0;
let minuteOfDay: number | null = null; // null: now
let cameraOn = false;
let cameraStarting = false;
let aligning = false;
let fovDeg = 65;

const LINES_SETTING = "starradar-lines-on";
const TERRAIN_SETTING = "starradar-terrain-on";
const NIGHT_SETTING = "starradar-night";
let linesOn = localStorage.getItem(LINES_SETTING) !== "false";
let terrainOn = localStorage.getItem(TERRAIN_SETTING) !== "false";
let nightOn = localStorage.getItem(NIGHT_SETTING) === "true";

const tileCache = new TileCache({
  store: cacheStorageStore("starradar-terrain-v1"),
  ...localStorageIndex("starradar-terrain"),
  fetch: (url, init) => fetch(url, init),
  now: () => Date.now(),
});
const terrain = new Terrain({
  almucantar,
  cache: tileCache,
  decode: decodePng,
  onChange: (_state: TerrainState) => {},
});
let hasLocation = false;

const terrainProfile = (): HorizonProfile | null =>
  terrainOn && terrain.state.kind === "ready" ? terrain.state.profile : null;

/** The altitude of the visible horizon in a direction: the terrain, or 0°. */
function horizonAt(profile: HorizonProfile | null, azimuthDeg: number): number {
  if (!profile) return 0;
  const t = almucantar.terrainAltitudeDeg(profile, azimuthDeg);
  return Number.isNaN(t) ? 0 : t;
}

function terrainStatus(): string {
  const s = terrain.state;
  if (!terrainOn) return "";
  if (!hasLocation) return "Terrain: needs the location";
  switch (s.kind) {
    case "loading":
      return `Terrain: loading ${s.done}/${s.total}`;
    case "failed":
      return `Terrain: ${s.message}`;
    case "ready":
      return s.missing ? `Terrain: ${s.missing} tiles missing` : "";
    default:
      return "";
  }
}

// --- Time ---------------------------------------------------------------------------------------

function selectedTime(): number {
  if (minuteOfDay === null && dayOffset === 0) return Date.now() / 1000;
  const now = wallTime(zone, Date.now());
  const minute = minuteOfDay ?? now.hour * 60 + now.minute;
  // The wall clock time, not midnight + minutes, because a day with a DST change has 23 or 25 h.
  return (
    fromWallTime(
      zone,
      now.year,
      now.month,
      now.day + dayOffset,
      Math.floor(minute / 60),
      minute % 60,
    ) / 1000
  );
}

const hhmm = (unix: number) =>
  new Date(unix * 1000).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    timeZone: zone,
  });

// --- The sky at the selected time (calculated again when the time or the place changes) ----------

interface Sky {
  time: number;
  key: string;
  /** The directions of all stars: [azimuth0, altitude0, ...]. */
  stars: Float64Array;
  labels: Float64Array;
  planets: { planet: Planet; name: string; color: string; direction: Horizontal; mag: number }[];
  sun: Horizontal;
  moon: MoonAt;
  milkyWay: Horizontal[];
  galacticCenter: Horizontal;
}

let sky: Sky | null = null;

function computeSky(time: number): Sky {
  const key = `${observer.latitudeDeg}:${observer.longitudeDeg}`;
  // The stars move 0.004° per second, so once per second is enough for a frame.
  if (sky && sky.key === key && Math.abs(sky.time - time) < 1) return sky;
  sky = {
    time,
    key,
    stars: almucantar.starPositions(stars.catalog, time, observer),
    labels: almucantar.starPositions(labelCatalog, time, observer),
    planets: PLANETS.map((p) => ({
      ...p,
      direction: almucantar.planetPosition(p.planet, time, observer),
      mag: almucantar.planetMagnitude(p.planet, time),
    })),
    sun: almucantar.sunPosition(time, observer),
    moon: {
      direction: almucantar.moonPosition(time, observer),
      fraction: almucantar.moonIlluminatedFraction(time),
    },
    milkyWay: almucantar.galacticPlane(time, observer, 2),
    galacticCenter: almucantar.galacticPosition(
      GALACTIC_CENTER.longitudeDeg,
      GALACTIC_CENTER.latitudeDeg,
      time,
      observer,
    ),
  };
  return sky;
}

const starAt = (s: Sky, i: number): Horizontal => ({
  azimuthDeg: s.stars[2 * i],
  altitudeDeg: s.stars[2 * i + 1],
});

// --- The objects that the user can tap or find ---------------------------------------------------

type Target =
  | { kind: "star"; index: number }
  | { kind: "planet"; planet: Planet }
  | { kind: "moon" }
  | { kind: "sun" }
  | { kind: "constellation"; index: number }
  | { kind: "galactic-center" };

let selected: Target | null = null;

function targetName(t: Target): string {
  switch (t.kind) {
    case "star":
      return stars.names.get(t.index) ?? `HR ${t.index}`;
    case "planet":
      return PLANETS.find((p) => p.planet === t.planet)!.name;
    case "moon":
      return "Moon";
    case "sun":
      return "Sun";
    case "constellation":
      return stars.constellations[t.index].name;
    case "galactic-center":
      return "Center of the Milky Way";
  }
}

function targetDirection(t: Target, s: Sky): Horizontal {
  switch (t.kind) {
    case "star":
      return starAt(s, t.index);
    case "planet":
      return s.planets.find((p) => p.planet === t.planet)!.direction;
    case "moon":
      return s.moon.direction;
    case "sun":
      return s.sun;
    case "constellation":
      return { azimuthDeg: s.labels[2 * t.index], altitudeDeg: s.labels[2 * t.index + 1] };
    case "galactic-center":
      return s.galacticCenter;
  }
}

/** The direction of a target at another time, for its rise and set. */
function directionAt(t: Target, time: number): Horizontal {
  switch (t.kind) {
    case "star": {
      const c = stars.catalog;
      const one = {
        raDeg: [c.raDeg[t.index]],
        decDeg: [c.decDeg[t.index]],
        pmRaArcsecPerYear: [c.pmRaArcsecPerYear[t.index]],
        pmDecArcsecPerYear: [c.pmDecArcsecPerYear[t.index]],
      };
      const d = almucantar.starPositions(one, time, observer);
      return { azimuthDeg: d[0], altitudeDeg: d[1] };
    }
    case "planet":
      return almucantar.planetPosition(t.planet, time, observer);
    case "moon":
      return almucantar.moonPosition(time, observer);
    case "sun":
      return almucantar.sunPosition(time, observer);
    case "constellation": {
      const d = almucantar.starPositions(
        { raDeg: [labelCatalog.raDeg[t.index]], decDeg: [labelCatalog.decDeg[t.index]] },
        time,
        observer,
      );
      return { azimuthDeg: d[0], altitudeDeg: d[1] };
    }
    case "galactic-center":
      return almucantar.galacticPosition(0, 0, time, observer);
  }
}

/**
 * The next rise and set of a target in the 24 hours after `time`, over the terrain if there is
 * one: samples every 10 minutes, and a linear interpolation between them (about a minute).
 */
function riseAndSet(
  t: Target,
  time: number,
): { rise: number | null; set: number | null; always: boolean | null } {
  const profile = terrainProfile();
  const step = 600;
  let rise: number | null = null;
  let set: number | null = null;
  let up = 0;
  let previous: { time: number; height: number } | null = null;
  for (let k = 0; k <= 144; k++) {
    const at = time + k * step;
    const d = directionAt(t, at);
    const height = d.altitudeDeg - horizonAt(profile, d.azimuthDeg);
    if (height > 0) up++;
    if (previous && previous.height > 0 !== height > 0) {
      const crossing = previous.time + (step * previous.height) / (previous.height - height);
      if (height > 0 && rise === null) rise = crossing;
      if (height <= 0 && set === null) set = crossing;
    }
    previous = { time: at, height };
  }
  const always = up === 145 ? true : up === 0 ? false : null;
  return { rise, set, always };
}

let infoKey = "";

function showInfo(s: Sky) {
  if (!selected) {
    info.classList.add("hidden");
    infoKey = "";
    return;
  }
  // The rise and the set change slowly: calculate them again only for a new target, a new place,
  // or another hour.
  const key = `${JSON.stringify(selected)}:${s.key}:${Math.floor(s.time / 3600)}:${terrainProfile() ? 1 : 0}`;
  const d = targetDirection(selected, s);
  const position = `${d.altitudeDeg.toFixed(0)}° high, azimuth ${d.azimuthDeg.toFixed(0)}°`;
  if (key !== infoKey) {
    infoKey = key;
    const { rise, set, always } = riseAndSet(selected, s.time);
    const over = terrainProfile() ? " over the terrain" : "";
    const times =
      always === true
        ? `Up all day${over}`
        : always === false
          ? `Below the horizon all day${over}`
          : [
              ...(rise !== null ? [{ time: rise, text: `rises ${hhmm(rise)}` }] : []),
              ...(set !== null ? [{ time: set, text: `sets ${hhmm(set)}` }] : []),
            ]
              .sort((a, b) => a.time - b.time)
              .map((e) => e.text)
              .join(" · ")
              .replace(/^./, (c) => c.toUpperCase()) + over;
    let kind = "";
    switch (selected.kind) {
      case "star":
        kind = `Star · magnitude ${stars.mag[selected.index].toFixed(1)}`;
        break;
      case "planet": {
        const p = selected.planet;
        kind = `Planet · magnitude ${s.planets.find((x) => x.planet === p)!.mag.toFixed(1)}`;
        break;
      }
      case "moon":
        kind = `Moon · ${Math.round(s.moon.fraction * 100)}% lit`;
        break;
      case "sun":
        kind = "Sun";
        break;
      case "constellation":
        kind = "Constellation";
        break;
      case "galactic-center":
        kind = "Milky Way · Sagittarius A*";
        break;
    }
    info.dataset.static = `<div class="name">${targetName(selected)}</div><div class="muted">${kind}</div><div>${times}</div>`;
  }
  info.innerHTML = `${info.dataset.static}<div class="muted">${position}</div>`;
  info.classList.remove("hidden");
}

// --- The view -----------------------------------------------------------------------------------

function currentView(): View {
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  const fov = cameraOn ? fovDeg : skyFovDeg || (width < height ? 70 : 100);
  const q = sensorsActive && deviceToEnu ? deviceToEnu : upright(dragAzimuth, dragPitch);
  return {
    deviceToEnu: q,
    screenRotation: sensorsActive
      ? screenRotation(
          screen.orientation?.angle ?? (window as { orientation?: number }).orientation ?? 0,
        )
      : 0,
    headingOffsetDeg: sensorsActive ? heading.offsetDeg : 0,
    camera: almucantar.cameraFromFov(width, height, fov),
  };
}

// --- Drawing ------------------------------------------------------------------------------------

/** The color of the sky without the camera: day blue, twilight, and night. */
function skyColor(sunAltitudeDeg: number): string {
  const stops: [number, [number, number, number]][] = [
    [-18, [5, 9, 22]],
    [-12, [10, 18, 42]],
    [-6, [24, 40, 82]],
    [0, [44, 74, 130]],
    [10, [52, 104, 170]],
  ];
  const a = Math.max(-18, Math.min(10, sunAltitudeDeg));
  for (let i = 1; i < stops.length; i++) {
    if (a <= stops[i][0]) {
      const [a0, c0] = stops[i - 1];
      const [a1, c1] = stops[i];
      const t = (a - a0) / (a1 - a0);
      return `rgb(${c0.map((c, k) => Math.round(c + (c1[k] - c) * t)).join(", ")})`;
    }
  }
  return "rgb(52, 104, 170)";
}

/** Draws a label upright relative to the horizon: its top points to a point higher in the sky. */
function uprightLabel(view: View, direction: Horizontal, text: string, gap: number) {
  const at = almucantar.project(view, direction);
  if (!at.valid) return;
  const higher = almucantar.project(view, {
    azimuthDeg: direction.azimuthDeg,
    altitudeDeg: Math.min(direction.altitudeDeg + 0.5, 89.9),
  });
  const angle = higher.valid
    ? Math.atan2(higher.point.x - at.point.x, -(higher.point.y - at.point.y))
    : 0;
  ctx.save();
  ctx.translate(at.point.x, at.point.y);
  ctx.rotate(angle);
  ctx.fillText(text, 0, -gap);
  ctx.restore();
}

function strokeProjected(
  view: View,
  line: Horizontal[],
  color: string,
  width: number,
  dash: number[] = [],
) {
  ctx.strokeStyle = color;
  ctx.lineWidth = width;
  ctx.setLineDash(dash);
  for (const part of almucantar.projectLine(view, line)) {
    if (part.length < 2) continue;
    ctx.beginPath();
    ctx.moveTo(part[0].x, part[0].y);
    for (const p of part.slice(1)) ctx.lineTo(p.x, p.y);
    ctx.stroke();
  }
  ctx.setLineDash([]);
}

/** Draws the moon at (x, y) with the lit part toward the sun (see `towardSun`). */
function drawMoon(view: View, moon: MoonAt, sun: Horizontal, x: number, y: number, r: number) {
  let ux = 0;
  let uy = -1;
  const q = almucantar.project(view, towardSun(moon.direction, sun));
  if (q.valid) {
    const length = Math.hypot(q.point.x - x, q.point.y - y);
    if (length > 1e-3) {
      ux = (q.point.x - x) / length;
      uy = (q.point.y - y) / length;
    }
  }
  ctx.fillStyle = "rgba(224, 230, 240, 0.22)";
  ctx.beginPath();
  ctx.arc(x, y, r, 0, 2 * Math.PI);
  ctx.fill();
  ctx.fillStyle = "#e0e6f0";
  ctx.beginPath();
  litOutline(moon.fraction).forEach(([px, py], i) => {
    const sx = x + (ux * px - uy * py) * r;
    const sy = y + (uy * px + ux * py) * r;
    if (i === 0) ctx.moveTo(sx, sy);
    else ctx.lineTo(sx, sy);
  });
  ctx.closePath();
  ctx.fill();
}

/** The terrain: a line, and without the camera a dark fill below it, so the ground hides the sky. */
function drawTerrain(view: View, profile: HorizonProfile) {
  const line: Horizontal[] = [];
  for (let az = 0; az <= 360; az++) {
    line.push({ azimuthDeg: az % 360, altitudeDeg: horizonAt(profile, az % 360) });
  }
  if (!cameraOn) {
    ctx.fillStyle = "rgba(14, 22, 20, 0.72)";
    ctx.beginPath();
    for (let i = 0; i < 360; i++) {
      const corners = [
        line[i],
        line[i + 1],
        {
          azimuthDeg: line[i + 1].azimuthDeg,
          altitudeDeg: Math.min(line[i + 1].altitudeDeg, 0) - 10,
        },
        { azimuthDeg: line[i].azimuthDeg, altitudeDeg: Math.min(line[i].altitudeDeg, 0) - 10 },
      ].map((h) => almucantar.project(view, h));
      if (!corners.every((p) => p.valid)) continue;
      corners.forEach((p, k) =>
        k ? ctx.lineTo(p.point.x, p.point.y) : ctx.moveTo(p.point.x, p.point.y),
      );
      ctx.closePath();
    }
    ctx.fill("nonzero");
  }
  strokeProjected(view, line, "rgba(170, 210, 160, 0.85)", 1.5);
}

/** The screen positions of the drawn objects, for a tap. */
interface Hit {
  target: Target;
  x: number;
  y: number;
  /** A brighter object wins a tap between two objects. */
  mag: number;
}

let hits: Hit[] = [];

function draw(view: View, s: Sky) {
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  hits = [];
  ctx.clearRect(0, 0, w, h);
  if (!cameraOn) {
    ctx.fillStyle = skyColor(s.sun.altitudeDeg);
    ctx.fillRect(0, 0, w, h);
  }
  const profile = terrainProfile();
  const limit = limitingMagnitude(s.sun.altitudeDeg);
  const points = almucantar.projectPoints(view, s.stars);
  // A point below the horizon or the terrain is dim, so the user can still find it.
  const hidden = (d: Horizontal) => d.altitudeDeg < horizonAt(profile, d.azimuthDeg);

  // The Milky Way: a soft band (three wide strokes on top of each other, so the edges fade) and
  // its center line, only in a dark sky.
  if (limit >= 3.5) {
    const alpha = Math.min(1, (limit - 3.5) / 2.5);
    const band = [...s.milkyWay, s.milkyWay[0]];
    for (const width of [64, 40, 20]) {
      strokeProjected(view, band, `rgba(190, 200, 235, ${0.035 * alpha})`, width);
    }
    strokeProjected(view, band, `rgba(190, 200, 235, ${0.18 * alpha})`, 1, [2, 6]);
  }

  // The constellation lines between projected stars. A segment needs both ends on the screen.
  if (linesOn) {
    ctx.strokeStyle = "rgba(127, 170, 255, 0.38)";
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (const c of stars.constellations) {
      for (const line of c.lines) {
        for (let k = 1; k < line.length; k++) {
          const a = line[k - 1];
          const b = line[k];
          const ax = points[2 * a];
          const bx = points[2 * b];
          if (Number.isNaN(ax) || Number.isNaN(bx)) continue;
          ctx.moveTo(ax, points[2 * a + 1]);
          ctx.lineTo(bx, points[2 * b + 1]);
        }
      }
    }
    ctx.stroke();
  }

  // The stars, the brightest last, so they are on top.
  const shown: number[] = [];
  for (let i = 0; i < starCount; i++) {
    if (stars.mag[i] > limit) break; // sorted by magnitude
    if (!Number.isNaN(points[2 * i])) shown.push(i);
  }
  for (let k = shown.length - 1; k >= 0; k--) {
    const i = shown[k];
    const x = points[2 * i];
    const y = points[2 * i + 1];
    const mag = stars.mag[i];
    const fade = Math.min(1, 0.35 + (limit - mag) / 1.5);
    const alpha = hidden(starAt(s, i)) ? 0.22 * fade : fade;
    const color = starColor(stars.bv[i]);
    const r = starRadius(mag);
    if (mag < 1.5) {
      ctx.fillStyle = `rgba(${color}, ${0.18 * alpha})`;
      ctx.beginPath();
      ctx.arc(x, y, r * 2.6, 0, 2 * Math.PI);
      ctx.fill();
    }
    ctx.fillStyle = `rgba(${color}, ${alpha})`;
    ctx.beginPath();
    ctx.arc(x, y, r, 0, 2 * Math.PI);
    ctx.fill();
    hits.push({ target: { kind: "star", index: i }, x, y, mag });
  }

  ctx.textAlign = "center";
  ctx.shadowColor = "black";
  ctx.shadowBlur = 3;

  // The names of the brightest stars and of the constellations.
  ctx.font = "12px system-ui";
  for (const i of shown) {
    if (stars.mag[i] > Math.min(limit - 1, 1.6)) break;
    const name = stars.names.get(i);
    if (!name) continue;
    ctx.fillStyle = hidden(starAt(s, i)) ? "rgba(220, 228, 255, 0.3)" : "rgba(220, 228, 255, 0.85)";
    uprightLabel(view, starAt(s, i), name, 8);
  }
  if (linesOn) {
    ctx.font = "italic 12px system-ui";
    ctx.fillStyle = "rgba(127, 170, 255, 0.75)";
    stars.constellations.forEach((c, i) => {
      const d = { azimuthDeg: s.labels[2 * i], altitudeDeg: s.labels[2 * i + 1] };
      const p = almucantar.project(view, d);
      if (!p.valid) return;
      uprightLabel(view, d, c.name, 0);
      hits.push({
        target: { kind: "constellation", index: i },
        x: p.point.x,
        y: p.point.y,
        mag: 7,
      });
    });
  }
  if (limit >= 3.5) {
    const p = almucantar.project(view, s.galacticCenter);
    if (p.valid) {
      ctx.font = "12px system-ui";
      ctx.fillStyle = "rgba(200, 210, 240, 0.6)";
      uprightLabel(view, s.galacticCenter, "Milky Way center", 6);
      hits.push({ target: { kind: "galactic-center" }, x: p.point.x, y: p.point.y, mag: 6.5 });
    }
  }

  // The planets that the eye sees now.
  ctx.font = "bold 13px system-ui";
  for (const planet of s.planets) {
    if (planet.mag > limit + 0.5) continue;
    const p = almucantar.project(view, planet.direction);
    if (!p.valid) continue;
    const alpha = hidden(planet.direction) ? 0.3 : 1;
    ctx.fillStyle = `rgba(${planet.color}, ${alpha})`;
    ctx.beginPath();
    ctx.arc(p.point.x, p.point.y, starRadius(planet.mag) + 1, 0, 2 * Math.PI);
    ctx.fill();
    ctx.fillStyle = `rgba(255, 220, 160, ${alpha})`;
    uprightLabel(view, planet.direction, planet.name, 9);
    hits.push({
      target: { kind: "planet", planet: planet.planet },
      x: p.point.x,
      y: p.point.y,
      mag: planet.mag - 1,
    });
  }

  // The moon with its phase, and the sun.
  const m = almucantar.project(view, s.moon.direction);
  if (m.valid && s.moon.fraction >= 0.01) {
    drawMoon(view, s.moon, s.sun, m.point.x, m.point.y, 11);
    ctx.font = "12px system-ui";
    ctx.fillStyle = "white";
    uprightLabel(view, s.moon.direction, "Moon", 16);
    hits.push({ target: { kind: "moon" }, x: m.point.x, y: m.point.y, mag: -12 });
  }
  const sun = almucantar.project(view, s.sun);
  if (sun.valid) {
    ctx.fillStyle = "#ffe08a";
    ctx.beginPath();
    ctx.arc(sun.point.x, sun.point.y, 13, 0, 2 * Math.PI);
    ctx.fill();
    hits.push({ target: { kind: "sun" }, x: sun.point.x, y: sun.point.y, mag: -26 });
  }

  // The horizon, the terrain, and the cardinal points.
  ctx.shadowBlur = 0;
  strokeProjected(
    view,
    Array.from({ length: 73 }, (_, i) => ({ azimuthDeg: (i * 5) % 360, altitudeDeg: 0 })),
    profile ? "rgba(255,255,255,0.3)" : "rgba(255,255,255,0.7)",
    1,
  );
  if (profile) drawTerrain(view, profile);
  ctx.shadowBlur = 3;
  ctx.font = "bold 14px system-ui";
  ctx.fillStyle = "white";
  for (const [az, label] of [
    [0, "N"],
    [90, "E"],
    [180, "S"],
    [270, "W"],
  ] as const) {
    uprightLabel(view, { azimuthDeg: az, altitudeDeg: 0 }, label, 12);
  }

  if (selected) drawSelection(view, targetDirection(selected, s), w, h);
  ctx.shadowBlur = 0;
}

/** A ring around the selected object, or an arrow at the border of the screen that leads to it. */
function drawSelection(view: View, target: Horizontal, w: number, h: number) {
  const p = almucantar.project(view, target);
  const margin = 28;
  ctx.strokeStyle = "#7fd8ff";
  ctx.fillStyle = "#7fd8ff";
  ctx.lineWidth = 2;
  if (
    p.valid &&
    p.point.x > margin &&
    p.point.x < w - margin &&
    p.point.y > margin &&
    p.point.y < h - margin
  ) {
    ctx.beginPath();
    ctx.arc(p.point.x, p.point.y, 16, 0, 2 * Math.PI);
    ctx.stroke();
    return;
  }
  // The direction on the screen: from the center, a few degrees toward the target on the great
  // circle, so it works also when the target is behind the camera.
  const center = almucantar.unproject(view, { x: view.camera.cx, y: view.camera.cy });
  const step = almucantar.project(view, towardSun(center, target, 3));
  if (!step.valid) return;
  const dx = step.point.x - view.camera.cx;
  const dy = step.point.y - view.camera.cy;
  const length = Math.hypot(dx, dy);
  if (length < 1e-6) return;
  const ux = dx / length;
  const uy = dy / length;
  // The point where the ray from the center meets the border, inside the margin.
  const tx =
    ux > 0
      ? (w - margin - view.camera.cx) / ux
      : ux < 0
        ? (margin - view.camera.cx) / ux
        : Infinity;
  const ty =
    uy > 0
      ? (h - margin - view.camera.cy) / uy
      : uy < 0
        ? (margin - view.camera.cy) / uy
        : Infinity;
  const t = Math.min(tx, ty);
  const x = view.camera.cx + ux * t;
  const y = view.camera.cy + uy * t;
  ctx.beginPath();
  ctx.moveTo(x + ux * 14, y + uy * 14);
  ctx.lineTo(x - ux * 4 - uy * 9, y - uy * 4 + ux * 9);
  ctx.lineTo(x - ux * 4 + uy * 9, y - uy * 4 - ux * 9);
  ctx.closePath();
  ctx.fill();
}

// --- The frame ----------------------------------------------------------------------------------

let lastView: View | null = null;
let lastSky: Sky | null = null;

function frame() {
  const dpr = window.devicePixelRatio || 1;
  const [width, height] = [canvas.clientWidth, canvas.clientHeight].map((v) => Math.round(v * dpr));
  if (canvas.width !== width || canvas.height !== height)
    [canvas.width, canvas.height] = [width, height];
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  const time = selectedTime();
  const view = currentView();
  const s = computeSky(time);
  lastView = view;
  lastSky = s;
  draw(view, s);
  showInfo(s);

  const sigma = Math.sqrt(heading.varianceDeg2);
  const headingText = !sensorsActive
    ? `<span class="heading-fair">No sensors: drag to look around</span>`
    : sigma > 10
      ? `<span class="heading-poor">Heading ±${sigma.toFixed(0)}° (align with a bright star)</span>`
      : sigma > 3
        ? `<span class="heading-fair">Heading ±${sigma.toFixed(0)}°</span>`
        : `<span class="heading-good">Heading ±${sigma.toFixed(1)}°</span>`;
  const moonText =
    s.moon.direction.altitudeDeg > 0
      ? `Moon up, ${Math.round(s.moon.fraction * 100)}% lit`
      : `Moon down (${Math.round(s.moon.fraction * 100)}% lit)`;
  const terrainText = terrainStatus();
  status.innerHTML =
    `<b>${placeText}</b><br>${headingText}<br>${darkness(s.sun.altitudeDeg)} · ${moonText}` +
    (terrainText ? `<br><span class="heading-fair">${terrainText}</span>` : "");

  $("date-label").textContent = new Date(time * 1000).toLocaleDateString([], {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: zone,
  });
  $("time-label").textContent =
    minuteOfDay === null && dayOffset === 0 ? `${hhmm(time)} (now)` : hhmm(time);
  $("fov-label").textContent = `${fovDeg}°`;
  requestAnimationFrame(frame);
}

// --- Sensors, location, camera ------------------------------------------------------------------

function onOrientation(event: DeviceOrientationEvent & { webkitCompassHeading?: number }) {
  if (event.alpha === null || event.beta === null || event.gamma === null) return;
  const compass = event.webkitCompassHeading;
  // iOS gives -1 while the compass is not calibrated.
  const hasCompass = compass !== undefined && Number.isFinite(compass) && compass >= 0;
  const q = fromDeviceOrientation(event.alpha, event.beta, event.gamma);
  deviceToEnu = hasCompass ? withCompass(q, compass) : q;
  sensorsActive = true;
}

async function startSensors() {
  if ("DeviceOrientationEvent" in window) {
    const request = (
      DeviceOrientationEvent as unknown as { requestPermission?: () => Promise<string> }
    ).requestPermission;
    if (
      request &&
      (await request.call(DeviceOrientationEvent).catch(() => "denied")) !== "granted"
    ) {
      showMessage("The orientation sensors are not allowed.");
    } else {
      const hasAbsolute = "ondeviceorientationabsolute" in (window as object);
      window.addEventListener(
        hasAbsolute ? "deviceorientationabsolute" : "deviceorientation",
        onOrientation as EventListener,
      );
    }
  }
  navigator.geolocation?.getCurrentPosition(
    (position) => {
      // A place that the user dropped stays, until the user asks for the location in the panel.
      if (place.manual) return;
      setPlace(gpsPlace(position));
    },
    () => {
      if (!place.manual) showMessage(`No location: the app uses the last place (${placeText}).`);
    },
    { enableHighAccuracy: false, timeout: 20000 },
  );
  resetHeading();
  $("start").classList.add("hidden");
}

/** Changes the place: the sky, the terrain, and the declination follow. */
function setPlace(next: StoredPlace) {
  place = next;
  observer = next.observer;
  placeText = next.name;
  zone = next.timeZone;
  localStorage.setItem(PLACE_KEY, JSON.stringify(next));
  hasLocation = true;
  if (terrainOn) terrain.update(observer);
  placePanel.refresh();
  // Only the declination changes, so an alignment before stays.
  const old = declination;
  declination = almucantar.magneticDeclinationDeg(observer, Date.now() / 1000);
  heading = { ...heading, offsetDeg: heading.offsetDeg + declination - old };
}

let declination = 0;
function resetHeading() {
  declination = almucantar.magneticDeclinationDeg(observer, Date.now() / 1000);
  heading = { offsetDeg: declination, varianceDeg2: 15 * 15 };
}

async function toggleCamera() {
  if (cameraStarting) return;
  if (cameraOn) {
    (video.srcObject as MediaStream | null)?.getTracks().forEach((t) => t.stop());
    video.srcObject = null;
    cameraOn = false;
  } else {
    cameraStarting = true;
    try {
      video.srcObject = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "environment" },
        audio: false,
      });
      await video.play();
      cameraOn = true;
    } catch {
      (video.srcObject as MediaStream | null)?.getTracks().forEach((t) => t.stop());
      video.srcObject = null;
      showMessage("The camera is not available.");
    } finally {
      cameraStarting = false;
    }
  }
  video.style.display = cameraOn ? "block" : "none";
  $("camera-button").textContent = cameraOn ? "Sky" : "Camera";
  $("fov-row").classList.toggle("hidden", !cameraOn);
}

// --- Input --------------------------------------------------------------------------------------

/** The object nearest to a screen point, within `radius` pixels. A brighter object wins a tie. */
function hitAt(
  x: number,
  y: number,
  radius: number,
  accept: (h: Hit) => boolean = () => true,
): Hit | null {
  let best: Hit | null = null;
  let bestScore = Infinity;
  for (const hit of hits) {
    if (!accept(hit)) continue;
    const distance = Math.hypot(hit.x - x, hit.y - y);
    if (distance > radius) continue;
    const score = distance + hit.mag * 3;
    if (score < bestScore) {
      best = hit;
      bestScore = score;
    }
  }
  return best;
}

let pointer: { x: number; y: number; moved: boolean } | null = null;
canvas.addEventListener("pointerdown", (e) => {
  pointer = { x: e.clientX, y: e.clientY, moved: false };
});
window.addEventListener("pointermove", (e) => {
  if (!pointer) return;
  const dx = e.clientX - pointer.x;
  const dy = e.clientY - pointer.y;
  if (!pointer.moved && Math.hypot(dx, dy) < 6) return;
  pointer.moved = true;
  if (sensorsActive) return;
  const fov = skyFovDeg || (canvas.clientWidth < canvas.clientHeight ? 70 : 100);
  const scale = fov / canvas.clientWidth;
  dragAzimuth = (dragAzimuth - dx * scale + 360) % 360;
  dragPitch = Math.max(-80, Math.min(89, dragPitch + dy * scale));
  pointer.x = e.clientX;
  pointer.y = e.clientY;
});
window.addEventListener("pointerup", (e) => {
  const tap = pointer && !pointer.moved;
  pointer = null;
  if (!tap || e.target !== canvas) return;
  if (aligning && lastView && lastSky) {
    alignWith(lastView, lastSky, e.clientX, e.clientY);
    return;
  }
  const hit = hitAt(e.clientX, e.clientY, 32);
  selected = hit?.target ?? null;
});
window.addEventListener("pointercancel", () => (pointer = null));
canvas.addEventListener(
  "wheel",
  (e) => {
    e.preventDefault();
    if (cameraOn) return;
    const fov = skyFovDeg || (canvas.clientWidth < canvas.clientHeight ? 70 : 100);
    skyFovDeg = Math.max(20, Math.min(120, fov * Math.exp(e.deltaY * 0.001)));
  },
  { passive: false },
);

/** An alignment: the tapped object must be bright, so the user finds it in the sky. */
function alignWith(view: View, s: Sky, x: number, y: number) {
  aligning = false;
  $("align-button").textContent = "Align";
  const hit = hitAt(x, y, 40, (h) => h.mag <= 2.5 && h.target.kind !== "constellation");
  if (!hit) {
    showMessage("Tap a bright star, a planet, or the moon.");
    return;
  }
  const truth = targetDirection(hit.target, s);
  let measurement;
  try {
    measurement = almucantar.headingFromDirection(view, { x, y }, truth.azimuthDeg, 0.3);
  } catch (e) {
    // The core rejects a direction that gives no heading (for example near the zenith).
    if (!(e instanceof RangeError)) throw e;
    showMessage(`No alignment: ${e.message}.`);
    return;
  }
  heading = almucantar.headingUpdate(heading, measurement);
  selected = hit.target;
  showMessage(
    `Heading aligned with ${targetName(hit.target)} (±${Math.sqrt(heading.varianceDeg2).toFixed(1)}°).`,
  );
}

// --- Find ---------------------------------------------------------------------------------------

interface Entry {
  name: string;
  kind: string;
  target: Target;
}

const entries: Entry[] = [
  ...PLANETS.map((p) => ({
    name: p.name,
    kind: "Planet",
    target: { kind: "planet", planet: p.planet } as Target,
  })),
  { name: "Moon", kind: "Moon", target: { kind: "moon" } },
  { name: "Sun", kind: "Star", target: { kind: "sun" } },
  { name: "Milky Way center", kind: "Milky Way", target: { kind: "galactic-center" } },
  ...stars.constellations.map((c, i) => ({
    name: c.name,
    kind: "Constellation",
    target: { kind: "constellation", index: i } as Target,
  })),
  ...[...stars.names].map(([i, name]) => ({
    name,
    kind: `Star · ${stars.mag[i].toFixed(1)}`,
    target: { kind: "star", index: i } as Target,
  })),
];

/** Without accents and in lower case, so "Alnilam" also finds "alnilam". */
const fold = (text: string) => text.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

const findInput = $<HTMLInputElement>("find-input");
const findResults = $("find-results");

function showFindResults() {
  const query = fold(findInput.value.trim());
  findResults.replaceChildren();
  if (!query) return;
  const found = entries
    .filter((e) => fold(e.name).includes(query))
    .sort(
      (a, b) => Number(!fold(a.name).startsWith(query)) - Number(!fold(b.name).startsWith(query)),
    )
    .slice(0, 12);
  for (const entry of found) {
    const item = document.createElement("button");
    const name = document.createElement("span");
    name.textContent = entry.name;
    const kind = document.createElement("span");
    kind.className = "kind";
    kind.textContent = entry.kind;
    item.append(name, kind);
    item.addEventListener("click", () => {
      selected = entry.target;
      findInput.value = "";
      findResults.replaceChildren();
      $("find-panel").classList.add("hidden");
      // Without sensors, the view turns to the target.
      if (!sensorsActive && lastSky) {
        const d = targetDirection(entry.target, lastSky);
        dragAzimuth = d.azimuthDeg;
        dragPitch = Math.max(-80, Math.min(89, d.altitudeDeg));
      }
    });
    findResults.append(item);
  }
}

// --- Buttons and settings -----------------------------------------------------------------------

$("start").addEventListener("click", () => void startSensors());
$("camera-button").addEventListener("click", () => void toggleCamera());
$("time-button").addEventListener("click", () => $("time-panel").classList.toggle("hidden"));
$("find-button").addEventListener("click", () => {
  const panel = $("find-panel");
  panel.classList.toggle("hidden");
  if (!panel.classList.contains("hidden")) findInput.focus();
});
findInput.addEventListener("input", showFindResults);
$("align-button").addEventListener("click", () => {
  if (!sensorsActive)
    return showMessage("The alignment needs the orientation sensors: press Start sensors.");
  aligning = !aligning;
  $("align-button").textContent = aligning ? "Cancel" : "Align";
  showMessage(aligning ? "Point at a bright star, a planet, or the moon, and tap it." : null);
});
$<HTMLInputElement>("date-offset").addEventListener("input", (e) => {
  dayOffset = Number((e.target as HTMLInputElement).value);
});
$<HTMLInputElement>("minute").addEventListener("input", (e) => {
  minuteOfDay = Number((e.target as HTMLInputElement).value);
});
$("now").addEventListener("click", () => {
  minuteOfDay = null;
  dayOffset = 0;
  const now = wallTime(zone, Date.now());
  $<HTMLInputElement>("date-offset").value = "0";
  $<HTMLInputElement>("minute").value = String(now.hour * 60 + now.minute);
});
const linesBox = $<HTMLInputElement>("lines-on");
linesBox.checked = linesOn;
linesBox.addEventListener("change", () => {
  linesOn = linesBox.checked;
  localStorage.setItem(LINES_SETTING, String(linesOn));
});
const terrainBox = $<HTMLInputElement>("terrain-on");
terrainBox.checked = terrainOn;
terrainBox.addEventListener("change", () => {
  terrainOn = terrainBox.checked;
  localStorage.setItem(TERRAIN_SETTING, String(terrainOn));
  if (!terrainOn) terrain.stop();
  else if (hasLocation) terrain.update(observer);
});
const nightBox = $<HTMLInputElement>("night-on");
function applyNight() {
  $("night").classList.toggle("hidden", !nightOn);
  nightBox.checked = nightOn;
}
applyNight();
nightBox.addEventListener("change", () => {
  nightOn = nightBox.checked;
  localStorage.setItem(NIGHT_SETTING, String(nightOn));
  applyNight();
});
$<HTMLInputElement>("fov").addEventListener("input", (e) => {
  fovDeg = Number((e.target as HTMLInputElement).value);
});
$("credits").textContent =
  `${stars.attribution} Terrain: Terrain Tiles on AWS (Mapzen and its sources).`;

if ("serviceWorker" in navigator && location.protocol !== "http:") {
  void navigator.serviceWorker.register("./sw.js");
}
if (!("DeviceOrientationEvent" in window)) $("start").textContent = "Use location";

requestAnimationFrame(frame);

// The place panel (@almucantar/web-shared/placePanel).
const placePanel = setupPlacePanel({
  almucantar,
  tileCache,
  baseMapUrl,
  current: () => ({ observer, name: placeText, zone }),
  hasPlace: () => hasLocation,
  terrainState: () => terrain.state,
  setPlace,
  showMessage,
});

// The last place starts the terrain also before the location arrives.
if (place !== DEFAULT_PLACE) {
  hasLocation = true;
  if (terrainOn) terrain.update(observer);
}
