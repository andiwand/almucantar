// The Sonnseite PWA: the sky view with the path of the sun, the horizon, and the time when the sun
// is in the direction of the crosshair. The core comes from @almucantar/core (WebAssembly).

import { DayKind, load } from "@almucantar/core";
import type {
  AzimuthCrossing,
  HeadingEstimate,
  HorizonProfile,
  Horizontal,
  Polyline,
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
import { css, mix, skyColors } from "@almucantar/web-shared/panorama";
import { DEFAULT_PLACE, gpsPlace, loadPlace } from "@almucantar/web-shared/place";
import type { StoredPlace } from "@almucantar/web-shared/place";
import { setupPlacePanel } from "@almucantar/web-shared/placePanel";
import {
  Terrain,
  TileCache,
  cacheStorageStore,
  decodePng,
  localStorageIndex,
} from "@almucantar/web-shared/terrain";
import type { TerrainState } from "@almucantar/web-shared/terrain";
import { fromWallTime, startOfDay, wallTime } from "@almucantar/web-shared/zone";
import baseMapUrl from "../../../../assets/basemap/basemap.json?url";

const almucantar = await load();

// --- State --------------------------------------------------------------------------------------

// The place: the location of the device, a place that the user dropped on the map, or Vienna
// (@almucantar/web-shared/place).
const PLACE_KEY = "sonnseite-place";
let place = loadPlace(PLACE_KEY);
let observer = place.observer;
let placeText = place.name;
let zone = place.timeZone;

let deviceToEnu: Quaternion | null = null;
let sensorsActive = false;
// The desktop view, if there are no sensors: the user drags it.
let dragAzimuth = 180;
let dragPitch = 20;

// The heading: the browser gives a magnetic heading, so the offset starts at the declination.
// A tap on the sun corrects it.
let heading: HeadingEstimate = { offsetDeg: 0, varianceDeg2: 15 * 15 };

let dayOffset = 0;
let minuteOfDay: number | null = null; // null: now
let cameraOn = false;
let cameraStarting = false;
let aligning = false;
let fovDeg = 65;

const MAX_CROSSHAIR_ALTITUDE_DEG = 75;

// The terrain horizon. It needs the place, so it starts when the location arrives.
const TERRAIN_SETTING = "sonnseite-terrain-on";
let terrainOn = localStorage.getItem(TERRAIN_SETTING) !== "false";
let terrainVersion = 0;
const tileCache = new TileCache({
  store: cacheStorageStore(),
  ...localStorageIndex(),
  fetch: (url, init) => fetch(url, init),
  now: () => Date.now(),
});
const terrain = new Terrain({
  almucantar,
  cache: tileCache,
  decode: decodePng,
  onChange: (state: TerrainState) => {
    if (state.kind === "ready" || state.kind === "off") terrainVersion++;
  },
});
let hasLocation = false;

/** The profile of the terrain, or null while it is not ready or switched off. */
const terrainProfile = () => (terrain.state.kind === "ready" ? terrain.state.profile : null);

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

// --- Elements -----------------------------------------------------------------------------------

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const canvas = $<HTMLCanvasElement>("sky");
const video = $<HTMLVideoElement>("camera");
const status = $("status");
const message = $("message");
const readout = $("readout");
const ctx = canvas.getContext("2d")!;

function showMessage(text: string | null) {
  message.textContent = text ?? "";
  message.classList.toggle("hidden", text === null);
}

// --- Time ---------------------------------------------------------------------------------------

/** The start of a day in the time zone of the place, in Unix seconds. */
function startOfLocalDay(offsetDays: number): number {
  return startOfDay(zone, Date.now(), offsetDays);
}

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

// --- The day (calculated once per day and place) --------------------------------------------------

interface Day {
  key: string;
  start: number;
  end: number;
  path: Horizontal[];
  daySegments: Horizontal[][];
  /** The parts of the path above the horizon but behind the terrain. */
  hiddenSegments: Horizontal[][];
  hours: { direction: Horizontal; label: string }[];
  summary: string;
  guides: Horizontal[][];
}

let day: Day | null = null;

function computeDay(): Day {
  const start = startOfLocalDay(dayOffset);
  const end = startOfLocalDay(dayOffset + 1);
  const key = `${start}:${zone}:${observer.latitudeDeg}:${observer.longitudeDeg}:${terrainVersion}`;
  if (day?.key === key) return day;
  const profile = terrainProfile();
  const points = almucantar.sunPath(observer, start, end);
  const segments = (
    pts: typeof points,
    keep: (h: Horizontal) => boolean = (h) => h.altitudeDeg >= 0,
  ) => {
    const result: Horizontal[][] = [];
    let current: Horizontal[] = [];
    for (const p of pts) {
      if (keep(p.position)) current.push(p.position);
      else if (current.length) {
        if (current.length > 1) result.push(current);
        current = [];
      }
    }
    if (current.length > 1) result.push(current);
    return result;
  };
  // With the terrain, the parts of the path behind the terrain are a separate, dimmer line.
  const terrainAt = (h: Horizontal) => {
    const t = profile ? almucantar.terrainAltitudeDeg(profile, h.azimuthDeg) : NaN;
    return Number.isNaN(t) ? 0 : t;
  };
  const visible = (h: Horizontal) => h.altitudeDeg >= 0 && h.altitudeDeg >= terrainAt(h);
  const hidden = (h: Horizontal) => h.altitudeDeg >= 0 && h.altitudeDeg < terrainAt(h);
  const events = almucantar.sunEvents(observer, start, end);
  const summary =
    events.kind === DayKind.polarDay
      ? "Polar day"
      : events.kind === DayKind.polarNight
        ? "Polar night"
        : events.crossings
            .map((c) => `${c.rising ? "Sunrise" : "Sunset"} ${hhmm(c.unixUtc)}`)
            .join(" · ");
  let terrainSummary = "";
  if (profile) {
    const t = almucantar.terrainEvents(observer, profile, start, end);
    terrainSummary =
      t.kind === DayKind.polarDay
        ? "Over the terrain all day"
        : t.kind === DayKind.polarNight
          ? "Behind the terrain all day"
          : "Over the terrain: " +
            t.crossings.map((c) => `${c.rising ? "↑" : "↓"} ${hhmm(c.unixUtc)}`).join(" ");
  }
  const year = wallTime(zone, start * 1000).year;
  const guides = [6, 12].flatMap((month) => {
    const s = fromWallTime(zone, year, month, 21) / 1000;
    return segments(almucantar.sunPath(observer, s, s + 86400));
  });
  day = {
    key,
    start,
    end,
    path: points.map((p) => p.position),
    daySegments: segments(points, visible),
    hiddenSegments: profile ? segments(points, hidden) : [],
    hours: points
      .filter((p) => (p.unixUtc - start) % 3600 === 0 && p.position.altitudeDeg > -6)
      .map((p) => ({
        direction: p.position,
        label: String(wallTime(zone, p.unixUtc * 1000).hour).padStart(2, "0"),
      })),
    summary: terrainSummary ? `${summary}<br>${terrainSummary}` : summary,
    guides,
  };
  return day;
}

// --- The view -----------------------------------------------------------------------------------

function currentView(): View {
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  const fov = cameraOn ? fovDeg : width < height ? 60 : 90;
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

function strokeLines(lines: Polyline[], color: string, width: number, dash: number[] = []) {
  ctx.strokeStyle = color;
  ctx.lineWidth = width;
  ctx.setLineDash(dash);
  for (const line of lines) {
    if (line.length < 2) continue;
    ctx.beginPath();
    ctx.moveTo(line[0].x, line[0].y);
    for (const p of line.slice(1)) ctx.lineTo(p.x, p.y);
    ctx.stroke();
  }
  ctx.setLineDash([]);
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

/**
 * Draws the moon at (x, y) with the lit part toward the sun. The direction of the sun on the
 * screen comes from a point 1° from the moon toward the sun, see `towardSun`.
 */
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
    // The y axis of the outline is perpendicular to the direction of the sun.
    const sx = x + (ux * px - uy * py) * r;
    const sy = y + (uy * px + ux * py) * r;
    if (i === 0) ctx.moveTo(sx, sy);
    else ctx.lineTo(sx, sy);
  });
  ctx.closePath();
  ctx.fill();
}

/**
 * Draws the terrain horizon: a line, and in the sky view (without the camera) a light fill below
 * it. The fill is a band down to 10° below the terrain, in steps of 1° of azimuth.
 */
function drawTerrain(view: View, profile: HorizonProfile) {
  const line: Horizontal[] = [];
  for (let az = 0; az <= 360; az++) {
    const altitude = almucantar.terrainAltitudeDeg(profile, az % 360);
    line.push({ azimuthDeg: az % 360, altitudeDeg: Number.isNaN(altitude) ? 0 : altitude });
  }
  if (!cameraOn) {
    // One path and one fill for all bands, so the edges between the bands do not show.
    ctx.fillStyle = "rgba(120, 140, 110, 0.25)";
    ctx.beginPath();
    for (let i = 0; i < 360; i++) {
      const corners = [
        line[i],
        line[i + 1],
        { azimuthDeg: line[i + 1].azimuthDeg, altitudeDeg: line[i + 1].altitudeDeg - 10 },
        { azimuthDeg: line[i].azimuthDeg, altitudeDeg: line[i].altitudeDeg - 10 },
      ].map((h) => almucantar.project(view, h));
      if (!corners.every((p) => p.valid)) continue;
      corners.forEach((p, k) =>
        k ? ctx.lineTo(p.point.x, p.point.y) : ctx.moveTo(p.point.x, p.point.y),
      );
      ctx.closePath();
    }
    ctx.fill("nonzero");
  }
  strokeLines(almucantar.projectLine(view, line), "rgba(190, 230, 170, 0.9)", 2);
}

/**
 * The sky view without the camera: a sky in the colors of the height of the sun, a glow around
 * the sun, and the ground below the horizon, light by day.
 */
function drawSkyView(view: View, sun: Horizontal, w: number, h: number) {
  const [top, low] = skyColors(sun.altitudeDeg);
  const day = Math.min(1, Math.max(0, (sun.altitudeDeg + 6) / 12));
  // The horizon below the middle of the screen gives the end of the gradient.
  const middle = almucantar.unproject(view, { x: w / 2, y: h / 2 });
  const azimuth = Number.isFinite(middle.azimuthDeg) ? middle.azimuthDeg : 180;
  const horizon = almucantar.project(view, { azimuthDeg: azimuth, altitudeDeg: 0 });
  const zenith = almucantar.project(view, { azimuthDeg: azimuth, altitudeDeg: 60 });
  const y0 = zenith.valid ? zenith.point.y : 0;
  const y1 = horizon.valid ? horizon.point.y : h;
  const sky = ctx.createLinearGradient(0, Math.min(y0, y1 - 1), 0, y1);
  sky.addColorStop(0, css(top));
  sky.addColorStop(1, css(low));
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, w, h);
  const s = almucantar.project(view, sun);
  if (s.valid && sun.altitudeDeg > -10) {
    const strength = Math.min(1, (sun.altitudeDeg + 10) / 14);
    const glow = ctx.createRadialGradient(
      s.point.x,
      s.point.y,
      0,
      s.point.x,
      s.point.y,
      Math.max(w, h) * 0.6,
    );
    glow.addColorStop(0, `rgba(255, 220, 150, ${0.45 * strength})`);
    glow.addColorStop(0.35, `rgba(255, 180, 90, ${0.12 * strength})`);
    glow.addColorStop(1, "rgba(255, 180, 90, 0)");
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, w, h);
  }
  // The ground: each piece of the horizon line moved far toward the nadir closes the area below.
  const below = almucantar.project(view, { azimuthDeg: azimuth, altitudeDeg: -30 });
  let dx = 0;
  let dy = 1;
  if (horizon.valid && below.valid) {
    const length = Math.hypot(below.point.x - horizon.point.x, below.point.y - horizon.point.y);
    if (length > 1) {
      dx = (below.point.x - horizon.point.x) / length;
      dy = (below.point.y - horizon.point.y) / length;
    }
  }
  const far = (w + h) * 2;
  ctx.fillStyle = css(mix([10, 14, 22], [214, 204, 186], day));
  for (const line of almucantar.horizonLine(view)) {
    if (line.length < 2) continue;
    ctx.beginPath();
    line.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
    for (let i = line.length - 1; i >= 0; i--)
      ctx.lineTo(line[i].x + dx * far, line[i].y + dy * far);
    ctx.closePath();
    ctx.fill();
  }
}

function draw(
  view: View,
  d: Day,
  sun: Horizontal,
  moon: MoonAt | null,
  center: Horizontal,
  crossing: AzimuthCrossing | null,
) {
  const w = canvas.clientWidth;
  const h = canvas.clientHeight;
  ctx.clearRect(0, 0, w, h);
  if (!cameraOn) drawSkyView(view, sun, w, h);
  // In the sky view the sky is bright by day, so the helpers are dark; on the camera image they
  // are light.
  const bright = !cameraOn && sun.altitudeDeg > -4;
  const helper = bright ? "#1f4f8f" : "#7fd8ff";
  const ink = bright ? "#1f2633" : "white";
  ctx.textAlign = "center";
  ctx.shadowColor = bright ? "rgba(255, 255, 255, 0.85)" : "black";
  ctx.shadowBlur = 4;
  ctx.fillStyle = ink;
  ctx.font = "bold 14px system-ui";

  const profile = terrainProfile();
  strokeLines(
    almucantar.horizonLine(view),
    profile ? "rgba(255,255,255,0.35)" : "rgba(255,255,255,0.8)",
    profile ? 1 : 1.5,
  );
  if (profile) drawTerrain(view, profile);
  for (const [az, label] of [
    [0, "N"],
    [90, "E"],
    [180, "S"],
    [270, "W"],
  ] as const) {
    uprightLabel(view, { azimuthDeg: az, altitudeDeg: 0 }, label, 14);
  }
  for (const g of d.guides)
    strokeLines(almucantar.projectLine(view, g), "rgba(255,255,255,0.35)", 1.5, [4, 4]);
  strokeLines(almucantar.projectLine(view, d.path), "rgba(255,200,61,0.5)", 2, [8, 6]);
  for (const s of d.hiddenSegments)
    strokeLines(almucantar.projectLine(view, s), "rgba(255,200,61,0.45)", 2, [3, 5]);
  for (const s of d.daySegments) {
    const lines = almucantar.projectLine(view, s);
    strokeLines(lines, "rgba(180,95,6,0.35)", 6);
    strokeLines(lines, "rgba(255,200,61,0.25)", 12);
    strokeLines(lines, "#ffc83d", 3.5);
  }
  for (const mark of d.hours) {
    const p = almucantar.project(view, mark.direction);
    if (!p.valid) continue;
    ctx.beginPath();
    ctx.arc(p.point.x, p.point.y, 4, 0, 2 * Math.PI);
    ctx.fillStyle = "#ffc83d";
    ctx.fill();
    ctx.fillStyle = ink;
    uprightLabel(view, mark.direction, mark.label, 10);
  }

  if (crossing) {
    const end = crossing.sun.altitudeDeg;
    const steps = Math.max(2, Math.ceil(Math.abs(end)));
    const vertical = Array.from({ length: steps + 1 }, (_, i) => ({
      azimuthDeg: center.azimuthDeg,
      altitudeDeg: (end * i) / steps,
    }));
    strokeLines(almucantar.projectLine(view, vertical), helper, 1.5, [6, 5]);
    const p = almucantar.project(view, crossing.sun);
    if (p.valid) {
      ctx.beginPath();
      ctx.arc(p.point.x, p.point.y, 9, 0, 2 * Math.PI);
      ctx.strokeStyle = helper;
      ctx.lineWidth = 2.5;
      ctx.stroke();
    }
  }

  // The moon with its phase. The faint disc shows the dark part.
  if (moon) {
    const m = almucantar.project(view, moon.direction);
    if (m.valid) {
      drawMoon(view, moon, sun, m.point.x, m.point.y, 11);
      ctx.fillStyle = ink;
      uprightLabel(view, moon.direction, "moon", 16);
    }
  }

  const s = almucantar.project(view, sun);
  if (s.valid) {
    ctx.save();
    ctx.shadowColor = "rgba(255, 190, 60, 0.95)";
    ctx.shadowBlur = 26;
    ctx.beginPath();
    ctx.arc(s.point.x, s.point.y, 14, 0, 2 * Math.PI);
    ctx.fillStyle = "#fff4d2";
    ctx.fill();
    ctx.restore();
  }

  ctx.shadowBlur = 0;
  ctx.strokeStyle = ink;
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.moveTo(w / 2 - 16, h / 2);
  ctx.lineTo(w / 2 + 16, h / 2);
  ctx.moveTo(w / 2, h / 2 - 16);
  ctx.lineTo(w / 2, h / 2 + 16);
  ctx.stroke();
}

// --- The dates for the crosshair (debounced, because the search takes some milliseconds) ----------

let datesKey = "";
let datesText = "";
let datesTimer = 0;

function scheduleDates(center: Horizontal | null) {
  // The readout does not show the dates while the crosshair is not usable. The state stays, so
  // one unusable frame from sensor noise does not clear the text or start the search again.
  if (!center) return;
  const start = startOfLocalDay(0);
  const key = `${start}:${Math.round(center.azimuthDeg * 2)}:${Math.round(center.altitudeDeg * 2)}:${observer.latitudeDeg}:${observer.longitudeDeg}`;
  if (key === datesKey) return;
  datesKey = key;
  clearTimeout(datesTimer);
  if (center.altitudeDeg < 0) {
    datesText = "";
    return;
  }
  // The old text stays until the new result is ready, so sensor noise does not make it flicker.
  datesTimer = window.setTimeout(() => {
    const dates = almucantar.findDates(observer, center, start, start + 365 * 86400);
    datesText = dates.length
      ? "In the crosshair: " +
        dates
          .map(
            (m) =>
              `${new Date(m.unixUtc * 1000).toLocaleDateString([], { day: "numeric", month: "short", timeZone: zone })} ${hhmm(m.unixUtc)}`,
          )
          .join(", ")
      : "";
  }, 400);
}

// --- The frame ----------------------------------------------------------------------------------

let lastView: View | null = null;

function frame() {
  const dpr = window.devicePixelRatio || 1;
  const [width, height] = [canvas.clientWidth, canvas.clientHeight].map((v) => Math.round(v * dpr));
  if (canvas.width !== width || canvas.height !== height)
    [canvas.width, canvas.height] = [width, height];
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  const d = computeDay();
  const view = currentView();
  lastView = view;
  const time = selectedTime();
  const sun = almucantar.sunPosition(time, observer);
  const center = almucantar.unproject(view, { x: view.camera.cx, y: view.camera.cy });
  const usable = Math.abs(center.altitudeDeg) <= MAX_CROSSHAIR_ALTITUDE_DEG;
  const crossing = usable
    ? (almucantar.findAzimuthTimes(observer, center.azimuthDeg, d.start, d.end)[0] ?? null)
    : null;
  scheduleDates(usable ? center : null);

  const moonFraction = almucantar.moonIlluminatedFraction(time);
  const moon: MoonAt | null =
    moonFraction >= 0.02
      ? { direction: almucantar.moonPosition(time, observer), fraction: moonFraction }
      : null;
  draw(view, d, sun, moon, center, crossing);

  const sigma = Math.sqrt(heading.varianceDeg2);
  const headingText = !sensorsActive
    ? `<span class="heading-fair">No sensors: drag to look around</span>`
    : sigma > 10
      ? `<span class="heading-poor">Heading ±${sigma.toFixed(0)}° (align with the sun)</span>`
      : sigma > 3
        ? `<span class="heading-fair">Heading ±${sigma.toFixed(0)}°</span>`
        : `<span class="heading-good">Heading ±${sigma.toFixed(1)}°</span>`;
  const terrainText = terrainStatus();
  status.innerHTML =
    `<b>${placeText}</b><br>${headingText}<br>${d.summary}` +
    (terrainText ? `<br><span class="heading-fair">${terrainText}</span>` : "");

  readout.innerHTML = !usable
    ? "Hold the phone up to see when the sun is in that direction."
    : crossing === null
      ? "The sun does not reach this direction on this day."
      : `<div class="time">${hhmm(crossing.unixUtc)}</div>` +
        (crossing.sun.altitudeDeg >= 0
          ? `The sun is in this direction, ${crossing.sun.altitudeDeg.toFixed(0)}° high`
          : "The sun is in this direction below the horizon") +
        (datesText ? `<div class="dates">${datesText}</div>` : "");

  $("date-label").textContent = new Date(startOfLocalDay(dayOffset) * 1000).toLocaleDateString([], {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: zone,
  });
  $("time-label").textContent =
    minuteOfDay === null ? `${hhmm(Date.now() / 1000)} (now)` : hhmm(time);
  $("fov-label").textContent = `${fovDeg}°`;
  requestAnimationFrame(frame);
}

// --- Sensors, location, camera ------------------------------------------------------------------

function onOrientation(event: DeviceOrientationEvent & { webkitCompassHeading?: number }) {
  if (event.alpha === null || event.beta === null || event.gamma === null) return;
  const compass = event.webkitCompassHeading;
  // iOS gives -1 while the compass is not calibrated. Without a compass and without an absolute
  // event, the heading is relative: the view follows the phone, and an alignment corrects it.
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
      // Not "in window": the DOM types have the property, so TypeScript would narrow window to never.
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

/** Changes the place: the day, the terrain, and the declination follow. */
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

/** The browser heading is magnetic, so the offset is the declination until the user aligns. */
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

let dragging: { x: number; y: number } | null = null;
canvas.addEventListener("pointerdown", (e) => {
  if (aligning && lastView) {
    alignWithSun(lastView, e.clientX, e.clientY);
    return;
  }
  dragging = { x: e.clientX, y: e.clientY };
});
for (const type of ["pointerup", "pointercancel"])
  window.addEventListener(type, () => (dragging = null));
window.addEventListener("pointermove", (e) => {
  if (!dragging || sensorsActive) return;
  dragAzimuth = (dragAzimuth - (e.clientX - dragging.x) * 0.15 + 360) % 360;
  dragPitch = Math.max(-80, Math.min(80, dragPitch + (e.clientY - dragging.y) * 0.15));
  dragging = { x: e.clientX, y: e.clientY };
});

function alignWithSun(view: View, x: number, y: number) {
  aligning = false;
  $("align-button").textContent = "Align with the sun";
  const sun = almucantar.sunPosition(Date.now() / 1000, observer);
  const tapped = almucantar.unproject(view, { x, y });
  if (Math.abs(tapped.altitudeDeg - sun.altitudeDeg) > 5) {
    showMessage("That point is not at the height of the sun. Tap the sun itself.");
    return;
  }
  let measurement;
  try {
    measurement = almucantar.headingFromDirection(view, { x, y }, sun.azimuthDeg, 0.5);
  } catch (e) {
    // The core rejects a direction that gives no heading (for example near the zenith).
    if (!(e instanceof RangeError)) throw e;
    showMessage(`No alignment: ${e.message}.`);
    return;
  }
  heading = almucantar.headingUpdate(heading, measurement);
  showMessage(`Heading aligned with the sun (±${Math.sqrt(heading.varianceDeg2).toFixed(1)}°).`);
}

$("start").addEventListener("click", () => void startSensors());
$("camera-button").addEventListener("click", () => void toggleCamera());
$("time-button").addEventListener("click", () => $("time-panel").classList.toggle("hidden"));
$("align-button").addEventListener("click", () => {
  const sunUp = almucantar.sunPosition(Date.now() / 1000, observer).altitudeDeg > 0;
  if (!sunUp) return showMessage("The sun is below the horizon.");
  if (!sensorsActive)
    return showMessage("The alignment needs the orientation sensors: press Start sensors.");
  if (!cameraOn) void toggleCamera();
  aligning = !aligning;
  $("align-button").textContent = aligning ? "Cancel" : "Align with the sun";
  showMessage(aligning ? "Tap the sun in the camera image." : null);
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
const terrainBox = $<HTMLInputElement>("terrain-on");
terrainBox.checked = terrainOn;
terrainBox.addEventListener("change", () => {
  terrainOn = terrainBox.checked;
  localStorage.setItem(TERRAIN_SETTING, String(terrainOn));
  if (!terrainOn) terrain.stop();
  else if (hasLocation) terrain.update(observer);
});
$<HTMLInputElement>("fov").addEventListener("input", (e) => {
  fovDeg = Number((e.target as HTMLInputElement).value);
});

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
