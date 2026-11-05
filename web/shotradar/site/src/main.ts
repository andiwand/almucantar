// The website of shotradar: one print on the paper of the page that develops while you scroll.
// The chapters follow the three ways of the start page of the app: A "From where I am" (the peaks
// of the skyline and the shots behind them), B "I have a landmark" (the subject, the landmark, the
// calendar, the spot, the check, and the shot), and C "The next eclipses". Each chapter sets the
// time, the view, and the picture of the print. The core (the same WebAssembly package as the app)
// calculates the sky, the alignments, the line of the spots, the darkness, and the eclipses; the
// terrain and the coastlines come from src/shot.json.

import { Body as CoreBody, EclipseKind, load } from "@almucantar/core";
import type {
  Alignment,
  AlignmentLine,
  Horizontal,
  LocalSolarEclipse,
  LunarEclipse,
  Observer,
} from "@almucantar/core";
import { milkyWayDust } from "@almucantar/web-shared/milkyway";
import { discCoverage } from "@almucantar/web-shared/photo";
import { decodeStars, limitingMagnitude } from "@almucantar/web-shared/stars";
import type { Stars, StarsJson } from "@almucantar/web-shared/stars";
import starsUrl from "../../../../assets/stars/stars.json?url";
import {
  FINE_WINDOW,
  HORIZON,
  LAYERS,
  MAP,
  PEAK,
  PROFILE,
  SHOT,
  SIGHT,
  SPOT,
  ZONE,
  terrainAltitude,
} from "./data";
import { Scene, printRect } from "./scene";
import type {
  Body,
  Frame,
  MapState,
  Moment,
  PeakMark,
  ProfileState,
  Track,
  View,
  WorldState,
} from "./scene";

const almucantar = await load();
const dust = milkyWayDust(2400, 3600);

const MINUTE = 60;
const HOUR = 3600;
const DAY = 86400;
const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const ease = (t: number) => t * t * (3 - 2 * t);
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
const wrap180 = (deg: number) => (((deg % 360) + 540) % 360) - 180;
const wrap360 = (deg: number) => ((deg % 360) + 360) % 360;

const SUMMIT: Horizontal = { azimuthDeg: SIGHT.azimuthDeg, altitudeDeg: SIGHT.altitudeDeg };
const FOCAL_MM = 600;
/** The search of the calendar and the playground: the months after the story. */
const FROM = Date.UTC(2026, 10, 1) / 1000;
const TO = Date.UTC(2027, 11, 31) / 1000;

// --- Formats ------------------------------------------------------------------------------------

const format = (t: number, options: Intl.DateTimeFormatOptions) =>
  new Date(t * 1000).toLocaleString("en-GB", { timeZone: ZONE, ...options });
const hhmm = (t: number) => format(t, { hour: "2-digit", minute: "2-digit" });
const hhmmss = (t: number) => format(t, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const dayMonth = (t: number) => format(t, { day: "numeric", month: "short" });
const date = (t: number) => format(t, { day: "numeric", month: "short", year: "numeric" });
const longDate = (t: number) => format(t, { day: "numeric", month: "long", year: "numeric" });
const zoneName = (t: number) =>
  new Intl.DateTimeFormat("en-GB", { timeZone: ZONE, timeZoneName: "short" })
    .formatToParts(new Date(t * 1000))
    .find((p) => p.type === "timeZoneName")?.value ?? "";
const deg = (v: number, digits = 2) => `${v.toFixed(digits)}°`;
const percent = (f: number) => `${Math.round(f * 100)} %`;

/** The light of a moment, in the words of a photographer. */
function light(sunAltitudeDeg: number): string {
  if (sunAltitudeDeg >= 6) return "Daylight";
  if (sunAltitudeDeg >= -4) return "Golden hour";
  if (sunAltitudeDeg >= -8) return "Blue hour";
  if (sunAltitudeDeg >= -18) return "Late twilight";
  return "Night";
}

function phaseName(fraction: number, waxing: boolean): string {
  if (fraction > 0.97) return "Full moon";
  if (fraction < 0.03) return "New moon";
  if (Math.abs(fraction - 0.5) < 0.08) return waxing ? "First quarter" : "Last quarter";
  if (fraction > 0.5) return waxing ? "Waxing gibbous" : "Waning gibbous";
  return waxing ? "Waxing crescent" : "Waning crescent";
}

const sunAt = (t: number) => almucantar.sunPosition(t, SPOT.observer);
const moonAt = (t: number) => almucantar.moonPosition(t, SPOT.observer);
const fractionAt = (t: number) => almucantar.moonIlluminatedFraction(t);
const waxingAt = (t: number) => fractionAt(t + HOUR) > fractionAt(t);

// --- The searches -------------------------------------------------------------------------------

/** "I stand here": the times when a body is behind the summit, seen from the spot. */
function alignments(body: number, maxSeparationDeg = 0.3): Alignment[] {
  return almucantar.findAlignments(body, SPOT.observer, SUMMIT, FROM, TO, { maxSeparationDeg });
}

// The rule of darkEnough() in app/src/search.ts, with the same constants.
/** The smallest lit part of the moon that a photo shows in any sky. */
const MIN_MOON_FRACTION = 0.1;
/** The thinnest crescent that a photo shows. It is still near the sun. */
const MIN_CRESCENT_FRACTION = 0.03;
/** The sun must be this low for a thin crescent: then the twilight and the earthshine show. */
const CRESCENT_MAX_SUN_DEG = -3;
/** The sky must be this dark for a subject: the airless altitude of the sun (SUBJECTS of the app). */
const MAX_SUN_DEG = new Map<number, number | null>([
  [CoreBody.moon, null],
  [CoreBody.sun, null],
  [CoreBody.galacticCenter, -15],
  [CoreBody.venus, -4],
  [CoreBody.jupiter, -6],
  [CoreBody.saturn, -8],
  [CoreBody.mars, -6],
]);

/** True if the app shows the shot: a lit moon, and a sky that is dark enough for the subject. */
function darkEnough(body: number, t: number): boolean {
  const sunDeg = almucantar.sunPosition(t, SPOT.observer, { refraction: false }).altitudeDeg;
  if (body === CoreBody.moon) {
    const fraction = fractionAt(t);
    if (fraction < MIN_CRESCENT_FRACTION) return false;
    if (fraction < MIN_MOON_FRACTION && sunDeg > CRESCENT_MAX_SUN_DEG) return false;
  }
  const limit = MAX_SUN_DEG.get(body) ?? null;
  return limit === null || sunDeg <= limit;
}

/** The dates that the app shows: the alignments in a sky that is dark enough. */
const shownAlignments = (body: number) =>
  alignments(body, 0.3).filter((a) => darkEnough(body, a.unixUtc));

const calendar = shownAlignments(CoreBody.moon);
const storyIndex = Math.max(
  0,
  calendar.findIndex((a) => Math.abs(a.unixUtc - SHOT.unixUtc) < HOUR),
);
/** The frames of the contact sheet: the story and the next matches. */
const sheet = calendar.slice(Math.max(0, storyIndex - 1), Math.max(0, storyIndex - 1) + 8);

/** The subjects of the first chapter, each at a moment when it is near the summit. */
interface Subject {
  key: string;
  name: string;
  time: number;
  detail: string;
  track: Track | null;
  body: (t: number) => Body | null;
  milkyWay: boolean;
}

function track(direction: (t: number) => Horizontal, center: number): Track {
  const points: Horizontal[] = [];
  for (let t = center - 3 * HOUR; t <= center + 3 * HOUR; t += 6 * MINUTE)
    points.push(direction(t));
  const ticks: Track["ticks"] = [];
  const firstHour = Math.ceil((center - 3 * HOUR) / HOUR) * HOUR;
  for (let t = firstHour; t <= center + 3 * HOUR; t += HOUR) {
    const d = direction(t);
    if (d.altitudeDeg > 0) ticks.push({ direction: d, label: hhmm(t) });
  }
  return { points, ticks };
}

/** The angle between two directions, in degrees. */
function separation(a: Horizontal, b: Horizontal): number {
  const u = toVector(a);
  const v = toVector(b);
  return (Math.acos(clamp(u[0] * v[0] + u[1] * v[1] + u[2] * v[2], -1, 1)) * 180) / Math.PI;
}

/**
 * The sun. Seen from this spot, the summit is farther north than the sun ever rises, so the sun
 * never sits on it. The chapter shows the nearest pass: the sunrise at the solstice of June.
 */
function sunSubject(): Subject {
  const match = alignments(CoreBody.sun, 0.3)[0];
  if (match) {
    return {
      key: "sun",
      name: "The sun",
      time: match.unixUtc,
      detail: `It rises over the summit on ${longDate(match.unixUtc)} at ${hhmm(match.unixUtc)}.`,
      track: track(sunAt, match.unixUtc),
      body: (t) => ({ kind: "sun", direction: sunAt(t), label: "Sun" }),
      milkyWay: false,
    };
  }
  const solstice = Date.UTC(2027, 5, 21, 2) / 1000;
  let best = { time: solstice, deg: 180 };
  for (let t = solstice; t < solstice + 8 * HOUR; t += MINUTE) {
    const d = separation(sunAt(t), SUMMIT);
    if (d < best.deg) best = { time: t, deg: d };
  }
  const side = wrap180(sunAt(best.time).azimuthDeg - SUMMIT.azimuthDeg) > 0 ? "right" : "left";
  return {
    key: "sun",
    name: "The sun",
    time: best.time,
    detail: `Not from here: even on ${longDate(best.time)} it passes ${best.deg.toFixed(0)}° to the ${side} of the summit. Shotradar would send you to another spot.`,
    track: track(sunAt, best.time),
    body: (t) => ({ kind: "sun", direction: sunAt(t), label: "Sun" }),
    milkyWay: false,
  };
}

function planetSubject(): Subject {
  const candidates = [
    { body: CoreBody.jupiter, planet: 3 as const, name: "Jupiter" },
    { body: CoreBody.venus, planet: 1 as const, name: "Venus" },
    { body: CoreBody.saturn, planet: 4 as const, name: "Saturn" },
    { body: CoreBody.mars, planet: 2 as const, name: "Mars" },
  ];
  for (const c of candidates) {
    const match = alignments(c.body, 0.3).find((a) => sunAt(a.unixUtc).altitudeDeg < -9);
    if (!match) continue;
    const direction = (t: number) => almucantar.planetPosition(c.planet, t, SPOT.observer);
    return {
      key: "planet",
      name: c.name,
      time: match.unixUtc,
      detail: `${c.name} rises behind the summit on ${longDate(match.unixUtc)} at ${hhmm(match.unixUtc)}.`,
      track: track(direction, match.unixUtc),
      body: (t) => ({ kind: "planet", direction: direction(t), label: c.name }),
      milkyWay: false,
    };
  }
  // No match: the planets stay near the path of the sun, so they miss this summit too. Show the
  // nearest pass of the brightest one in a dark sky.
  let best = { c: candidates[0], time: SHOT.unixUtc, deg: 180 };
  for (const c of candidates.slice(0, 2)) {
    for (let t = FROM; t < TO; t += 10 * MINUTE) {
      const p = almucantar.planetPosition(c.planet, t, SPOT.observer);
      if (p.altitudeDeg < 2 || p.altitudeDeg > 25 || sunAt(t).altitudeDeg > -9) continue;
      const d = separation(p, SUMMIT);
      if (d < best.deg) best = { c, time: t, deg: d };
    }
  }
  const direction = (t: number) => almucantar.planetPosition(best.c.planet, t, SPOT.observer);
  const side = wrap180(direction(best.time).azimuthDeg - SUMMIT.azimuthDeg) > 0 ? "right" : "left";
  return {
    key: "planet",
    name: best.c.name,
    time: best.time,
    detail: `It comes nearest on ${longDate(best.time)} at ${hhmm(best.time)}, ${best.deg.toFixed(0)}° to the ${side} of the summit. For a planet behind the peak, Shotradar finds another spot.`,
    track: track(direction, best.time),
    body: (t) => ({ kind: "planet", direction: direction(t), label: best.c.name }),
    milkyWay: false,
  };
}

/**
 * The Milky Way: its center never comes this far north, so the search looks for the bright clouds
 * of Cygnus over the summit in a dark night without the moon.
 */
function milkyWaySubject(): Subject {
  let best = { time: SHOT.unixUtc, distance: 1e9 };
  for (
    let night = Date.UTC(2027, 3, 1) / 1000;
    night < Date.UTC(2027, 9, 30) / 1000;
    night += DAY
  ) {
    for (let t = night + 18 * HOUR; t < night + 28 * HOUR; t += 15 * MINUTE) {
      if (sunAt(t).altitudeDeg > -18) continue;
      if (moonAt(t).altitudeDeg > 0 && fractionAt(t) > 0.2) continue;
      const d = separation(almucantar.galacticPosition(76, 1, t, SPOT.observer), SUMMIT);
      if (d < best.distance) best = { time: t, distance: d };
    }
  }
  return {
    key: "milkyway",
    name: "The Milky Way",
    time: best.time,
    detail: `Its clouds in Cygnus rise behind the summit on ${longDate(best.time)} at ${hhmm(best.time)}, in a dark sky without the moon.`,
    track: null,
    body: () => null,
    milkyWay: true,
  };
}

function toVector(h: Horizontal): [number, number, number] {
  const az = (h.azimuthDeg * Math.PI) / 180;
  const alt = (h.altitudeDeg * Math.PI) / 180;
  return [Math.cos(alt) * Math.sin(az), Math.cos(alt) * Math.cos(az), Math.sin(alt)];
}

const moonSubject: Subject = {
  key: "moon",
  name: "The full moon",
  time: SHOT.unixUtc,
  detail: `It rises behind the summit on ${longDate(SHOT.unixUtc)} at ${hhmm(SHOT.unixUtc)}.`,
  track: track(moonAt, SHOT.unixUtc + 1.5 * HOUR),
  body: () => null,
  milkyWay: false,
};

let subjects: Subject[] | null = null;
/** The other subjects need a few searches, so they wait until the chapter needs them. */
function allSubjects(): Subject[] {
  subjects ??= [moonSubject, milkyWaySubject(), sunSubject(), planetSubject()];
  return subjects;
}

// --- Way A: the peaks of the skyline, and the shots behind them ------------------------------------

const COMPASS = "N NNE NE ENE E ESE SE SSE S SSW SW WSW W WNW NW NNW".split(" ");
const compass = (azimuthDeg: number) =>
  COMPASS[Math.round((((azimuthDeg % 360) + 360) % 360) / 22.5) % 16];

/** A prominent peak on the skyline of the spot. */
interface Peak {
  direction: Horizontal;
  distanceM: number;
  /** The height of the summit, from its altitude and its distance (about). */
  elevationM: number;
  /** "Großglockner" or "peak to the ENE", for a sentence. */
  name: string;
  /** The label in the photo. */
  label: string;
}

/**
 * The peaks of the skyline: the same rule as findPeaks() of the app. A peak is the highest point
 * within 5° on both sides, and it stands out 0.6° over the lowest point on each side. In the window
 * of the fine horizon, the summit moves to the highest point of the fine data.
 */
function findPeaks(windowDeg = 5, minProminenceDeg = 0.6, minDistanceM = 1500, count = 10) {
  const a = HORIZON.altitudeDeg;
  const n = a.length;
  const step = HORIZON.azimuthStepDeg;
  const w = Math.max(1, Math.round(windowDeg / step));
  const at = (i: number) => a[((i % n) + n) % n];
  const found: { i: number; prominence: number }[] = [];
  for (let i = 0; i < n; i++) {
    const h = a[i];
    if (!(HORIZON.distanceM[i] >= minDistanceM)) continue;
    let highest = true;
    let lowLeft = h;
    let lowRight = h;
    for (let k = 1; k <= w && highest; k++) {
      // A plateau counts once: the first sample of equal values wins.
      if (at(i - k) >= h || at(i + k) > h) highest = false;
      lowLeft = Math.min(lowLeft, at(i - k));
      lowRight = Math.min(lowRight, at(i + k));
    }
    const prominence = h - Math.max(lowLeft, lowRight);
    if (highest && prominence >= minProminenceDeg) found.push({ i, prominence });
  }
  const eye = SPOT.eye.elevationM;
  return found
    .sort((p, q) => q.prominence - p.prominence)
    .slice(0, count)
    .map(({ i }): Peak => {
      let direction: Horizontal = { azimuthDeg: i * step, altitudeDeg: a[i] };
      if (wrap360(direction.azimuthDeg - FINE_WINDOW.firstAzimuthDeg) <= FINE_WINDOW.spanDeg) {
        for (let az = direction.azimuthDeg - step; az <= direction.azimuthDeg + step; az += 0.05) {
          const alt = terrainAltitude(LAYERS - 1, az);
          if (alt > direction.altitudeDeg) direction = { azimuthDeg: az, altitudeDeg: alt };
        }
      }
      const d = HORIZON.distanceM[i];
      const summit = Math.abs(wrap180(direction.azimuthDeg - SIGHT.azimuthDeg)) < 0.6;
      if (summit) direction = SUMMIT;
      // The height from the altitude: the inverse of the model of sight(), with the refraction.
      const drop = (d * d * (1 - 0.13)) / (2 * 6_371_008.8);
      const elevationM = summit
        ? PEAK.site.elevationM
        : eye + d * Math.tan((direction.altitudeDeg * Math.PI) / 180) + drop;
      const side = compass(direction.azimuthDeg);
      return {
        direction,
        distanceM: summit ? SIGHT.distanceM : d,
        elevationM,
        name: summit ? PEAK.name : `peak to the ${side}`,
        label: summit ? PEAK.name.toUpperCase() : `${side} · ${Math.round(elevationM / 10) * 10} M`,
      };
    });
}

const PEAKS = findPeaks();

/** The subjects of way A, with the separation of the app. darkEnough() checks the sky. */
const HERE_SUBJECTS = [
  { key: "moon", name: "The moon", short: "Moon", body: CoreBody.moon, sep: 0.3 },
  { key: "sun", name: "The sun", short: "Sun", body: CoreBody.sun, sep: 0.3 },
  {
    key: "milkyway",
    name: "The core of the Milky Way",
    short: "Core",
    body: CoreBody.galacticCenter,
    sep: 2,
  },
  { key: "venus", name: "Venus", short: "Venus", body: CoreBody.venus, sep: 0.4 },
  { key: "jupiter", name: "Jupiter", short: "Jupiter", body: CoreBody.jupiter, sep: 0.4 },
  { key: "saturn", name: "Saturn", short: "Saturn", body: CoreBody.saturn, sep: 0.4 },
  { key: "mars", name: "Mars", short: "Mars", body: CoreBody.mars, sep: 0.4 },
];

/** A shot of way A: a subject behind a peak, or alone over the skyline (peak is null). */
interface HereShot extends Subject {
  short: string;
  peak: Peak | null;
  direction: Horizontal;
  /** The subject alone: the dark hours over the skyline. */
  window: { startUtc: number; endUtc: number } | null;
}

/** The search of way A starts on the day of the story and looks one year ahead. */
const HERE_FROM = Date.UTC(2026, 10, 24) / 1000;
const HERE_TO = HERE_FROM + 365 * DAY;

/** The first time when the subject is behind one of the peaks, in a sky dark enough for it. */
function firstBehindPeak(s: (typeof HERE_SUBJECTS)[number]): HereShot | null {
  let best: { a: Alignment; peak: Peak } | null = null;
  for (const peak of PEAKS) {
    const match = almucantar
      .findAlignments(s.body, SPOT.observer, peak.direction, HERE_FROM, HERE_TO, {
        maxSeparationDeg: s.sep,
      })
      .find((a) => darkEnough(s.body, a.unixUtc));
    if (match && (!best || match.unixUtc < best.a.unixUtc)) best = { a: match, peak };
  }
  if (!best) return null;
  const { a, peak } = best;
  const direction = (t: number) => almucantar.bodyPosition(s.body, t, SPOT.observer);
  const rises = direction(a.unixUtc + 120).altitudeDeg > direction(a.unixUtc).altitudeDeg;
  const where =
    peak.name === PEAK.name
      ? `the ${peak.name}, ${(peak.distanceM / 1000).toFixed(1)} km away`
      : `the ${peak.name}, ${(peak.distanceM / 1000).toFixed(1)} km away, about ${Math.round(peak.elevationM / 10) * 10} m`;
  return {
    key: s.key,
    name: s.name,
    short: s.short,
    time: a.unixUtc,
    detail: `${s.name} ${rises ? "rises" : "sets"} behind ${where}.`,
    track: track(direction, a.unixUtc),
    body: (t) =>
      s.body === CoreBody.moon || s.body === CoreBody.galacticCenter
        ? null
        : {
            kind: s.body === CoreBody.sun ? "sun" : "planet",
            direction: direction(t),
            label: s.short,
          },
    milkyWay: s.body === CoreBody.galacticCenter,
    peak,
    direction: peak.direction,
    window: null,
  };
}

/**
 * The core of the Milky Way alone over the ridge: the longest window from April to September 2027
 * with the core over the skyline, the sun 18° down, and the moon down (darkWindows of the core).
 */
function coreOverRidge(): HereShot | null {
  const windows = almucantar.darkWindows(
    SPOT.observer,
    Date.UTC(2027, 3, 1) / 1000,
    Date.UTC(2027, 9, 1) / 1000,
    { galacticCenter: true },
    HORIZON,
  );
  const w = windows.sort((p, q) => q.endUtc - q.startUtc - (p.endUtc - p.startUtc))[0];
  if (!w) return null;
  const time = (w.startUtc + w.endUtc) / 2;
  const core = (t: number) => almucantar.bodyPosition(CoreBody.galacticCenter, t, SPOT.observer);
  const side = compass(core(time).azimuthDeg);
  return {
    key: "core-alone",
    name: "The core of the Milky Way",
    short: "Alone",
    time,
    detail: `The core of the Milky Way stands alone over the ridge to the ${side}, from ${hhmm(w.startUtc)} to ${hhmm(w.endUtc)}, with no moon in the sky.`,
    track: track(core, time),
    body: () => null,
    milkyWay: true,
    peak: null,
    direction: core(time),
    window: w,
  };
}

let hereShots: HereShot[] = [];

/** The searches of way A take a few tenths of a second, so they run in steps after the start. */
async function searchHere() {
  const out: HereShot[] = [];
  for (const s of HERE_SUBJECTS) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    const shot = firstBehindPeak(s);
    if (shot) out.push(shot);
  }
  const alone = coreOverRidge();
  if (alone) out.push(alone);
  hereShots = out.sort((p, q) => p.time - q.time);
  fillHereSheet();
}

/** The view of way A: the peak (or the subject) low in the print, with the skyline around it. */
function hereView(direction: Horizontal): View {
  const r = printRect(innerWidth, innerHeight);
  const span = narrow() ? 40 : 56;
  const tall = (span * r.h) / r.w;
  return {
    azimuthDeg: direction.azimuthDeg,
    altitudeDeg: Math.max(direction.altitudeDeg, 2) + 0.22 * tall,
    spanDeg: span,
  };
}

// --- Way C: the eclipses ------------------------------------------------------------------------

const ECLIPSE_NAMES = ["Penumbral", "Partial", "Annular", "Total"];

interface EclipseShot extends Subject {
  type: "solar" | "lunar";
  kind: number;
  peakUtc: number;
  /** The times of the photo: from the first to the last contact. */
  begin: number;
  end: number;
  local: LocalSolarEclipse | null;
  lunar: LunarEclipse | null;
  sunRadiusDeg: number;
  moonRadiusDeg: number;
  /** The point of the greatest eclipse of a total or annular solar eclipse. */
  peakPlace: Observer | null;
  /** The length of the central phase at that point, in seconds. */
  centralS: number;
  world: WorldState | null;
  here: string;
  line: string;
}

const airlessSunAt = (t: number) => almucantar.sunPosition(t, SPOT.observer, { refraction: false });
const airlessMoonAt = (t: number) =>
  almucantar.moonPosition(t, SPOT.observer, { refraction: false });

const duration = (s: number) => {
  const m = Math.floor(s / 60);
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min ${Math.round(s - 60 * m)} s`;
};
const latLon = (o: Observer) =>
  `${Math.abs(o.latitudeDeg).toFixed(1)}° ${o.latitudeDeg >= 0 ? "N" : "S"}, ${Math.abs(o.longitudeDeg).toFixed(1)}° ${o.longitudeDeg >= 0 ? "E" : "W"}`;

/** The box of the map of an eclipse: the central line and the spot, with a margin. */
function worldBox(points: Observer[]) {
  const lons = points.map((p) => p.longitudeDeg);
  const lats = points.map((p) => p.latitudeDeg);
  return {
    west: Math.max(-45, Math.min(...lons) - 6),
    east: Math.min(100, Math.max(...lons) + 6),
    south: Math.max(-20, Math.min(...lats) - 6),
    north: Math.min(72, Math.max(...lats) + 6),
  };
}

function solarShot(e: { kind: number; peakUtc: number; peakPlace: Observer }): EclipseShot | null {
  const local = almucantar
    .localSolarEclipses(SPOT.observer, e.peakUtc - DAY, e.peakUtc + DAY)
    .find((l) => Math.abs(l.peakUtc - e.peakUtc) < 6 * HOUR);
  if (!local || local.peakSunAltitudeDeg <= 0 || local.obscuration < 0.3) return null;
  // The radii from the contacts, as in the app: at the first contact the discs touch outside (the
  // sum of the radii), at the second inside (the difference).
  const outside = separation(
    airlessSunAt(local.partialBeginUtc),
    airlessMoonAt(local.partialBeginUtc),
  );
  let sunRadiusDeg = 0.2666;
  let moonRadiusDeg = outside - sunRadiusDeg;
  if (Number.isFinite(local.totalBeginUtc)) {
    const inside = separation(
      airlessSunAt(local.totalBeginUtc),
      airlessMoonAt(local.totalBeginUtc),
    );
    sunRadiusDeg =
      local.kind === EclipseKind.total ? (outside - inside) / 2 : (outside + inside) / 2;
    moonRadiusDeg =
      local.kind === EclipseKind.total ? (outside + inside) / 2 : (outside - inside) / 2;
  }
  const central = Number.isFinite(e.peakPlace.latitudeDeg);
  const points = central ? almucantar.eclipseCentralLine(e.peakUtc, 120) : [];
  const at = central
    ? almucantar
        .localSolarEclipses(e.peakPlace, e.peakUtc - DAY, e.peakUtc + DAY)
        .find((l) => Math.abs(l.peakUtc - e.peakUtc) < 6 * HOUR)
    : undefined;
  const centralS = at ? at.totalEndUtc - at.totalBeginUtc : NaN;
  const name = `${ECLIPSE_NAMES[e.kind]} solar eclipse`;
  const phase = e.kind === EclipseKind.annular ? "annular" : "total";
  const utc = (t: number) =>
    new Date(t * 1000).toLocaleTimeString("en-GB", {
      timeZone: "UTC",
      hour: "2-digit",
      minute: "2-digit",
    });
  const world: WorldState = {
    box: worldBox([...points.map((p) => p.place), SPOT.observer]),
    line: points.map((p) => ({
      lon: p.place.longitudeDeg,
      lat: p.place.latitudeDeg,
      radiusKm: Math.abs(p.umbraRadiusKm),
    })),
    ticks: points
      .filter((_, i) => i % 15 === 0 && i > 0 && i < points.length - 1)
      .map((p) => ({
        lon: p.place.longitudeDeg,
        lat: p.place.latitudeDeg,
        label: `${utc(p.unixUtc)} UTC`,
      })),
    moonUp: null,
    spot: SPOT.observer,
    spotLabel: `YOU · ${percent(local.obscuration)}`,
    peak:
      central && Number.isFinite(centralS)
        ? {
            place: e.peakPlace,
            label: `${phase.toUpperCase()} ${duration(centralS).toUpperCase()}`,
          }
        : null,
    title: `${date(e.peakUtc).toUpperCase()} · ${name.toUpperCase()}`,
    legend: central ? `THE PATH OF THE ${phase === "total" ? "UMBRA" : "ANTUMBRA"}` : "",
  };
  return {
    key: `solar-${e.peakUtc}`,
    name,
    time: local.peakUtc,
    detail: "",
    track: null,
    body: () => null,
    milkyWay: false,
    type: "solar",
    kind: e.kind,
    peakUtc: local.peakUtc,
    begin: local.partialBeginUtc,
    end: local.partialEndUtc,
    local,
    lunar: null,
    sunRadiusDeg,
    moonRadiusDeg,
    peakPlace: central ? e.peakPlace : null,
    centralS,
    world,
    here: `From the ridge: partial, the moon covers ${percent(local.obscuration)} of the sun at ${hhmm(local.peakUtc)} ${zoneName(local.peakUtc)}, with the sun ${deg(local.peakSunAltitudeDeg, 0)} high. It starts at ${hhmm(local.partialBeginUtc)} and ends at ${hhmm(local.partialEndUtc)}.`,
    line:
      central && Number.isFinite(centralS)
        ? `At the point of the greatest eclipse (${latLon(e.peakPlace)}), the ${phase} phase lasts ${duration(centralS)}.`
        : "",
  };
}

function lunarShot(l: LunarEclipse): EclipseShot | null {
  if (l.kind < EclipseKind.partial || l.obscuration < 0.3) return null;
  const moon = moonAt(l.peakUtc);
  if (moon.altitudeDeg <= almucantar.terrainAltitudeDeg(HORIZON, moon.azimuthDeg)) return null;
  const total = Number.isFinite(l.totalBeginUtc);
  const name = `${ECLIPSE_NAMES[l.kind]} lunar eclipse`;
  // Where the moon is up at the peak: a grid of the map of the Earth.
  const cellDeg = 2.5;
  const cells: [number, number][] = [];
  for (let lat = -20; lat <= 72; lat += cellDeg) {
    for (let lon = -45; lon <= 100; lon += cellDeg) {
      const at = { latitudeDeg: lat, longitudeDeg: lon };
      if (almucantar.moonPosition(l.peakUtc, at).altitudeDeg > 0) cells.push([lon, lat]);
    }
  }
  const above = (t: number) => {
    const m = moonAt(t);
    return m.altitudeDeg > almucantar.terrainAltitudeDeg(HORIZON, m.azimuthDeg);
  };
  const into = total ? l.totalBeginUtc : l.partialBeginUtc;
  const world: WorldState = {
    box: { west: -45, east: 100, south: -20, north: 72 },
    line: [],
    ticks: [],
    moonUp: { cellDeg, cells },
    spot: SPOT.observer,
    spotLabel: `YOU · MOON ${deg(moon.altitudeDeg, 0)} HIGH`,
    peak: null,
    title: `${date(l.peakUtc).toUpperCase()} · ${name.toUpperCase()}`,
    legend: "THE MOON IS UP AT THE PEAK",
  };
  return {
    key: `lunar-${l.peakUtc}`,
    name,
    time: l.peakUtc,
    detail: "",
    track: null,
    body: () => null,
    milkyWay: false,
    type: "lunar",
    kind: l.kind,
    peakUtc: l.peakUtc,
    begin: l.partialBeginUtc,
    end: l.partialEndUtc,
    local: null,
    lunar: l,
    sunRadiusDeg: 0.2666,
    moonRadiusDeg: 0.26,
    peakPlace: null,
    centralS: total ? l.totalEndUtc - l.totalBeginUtc : NaN,
    world,
    here: `From the ridge: the peak is at ${hhmm(l.peakUtc)} ${zoneName(l.peakUtc)}, with the moon ${deg(moon.altitudeDeg, 0)} high to the ${compass(moon.azimuthDeg)}. At the start of the ${total ? "total" : "partial"} phase, the moon is ${above(into) ? "over" : "still behind"} the mountains.`,
    line: total
      ? `The total phase lasts ${duration(l.totalEndUtc - l.totalBeginUtc)}, from ${hhmm(l.totalBeginUtc)} to ${hhmm(l.totalEndUtc)}: the moon turns red.`
      : `At the peak, ${percent(l.obscuration)} of the moon is in the shadow of the Earth.`,
  };
}

/** The part of the moon in the umbra at a time: the same ramp as lunarUmbra() of the app. */
function umbraAt(l: LunarEclipse, t: number): number {
  const ramp = (a: number, b: number) => clamp((t - a) / (b - a));
  if (!Number.isFinite(l.partialBeginUtc)) return 0;
  const top = Number.isFinite(l.totalBeginUtc) ? 1 : l.obscuration;
  const into = Number.isFinite(l.totalBeginUtc) ? l.totalBeginUtc : l.peakUtc;
  const out = Number.isFinite(l.totalEndUtc) ? l.totalEndUtc : l.peakUtc;
  if (t < into) return top * ramp(l.partialBeginUtc, into);
  if (t > out) return top * (1 - ramp(out, l.partialEndUtc));
  return top;
}

/** The next eclipses that the spot sees well: 30 % of the sun or of the moon, over the terrain. */
function searchEclipses(): EclipseShot[] {
  const year = 365.25 * DAY;
  const solar = almucantar
    .solarEclipses(HERE_FROM, HERE_FROM + 10 * year)
    .map(solarShot)
    .filter((e): e is EclipseShot => e !== null);
  const lunar = almucantar
    .lunarEclipses(HERE_FROM, HERE_FROM + 5 * year)
    .map(lunarShot)
    .filter((e): e is EclipseShot => e !== null);
  return [...solar, ...lunar].sort((a, b) => a.peakUtc - b.peakUtc).slice(0, 4);
}

const ECLIPSES = searchEclipses();
const isEclipse = (s: Subject | null): s is EclipseShot => s !== null && "begin" in s;
/** The eclipse of the chapter: the first total solar eclipse, until a click picks another one. */
let eclipseIndex = Math.max(
  0,
  ECLIPSES.findIndex((e) => e.type === "solar" && e.kind === EclipseKind.total),
);

/** The view of the photo of an eclipse at a time. */
function eclipseView(e: EclipseShot, t: number): View {
  if (e.type === "solar") {
    const sun = airlessSunAt(t);
    return { azimuthDeg: sun.azimuthDeg, altitudeDeg: sun.altitudeDeg, spanDeg: narrow() ? 3 : 4 };
  }
  const peak = moonAt(e.peakUtc);
  if (peak.altitudeDeg < 25) {
    // A low moon: a fixed view with the mountains, so the moon moves over the skyline.
    return {
      azimuthDeg: peak.azimuthDeg - 3,
      altitudeDeg: peak.altitudeDeg + 1,
      spanDeg: narrow() ? 14 : 20,
    };
  }
  const moon = moonAt(t);
  return { azimuthDeg: moon.azimuthDeg, altitudeDeg: moon.altitudeDeg, spanDeg: narrow() ? 4 : 6 };
}

// --- The line of the spots and the line of sight --------------------------------------------------

let lineCache: { t: number; line: AlignmentLine } | null = null;

function lineAt(t: number): AlignmentLine {
  const key = Math.round(t / 15) * 15;
  if (lineCache?.t === key) return lineCache.line;
  const line = almucantar.alignmentLine(CoreBody.moon, PEAK.site, key, [MAP], {
    minDistanceM: 1500,
    maxDistanceM: 16000,
    stepM: 50,
  });
  lineCache = { t: key, line };
  return line;
}

/**
 * The highest ground in front of the summit, seen from the eye. The last 800 m are the flanks of
 * the summit itself, so they do not count.
 */
function clearance(): { index: number; deg: number } {
  const n = PROFILE.groundM.length - 1;
  let best = { index: 1, altitude: -90 };
  for (let i = 4; i <= n; i++) {
    const d = (SIGHT.distanceM * i) / n;
    if (d > SIGHT.distanceM - 800) break;
    const f = i / n;
    const s = almucantar.sight(SPOT.eye, {
      latitudeDeg: SPOT.eye.latitudeDeg + (PEAK.site.latitudeDeg - SPOT.eye.latitudeDeg) * f,
      longitudeDeg: SPOT.eye.longitudeDeg + (PEAK.site.longitudeDeg - SPOT.eye.longitudeDeg) * f,
      elevationM: PROFILE.groundM[i],
    });
    if (s.direction.altitudeDeg > best.altitude)
      best = { index: i, altitude: s.direction.altitudeDeg };
  }
  return { index: best.index, deg: SIGHT.altitudeDeg - best.altitude };
}

const CLEAR = clearance();
const VIEW_CHECK = almucantar.lineOfSight(SPOT.eye, PEAK.site, [MAP]);

/** The evening of the story, for the time line of the check. */
function evening(): ProfileState["timeline"] {
  const out: Moment[] = [];
  for (let t = SHOT.unixUtc - 2 * HOUR; t <= SHOT.unixUtc + 3 * HOUR; t += 5 * MINUTE) {
    const moon = moonAt(t);
    out.push({
      t,
      sunAltitudeDeg: sunAt(t).altitudeDeg,
      moonUp: moon.altitudeDeg > almucantar.terrainAltitudeDeg(HORIZON, moon.azimuthDeg),
    });
  }
  return out;
}

const TIMELINE = evening();
const TICKS = (() => {
  const ticks: ProfileState["ticks"] = [];
  for (
    let t = Math.ceil(TIMELINE[0].t / HOUR) * HOUR;
    t <= TIMELINE[TIMELINE.length - 1].t;
    t += HOUR
  ) {
    ticks.push({ t, label: hhmm(t) });
  }
  return ticks;
})();

/** The next dark sky after the story: the sun 18° down and the moon down or nearly new. */
const nextDark = almucantar
  .darkWindows(SPOT.observer, SHOT.unixUtc, SHOT.unixUtc + 40 * DAY, {}, HORIZON)
  .find((w) => w.endUtc - w.startUtc > HOUR);
const darkTonight = almucantar.darkWindows(
  SPOT.observer,
  SHOT.unixUtc,
  SHOT.unixUtc + 16 * HOUR,
  {},
  HORIZON,
);

// --- The chapters -------------------------------------------------------------------------------

type Mode = "photo" | "map" | "profile" | "world";

interface Target {
  time: number;
  view: View;
  mode: Mode;
  pin: number;
  finder: number;
  print: number;
  subject: Subject | null;
  trackAlpha: number;
  reveal: number;
  focal: number;
  /** Way A: the marks of the peaks, and the peak of the shot. */
  peaks: number;
  activePeak: Peak | null;
  /** Way C: the eclipse in the photo or on the map. */
  eclipse: EclipseShot | null;
}

// --- The choices of the cards ---------------------------------------------------------------------

/**
 * A card with a list (the subjects, the calendar, the shots of way A) steps through it while you
 * scroll. A click picks an item; the pick holds until the scroll reaches the next item.
 */
const picks = new Map<string, { index: number; scrolled: number }>();
/** The raw progress of each chapter, from the last frame. */
let rawProgress = { name: "", progress: 0 };

function choice(name: string, p: number, n: number): number {
  const scrolled = Math.min(n - 1, Math.floor(p * n));
  const pick = picks.get(name);
  if (!pick) return scrolled;
  // Before the first frame in the chapter, the pick holds.
  if (rawProgress.name !== name) return pick.index;
  if (Math.min(n - 1, Math.floor(rawProgress.progress * n)) === pick.scrolled) return pick.index;
  picks.delete(name);
  return scrolled;
}

/** Makes the button of a list item pick it: a click, or Enter or Space on the keyboard. */
function pickOnClick(button: HTMLButtonElement, name: string, index: number, n: () => number) {
  button.addEventListener("click", () => {
    const section = button.closest<HTMLElement>("[data-chapter]")!;
    const rect = section.getBoundingClientRect();
    const progress = clamp(-rect.top / Math.max(1, rect.height - innerHeight));
    picks.set(name, { index, scrolled: Math.min(n() - 1, Math.floor(progress * n())) });
  });
}

/** Marks the shown item of a list as pressed. */
function press(list: ParentNode, index: number) {
  list.querySelectorAll("button").forEach((b, i) => {
    b.setAttribute("aria-pressed", String(i === index));
  });
}

const narrow = () => innerWidth < 860;
const tele = (spanDeg: number, lift = 0.5): View => ({
  azimuthDeg: SIGHT.azimuthDeg,
  // A narrow print is low, so the summit moves up toward the center.
  altitudeDeg: SIGHT.altitudeDeg + (narrow() ? lift * 0.4 : lift),
  spanDeg: narrow() ? spanDeg * 0.82 : spanDeg,
});

let heroMs = 0;
/** The hero: the moon rises slowly over the summit and sinks back, about two minutes each way. */
const heroTime = () =>
  SHOT.unixUtc + 50 + (reducedMotion.matches ? 0 : 110 * Math.sin(heroMs / 5200));

const chapters: Record<string, (p: number) => Partial<Target>> = {
  hero: () => ({ time: heroTime(), view: tele(6.5, 0.7) }),
  here: (p) => {
    if (!hereShots.length) {
      return { time: SHOT.unixUtc, view: hereView(SUMMIT), peaks: 1, activePeak: PEAKS[0] };
    }
    const s = hereShots[choice("here", p, hereShots.length)];
    return {
      time: s.time,
      view: hereView(s.direction),
      subject: s,
      trackAlpha: 1,
      peaks: 1,
      activePeak: s.peak,
    };
  },
  subject: (p) => {
    const list = allSubjects();
    const s = list[choice("subject", p, list.length)];
    return {
      time: s.time,
      view: {
        azimuthDeg: SIGHT.azimuthDeg + (s.key === "sun" ? 6 : 0),
        altitudeDeg: s.milkyWay ? 26 : 19,
        spanDeg: narrow() ? 64 : 84,
      },
      subject: s,
      trackAlpha: 1,
    };
  },
  landmark: (p) => ({
    time: SHOT.unixUtc - 25 * MINUTE,
    view: tele(26 - 12 * ease(clamp(p * 1.6)), 1.6 - 0.8 * ease(clamp(p * 1.6))),
    pin: clamp((p - 0.15) * 4),
  }),
  calendar: (p) => {
    const a = sheet[choice("calendar", p, sheet.length)];
    return { time: (a?.unixUtc ?? SHOT.unixUtc) + 40, view: tele(9, 0.8) };
  },
  spot: (p) => ({
    time: SHOT.unixUtc - 24 * MINUTE + 44 * MINUTE * p,
    mode: "map",
    view: tele(9, 0.8),
  }),
  check: (p) => ({
    time: SHOT.unixUtc,
    mode: "profile",
    reveal: clamp(p * 1.8 - 0.1),
    view: tele(9, 0.8),
  }),
  shot: (p) => ({
    time: SHOT.unixUtc - 150 + 300 * p,
    view: tele(6.2, 0.5),
    finder: clamp(p * 4),
  }),
  eclipse: (p) => {
    const e = ECLIPSES[eclipseIndex];
    if (!e) return { time: SHOT.unixUtc, view: tele(9, 0.8) };
    // The first part shows the map of the Earth, the rest the photo from the first to the last
    // contact.
    const q = clamp((p - 0.4) / 0.55);
    const t = e.begin + (e.end - e.begin) * q;
    return {
      time: p < 0.4 ? e.begin : t,
      mode: p < 0.4 ? "world" : "photo",
      view: eclipseView(e, p < 0.4 ? e.begin : t),
      subject: e,
      eclipse: e,
      // A low moon rises next to the summit, so the photo names the summit.
      pin: e.type === "lunar" && moonAt(e.peakUtc).altitudeDeg < 25 ? 1 : 0,
    };
  },
  play: () => {
    // The view shows the frame of the lens with some room around it.
    const fov = (2 * Math.atan(18 / play.focal) * 180) / Math.PI;
    return {
      time: playTime(),
      view: tele(Math.max(5, fov * 1.9), 0.4),
      finder: 1,
      focal: play.focal,
    };
  },
  rest: () => ({ time: SHOT.unixUtc + 60, view: tele(9, 0.8), print: 0 }),
};

// --- The playground -----------------------------------------------------------------------------

/** The bodies of the playground: only the ones that pass behind the summit in the months ahead. */
const PLAY_BODIES = [
  { body: CoreBody.moon, name: "Moon" },
  { body: CoreBody.sun, name: "Sun" },
  { body: CoreBody.venus, name: "Venus" },
  { body: CoreBody.jupiter, name: "Jupiter" },
  { body: CoreBody.saturn, name: "Saturn" },
  { body: CoreBody.mars, name: "Mars" },
].filter((b) => b.body === CoreBody.moon || shownAlignments(b.body).length > 0);
const playSubject = $<HTMLSelectElement>("play-subject");
const playDate = $<HTMLSelectElement>("play-date");
const playLens = $<HTMLSelectElement>("play-lens");
const play = { matches: [] as Alignment[], time: SHOT.unixUtc + 40, focal: FOCAL_MM };

function playBody() {
  return PLAY_BODIES[Number(playSubject.value)] ?? PLAY_BODIES[0];
}

function fillPlayDates() {
  const b = playBody();
  play.matches = shownAlignments(b.body);
  playDate.replaceChildren(
    ...play.matches.map((a, i) => {
      const option = document.createElement("option");
      option.value = String(i);
      const sun = sunAt(a.unixUtc).altitudeDeg;
      const extra =
        b.body === CoreBody.moon ? ` · ${percent(fractionAt(a.unixUtc))}` : ` · ${light(sun)}`;
      option.textContent = `${date(a.unixUtc)} · ${hhmm(a.unixUtc)}${extra}`;
      return option;
    }),
  );
  if (!play.matches.length) {
    const option = document.createElement("option");
    option.textContent = "No match in 2026 and 2027";
    playDate.append(option);
  }
  const story = play.matches.findIndex((a) => Math.abs(a.unixUtc - SHOT.unixUtc) < HOUR);
  playDate.value = String(Math.max(0, story));
  pickPlayDate();
}

function pickPlayDate() {
  const a = play.matches[Number(playDate.value)];
  play.time = a ? a.unixUtc + (playBody().body === CoreBody.moon ? 40 : 0) : SHOT.unixUtc;
}

const playTime = () => play.time;

playSubject.replaceChildren(
  ...PLAY_BODIES.map((b, i) => {
    const option = document.createElement("option");
    option.value = String(i);
    option.textContent = b.name;
    return option;
  }),
);
playSubject.addEventListener("change", fillPlayDates);
playDate.addEventListener("change", pickPlayDate);
playLens.addEventListener("change", () => (play.focal = Number(playLens.value)));
// With one subject, the choice has no use, so it is hidden.
$("play-subject-label").hidden = PLAY_BODIES.length < 2;
fillPlayDates();

// --- The contact sheet of the calendar -------------------------------------------------------------

const sheetList = $("calendar");

/** A small moon in its phase, as SVG: the lit part on the side of the sun. */
function moonIcon(fraction: number, waxing: boolean): SVGSVGElement {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "-1 -1 2 2");
  svg.setAttribute("aria-hidden", "true");
  const disc = document.createElementNS(ns, "circle");
  disc.setAttribute("r", "1");
  disc.setAttribute("class", "dark");
  const lit = document.createElementNS(ns, "path");
  const k = 1 - 2 * fraction;
  const side = waxing ? 1 : -1;
  // The limb on the lit side, then the terminator back as a half ellipse.
  lit.setAttribute(
    "d",
    `M 0 -1 A 1 1 0 0 ${side > 0 ? 1 : 0} 0 1 A ${Math.abs(k)} 1 0 0 ${k * side > 0 ? 0 : 1} 0 -1 Z`,
  );
  lit.setAttribute("class", "lit");
  svg.append(disc, lit);
  return svg;
}

/** A small SVG icon of a subject or an eclipse, in a box of -1 to 1. */
function icon(kind: string): SVGSVGElement {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "-1 -1 2 2");
  svg.setAttribute("aria-hidden", "true");
  const shapes: Record<string, string> = {
    sun: '<circle r="0.45" class="lit"/><g class="ray"><path d="M0 -0.95V-0.65M0 0.65V0.95M-0.95 0H-0.65M0.65 0H0.95M-0.67 -0.67L-0.46 -0.46M0.46 0.46L0.67 0.67M-0.67 0.67L-0.46 0.46M0.46 -0.46L0.67 -0.67"/></g>',
    planet:
      '<circle r="0.32" class="lit"/><ellipse rx="0.8" ry="0.22" class="ring" transform="rotate(-20)"/>',
    milkyway:
      '<ellipse rx="0.95" ry="0.32" class="band" transform="rotate(-35)"/><circle r="0.22" class="lit"/>',
    solar: '<circle r="0.8" class="lit"/><circle cx="0.32" cy="-0.2" r="0.8" class="cover"/>',
    lunar: '<circle r="0.8" class="red"/>',
  };
  svg.innerHTML = shapes[kind] ?? shapes.planet;
  return svg;
}

/** A frame of a contact sheet: a button with an icon, a title, and a line of text. */
function frameButton(svg: SVGSVGElement, title: string, line: string, label: string) {
  const item = document.createElement("li");
  const button = document.createElement("button");
  button.type = "button";
  button.setAttribute("aria-pressed", "false");
  button.setAttribute("aria-label", label);
  const text = document.createElement("span");
  const b = document.createElement("b");
  b.textContent = title;
  text.append(b, line);
  button.append(svg, text);
  item.append(button);
  return { item, button };
}

sheetList.replaceChildren(
  ...sheet.map((a, i) => {
    const f = fractionAt(a.unixUtc);
    const { item, button } = frameButton(
      moonIcon(f, waxingAt(a.unixUtc)),
      dayMonth(a.unixUtc),
      hhmm(a.unixUtc),
      `${date(a.unixUtc)}, ${hhmm(a.unixUtc)}: ${phaseName(f, waxingAt(a.unixUtc)).toLowerCase()}, ${percent(f)} lit`,
    );
    if (Math.abs(a.unixUtc - SHOT.unixUtc) < HOUR) button.classList.add("story");
    pickOnClick(button, "calendar", i, () => sheet.length);
    return item;
  }),
);

// --- The chips of the subjects ------------------------------------------------------------------

/** The chips name the subjects in the order of allSubjects(). */
const SUBJECT_KEYS = ["moon", "milkyway", "sun", "planet"];
document.querySelectorAll<HTMLButtonElement>("[data-subject]").forEach((chip) => {
  const index = SUBJECT_KEYS.indexOf(chip.dataset.subject!);
  pickOnClick(chip, "subject", index, () => SUBJECT_KEYS.length);
});

// --- The contact sheet of way A ---------------------------------------------------------------------

const hereList = $("here-sheet");

function fillHereSheet() {
  hereList.replaceChildren(
    ...hereShots.map((s, i) => {
      const kind =
        s.key === "moon" ? "moon" : s.key === "sun" ? "sun" : s.milkyWay ? "milkyway" : "planet";
      const svg = kind === "moon" ? moonIcon(fractionAt(s.time), waxingAt(s.time)) : icon(kind);
      const where = s.peak ? `behind the ${s.peak.name}` : "alone over the ridge";
      const { item, button } = frameButton(
        svg,
        s.short,
        dayMonth(s.time),
        `${s.name} ${where}: ${date(s.time)}, ${hhmm(s.time)}`,
      );
      pickOnClick(button, "here", i, () => hereShots.length);
      return item;
    }),
  );
  activeHere = -1;
}

// --- The list of the eclipses -----------------------------------------------------------------------

const eclipseList = $("eclipse-sheet");
eclipseList.replaceChildren(
  ...ECLIPSES.map((e, i) => {
    const year = format(e.peakUtc, { year: "2-digit" });
    const { item, button } = frameButton(
      icon(e.type),
      `${dayMonth(e.peakUtc)} ${year}`,
      e.type === "solar" ? `Sun ${percent(e.local!.obscuration)}` : ECLIPSE_NAMES[e.kind],
      `${e.name}, ${longDate(e.peakUtc)}`,
    );
    // The icon shows the moon of a lunar eclipse, so its label is the kind only: "Moon total" is
    // too long for the tile.
    // The list picks the eclipse of the chapter; the scroll moves from the map to the photo.
    button.addEventListener("click", () => {
      eclipseIndex = i;
      press(eclipseList, i);
    });
    return item;
  }),
);
press(eclipseList, eclipseIndex);

// --- The scroll position ------------------------------------------------------------------------

const sections = [...document.querySelectorAll<HTMLElement>("[data-chapter]")];

/** The chapter in the middle of the screen, and the progress through it. */
function scrollState(): { name: string; progress: number } {
  const middle = innerHeight / 2;
  for (const section of sections) {
    const rect = section.getBoundingClientRect();
    if (rect.top <= middle && rect.bottom > middle) {
      const travel = Math.max(1, rect.height - innerHeight);
      return { name: section.dataset.chapter!, progress: clamp(-rect.top / travel) };
    }
  }
  return { name: scrollY < 10 ? "hero" : "rest", progress: 0 };
}

// --- The rail: one frame of film for each chapter ---------------------------------------------------

const rail = $("rail");
const railStops = sections
  .filter((section) => section.dataset.chapter !== "rest")
  .map((section, i) => {
    const step = section.querySelector(".step b");
    const label = section.dataset.chapter === "hero" ? "Shotradar" : (step?.textContent ?? "");
    const button = document.createElement("button");
    button.type = "button";
    button.setAttribute("aria-label", label);
    const number = document.createElement("i");
    number.textContent = String(i).padStart(2, "0");
    const text = document.createElement("span");
    text.textContent = label;
    button.append(number, text);
    button.addEventListener("click", () => {
      const top = section.getBoundingClientRect().top + scrollY;
      const travel = Math.max(0, section.offsetHeight - innerHeight);
      scrollTo({ top: top + travel * 0.5, behavior: reducedMotion.matches ? "auto" : "smooth" });
    });
    rail.append(button);
    return { name: section.dataset.chapter!, button };
  });

function updateRail(name: string) {
  const active = railStops.findIndex((stop) => stop.name === name);
  railStops.forEach((stop, i) => {
    stop.button.classList.toggle("active", i === active);
    stop.button.classList.toggle("done", active >= 0 && i < active);
  });
}

// --- The readouts -------------------------------------------------------------------------------

function setText(id: string, text: string) {
  const element = document.getElementById(id);
  if (element && element.textContent !== text) element.textContent = text;
}

let activeSubject = "";
let activeSheet = -1;
let activeHere = -1;

function readouts(name: string, target: Target, t: number, p: number) {
  if (name === "here" && target.subject) {
    const s = target.subject as HereShot;
    const i = hereShots.indexOf(s);
    if (i !== activeHere) {
      activeHere = i;
      press(hereList, i);
    }
    const sun = sunAt(s.time).altitudeDeg;
    setText("here-name", `${date(s.time)} · ${hhmm(s.time)}`);
    setText("here-detail", s.detail);
    setText(
      "here-light",
      s.key === "moon"
        ? `${phaseName(fractionAt(s.time), waxingAt(s.time))}, ${percent(fractionAt(s.time))} lit · ${light(sun)}`
        : `${light(sun)} · the sun at ${deg(sun, 1)}`,
    );
  } else if (name === "subject" && target.subject) {
    const s = target.subject;
    if (activeSubject !== s.key) {
      activeSubject = s.key;
      document.querySelectorAll<HTMLElement>("[data-subject]").forEach((chip) => {
        chip.setAttribute("aria-pressed", String(chip.dataset.subject === s.key));
      });
    }
    setText("subject-name", s.name);
    setText("subject-detail", s.detail);
  } else if (name === "calendar") {
    const i = choice("calendar", p, sheet.length);
    if (i !== activeSheet) {
      activeSheet = i;
      press(sheetList, i);
    }
    const a = sheet[i];
    if (!a) return;
    const f = fractionAt(a.unixUtc);
    setText("calendar-date", `${date(a.unixUtc)} · ${hhmm(a.unixUtc)}`);
    setText(
      "calendar-detail",
      `${phaseName(f, waxingAt(a.unixUtc))}, ${percent(f)} lit · ${light(sunAt(a.unixUtc).altitudeDeg)}`,
    );
  } else if (name === "eclipse" && target.eclipse) {
    const e = target.eclipse;
    setText("eclipse-name", `${date(e.peakUtc)} · ${e.name}`);
    setText("eclipse-here", e.here);
    setText("eclipse-line", e.line);
    if (target.mode === "photo") {
      const now =
        e.type === "solar"
          ? `${percent(discCoverage(separation(airlessSunAt(t), airlessMoonAt(t)), e.sunRadiusDeg, e.moonRadiusDeg))} of the sun covered`
          : `${percent(umbraAt(e.lunar!, t))} of the moon in the umbra`;
      setText("eclipse-now", `${hhmm(t)} ${zoneName(t)} · ${now}`);
    } else setText("eclipse-now", narrow() ? "" : "Scroll on for the photo from the ridge.");
  } else if (name === "spot") {
    const line = lineAt(t);
    const near = line.spots[0];
    setText("spot-time", `${hhmm(t)} ${zoneName(t)}`);
    setText(
      "spot-detail",
      line.spots.length
        ? `${line.spots.length} ${line.spots.length === 1 ? "spot" : "spots"} on the line. The nearest: ${(near.distanceM / 1000).toFixed(1)} km from the summit, at ${near.groundElevationM.toFixed(0)} m.`
        : "No spot: the ground on the line is too high or too low.",
    );
  } else if (name === "shot") {
    const moon = moonAt(t);
    setText("shot-time", `${hhmmss(t)} ${zoneName(t)}`);
    setText(
      "shot-detail",
      `${FOCAL_MM} mm · moon az ${deg(moon.azimuthDeg)} · alt ${deg(moon.altitudeDeg)} · ${percent(fractionAt(t))}`,
    );
  } else if (name === "play") {
    const b = playBody();
    const a = play.matches[Number(playDate.value)];
    if (!a) {
      setText("play-readout", `${b.name} does not pass the summit in 2026 and 2027.`);
      return;
    }
    const sun = sunAt(a.unixUtc).altitudeDeg;
    setText(
      "play-readout",
      `${b.name} behind the ${PEAK.name}: ${longDate(a.unixUtc)}, ${hhmm(a.unixUtc)} · ${light(sun)} (sun ${deg(sun, 1)})` +
        (b.body === CoreBody.moon ? ` · ${percent(fractionAt(a.unixUtc))} lit` : "") +
        ` · ${play.focal} mm`,
    );
  }
}

function hud(name: string, t: number, view: View): Frame["hud"] {
  const moon = moonAt(t);
  const stamp = `${date(t).toUpperCase()} · ${hhmmss(t)} ${zoneName(t)}`;
  if (name === "here") {
    return {
      topLeft: `${view.spanDeg.toFixed(0)}° FIELD · ${PEAKS.length} PEAKS`,
      topRight: `AZ ${deg(wrap360(view.azimuthDeg), 1)}`,
      bottom: stamp,
    };
  }
  if (name === "eclipse") {
    const sun = airlessSunAt(t);
    return {
      topLeft: `${view.spanDeg.toFixed(0)}° FIELD`,
      topRight: `SUN ${deg(sun.altitudeDeg, 1)} · MOON ${deg(moon.altitudeDeg, 1)}`,
      bottom: stamp,
    };
  }
  if (name === "subject") {
    return {
      topLeft: `${view.spanDeg.toFixed(0)}° FIELD`,
      topRight: `AZ ${deg(view.azimuthDeg, 1)}`,
      bottom: stamp,
    };
  }
  return {
    topLeft: `AZ ${deg(SIGHT.azimuthDeg)} · ALT ${deg(SIGHT.altitudeDeg)}`,
    topRight: `MOON ${percent(fractionAt(t))} · ${deg(moon.altitudeDeg, 1)}`,
    bottom: `${stamp} · ${(SIGHT.distanceM / 1000).toFixed(2)} KM`,
  };
}

// --- The frame ----------------------------------------------------------------------------------

const canvas = $<HTMLCanvasElement>("scene");
const scene = new Scene(canvas, null, dust);
let stars: Stars | null = null;
fetch(starsUrl)
  .then((r) => r.json())
  .then((json: StarsJson) => {
    stars = decodeStars(json);
    scene.setStars(stars);
  })
  .catch(() => undefined);

const shown = {
  time: heroTime(),
  az: SIGHT.azimuthDeg,
  alt: SIGHT.altitudeDeg + 0.7,
  logSpan: Math.log(6.5),
  photo: 1,
  map: 0,
  profile: 0,
  world: 0,
  peaks: 0,
  pin: 0,
  finder: 0,
  print: 1,
  track: 0,
  fade: 0,
  flash: 0,
  progress: 0,
};
let shownChapter = "";
let shownSubject: Subject | null = null;
let lastMs = performance.now();

function frame(ms: number) {
  const dt = Math.min(0.1, (ms - lastMs) / 1000);
  lastMs = ms;
  heroMs += dt * 1000;
  const { name, progress } = scrollState();
  rawProgress = { name, progress };
  if (document.body.dataset.chapter !== name) updateRail(name);
  document.body.dataset.chapter = name;
  const k = reducedMotion.matches ? 1 : 1 - Math.exp(-dt * 5);
  if (name !== shownChapter) {
    shownChapter = name;
    shown.progress = progress;
  } else shown.progress += (progress - shown.progress) * Math.min(1, k * 1.6);

  const target: Target = {
    time: SHOT.unixUtc,
    view: tele(9),
    mode: "photo",
    pin: 0,
    finder: 0,
    print: 1,
    subject: null,
    trackAlpha: 0,
    reveal: 0,
    focal: FOCAL_MM,
    peaks: 0,
    activePeak: null,
    eclipse: null,
    ...chapters[name](shown.progress),
  };

  // A jump of more than three hours (another date, another subject) fades out and in, like the
  // next frame on a roll of film. A shorter change of the time moves the sky smoothly.
  const jump = Math.abs(target.time - shown.time) > 3 * HOUR || target.subject !== shownSubject;
  if (jump) {
    if (shown.fade >= 0.97 || reducedMotion.matches) {
      shown.time = target.time;
      shownSubject = target.subject;
      shown.track = 0;
    }
    shown.fade += (1 - shown.fade) * Math.min(1, k * 4);
  } else {
    shown.fade += (0 - shown.fade) * Math.min(1, k * 1.5);
    shown.time += (target.time - shown.time) * Math.min(1, k * 1.5);
  }
  // The view moves in log of the span, so a zoom feels the same at each scale.
  shown.az += wrap180(target.view.azimuthDeg - shown.az) * k;
  shown.alt += (target.view.altitudeDeg - shown.alt) * k;
  shown.logSpan += (Math.log(target.view.spanDeg) - shown.logSpan) * k;
  for (const mode of ["photo", "map", "profile", "world"] as const) {
    shown[mode] += ((target.mode === mode ? 1 : 0) - shown[mode]) * Math.min(1, k * 1.4);
  }
  shown.pin += (target.pin - shown.pin) * k;
  shown.peaks += (target.peaks - shown.peaks) * k;
  shown.finder += (target.finder - shown.finder) * k;
  shown.print += (target.print - shown.print) * k;
  shown.track += ((shownSubject ? target.trackAlpha : 0) - shown.track) * k;

  // The flash of the shutter, once when the time of the shot passes in the last chapter.
  if (name === "shot" && !reducedMotion.matches) {
    const before = shown.time - SHOT.unixUtc;
    if (Math.abs(before) < 4 && !flashed) {
      shown.flash = 1;
      flashed = true;
    }
    if (Math.abs(before) > 20) flashed = false;
  }
  shown.flash *= Math.exp(-dt * 5);

  const t = shown.time;
  // An eclipse uses the positions without the refraction, as the contacts of the core do.
  const eclipse = isEclipse(shownSubject) ? shownSubject : null;
  const sun = eclipse ? airlessSunAt(t) : sunAt(t);
  const moon = eclipse ? airlessMoonAt(t) : moonAt(t);
  const view: View = {
    azimuthDeg: shown.az,
    altitudeDeg: shown.alt,
    spanDeg: Math.exp(shown.logSpan),
  };
  const subject = shownSubject;
  const limit = limitingMagnitude(sun.altitudeDeg);
  const night = limit > 1;
  let mapState: MapState | null = null;
  if (shown.map > 0.01) {
    const line = lineAt(t);
    const m = moonAt(t);
    mapState = {
      points: line.points,
      spots: line.spots,
      moonAzimuthDeg: m.azimuthDeg,
      label: `${hhmm(t)} · LINE TO ${deg((m.azimuthDeg + 180) % 360, 1)}`,
    };
  }
  const f: Frame = {
    sun,
    moon,
    moonFraction: fractionAt(t),
    view,
    photo: shown.photo,
    map: shown.map,
    profile: shown.profile,
    print: shown.print,
    pin: shown.pin,
    finder: shown.finder,
    focalMm: target.focal,
    track: subject?.track ?? null,
    trackAlpha: shown.track,
    body: subject?.body(t) ?? null,
    stars: night && stars ? almucantar.starPositions(stars.catalog, t, SPOT.observer) : null,
    limitMag: limit,
    dust: subject?.milkyWay ? almucantar.starPositions(dust.catalog, t, SPOT.observer) : null,
    milkyWay: subject?.milkyWay ? clamp((limit - 3.5) / 2.5) : 0,
    hud: hud(name, t, view),
    mapState,
    profileState:
      shown.profile > 0.01
        ? {
            reveal: target.mode === "profile" ? target.reveal : 1,
            clearance: CLEAR,
            timeline: TIMELINE,
            shotT: SHOT.unixUtc,
            ticks: TICKS,
          }
        : null,
    world: shown.world,
    worldState: eclipse?.world ?? null,
    peaks:
      shown.peaks > 0.01
        ? PEAKS.map((p): PeakMark => ({
            direction: p.direction,
            label: p.label,
            active: p === target.activePeak,
          }))
        : null,
    peaksAlpha: shown.peaks,
    solarEclipse:
      eclipse?.type === "solar"
        ? {
            sunRadiusDeg: eclipse.sunRadiusDeg,
            moonRadiusDeg: eclipse.moonRadiusDeg,
            coverage: discCoverage(
              separation(sun, moon),
              eclipse.sunRadiusDeg,
              eclipse.moonRadiusDeg,
            ),
          }
        : null,
    lunarUmbra: eclipse?.type === "lunar" ? umbraAt(eclipse.lunar!, t) : 0,
    fade: shown.fade,
    flash: shown.flash,
  };
  scene.draw(f, ms);
  readouts(name, target, t, shown.progress);
  requestAnimationFrame(frame);
}
let flashed = false;

// --- The static texts that the core gives -----------------------------------------------------------

setText("hero-time", `${longDate(SHOT.unixUtc)} · ${hhmm(SHOT.unixUtc)} ${zoneName(SHOT.unixUtc)}`);
setText(
  "check-view",
  VIEW_CHECK.visible ? `Clear view, ${CLEAR.deg.toFixed(1)}° to spare` : "The view is blocked",
);
const shotSun = sunAt(SHOT.unixUtc).altitudeDeg;
setText(
  "check-detail",
  `The highest ground in front of the peak is ${((SIGHT.distanceM * CLEAR.index) / (PROFILE.groundM.length - 1) / 1000).toFixed(1)} km away and stays ${CLEAR.deg.toFixed(1)}° below the summit.`,
);
const darkText = darkTonight.length
  ? `The first dark sky of the night starts at ${hhmm(darkTonight[0].startUtc)}, when the moon has set behind the mountains.`
  : nextDark
    ? `The full moon keeps the sky bright all night; the next dark sky is on ${dayMonth(nextDark.startUtc)} from ${hhmm(nextDark.startUtc)}.`
    : "The moon keeps the sky bright all night.";
setText(
  "check-light",
  `${light(shotSun)}: the sun is ${deg(-shotSun, 1)} below the horizon. ${darkText}`,
);

document.documentElement.classList.add("ready");
requestAnimationFrame(frame);
void searchHere();
