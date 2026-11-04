// The Shotradar PWA, the photo planner (PLAN.md 14.1). The workflow: pick the subject (the moon,
// the Milky Way, the sun, or a planet), tap the landmark on the map, then plan: "I stand here"
// finds the dates and times when the subject is behind the landmark, and "Where must I stand?"
// draws the line of places for a time, with the spots where the ground has the right height. The
// core (@almucantar/core, WebAssembly) calculates; the map is the shaded relief of the Terrarium
// tiles with the Natural Earth base map on top.

import { Body, load } from "@almucantar/core";
import type {
  Alignment,
  AlignmentLine,
  ElevationGrid,
  LinePoint,
  Observer,
  Site,
} from "@almucantar/core";
import {
  decodeBaseMap,
  drawBaseMap,
  fromWorld,
  nearestPlace,
  searchPlaces,
  timeZoneAt,
  toWorld,
} from "@almucantar/web-shared/basemap";
import type { BaseMap, BaseMapStyle, MapView } from "@almucantar/web-shared/basemap";
import {
  TileCache,
  cacheStorageStore,
  decodePng,
  localStorageIndex,
  tileUrl,
} from "@almucantar/web-shared/terrain";
import { browserTimeZone, fromWallTime, wallTime } from "@almucantar/web-shared/zone";
import baseMapUrl from "../../../../assets/basemap/basemap.json?url";
import { heightAt, shadeTile } from "./relief";

const almucantar = await load();

// --- Elements -----------------------------------------------------------------------------------

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const canvas = $<HTMLCanvasElement>("map");
const ctx = canvas.getContext("2d")!;
const hint = $("hint");

// --- The subjects -------------------------------------------------------------------------------

interface Subject {
  id: string;
  name: string;
  body: number;
  note: string;
  /** A match puts the center of the subject this near to the landmark. */
  maxSeparationDeg: number;
}

const SUBJECTS: Subject[] = [
  {
    id: "moon",
    name: "Moon",
    body: Body.moon,
    note: "rise, set, full moon",
    maxSeparationDeg: 0.3,
  },
  {
    id: "milkyway",
    name: "Milky Way",
    body: Body.galacticCenter,
    note: "its bright center",
    maxSeparationDeg: 2,
  },
  {
    id: "sun",
    name: "Sun",
    body: Body.sun,
    note: "sunrise, sunset",
    maxSeparationDeg: 0.3,
  },
  {
    id: "venus",
    name: "Venus",
    body: Body.venus,
    note: "the evening star",
    maxSeparationDeg: 0.5,
  },
  {
    id: "jupiter",
    name: "Jupiter",
    body: Body.jupiter,
    note: "planet",
    maxSeparationDeg: 0.5,
  },
  {
    id: "saturn",
    name: "Saturn",
    body: Body.saturn,
    note: "planet",
    maxSeparationDeg: 0.5,
  },
  {
    id: "mars",
    name: "Mars",
    body: Body.mars,
    note: "planet",
    maxSeparationDeg: 0.5,
  },
];

// --- State --------------------------------------------------------------------------------------

/** A place that the user tapped, with the height of the ground once it is known. */
interface Point {
  latitudeDeg: number;
  longitudeDeg: number;
  name: string;
  groundM: number | null;
}

type Step = "subject" | "landmark" | "plan";
type Mode = "here" | "where";

interface Saved {
  subject: string;
  landmark: Point | null;
  extraM: number;
  spot: Point | null;
  mode: Mode;
  view: MapView;
}

const STATE_KEY = "shotradar-state-v1";
const EYE_HEIGHT_M = 1.6;
/** The farthest place of the line of "Where must I stand?", and the radius of its tiles. */
const LINE_DISTANCE_M = 30_000;
/** The zoom of the elevation grids of the core (about 50 m per pixel in the Alps). */
const GRID_ZOOM = 11;
/** The zoom of the height of a tapped point (about 10 m per pixel). */
const POINT_ZOOM = 13;

function loadState(): Saved {
  const fallback: Saved = {
    subject: "moon",
    landmark: null,
    extraM: 0,
    spot: null,
    mode: "here",
    view: { lon: 11.39, lat: 47.27, zoom: 10 },
  };
  try {
    const saved = JSON.parse(localStorage.getItem(STATE_KEY) ?? "null") as Saved | null;
    if (saved && Number.isFinite(saved.view?.zoom)) return { ...fallback, ...saved };
  } catch {
    // A damaged entry: start again.
  }
  return fallback;
}

const state = loadState();
let step: Step = state.landmark ? "plan" : "subject";
let picking: "landmark" | "spot" | null = null;
let matches: Alignment[] = [];
/** The search ran for the spot, the landmark, and the subject of now. */
let searched = false;
let line: AlignmentLine | null = null;
let selectedSpot: LinePoint | null = null;
/** The time of "Where must I stand?", in Unix seconds. */
let lineTime = Math.round(Date.now() / 1000 / 300) * 300;
let baseMap: BaseMap | null = null;

const save = () => localStorage.setItem(STATE_KEY, JSON.stringify(state));
const subject = () => SUBJECTS.find((s) => s.id === state.subject) ?? SUBJECTS[0];
const observerOf = (p: Point): Observer => ({
  latitudeDeg: p.latitudeDeg,
  longitudeDeg: p.longitudeDeg,
});
/** The time zone of the landmark: all dates and times use it. */
const zone = () =>
  state.landmark && baseMap
    ? timeZoneAt(baseMap.places, observerOf(state.landmark), browserTimeZone())
    : browserTimeZone();

// --- The elevation tiles ------------------------------------------------------------------------

const tileCache = new TileCache({
  store: cacheStorageStore("shotradar-terrain-v1"),
  ...localStorageIndex("shotradar-terrain"),
  fetch: (url, init) => fetch(url, init),
  now: () => Date.now(),
});

interface Decoded {
  rgba: Uint8ClampedArray;
  width: number;
  height: number;
}

/** The decoded tiles in memory, the oldest first, so the oldest go first when there are many. */
const decoded = new Map<string, Promise<Decoded | null>>();

function decodedTile(zoom: number, x: number, y: number): Promise<Decoded | null> {
  const url = tileUrl({ zoom, x, y });
  let tile = decoded.get(url);
  if (!tile) {
    tile = tileCache
      .get(url)
      .then((blob) => (blob ? decodePng(blob) : null))
      .catch(() => null);
    decoded.set(url, tile);
    if (decoded.size > 300) decoded.delete(decoded.keys().next().value!);
  }
  return tile;
}

/** The shaded relief of the tiles of the map, as bitmaps, once they are ready. */
const relief = new Map<string, ImageBitmap | null>();

function reliefTile(zoom: number, x: number, y: number): ImageBitmap | null {
  const key = `${zoom}/${x}/${y}`;
  if (relief.has(key)) return relief.get(key)!;
  relief.set(key, null);
  void decodedTile(zoom, x, y).then(async (tile) => {
    if (!tile) return;
    const shaded = shadeTile(tile.rgba, tile.width, tile.height, zoom, y);
    relief.set(key, await createImageBitmap(new ImageData(shaded, tile.width, tile.height)));
    if (relief.size > 400) relief.delete(relief.keys().next().value!);
    draw();
  });
  return null;
}

/** The height of the ground at a place, from the Terrarium tile of POINT_ZOOM. */
async function groundAt(at: Observer): Promise<number | null> {
  const n = 2 ** POINT_ZOOM;
  const [wx, wy] = toWorld(at.longitudeDeg, at.latitudeDeg, POINT_ZOOM);
  const x = Math.floor(wx / 256);
  const y = Math.floor(wy / 256);
  const tile = await decodedTile(POINT_ZOOM, ((x % n) + n) % n, y);
  if (!tile) return null;
  const scale = tile.width / 256;
  return heightAt(
    tile.rgba,
    tile.width,
    tile.height,
    (wx - x * 256) * scale,
    (wy - y * 256) * scale,
  );
}

/** The highest point within `radiusM` of `at`, from the tile of POINT_ZOOM, or null offline. */
async function summitNear(at: Observer, radiusM: number): Promise<Observer | null> {
  const n = 2 ** POINT_ZOOM;
  const [wx, wy] = toWorld(at.longitudeDeg, at.latitudeDeg, POINT_ZOOM);
  const x = Math.floor(wx / 256);
  const y = Math.floor(wy / 256);
  const tile = await decodedTile(POINT_ZOOM, ((x % n) + n) % n, y);
  if (!tile) return null;
  const scale = tile.width / 256;
  const metersPerPixel =
    (40_075_016.686 * Math.cos((at.latitudeDeg * Math.PI) / 180)) / (256 * n * scale);
  const r = Math.max(1, Math.round(radiusM / metersPerPixel));
  const px = Math.floor((wx - x * 256) * scale);
  const py = Math.floor((wy - y * 256) * scale);
  let best = -Infinity;
  let bestX = px;
  let bestY = py;
  for (let yy = Math.max(0, py - r); yy <= Math.min(tile.height - 1, py + r); yy++) {
    for (let xx = Math.max(0, px - r); xx <= Math.min(tile.width - 1, px + r); xx++) {
      if ((xx - px) ** 2 + (yy - py) ** 2 > r * r) continue;
      const h = heightAt(tile.rgba, tile.width, tile.height, xx + 0.5, yy + 0.5);
      if (h > best) {
        best = h;
        bestX = xx;
        bestY = yy;
      }
    }
  }
  const [lon, lat] = fromWorld(
    x * 256 + (bestX + 0.5) / scale,
    y * 256 + (bestY + 0.5) / scale,
    POINT_ZOOM,
  );
  return { latitudeDeg: lat, longitudeDeg: lon };
}

/** The elevation grids of the core around a place, for the line and the line of sight. */
const grids = new Map<string, Promise<ElevationGrid | null>>();

async function gridsAround(center: Observer, radiusM: number): Promise<ElevationGrid[]> {
  const tiles = almucantar.mapTiles(center, radiusM, GRID_ZOOM);
  const loaded = await Promise.all(
    tiles.map((t) => {
      const url = tileUrl(t);
      let grid = grids.get(url);
      if (!grid) {
        grid = decodedTile(t.zoom, t.x, t.y).then((tile) =>
          tile ? almucantar.terrariumGrid(tile.rgba, tile.width, tile.height, t) : null,
        );
        grids.set(url, grid);
      }
      return grid;
    }),
  );
  return loaded.filter((g): g is ElevationGrid => g !== null);
}

// --- Times --------------------------------------------------------------------------------------

function formatTime(unix: number, withDate = true): string {
  return new Intl.DateTimeFormat(undefined, {
    timeZone: zone(),
    weekday: withDate ? "short" : undefined,
    day: withDate ? "numeric" : undefined,
    month: withDate ? "short" : undefined,
    year: withDate ? "numeric" : undefined,
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(unix * 1000));
}

/** The light of the sky from the airless altitude of the sun. */
function sky(unix: number, at: Observer): { label: string; dark: boolean } {
  const sun = almucantar.sunPosition(unix, at, {
    refraction: false,
  }).altitudeDeg;
  if (sun >= 0) return { label: "day", dark: false };
  if (sun >= -6) return { label: "civil twilight", dark: false };
  if (sun >= -12) return { label: "nautical twilight", dark: false };
  if (sun >= -18) return { label: "astronomical twilight", dark: true };
  return { label: "night", dark: true };
}

// --- The map ------------------------------------------------------------------------------------

/** The base map over the light relief: dark lines and names with a light halo. */
const LIGHT_MAP: BaseMapStyle = {
  background: null,
  rivers: "rgba(70, 120, 160, 0.45)",
  lakes: "rgba(70, 120, 160, 0.6)",
  coastline: "rgba(42, 31, 61, 0.55)",
  borders: "rgba(138, 74, 85, 0.55)",
  label: "rgba(42, 31, 61, 0.9)",
  halo: "rgba(255, 250, 242, 0.9)",
};

let width = 0;
let height = 0;

function resize() {
  const dpr = window.devicePixelRatio || 1;
  width = canvas.clientWidth;
  height = canvas.clientHeight;
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  draw();
}

/** The screen point of a place. */
function screen(at: Observer): [number, number] {
  const [cx, cy] = toWorld(state.view.lon, state.view.lat, state.view.zoom);
  const [x, y] = toWorld(at.longitudeDeg, at.latitudeDeg, state.view.zoom);
  return [x - cx + width / 2, y - cy + height / 2];
}

function placeAt(x: number, y: number): Observer {
  const [cx, cy] = toWorld(state.view.lon, state.view.lat, state.view.zoom);
  const [lon, lat] = fromWorld(cx + x - width / 2, cy + y - height / 2, state.view.zoom);
  return { latitudeDeg: lat, longitudeDeg: lon };
}

/** The point at `distance` meters from `from` in the direction `azimuthDeg`, on the sphere. */
function destination(from: Observer, azimuthDeg: number, distance: number): Observer {
  const rad = Math.PI / 180;
  const d = distance / 6_371_008.8;
  const phi = from.latitudeDeg * rad;
  const theta = azimuthDeg * rad;
  const phi2 = Math.asin(
    Math.sin(phi) * Math.cos(d) + Math.cos(phi) * Math.sin(d) * Math.cos(theta),
  );
  const lambda2 =
    from.longitudeDeg * rad +
    Math.atan2(
      Math.sin(theta) * Math.sin(d) * Math.cos(phi),
      Math.cos(d) - Math.sin(phi) * Math.sin(phi2),
    );
  return { latitudeDeg: phi2 / rad, longitudeDeg: lambda2 / rad };
}

function draw() {
  if (!width) return;
  ctx.fillStyle = "#f4ece0";
  ctx.fillRect(0, 0, width, height);
  const view = state.view;
  // The relief: the tiles of the nearest whole zoom, scaled to the zoom of the view.
  const z = Math.max(2, Math.min(13, Math.round(view.zoom)));
  const scale = 2 ** (view.zoom - z);
  const n = 2 ** z;
  const [cx, cy] = toWorld(view.lon, view.lat, z);
  const left = cx - width / 2 / scale;
  const top = cy - height / 2 / scale;
  for (
    let ty = Math.max(0, Math.floor(top / 256));
    ty <= Math.min(n - 1, Math.floor((top + height / scale) / 256));
    ty++
  ) {
    for (let tx = Math.floor(left / 256); tx <= Math.floor((left + width / scale) / 256); tx++) {
      const wx = ((tx % n) + n) % n;
      const bitmap = reliefTile(z, wx, ty);
      const size = 256 * scale;
      const dx = (tx * 256 - left) * scale;
      const dy = (ty * 256 - top) * scale;
      if (bitmap) {
        ctx.drawImage(bitmap, dx, dy, size + 0.5, size + 0.5);
        continue;
      }
      // While the tile loads, a part of a tile of a lower zoom that is in memory.
      for (let k = 1; k <= 4 && z - k >= 0; k++) {
        const parent = relief.get(`${z - k}/${wx >> k}/${ty >> k}`);
        if (!parent) continue;
        const part = parent.width / 2 ** k;
        const sx = (wx - ((wx >> k) << k)) * part;
        const sy = (ty - ((ty >> k) << k)) * part;
        ctx.drawImage(parent, sx, sy, part, part, dx, dy, size + 0.5, size + 0.5);
        break;
      }
    }
  }
  if (baseMap) drawBaseMap(ctx, baseMap, view, width, height, null, LIGHT_MAP);
  drawPlan();
}

function drawMarker(at: Observer, kind: "landmark" | "spot") {
  const [x, y] = screen(at);
  ctx.save();
  ctx.lineWidth = 2;
  if (kind === "landmark") {
    // A peak: a triangle with a ring.
    ctx.fillStyle = "#2a1f3d";
    ctx.beginPath();
    ctx.moveTo(x, y - 12);
    ctx.lineTo(x + 10, y + 6);
    ctx.lineTo(x - 10, y + 6);
    ctx.closePath();
    ctx.fill();
    ctx.strokeStyle = "#fff3d6";
    ctx.stroke();
  } else {
    // The photographer: a ring with a dot.
    ctx.fillStyle = "#fffaf2";
    ctx.strokeStyle = "#8a4a55";
    ctx.beginPath();
    ctx.arc(x, y, 9, 0, 2 * Math.PI);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = "#8a4a55";
    ctx.beginPath();
    ctx.arc(x, y, 3.5, 0, 2 * Math.PI);
    ctx.fill();
  }
  ctx.restore();
}

function polyline(points: Observer[], color: string, lineWidth: number, dash: number[] = []) {
  if (points.length < 2) return;
  ctx.save();
  ctx.strokeStyle = color;
  ctx.lineWidth = lineWidth;
  ctx.setLineDash(dash);
  ctx.lineCap = "round";
  ctx.beginPath();
  points.forEach((p, i) => {
    const [x, y] = screen(p);
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  });
  ctx.stroke();
  ctx.restore();
}

function drawPlan() {
  const landmark = state.landmark;
  if (step === "plan" && state.mode === "where" && line && landmark) {
    // The line of places, and the direction of the subject from the landmark.
    polyline(
      line.points.map((p) => p.place),
      "rgba(138, 74, 85, 0.85)",
      3,
      [8, 6],
    );
    const toward = line.points[0]?.body.azimuthDeg;
    if (toward !== undefined) {
      polyline(
        [observerOf(landmark), destination(observerOf(landmark), toward, 4000)],
        "rgba(232, 149, 94, 0.95)",
        3,
      );
    }
    for (const s of line.spots) {
      const [x, y] = screen(s.place);
      // A spot with a clear view is a full dot; a blocked one is a ring.
      const clear = spotViews.get(s) ?? false;
      const r = s === selectedSpot ? 9 : 7;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, 2 * Math.PI);
      if (clear) {
        ctx.fillStyle = s === selectedSpot ? "#2a1f3d" : "#e8955e";
        ctx.strokeStyle = "#fffaf2";
        ctx.fill();
        ctx.stroke();
      } else {
        ctx.fillStyle = "rgba(255, 250, 242, 0.85)";
        ctx.strokeStyle = s === selectedSpot ? "#2a1f3d" : "#8a7a80";
        ctx.fill();
        ctx.stroke();
      }
    }
  }
  if (step === "plan" && state.mode === "here" && landmark && state.spot) {
    // The line of sight from the spot through the landmark, on to the subject.
    const spot = observerOf(state.spot);
    const along = almucantar.sight(
      { ...spot, elevationM: 0 },
      { ...observerOf(landmark), elevationM: 0 },
    );
    polyline(
      [spot, destination(spot, along.direction.azimuthDeg, along.distanceM + 3000)],
      "rgba(232, 149, 94, 0.95)",
      3,
    );
  }
  if (state.spot && step === "plan") drawMarker(observerOf(state.spot), "spot");
  if (landmark) drawMarker(observerOf(landmark), "landmark");
}

// --- The gestures of the map --------------------------------------------------------------------

const pointers = new Map<number, { x: number; y: number }>();
let gesture: {
  x: number;
  y: number;
  moved: boolean;
  distance: number;
  zoom: number;
} | null = null;

function zoomAt(x: number, y: number, zoom: number) {
  const before = placeAt(x, y);
  state.view.zoom = Math.max(3, Math.min(15, zoom));
  // Keep the place under the finger where it is.
  const [bx, by] = screen(before);
  const [cx, cy] = toWorld(state.view.lon, state.view.lat, state.view.zoom);
  const [lon, lat] = fromWorld(cx + bx - x, cy + by - y, state.view.zoom);
  state.view.lon = lon;
  state.view.lat = lat;
}

canvas.addEventListener("pointerdown", (e) => {
  canvas.setPointerCapture(e.pointerId);
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  const [a, b] = [...pointers.values()];
  gesture = {
    x: e.clientX,
    y: e.clientY,
    moved: pointers.size > 1,
    distance: b ? Math.hypot(a.x - b.x, a.y - b.y) : 0,
    zoom: state.view.zoom,
  };
});

canvas.addEventListener("pointermove", (e) => {
  const last = pointers.get(e.pointerId);
  if (!last || !gesture) return;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    const distance = Math.hypot(a.x - b.x, a.y - b.y);
    if (gesture.distance > 0) {
      zoomAt(
        (a.x + b.x) / 2,
        (a.y + b.y) / 2,
        gesture.zoom + Math.log2(distance / gesture.distance),
      );
    }
  } else {
    const [cx, cy] = toWorld(state.view.lon, state.view.lat, state.view.zoom);
    const [lon, lat] = fromWorld(
      cx - (e.clientX - last.x),
      cy - (e.clientY - last.y),
      state.view.zoom,
    );
    state.view.lon = lon;
    state.view.lat = lat;
  }
  if (Math.hypot(e.clientX - gesture.x, e.clientY - gesture.y) > 6) gesture.moved = true;
  draw();
});

const endPointer = (e: PointerEvent) => {
  pointers.delete(e.pointerId);
  if (gesture && !gesture.moved && pointers.size === 0) tap(e.clientX, e.clientY);
  if (pointers.size === 0) {
    gesture = null;
    save();
  }
};
canvas.addEventListener("pointerup", endPointer);
canvas.addEventListener("pointercancel", (e) => {
  pointers.delete(e.pointerId);
  gesture = null;
});

canvas.addEventListener(
  "wheel",
  (e) => {
    e.preventDefault();
    zoomAt(e.clientX, e.clientY, state.view.zoom - e.deltaY / 300);
    draw();
    save();
  },
  { passive: false },
);

/** A tap: the landmark, the spot, or a spot on the line. */
function tap(x: number, y: number) {
  const at = placeAt(x, y);
  const name = baseMap ? (nearestPlace(baseMap.places, at)?.name ?? "") : "";
  if (step === "landmark" || picking === "landmark") {
    picking = null;
    updateHint();
    // A tap is a few pixels off a summit, so the landmark snaps to the highest point nearby. The
    // radius follows the zoom: at a large zoom, the user can tap a tower in a valley.
    const metersPerPixel =
      (40_075_016.686 * Math.cos((at.latitudeDeg * Math.PI) / 180)) / (256 * 2 ** state.view.zoom);
    void summitNear(at, Math.min(300, 14 * metersPerPixel)).then((top) =>
      setLandmark({ ...(top ?? at), name, groundM: null }),
    );
    return;
  }
  if (picking === "spot") {
    picking = null;
    updateHint();
    void setSpot({ ...at, name, groundM: null });
    return;
  }
  if (step === "plan" && state.mode === "where" && line) {
    let best: LinePoint | null = null;
    let bestPx = 28;
    for (const s of line.spots) {
      const [sx, sy] = screen(s.place);
      const d = Math.hypot(sx - x, sy - y);
      if (d < bestPx) {
        best = s;
        bestPx = d;
      }
    }
    selectedSpot = best;
    void showSpotCard();
    draw();
  }
}

// --- The steps ----------------------------------------------------------------------------------

function setStep(next: Step) {
  if (next === "plan" && !state.landmark) next = "landmark";
  step = next;
  picking = null;
  for (const s of ["subject", "landmark", "plan"] as const) {
    $(`step-${s}`).classList.toggle("hidden", s !== step);
  }
  for (const b of document.querySelectorAll<HTMLButtonElement>("#steps button")) {
    b.classList.toggle("active", b.dataset.step === step);
  }
  updateHint();
  if (step === "plan") showMode();
  draw();
}

function updateHint() {
  const text =
    step === "landmark" || picking === "landmark"
      ? "Tap the landmark"
      : picking === "spot"
        ? "Tap your spot"
        : step === "plan" && state.mode === "where" && line?.spots.length
          ? "Tap a spot on the line"
          : null;
  hint.textContent = text ?? "";
  hint.classList.toggle("hidden", text === null);
}

function renderSubjects() {
  const box = $("subjects");
  box.replaceChildren(
    ...SUBJECTS.map((s) => {
      const b = document.createElement("button");
      b.innerHTML = `${s.name}<small>${s.note}</small>`;
      b.classList.toggle("active", s.id === state.subject);
      b.addEventListener("click", () => {
        state.subject = s.id;
        matches = [];
        searched = false;
        save();
        renderSubjects();
        renderMatches();
        setStep(state.landmark ? "plan" : "landmark");
      });
      return b;
    }),
  );
}

function landmarkSite(): Site | null {
  const l = state.landmark;
  if (!l || l.groundM === null) return null;
  return {
    latitudeDeg: l.latitudeDeg,
    longitudeDeg: l.longitudeDeg,
    elevationM: l.groundM + state.extraM,
  };
}

function spotSite(): Site | null {
  const s = state.spot;
  if (!s || s.groundM === null) return null;
  return {
    latitudeDeg: s.latitudeDeg,
    longitudeDeg: s.longitudeDeg,
    elevationM: s.groundM + EYE_HEIGHT_M,
  };
}

function renderLandmark() {
  const l = state.landmark;
  const info = $("landmark-info");
  info.classList.toggle("hidden", !l);
  ($("to-plan") as HTMLButtonElement).disabled = !l || l.groundM === null;
  if (!l) return;
  const ground = l.groundM === null ? "…" : `${Math.round(l.groundM)} m`;
  info.innerHTML = `<b>${l.name ? `Near ${l.name}` : "The landmark"}</b>
    <div class="numbers">${l.latitudeDeg.toFixed(4)}°, ${l.longitudeDeg.toFixed(4)}° · ground ${ground}${
      state.extraM ? ` · top ${Math.round((l.groundM ?? 0) + state.extraM)} m` : ""
    }</div>`;
}

async function setLandmark(p: Point) {
  state.landmark = p;
  matches = [];
  searched = false;
  line = null;
  selectedSpot = null;
  save();
  renderLandmark();
  renderMatches();
  draw();
  p.groundM = await groundAt(observerOf(p));
  if (state.landmark !== p) return;
  save();
  renderLandmark();
  if (p.groundM === null)
    $("landmark-info").innerHTML += "<div>No terrain data here (offline?).</div>";
  if (step === "plan") showMode();
}

async function setSpot(p: Point) {
  state.spot = p;
  matches = [];
  searched = false;
  sightText = "";
  save();
  renderSpot();
  renderMatches();
  draw();
  p.groundM = await groundAt(observerOf(p));
  if (state.spot !== p) return;
  save();
  renderSpot();
}

// --- "I stand here" -----------------------------------------------------------------------------

let sightText = "";

async function renderSpot() {
  const box = $("spot-info");
  const spot = state.spot;
  if (!spot) {
    box.innerHTML = "Where do you stand? Pick your spot on the map, or use your location.";
    return;
  }
  const eye = spotSite();
  const target = landmarkSite();
  if (!eye || !target) {
    box.innerHTML = `<b>Your spot</b><div class="numbers">loading the terrain…</div>`;
    return;
  }
  const s = almucantar.sight(eye, target);
  box.innerHTML = `<b>Your spot${spot.name ? ` near ${spot.name}` : ""}</b>
    <div class="numbers">ground ${Math.round(spot.groundM ?? 0)} m · landmark ${(s.distanceM / 1000).toFixed(1)} km
    · azimuth ${s.direction.azimuthDeg.toFixed(1)}° · altitude ${s.direction.altitudeDeg.toFixed(2)}°</div>
    <div id="sight-check" class="numbers">${sightText}</div>`;
  // The view to the landmark over the terrain, if it is not too far for the tiles.
  if (s.distanceM <= 40_000) {
    const middle = destination(observerOf(spot), s.direction.azimuthDeg, s.distanceM / 2);
    const tiles = await gridsAround(middle, s.distanceM / 2 + 1000);
    if (state.spot !== spot || tiles.length === 0) return;
    try {
      const view = almucantar.lineOfSight(eye, target, tiles);
      sightText = view.visible
        ? `<span class="tag good">clear view</span> ${view.clearanceDeg.toFixed(2)}° above the terrain`
        : `<span class="tag bad">blocked</span> by the terrain ${(view.blockingDistanceM / 1000).toFixed(1)} km from you`;
    } catch {
      sightText = "";
    }
    const check = document.getElementById("sight-check");
    if (check) check.innerHTML = sightText;
  }
}

function findDates() {
  const eye = spotSite();
  const target = landmarkSite();
  if (!eye || !target) return;
  const s = almucantar.sight(eye, target);
  const start = Date.now() / 1000;
  matches = almucantar.findAlignments(
    subject().body,
    observerOf(state.spot!),
    s.direction,
    start,
    start + 365 * 86_400,
    { maxSeparationDeg: subject().maxSeparationDeg },
  );
  searched = true;
  renderMatches();
}

/** The northern and southern limits of the declination of each subject, about. */
function declinationLimit(body: number): number {
  if (body === Body.sun) return 23.5;
  if (body === Body.galacticCenter) return -29;
  return 29; // the moon in the years of its major standstill, and the planets less
}

/** Why the search found nothing: the direction can be out of the path of the subject. */
function noMatchReason(): string {
  const eye = spotSite();
  const target = landmarkSite();
  const name = subject().name;
  if (eye && target) {
    const d = almucantar.sight(eye, target).direction;
    const rad = Math.PI / 180;
    const phi = eye.latitudeDeg * rad;
    const dec =
      Math.asin(
        Math.sin(phi) * Math.sin(d.altitudeDeg * rad) +
          Math.cos(phi) * Math.cos(d.altitudeDeg * rad) * Math.cos(d.azimuthDeg * rad),
      ) / rad;
    const limit = declinationLimit(subject().body);
    if (subject().body === Body.galacticCenter && Math.abs(dec - limit) > 3) {
      return `The center of the Milky Way never comes to this direction (declination ${dec.toFixed(0)}°, the center is at ${limit}°). Choose a landmark lower in the south.`;
    }
    if (subject().body !== Body.galacticCenter && Math.abs(dec) > limit) {
      return `The ${name.toLowerCase()} never comes this far ${dec > 0 ? "north" : "south"}: the landmark is at the declination ${dec.toFixed(0)}°, the ${name.toLowerCase()} stays within ±${limit}°. Look from another side of the landmark.`;
    }
  }
  return "No match in the next 12 months. Try another spot or subject.";
}

function renderMatches() {
  const list = $("matches");
  if (matches.length === 0) {
    list.innerHTML = !(state.spot && state.landmark)
      ? ""
      : searched
        ? `<li class="lead">${noMatchReason()}</li>`
        : `<li class="lead">Press "Find the dates" for the next 12 months.</li>`;
    return;
  }
  const spot = observerOf(state.spot!);
  list.replaceChildren(
    ...matches.slice(0, 60).map((m) => {
      const li = document.createElement("li");
      const light = sky(m.unixUtc, spot);
      const tags: string[] = [];
      if (subject().body === Body.moon) {
        const lit = almucantar.moonIlluminatedFraction(m.unixUtc);
        tags.push(
          `<span class="tag ${lit > 0.9 ? "good" : ""}">${Math.round(lit * 100)}% lit</span>`,
        );
      }
      if (subject().body !== Body.sun) {
        tags.push(
          `<span class="tag ${light.dark ? "good" : light.label === "day" ? "bad" : ""}">${light.label}</span>`,
        );
      }
      if (m.body.altitudeDeg < 0) tags.push(`<span class="tag bad">below the horizon</span>`);
      li.innerHTML = `<button>Spots</button><div class="when">${formatTime(m.unixUtc)}</div>
        <div class="numbers">${tags.join("")} ${m.separationDeg.toFixed(2)}° from the top · ${m.body.altitudeDeg.toFixed(1)}° high</div>`;
      li.querySelector("button")!.addEventListener("click", () => {
        lineTime = Math.round(m.unixUtc);
        setMode("where");
      });
      return li;
    }),
  );
}

// --- "Where must I stand?" ----------------------------------------------------------------------

function setMode(mode: Mode) {
  state.mode = mode;
  save();
  showMode();
}

function showMode() {
  $("mode-here").classList.toggle("active", state.mode === "here");
  $("mode-where").classList.toggle("active", state.mode === "where");
  $("here").classList.toggle("hidden", state.mode !== "here");
  $("where").classList.toggle("hidden", state.mode !== "where");
  if (state.mode === "here") void renderSpot();
  else {
    setTimeInputs();
    renderEvents();
    void updateLine();
  }
  updateHint();
  draw();
}

function setTimeInputs() {
  const w = wallTime(zone(), lineTime * 1000);
  ($("date") as HTMLInputElement).value =
    `${w.year}-${String(w.month).padStart(2, "0")}-${String(w.day).padStart(2, "0")}`;
  ($("time") as HTMLInputElement).value = String(w.hour * 60 + w.minute);
  $("time-label").textContent = formatTime(lineTime, false);
}

function timeFromInputs() {
  const [year, month, day] = ($("date") as HTMLInputElement).value.split("-").map(Number);
  const minute = Number(($("time") as HTMLInputElement).value);
  if (!year) return;
  lineTime = fromWallTime(zone(), year, month, day, Math.floor(minute / 60), minute % 60) / 1000;
  $("time-label").textContent = formatTime(lineTime, false);
}

/** The rise and the set of the subject on the date of the inputs, as buttons that set the time. */
function renderEvents() {
  const box = $("events");
  const landmark = state.landmark;
  const [year, month, day] = ($("date") as HTMLInputElement).value.split("-").map(Number);
  if (!landmark || !year) {
    box.replaceChildren();
    return;
  }
  const at = observerOf(landmark);
  const start = fromWallTime(zone(), year, month, day) / 1000;
  const end = fromWallTime(zone(), year, month, day + 1) / 1000;
  const up = (t: number) => almucantar.bodyPosition(subject().body, t, at).altitudeDeg > 0;
  const buttons: HTMLButtonElement[] = [];
  let before = up(start);
  for (let t = start + 300; t <= end; t += 300) {
    const now = up(t);
    if (now !== before) {
      // A few minutes after the rise, or before the set, the subject is just above the horizon.
      const time = now ? t + 300 : t - 600;
      const b = document.createElement("button");
      b.textContent = `${now ? "rises" : "sets"} ${formatTime(t, false)}`;
      b.addEventListener("click", () => {
        lineTime = time;
        setTimeInputs();
        void updateLine();
      });
      buttons.push(b);
    }
    before = now;
  }
  box.replaceChildren(...buttons);
  if (buttons.length === 0) {
    box.textContent = `The ${subject().name.toLowerCase()} ${before ? "is up all day" : "stays below the horizon"}.`;
  }
}

let lineGeneration = 0;
/** For each spot of the line: true if it sees the landmark over the terrain. */
let spotViews = new Map<LinePoint, boolean>();

async function updateLine() {
  const target = landmarkSite();
  const info = $("line-info");
  if (!target || !state.landmark) {
    info.textContent = "The landmark is loading…";
    return;
  }
  const generation = ++lineGeneration;
  const body = almucantar.bodyPosition(subject().body, lineTime, observerOf(state.landmark));
  if (body.altitudeDeg < -0.5) {
    line = null;
    selectedSpot = null;
    $("spot-card").classList.add("hidden");
    info.innerHTML = `<b>The ${subject().name.toLowerCase()} is below the horizon</b>
      <div class="numbers">Choose a time when it is up, for example its rise or set above.</div>`;
    updateHint();
    draw();
    return;
  }
  const tiles = await gridsAround(observerOf(state.landmark), LINE_DISTANCE_M);
  if (generation !== lineGeneration) return;
  try {
    line = almucantar.alignmentLine(subject().body, target, lineTime, tiles, {
      maxDistanceM: LINE_DISTANCE_M,
      stepM: 50,
      eyeHeightM: EYE_HEIGHT_M,
    });
  } catch (e) {
    line = null;
    info.textContent = e instanceof Error ? e.message : String(e);
    draw();
    return;
  }
  selectedSpot = null;
  $("spot-card").classList.add("hidden");
  spotViews = new Map(
    line.spots.map((s) => {
      try {
        const eye = {
          ...s.place,
          elevationM: s.groundElevationM + EYE_HEIGHT_M,
        };
        return [s, almucantar.lineOfSight(eye, target, tiles).visible];
      } catch {
        return [s, false];
      }
    }),
  );
  const clear = [...spotViews.values()].filter(Boolean).length;
  const name = subject().name;
  const light = sky(lineTime, observerOf(state.landmark));
  info.innerHTML = `<b>${name} ${body.altitudeDeg.toFixed(1)}° high</b>
    · azimuth ${body.azimuthDeg.toFixed(0)}° · ${light.label}
    <div class="numbers">${
      line.spots.length
        ? `${line.spots.length} ${line.spots.length === 1 ? "spot" : "spots"} on the line, ${clear} with a clear view (the full dots): there, the landmark is in front of the ${name.toLowerCase()}.`
        : tiles.length
          ? "No spot within 30 km: the ground is too high or too low on the whole line."
          : "No terrain data (offline?): the line has no spots."
    }</div>`;
  updateHint();
  draw();
}

async function showSpotCard() {
  const card = $("spot-card");
  const s = selectedSpot;
  card.classList.toggle("hidden", !s);
  if (!s) return;
  card.innerHTML = `<b>A spot ${(s.distanceM / 1000).toFixed(2)} km from the landmark</b>
    <div class="numbers">${s.place.latitudeDeg.toFixed(5)}°, ${s.place.longitudeDeg.toFixed(5)}° · ground ${Math.round(s.groundElevationM)} m
    · look ${s.body.azimuthDeg.toFixed(1)}°, ${s.body.altitudeDeg.toFixed(2)}° up</div>
    <div id="spot-view" class="numbers">checking the view…</div>
    <div class="row"><button id="stand-here">Stand here</button></div>`;
  $("stand-here").addEventListener("click", () => {
    const name = baseMap ? (nearestPlace(baseMap.places, s.place)?.name ?? "") : "";
    void setSpot({ ...s.place, name, groundM: null });
    setMode("here");
  });
  const target = landmarkSite();
  if (!target) return;
  const tiles = await gridsAround(observerOf(state.landmark!), LINE_DISTANCE_M);
  if (selectedSpot !== s) return;
  const view = almucantar.lineOfSight(
    { ...s.place, elevationM: s.groundElevationM + EYE_HEIGHT_M },
    target,
    tiles,
  );
  $("spot-view").innerHTML = view.visible
    ? `<span class="tag good">clear view</span> to the landmark`
    : `<span class="tag bad">blocked</span> by the terrain ${(view.blockingDistanceM / 1000).toFixed(1)} km from the spot`;
}

// --- The search ---------------------------------------------------------------------------------

const search = $<HTMLInputElement>("search");
const results = $("results");

search.addEventListener("input", () => {
  const found = baseMap ? searchPlaces(baseMap.places, search.value, 6) : [];
  results.classList.toggle("hidden", found.length === 0);
  results.replaceChildren(
    ...found.map((p) => {
      const b = document.createElement("button");
      b.innerHTML = `${p.name} <small>${p.country}</small>`;
      b.addEventListener("click", () => {
        state.view = {
          lon: p.lon,
          lat: p.lat,
          zoom: Math.max(state.view.zoom, 11),
        };
        search.value = "";
        results.classList.add("hidden");
        save();
        draw();
      });
      return b;
    }),
  );
});

// --- Wiring -------------------------------------------------------------------------------------

for (const b of document.querySelectorAll<HTMLButtonElement>("#steps button")) {
  b.addEventListener("click", () => setStep(b.dataset.step as Step));
}
$("to-plan").addEventListener("click", () => setStep("plan"));
$<HTMLInputElement>("landmark-height").value = String(state.extraM);
$<HTMLInputElement>("landmark-height").addEventListener("input", (e) => {
  state.extraM = Math.max(0, Number((e.target as HTMLInputElement).value) || 0);
  matches = [];
  searched = false;
  save();
  renderLandmark();
  renderMatches();
});
$("mode-here").addEventListener("click", () => setMode("here"));
$("mode-where").addEventListener("click", () => setMode("where"));
$("pick-spot").addEventListener("click", () => {
  picking = "spot";
  updateHint();
});
$("use-location").addEventListener("click", () => {
  navigator.geolocation?.getCurrentPosition(
    (position) => {
      const at = {
        latitudeDeg: position.coords.latitude,
        longitudeDeg: position.coords.longitude,
      };
      void setSpot({ ...at, name: "your location", groundM: null });
    },
    () => ($("spot-info").textContent = "No location access. Pick your spot on the map."),
  );
});
$("find-dates").addEventListener("click", findDates);
$("date").addEventListener("change", () => {
  timeFromInputs();
  renderEvents();
  void updateLine();
});
$("time").addEventListener("input", () => {
  timeFromInputs();
  void updateLine();
});
window.addEventListener("resize", resize);

if ("serviceWorker" in navigator && location.protocol !== "http:") {
  void navigator.serviceWorker.register("./sw.js");
}

renderSubjects();
renderLandmark();
renderMatches();
setStep(step);
resize();

baseMap = decodeBaseMap(await (await fetch(baseMapUrl)).json());
renderLandmark();
draw();
// The height of a saved point is known again once its tile is in memory.
if (state.landmark && state.landmark.groundM === null) void setLandmark(state.landmark);
if (state.spot) void renderSpot();
