// The Sunradar PWA: the sky view with the path of the sun, the horizon, and the time when the sun
// is in the direction of the crosshair. The core comes from @almucantar/core (WebAssembly).

import { DayKind, load } from "@almucantar/core";
import type {
  AzimuthCrossing,
  HeadingEstimate,
  HorizonProfile,
  Horizontal,
  Observer,
  Polyline,
  Quaternion,
  View,
} from "@almucantar/core";
import { litOutline, towardSun } from "./moon";
import type { MoonAt } from "./moon";
import { fromDeviceOrientation, screenRotation, upright, withCompass } from "./orientation";
import {
  Terrain,
  TileCache,
  cacheStorageStore,
  decodePng,
  keepOffline,
  localStorageIndex,
} from "./terrain";
import type { TerrainState } from "./terrain";
import { browserTimeZone, fromWallTime, startOfDay, wallTime } from "./zone";
import {
  decodeBaseMap,
  drawBaseMap,
  fromWorld,
  nearestPlace,
  screenToPlace,
  searchPlaces,
  timeZoneAt,
  toWorld,
} from "./basemap";
import type { BaseMap, MapView } from "./basemap";
import baseMapUrl from "../../assets/basemap/basemap.json?url";

const almucantar = await load();

// --- State --------------------------------------------------------------------------------------

// The place: the location of the device, a place that the user dropped on the map, or Vienna.
// The app keeps the last place, so it starts at that place also offline and without a location.
// The times are in the time zone of the place, because a dropped place can be in another zone.
interface StoredPlace {
  observer: Observer;
  name: string;
  timeZone: string;
  manual: boolean;
}
const PLACE_KEY = "sunradar-place";
const DEFAULT_PLACE: StoredPlace = {
  observer: { latitudeDeg: 48.2082, longitudeDeg: 16.3738 },
  name: "Vienna · no location",
  timeZone: "Europe/Vienna",
  manual: false,
};
function loadPlace(): StoredPlace {
  try {
    const stored = JSON.parse(localStorage.getItem(PLACE_KEY) ?? "null") as StoredPlace | null;
    if (stored && Number.isFinite(stored.observer?.latitudeDeg)) {
      new Intl.DateTimeFormat("en-US", { timeZone: stored.timeZone }); // throws if not valid
      return stored;
    }
  } catch {
    // A damaged or old entry: use the default.
  }
  return DEFAULT_PLACE;
}
let place = loadPlace();
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
const TERRAIN_SETTING = "sunradar-terrain-on";
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
  if (!cameraOn) {
    ctx.fillStyle = "#0b1a30";
    ctx.fillRect(0, 0, w, h);
  }
  ctx.textAlign = "center";
  ctx.shadowColor = "black";
  ctx.shadowBlur = 4;
  ctx.fillStyle = "white";
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
    strokeLines(lines, "rgba(255,200,61,0.25)", 9);
    strokeLines(lines, "#ffc83d", 3);
  }
  for (const mark of d.hours) {
    const p = almucantar.project(view, mark.direction);
    if (!p.valid) continue;
    ctx.beginPath();
    ctx.arc(p.point.x, p.point.y, 4, 0, 2 * Math.PI);
    ctx.fillStyle = "#ffc83d";
    ctx.fill();
    ctx.fillStyle = "white";
    uprightLabel(view, mark.direction, mark.label, 10);
  }

  if (crossing) {
    const end = crossing.sun.altitudeDeg;
    const steps = Math.max(2, Math.ceil(Math.abs(end)));
    const vertical = Array.from({ length: steps + 1 }, (_, i) => ({
      azimuthDeg: center.azimuthDeg,
      altitudeDeg: (end * i) / steps,
    }));
    strokeLines(almucantar.projectLine(view, vertical), "#7fd8ff", 1.5, [6, 5]);
    const p = almucantar.project(view, crossing.sun);
    if (p.valid) {
      ctx.beginPath();
      ctx.arc(p.point.x, p.point.y, 9, 0, 2 * Math.PI);
      ctx.strokeStyle = "#7fd8ff";
      ctx.lineWidth = 2.5;
      ctx.stroke();
    }
  }

  // The moon with its phase. The faint disc shows the dark part.
  if (moon) {
    const m = almucantar.project(view, moon.direction);
    if (m.valid) {
      drawMoon(view, moon, sun, m.point.x, m.point.y, 11);
      ctx.fillStyle = "white";
      uprightLabel(view, moon.direction, "moon", 16);
    }
  }

  const s = almucantar.project(view, sun);
  if (s.valid) {
    ctx.beginPath();
    ctx.arc(s.point.x, s.point.y, 14, 0, 2 * Math.PI);
    ctx.fillStyle = "#ffe08a";
    ctx.fill();
  }

  ctx.shadowBlur = 0;
  ctx.strokeStyle = "white";
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

function gpsPlace(position: GeolocationPosition): StoredPlace {
  const { latitude, longitude, accuracy } = position.coords;
  return {
    observer: { latitudeDeg: latitude, longitudeDeg: longitude },
    name: `${latitude.toFixed(3)}°, ${longitude.toFixed(3)}° · ±${Math.round(accuracy)} m`,
    timeZone: browserTimeZone(),
    manual: false,
  };
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
  renderPlaceCard();
  // Only the declination changes, so an alignment before stays.
  const old = declination;
  declination = almucantar.magneticDeclinationDeg(observer, Date.now() / 1000);
  heading = { ...heading, offsetDeg: heading.offsetDeg + declination - old };
  // The place panel can be open, and "Keep offline" needs a place.
  renderKept();
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

// --- The place panel: the offline map (Natural Earth) and the search ------------------------------

let baseMap: BaseMap | null = null;
let mapView: MapView = { lon: observer.longitudeDeg, lat: observer.latitudeDeg, zoom: 5 };
const mapCanvas = $<HTMLCanvasElement>("place-map");
const searchInput = $<HTMLInputElement>("place-search");
const results = $("place-results");

async function loadBaseMap(): Promise<BaseMap | null> {
  if (!baseMap) {
    try {
      baseMap = decodeBaseMap(await (await fetch(baseMapUrl)).json());
    } catch {
      showMessage("The map is not available.");
    }
  }
  return baseMap;
}

function drawMap() {
  if (!baseMap || mapCanvas.clientWidth === 0) return;
  const dpr = window.devicePixelRatio || 1;
  const width = mapCanvas.clientWidth;
  const height = mapCanvas.clientHeight;
  mapCanvas.width = Math.round(width * dpr);
  mapCanvas.height = Math.round(height * dpr);
  const c = mapCanvas.getContext("2d")!;
  c.setTransform(dpr, 0, 0, dpr, 0, 0);
  drawBaseMap(c, baseMap, mapView, width, height, observer);
}

/** Drops the place at `at`, with the name of the nearest place and its time zone. */
function dropPlace(at: Observer, name?: string) {
  if (!baseMap) return;
  const near = nearestPlace(baseMap.places, at);
  const coordinates = `${at.latitudeDeg.toFixed(3)}°, ${at.longitudeDeg.toFixed(3)}°`;
  setPlace({
    observer: at,
    name: `${name ?? (near ? `near ${near.name}` : coordinates)} · ${coordinates}`,
    timeZone: timeZoneAt(baseMap.places, at, browserTimeZone()),
    manual: true,
  });
  drawMap();
}

/** The card at the bottom of the panel: the name of the place, its coordinates, and its zone. */
function renderPlaceCard() {
  // A dropped place has the name "near X · coordinates": the card shows the coordinates below.
  $("place-title").textContent = placeText.split(" · ")[0] || "Your location";
  // The readable name of the zone ("Central European Standard Time"), not the IANA ID.
  const zoneName =
    new Intl.DateTimeFormat(undefined, { timeZone: zone, timeZoneName: "long" })
      .formatToParts(new Date())
      .find((part) => part.type === "timeZoneName")?.value ?? zone;
  $("place-subtitle").textContent =
    `${observer.latitudeDeg.toFixed(3)}°, ${observer.longitudeDeg.toFixed(3)}° · ${zoneName}`;
}

function centerMap() {
  mapView = { ...mapView, lon: observer.longitudeDeg, lat: observer.latitudeDeg };
  drawMap();
}

$("place-button").addEventListener("click", async () => {
  $("place-panel").classList.remove("hidden");
  renderPlaceCard();
  mapView = { ...mapView, zoom: 7 };
  centerMap();
  if (await loadBaseMap()) drawMap();
});
$("place-close").addEventListener("click", () => $("place-panel").classList.add("hidden"));
$("place-here").addEventListener("click", () => {
  navigator.geolocation?.getCurrentPosition(
    (position) => {
      setPlace(gpsPlace(position));
      centerMap();
    },
    () => showMessage("No location: the place stays."),
    { enableHighAccuracy: false, timeout: 20000 },
  );
});
window.addEventListener("resize", () => {
  if (!$("place-panel").classList.contains("hidden")) drawMap();
});
for (const [id, step] of [
  ["place-zoom-in", 1],
  ["place-zoom-out", -1],
] as const) {
  $(id).addEventListener("click", () => {
    mapView = { ...mapView, zoom: Math.max(1, Math.min(12, mapView.zoom + step)) };
    drawMap();
  });
}
const PIN_ICON =
  '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 21c-4-5-6.5-8.4-6.5-11.5a6.5 6.5 0 0 1 13 0C18.5 12.6 16 16 12 21z" /><circle cx="12" cy="9.5" r="2" class="fill" /></svg>';

function showResults() {
  results.replaceChildren();
  $("place-clear").classList.toggle("hidden", searchInput.value === "");
  const found = baseMap ? searchPlaces(baseMap.places, searchInput.value) : [];
  results.classList.toggle("hidden", found.length === 0);
  for (const p of found) {
    const item = document.createElement("button");
    item.innerHTML = PIN_ICON;
    const text = document.createElement("span");
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = p.name;
    const country = document.createElement("span");
    country.className = "country";
    country.textContent = p.country;
    text.append(name, country);
    item.append(text);
    item.addEventListener("click", () => {
      mapView = { lon: p.lon, lat: p.lat, zoom: Math.max(mapView.zoom, 9) };
      dropPlace({ latitudeDeg: p.lat, longitudeDeg: p.lon }, p.name);
      searchInput.value = "";
      searchInput.blur();
      showResults();
    });
    results.append(item);
  }
}
searchInput.addEventListener("input", showResults);
$("place-clear").addEventListener("click", () => {
  searchInput.value = "";
  showResults();
  searchInput.focus();
});

// Drag to move the map, tap to drop the place, and the wheel to zoom.
let mapDrag: { x: number; y: number; moved: boolean } | null = null;
mapCanvas.addEventListener("pointerdown", (e) => {
  mapDrag = { x: e.clientX, y: e.clientY, moved: false };
  mapCanvas.setPointerCapture(e.pointerId);
});
mapCanvas.addEventListener("pointermove", (e) => {
  if (!mapDrag) return;
  const dx = e.clientX - mapDrag.x;
  const dy = e.clientY - mapDrag.y;
  if (!mapDrag.moved && Math.hypot(dx, dy) < 5) return;
  const [x, y] = toWorld(mapView.lon, mapView.lat, mapView.zoom);
  const [lon, lat] = fromWorld(x - dx, y - dy, mapView.zoom);
  mapView = { ...mapView, lon, lat };
  mapDrag = { x: e.clientX, y: e.clientY, moved: true };
  drawMap();
});
mapCanvas.addEventListener("pointerup", (e) => {
  if (mapDrag && !mapDrag.moved) {
    const rect = mapCanvas.getBoundingClientRect();
    dropPlace(
      screenToPlace(mapView, rect.width, rect.height, e.clientX - rect.left, e.clientY - rect.top),
    );
  }
  mapDrag = null;
});
// Without this, a cancelled touch leaves the drag on, and the mouse then moves the map.
mapCanvas.addEventListener("pointercancel", () => (mapDrag = null));
mapCanvas.addEventListener(
  "wheel",
  (e) => {
    e.preventDefault();
    mapView = {
      ...mapView,
      zoom: Math.max(1, Math.min(12, mapView.zoom - Math.sign(e.deltaY) * 0.5)),
    };
    drawMap();
  },
  { passive: false },
);

// The last place starts the terrain also before the location arrives.
if (place !== DEFAULT_PLACE) {
  hasLocation = true;
  if (terrainOn) terrain.update(observer);
}

// --- Keep the terrain of a place offline (for a trip) ----------------------------------------------

const keepButton = $<HTMLButtonElement>("place-keep");
const keepStatus = $("place-keep-status");
const keptList = $("place-kept");
let keeping = false;

// 1 MB is 1024 × 1024 bytes, as in the apps and in MAX_CACHE_BYTES.
const megabytes = (bytes: number) => `${Math.round(bytes / (1024 * 1024))} MB`;

function renderKept() {
  keptList.replaceChildren();
  for (const kept of tileCache.kept) {
    const row = document.createElement("div");
    row.className = "kept";
    const text = document.createElement("span");
    text.textContent = kept.name;
    const size = document.createElement("small");
    size.textContent = `${kept.urls.length} tiles, ${megabytes(tileCache.bytesOf(kept.urls))}`;
    text.append(size);
    const remove = document.createElement("button");
    remove.textContent = "Remove";
    remove.addEventListener("click", () => {
      tileCache.release(kept.id);
      renderKept();
    });
    row.append(text, remove);
    keptList.append(row);
  }
  keepButton.disabled = keeping || !hasLocation;
}

keepButton.addEventListener("click", async () => {
  if (keeping || !hasLocation) return;
  keeping = true;
  renderKept();
  // Ask the browser not to clear the storage of the app when the disk gets full.
  void navigator.storage?.persist?.();
  const name = placeText.split(" · ")[0];
  try {
    // The fine tiles only where the terrain matters, as the terrain of this place decided.
    // Without a decision (the terrain is off or not ready), keep all of them.
    const state = terrain.state;
    const fine =
      state.kind === "ready" &&
      state.observer.latitudeDeg === observer.latitudeDeg &&
      state.observer.longitudeDeg === observer.longitudeDeg
        ? state.fine
        : true;
    const result = await keepOffline(
      almucantar,
      tileCache,
      observer,
      name,
      (done, total) => {
        keepStatus.textContent = `Downloading ${done}/${total}`;
      },
      fine,
    );
    keepStatus.textContent = result.missing
      ? `${result.missing} tiles missing, try again online`
      : `Ready offline: ${result.tiles} tiles, ${megabytes(result.bytes)}`;
  } catch (e) {
    // For example, the storage is full.
    keepStatus.textContent = `Download stopped: ${e instanceof Error ? e.message : String(e)}`;
  } finally {
    keeping = false;
    renderKept();
  }
});
$("place-button").addEventListener("click", renderKept);
