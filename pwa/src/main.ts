// The Sunradar PWA: the sky view with the path of the sun, the horizon, and the time when the sun
// is in the direction of the crosshair. The core comes from @sunradar/core (WebAssembly).

import { DayKind, load } from "@sunradar/core";
import type {
  AzimuthCrossing,
  HeadingEstimate,
  HorizonProfile,
  Horizontal,
  Observer,
  Polyline,
  Quaternion,
  View,
} from "@sunradar/core";
import { litOutline, towardSun } from "./moon";
import type { MoonAt } from "./moon";
import { fromDeviceOrientation, screenRotation, upright, withCompass } from "./orientation";
import { Terrain, TileCache, cacheStorageStore, decodePng, localStorageIndex } from "./terrain";
import type { TerrainState } from "./terrain";

const sunradar = await load();

// --- State --------------------------------------------------------------------------------------

const DEFAULT_PLACE: Observer = { latitudeDeg: 48.2082, longitudeDeg: 16.3738 }; // Vienna
let observer = DEFAULT_PLACE;
let placeText = "Vienna · no location";

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
const terrain = new Terrain({
  sunradar,
  cache: new TileCache({
    store: cacheStorageStore(),
    ...localStorageIndex(),
    fetch: (url, init) => fetch(url, init),
    now: () => Date.now(),
  }),
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

function startOfLocalDay(offsetDays: number): Date {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + offsetDays);
  return d;
}

function selectedTime(): number {
  if (minuteOfDay === null && dayOffset === 0) return Date.now() / 1000;
  const now = new Date();
  const minute = minuteOfDay ?? now.getHours() * 60 + now.getMinutes();
  // The wall clock time, not midnight + minutes, because a day with a DST change has 23 or 25 h.
  const d = startOfLocalDay(dayOffset);
  d.setHours(Math.floor(minute / 60), minute % 60);
  return d.getTime() / 1000;
}

const hhmm = (unix: number) =>
  new Date(unix * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

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
  const start = startOfLocalDay(dayOffset).getTime() / 1000;
  const end = startOfLocalDay(dayOffset + 1).getTime() / 1000;
  const key = `${start}:${observer.latitudeDeg}:${observer.longitudeDeg}:${terrainVersion}`;
  if (day?.key === key) return day;
  const profile = terrainProfile();
  const points = sunradar.sunPath(observer, start, end);
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
    const t = profile ? sunradar.terrainAltitudeDeg(profile, h.azimuthDeg) : NaN;
    return Number.isNaN(t) ? 0 : t;
  };
  const visible = (h: Horizontal) => h.altitudeDeg >= 0 && h.altitudeDeg >= terrainAt(h);
  const hidden = (h: Horizontal) => h.altitudeDeg >= 0 && h.altitudeDeg < terrainAt(h);
  const events = sunradar.sunEvents(observer, start, end);
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
    const t = sunradar.terrainEvents(observer, profile, start, end);
    terrainSummary =
      t.kind === DayKind.polarDay
        ? "Over the terrain all day"
        : t.kind === DayKind.polarNight
          ? "Behind the terrain all day"
          : "Over the terrain: " +
            t.crossings.map((c) => `${c.rising ? "↑" : "↓"} ${hhmm(c.unixUtc)}`).join(" ");
  }
  const year = new Date(start * 1000).getFullYear();
  const guides = [5, 11].flatMap((month) => {
    const s = new Date(year, month, 21).getTime() / 1000;
    return segments(sunradar.sunPath(observer, s, s + 86400));
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
        label: String(new Date(p.unixUtc * 1000).getHours()).padStart(2, "0"),
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
    camera: sunradar.cameraFromFov(width, height, fov),
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
  const at = sunradar.project(view, direction);
  if (!at.valid) return;
  const higher = sunradar.project(view, {
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
  const q = sunradar.project(view, towardSun(moon.direction, sun));
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
    const altitude = sunradar.terrainAltitudeDeg(profile, az % 360);
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
      ].map((h) => sunradar.project(view, h));
      if (!corners.every((p) => p.valid)) continue;
      corners.forEach((p, k) =>
        k ? ctx.lineTo(p.point.x, p.point.y) : ctx.moveTo(p.point.x, p.point.y),
      );
      ctx.closePath();
    }
    ctx.fill("nonzero");
  }
  strokeLines(sunradar.projectLine(view, line), "rgba(190, 230, 170, 0.9)", 2);
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
    sunradar.horizonLine(view),
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
    strokeLines(sunradar.projectLine(view, g), "rgba(255,255,255,0.35)", 1.5, [4, 4]);
  strokeLines(sunradar.projectLine(view, d.path), "rgba(255,200,61,0.5)", 2, [8, 6]);
  for (const s of d.hiddenSegments)
    strokeLines(sunradar.projectLine(view, s), "rgba(255,200,61,0.45)", 2, [3, 5]);
  for (const s of d.daySegments) {
    const lines = sunradar.projectLine(view, s);
    strokeLines(lines, "rgba(255,200,61,0.25)", 9);
    strokeLines(lines, "#ffc83d", 3);
  }
  for (const mark of d.hours) {
    const p = sunradar.project(view, mark.direction);
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
    strokeLines(sunradar.projectLine(view, vertical), "#7fd8ff", 1.5, [6, 5]);
    const p = sunradar.project(view, crossing.sun);
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
    const m = sunradar.project(view, moon.direction);
    if (m.valid) {
      drawMoon(view, moon, sun, m.point.x, m.point.y, 11);
      ctx.fillStyle = "white";
      uprightLabel(view, moon.direction, "moon", 16);
    }
  }

  const s = sunradar.project(view, sun);
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
  const start = startOfLocalDay(0).getTime() / 1000;
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
    const dates = sunradar.findDates(observer, center, start, start + 365 * 86400);
    datesText = dates.length
      ? "In the crosshair: " +
        dates
          .map(
            (m) =>
              `${new Date(m.unixUtc * 1000).toLocaleDateString([], { day: "numeric", month: "short" })} ${hhmm(m.unixUtc)}`,
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
  const sun = sunradar.sunPosition(time, observer);
  const center = sunradar.unproject(view, { x: view.camera.cx, y: view.camera.cy });
  const usable = Math.abs(center.altitudeDeg) <= MAX_CROSSHAIR_ALTITUDE_DEG;
  const crossing = usable
    ? (sunradar.findAzimuthTimes(observer, center.azimuthDeg, d.start, d.end)[0] ?? null)
    : null;
  scheduleDates(usable ? center : null);

  const moonFraction = sunradar.moonIlluminatedFraction(time);
  const moon: MoonAt | null =
    moonFraction >= 0.02
      ? { direction: sunradar.moonPosition(time, observer), fraction: moonFraction }
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

  $("date-label").textContent = startOfLocalDay(dayOffset).toLocaleDateString([], {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
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
      observer = { latitudeDeg: position.coords.latitude, longitudeDeg: position.coords.longitude };
      placeText = `${observer.latitudeDeg.toFixed(3)}°, ${observer.longitudeDeg.toFixed(3)}° · ±${Math.round(position.coords.accuracy)} m`;
      // Only the declination changes, so an alignment before the location arrives stays.
      hasLocation = true;
      if (terrainOn) terrain.update(observer);
      const old = declination;
      declination = sunradar.magneticDeclinationDeg(observer, Date.now() / 1000);
      heading = { ...heading, offsetDeg: heading.offsetDeg + declination - old };
    },
    () => showMessage("No location: the app uses Vienna."),
    { enableHighAccuracy: false, timeout: 20000 },
  );
  resetHeading();
  $("start").classList.add("hidden");
}

/** The browser heading is magnetic, so the offset is the declination until the user aligns. */
let declination = 0;
function resetHeading() {
  declination = sunradar.magneticDeclinationDeg(observer, Date.now() / 1000);
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
  for (const id of ["fov-row", "fov-label"]) $(id).classList.toggle("hidden", !cameraOn);
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
  const sun = sunradar.sunPosition(Date.now() / 1000, observer);
  const tapped = sunradar.unproject(view, { x, y });
  if (Math.abs(tapped.altitudeDeg - sun.altitudeDeg) > 5) {
    showMessage("That point is not at the height of the sun. Tap the sun itself.");
    return;
  }
  let measurement;
  try {
    measurement = sunradar.headingFromDirection(view, { x, y }, sun.azimuthDeg, 0.5);
  } catch (e) {
    // The core rejects a direction that gives no heading (for example near the zenith).
    if (!(e instanceof RangeError)) throw e;
    showMessage(`No alignment: ${e.message}.`);
    return;
  }
  heading = sunradar.headingUpdate(heading, measurement);
  showMessage(`Heading aligned with the sun (±${Math.sqrt(heading.varianceDeg2).toFixed(1)}°).`);
}

$("start").addEventListener("click", () => void startSensors());
$("camera-button").addEventListener("click", () => void toggleCamera());
$("time-button").addEventListener("click", () => $("time-panel").classList.toggle("hidden"));
$("align-button").addEventListener("click", () => {
  const sunUp = sunradar.sunPosition(Date.now() / 1000, observer).altitudeDeg > 0;
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
  const now = new Date();
  $<HTMLInputElement>("date-offset").value = "0";
  $<HTMLInputElement>("minute").value = String(now.getHours() * 60 + now.getMinutes());
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
