// The website of shotradar: one print on the paper of the page that develops while you scroll.
// Each chapter is a step of the workflow of the app (the subject, the landmark, the calendar, the
// spot, the check, and the shot), and it sets the time, the view, and the picture of the print.
// The core (the same WebAssembly package as the app) calculates the sky, the alignments, the line
// of the spots, and the darkness; the terrain comes from src/shot.json.

import { Body as CoreBody, load } from "@almucantar/core";
import type { Alignment, AlignmentLine, Horizontal } from "@almucantar/core";
import { milkyWayDust } from "@almucantar/web-shared/milkyway";
import { decodeStars, limitingMagnitude } from "@almucantar/web-shared/stars";
import type { Stars, StarsJson } from "@almucantar/web-shared/stars";
import starsUrl from "../../../../assets/stars/stars.json?url";
import { HORIZON, MAP, PEAK, PROFILE, SHOT, SIGHT, SPOT, ZONE } from "./data";
import { Scene } from "./scene";
import type { Body, Frame, MapState, Moment, ProfileState, Track, View } from "./scene";

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

const calendar = alignments(CoreBody.moon);
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

type Mode = "photo" | "map" | "profile";

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
  subject: (p) => {
    const list = allSubjects();
    const s = list[Math.min(list.length - 1, Math.floor(p * list.length))];
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
    const a = sheet[Math.min(sheet.length - 1, Math.floor(p * sheet.length))];
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
].filter((b) => b.body === CoreBody.moon || alignments(b.body, 0.3).length > 0);
const playSubject = $<HTMLSelectElement>("play-subject");
const playDate = $<HTMLSelectElement>("play-date");
const playLens = $<HTMLSelectElement>("play-lens");
const play = { matches: [] as Alignment[], time: SHOT.unixUtc + 40, focal: FOCAL_MM };

function playBody() {
  return PLAY_BODIES[Number(playSubject.value)] ?? PLAY_BODIES[0];
}

function fillPlayDates() {
  const b = playBody();
  play.matches = alignments(b.body, 0.3);
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

sheetList.replaceChildren(
  ...sheet.map((a, i) => {
    const item = document.createElement("li");
    const f = fractionAt(a.unixUtc);
    item.append(moonIcon(f, waxingAt(a.unixUtc)));
    const text = document.createElement("span");
    text.innerHTML = `<b>${dayMonth(a.unixUtc)}</b>${hhmm(a.unixUtc)}`;
    item.append(text);
    item.dataset.index = String(i);
    if (Math.abs(a.unixUtc - SHOT.unixUtc) < HOUR) item.classList.add("story");
    return item;
  }),
);

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

function readouts(name: string, target: Target, t: number, p: number) {
  if (name === "subject" && target.subject) {
    const s = target.subject;
    if (activeSubject !== s.key) {
      activeSubject = s.key;
      document.querySelectorAll<HTMLElement>("[data-subject]").forEach((chip) => {
        chip.classList.toggle("active", chip.dataset.subject === s.key);
      });
    }
    setText("subject-name", s.name);
    setText("subject-detail", s.detail);
  } else if (name === "calendar") {
    const i = Math.min(sheet.length - 1, Math.floor(p * sheet.length));
    if (i !== activeSheet) {
      activeSheet = i;
      sheetList.querySelectorAll("li").forEach((li, j) => li.classList.toggle("active", j === i));
    }
    const a = sheet[i];
    if (!a) return;
    const f = fractionAt(a.unixUtc);
    setText("calendar-date", `${date(a.unixUtc)} · ${hhmm(a.unixUtc)}`);
    setText(
      "calendar-detail",
      `${phaseName(f, waxingAt(a.unixUtc))}, ${percent(f)} lit · ${light(sunAt(a.unixUtc).altitudeDeg)}`,
    );
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
  if (name === "subject") {
    return {
      topLeft: `${(view.spanDeg / 1).toFixed(0)}° FIELD`,
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
fetch(starsUrl)
  .then((r) => r.json())
  .then((json: StarsJson) => {
    stars = decodeStars(json);
    scene.setStars(stars);
  })
  .catch(() => undefined);
let stars: Stars | null = null;

const shown = {
  time: heroTime(),
  az: SIGHT.azimuthDeg,
  alt: SIGHT.altitudeDeg + 0.7,
  logSpan: Math.log(6.5),
  photo: 1,
  map: 0,
  profile: 0,
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
  for (const mode of ["photo", "map", "profile"] as const) {
    shown[mode] += ((target.mode === mode ? 1 : 0) - shown[mode]) * Math.min(1, k * 1.4);
  }
  shown.pin += (target.pin - shown.pin) * k;
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
  const sun = sunAt(t);
  const moon = moonAt(t);
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
