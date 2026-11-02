// The website: a sky in the background that follows the scroll position. Each chapter of the
// page sets the time, the date, and the parts of the sky. The core (the same WebAssembly package
// as the app) calculates the sun and the moon.

import { load } from "@sunradar/core";
import type { Horizontal, Observer, PathPoint } from "@sunradar/core";
import { Sky } from "./sky";
import type { Frame, HourMark } from "./sky";

const sunradar = await load();

const DAY = 86400;
const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const ease = (t: number) => t * t * (3 - 2 * t);
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");

// --- The place and the time zone ----------------------------------------------------------------

let observer: Observer = { latitudeDeg: 48.2082, longitudeDeg: 16.3738 };
let timeZone = "Europe/Vienna";
let placeName = "Vienna";

/** The offset of the time zone from UTC at a time, in seconds. */
function zoneOffset(unixUtc: number): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
    second: "numeric",
  }).formatToParts(new Date(unixUtc * 1000));
  const get = (type: string) => Number(parts.find((part) => part.type === type)!.value);
  const local = Date.UTC(get("year"), get("month") - 1, get("day"), get("hour"), get("minute"));
  return Math.round((local / 1000 - Math.floor(unixUtc / 60) * 60) / 60) * 60;
}

/** The start of the local day that contains a time. */
function startOfDay(unixUtc: number): number {
  const local = unixUtc + zoneOffset(unixUtc);
  const midnight = Math.floor(local / DAY) * DAY;
  return midnight - zoneOffset(midnight - zoneOffset(unixUtc));
}

/** The start of a local date. The month starts at 0. */
function dateStart(year: number, month: number, day: number): number {
  const guess = Date.UTC(year, month, day) / 1000;
  return guess - zoneOffset(guess - zoneOffset(guess));
}

function localYear(unixUtc: number): number {
  return new Date((unixUtc + zoneOffset(unixUtc)) * 1000).getUTCFullYear();
}

const hhmm = (unixUtc: number) =>
  new Date(unixUtc * 1000).toLocaleTimeString([], { timeZone, hour: "2-digit", minute: "2-digit" });
const dayMonth = (unixUtc: number) =>
  new Date(unixUtc * 1000).toLocaleDateString([], { timeZone, day: "numeric", month: "long" });

// --- The days, with a cache ---------------------------------------------------------------------

interface Day {
  start: number;
  points: PathPoint[];
  path: Horizontal[];
  hours: HourMark[];
  sunrise: number | null;
  sunset: number | null;
}

const days = new Map<string, Day>();

function dayAt(unixUtc: number): Day {
  const start = startOfDay(unixUtc);
  const key = `${start}:${observer.latitudeDeg}:${observer.longitudeDeg}`;
  const cached = days.get(key);
  if (cached) return cached;
  const end = startOfDay(start + DAY + 7200);
  const points = sunradar.sunPath(observer, start, end, { stepS: 300 });
  const events = sunradar.sunEvents(observer, start, end);
  const day: Day = {
    start,
    points,
    path: points.map((p) => p.position),
    hours: points
      .filter((p) => (p.unixUtc + zoneOffset(p.unixUtc)) % 3600 === 0)
      .map((p) => ({ direction: p.position, label: hhmm(p.unixUtc).slice(0, 2) })),
    sunrise: events.crossings.find((c) => c.rising)?.unixUtc ?? null,
    sunset: events.crossings.find((c) => !c.rising)?.unixUtc ?? null,
  };
  if (days.size > 400) days.clear();
  days.set(key, day);
  return day;
}

function solsticeGuides(year: number): Horizontal[][] {
  return [5, 11].map((month) => dayAt(dateStart(year, month, 21) + 43200).path);
}

// --- The chapters -------------------------------------------------------------------------------

interface Target {
  time: number;
  guides: number;
  band: number;
  window: number;
  moon: boolean;
  /** The chapter hides the moon, because it changes the date all the time. */
  hideMoon: boolean;
  tap: number;
  dim: number;
}

const now = () => Date.now() / 1000;
let dragHours = 0;

/** The window of the third chapter, in the directions of the sky. */
const WINDOW = { azimuth: [150, 214] as [number, number], altitude: [4, 44] as [number, number] };
/** The middle of the window. The readout gives the days on which the sun is there. */
const WINDOW_MIDDLE: Horizontal = {
  azimuthDeg: (WINDOW.azimuth[0] + WINDOW.azimuth[1]) / 2,
  altitudeDeg: (WINDOW.altitude[0] + WINDOW.altitude[1]) / 2,
};

/** The next night with a bright moon high in the sky, starting from tonight. */
function moonNight(): number {
  const today = startOfDay(now());
  for (let i = 0; i < 31; i++) {
    const midnight = today + (i + 1) * DAY;
    const fraction = sunradar.moonIlluminatedFraction(midnight);
    if (fraction > 0.55 && sunradar.moonPosition(midnight, observer).altitudeDeg > 15) {
      return midnight;
    }
  }
  return today + DAY;
}

let night = moonNight();

interface MoonArc {
  /** The path from moonrise to moonset. */
  path: Horizontal[];
  /** The time range of the moon chapter: the moon is up and the sky is dark. */
  from: number;
  to: number;
}

let moonArcKey = "";
let moonArcValue: MoonArc = { path: [], from: 0, to: 0 };

/**
 * The path of the moon from its rise to its set, around the midnight of the moon chapter. The
 * samples cover 14 hours on each side, because the moon can rise long before midnight.
 */
function moonArc(): MoonArc {
  const key = `${night}:${observer.latitudeDeg}:${observer.longitudeDeg}`;
  if (key === moonArcKey) return moonArcValue;
  const step = 600;
  const begin = night - 14 * 3600;
  const moon: Horizontal[] = [];
  const dark: boolean[] = [];
  for (let t = begin; t <= night + 14 * 3600; t += step) {
    moon.push(sunradar.moonPosition(t, observer));
    dark.push(sunradar.sunPosition(t, observer).altitudeDeg < -8);
  }
  // Go from midnight to both sides while the moon is above the horizon. Keep the first sample
  // below the horizon, so the line reaches the horizon.
  const middle = Math.round((14 * 3600) / step);
  let first = middle;
  while (first > 0 && moon[first].altitudeDeg > 0) first--;
  let last = middle;
  while (last < moon.length - 1 && moon[last].altitudeDeg > 0) last++;
  // The chapter shows the part in a dark sky and above the skyline (its buildings are at most
  // about 5° high), so the moon moves along its path until it goes behind the buildings.
  let from = middle;
  while (from > first && dark[from - 1] && moon[from - 1].altitudeDeg > 6) from--;
  let to = middle;
  while (to < last && dark[to + 1] && moon[to + 1].altitudeDeg > 6) to++;
  moonArcKey = key;
  moonArcValue = {
    path: moon.slice(first, last + 1),
    from: begin + from * step,
    to: begin + to * step,
  };
  return moonArcValue;
}

const chapters: Record<string, (p: number) => Partial<Target>> = {
  hero: () => ({ time: now() + dragHours * 3600 }),
  day: (p) => {
    const d = dayAt(now());
    const from = (d.sunrise ?? d.start + 6 * 3600) - 1800;
    const to = (d.sunset ?? d.start + 18 * 3600) + 1800;
    return { time: from + (to - from) * p };
  },
  year: (p) => {
    // From the summer solstice to the winter solstice, at the same hour as now.
    const year = localYear(now());
    const june = dateStart(year, 5, 21);
    const december = dateStart(year, 11, 21);
    const hour = clamp((now() - startOfDay(now())) / 3600, 9, 15);
    const date = june + (december - june) * p;
    return { time: startOfDay(date) + hour * 3600, guides: 1, hideMoon: true };
  },
  window: (p) => {
    const d = dayAt(now());
    const from = d.start + 8 * 3600;
    const to = d.start + 17 * 3600;
    return { time: from + (to - from) * p, window: 1 };
  },
  moon: (p) => {
    const arc = moonArc();
    return { time: arc.from + (arc.to - arc.from) * p, moon: true };
  },
  compass: (p) => {
    const d = dayAt(now());
    const time = d.start + 13 * 3600 + p * 3 * 3600;
    // Tap the sun at the middle of the chapter: the band gets narrow.
    const aligned = ease(clamp((p - 0.45) / 0.12));
    return { time, band: 12 - 11.5 * aligned, tap: clamp((p - 0.45) / 0.1) };
  },
  play: () => ({ time: playTime(), guides: 1 }),
  rest: () => ({ time: now(), dim: 0.62 }),
};

// --- The playground -----------------------------------------------------------------------------

const playDate = $<HTMLInputElement>("play-date");
const playHour = $<HTMLInputElement>("play-hour");

function playTime(): number {
  const year = localYear(now());
  const start = dateStart(year, 0, 1) + Number(playDate.value) * DAY + 7200;
  return startOfDay(start) + (Number(playHour.value) / 60) * 3600;
}

function resetPlay() {
  const t = now();
  const year = localYear(t);
  playDate.value = String(Math.floor((startOfDay(t) - dateStart(year, 0, 1) + 7200) / DAY));
  playHour.value = String(Math.round((t - startOfDay(t)) / 60));
}
resetPlay();

$("play-location").addEventListener("click", () => {
  const button = $<HTMLButtonElement>("play-location");
  if (!navigator.geolocation) {
    button.textContent = "No location in this browser";
    return;
  }
  button.textContent = "Finding you …";
  navigator.geolocation.getCurrentPosition(
    (position) => {
      observer = { latitudeDeg: position.coords.latitude, longitudeDeg: position.coords.longitude };
      timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      placeName = `${observer.latitudeDeg.toFixed(2)}°, ${observer.longitudeDeg.toFixed(2)}°`;
      days.clear();
      night = moonNight();
      datesFor = "";
      button.textContent = "Your location";
    },
    () => (button.textContent = "No location: the sky stays in Vienna"),
    { enableHighAccuracy: false, timeout: 15000 },
  );
});

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
  return { name: "rest", progress: 0 };
}

// --- The input: drag the sky to move the sun ----------------------------------------------------

const canvas = $<HTMLCanvasElement>("sky");
const sky = new Sky(canvas);
let drag: { x: number; hours: number } | null = null;

canvas.addEventListener("pointerdown", (e) => {
  if (scrollState().name !== "hero") return;
  drag = { x: e.clientX, hours: dragHours };
  canvas.setPointerCapture(e.pointerId);
  document.body.classList.add("dragging");
});
canvas.addEventListener("pointermove", (e) => {
  if (!drag) return;
  // One screen width is half a day.
  dragHours = drag.hours + ((e.clientX - drag.x) / innerWidth) * 12;
});
const endDrag = () => {
  drag = null;
  document.body.classList.remove("dragging");
};
canvas.addEventListener("pointerup", endDrag);
canvas.addEventListener("pointercancel", endDrag);

// --- The readouts of the chapters ---------------------------------------------------------------

/** The time ranges in which the sun is in the window. */
function windowTimes(day: Day): string {
  const ranges: [number, number][] = [];
  let open: number | null = null;
  for (const point of day.points) {
    const { azimuthDeg: az, altitudeDeg: alt } = point.position;
    const inside =
      az >= WINDOW.azimuth[0] &&
      az <= WINDOW.azimuth[1] &&
      alt >= WINDOW.altitude[0] &&
      alt <= WINDOW.altitude[1];
    if (inside && open === null) open = point.unixUtc;
    if (!inside && open !== null) {
      ranges.push([open, point.unixUtc]);
      open = null;
    }
  }
  if (!ranges.length) return "The sun does not reach this window today.";
  return `Sun in the window today: ${ranges.map(([a, b]) => `${hhmm(a)}–${hhmm(b)}`).join(", ")}`;
}

let datesFor = "";
let datesText = "";

function middleDates(): string {
  const start = startOfDay(now());
  const key = `${start}:${observer.latitudeDeg}:${observer.longitudeDeg}`;
  if (key !== datesFor) {
    datesFor = key;
    const dates = sunradar.findDates(observer, WINDOW_MIDDLE, start, start + 365 * DAY);
    datesText = dates.length
      ? `In the middle of the window: ${dates.map((m) => `${dayMonth(m.unixUtc)} at ${hhmm(m.unixUtc)}`).join(" and ")}`
      : "The sun does not reach the middle of the window this year.";
  }
  return datesText;
}

function setText(id: string, text: string) {
  const element = document.getElementById(id);
  if (element && element.textContent !== text) element.textContent = text;
}

function readouts(name: string, time: number, sun: Horizontal, band: number) {
  const day = dayAt(time);
  const height =
    sun.altitudeDeg >= 0
      ? `${sun.altitudeDeg.toFixed(0)}° above the horizon`
      : `${(-sun.altitudeDeg).toFixed(0)}° below the horizon`;
  if (name === "hero") {
    setText("hero-readout", `${placeName} · ${hhmm(time)} · the sun is ${height}`);
  } else if (name === "day") {
    setText("day-time", hhmm(time));
    setText("day-detail", `The sun is ${height}, in the direction ${sun.azimuthDeg.toFixed(0)}°.`);
    setText(
      "day-events",
      day.sunrise && day.sunset
        ? `Sunrise ${hhmm(day.sunrise)} · sunset ${hhmm(day.sunset)}`
        : "No sunrise or sunset today",
    );
  } else if (name === "year") {
    setText("year-date", dayMonth(time));
    const length = day.sunrise && day.sunset ? (day.sunset - day.sunrise) / 3600 : null;
    setText(
      "year-detail",
      length === null
        ? "No sunrise or sunset on this day"
        : `${Math.floor(length)} h ${String(Math.round((length % 1) * 60)).padStart(2, "0")} min of daylight`,
    );
  } else if (name === "window") {
    setText("window-time", hhmm(time));
    setText("window-detail", windowTimes(day));
    setText("window-dates", middleDates());
  } else if (name === "moon") {
    const fraction = sunradar.moonIlluminatedFraction(time);
    setText("moon-date", hhmm(time));
    setText("moon-night", `The night of ${dayMonth(night - DAY)}`);
    setText("moon-detail", `${Math.round(fraction * 100)} % of the moon is lit.`);
  } else if (name === "compass") {
    setText("compass-heading", `±${band.toFixed(band < 2 ? 1 : 0)}°`);
    setText(
      "compass-detail",
      band > 6
        ? "The compass in a building: the path can be anywhere in the blue band."
        : "Aligned with one tap on the sun.",
    );
  } else if (name === "play") {
    setText("play-readout", `${dayMonth(time)} · ${hhmm(time)} · the sun is ${height}`);
  }
}

/** The angle between two directions in the sky, in degrees. */
function separation(a: Horizontal, b: Horizontal): number {
  const r = Math.PI / 180;
  const cos =
    Math.sin(a.altitudeDeg * r) * Math.sin(b.altitudeDeg * r) +
    Math.cos(a.altitudeDeg * r) *
      Math.cos(b.altitudeDeg * r) *
      Math.cos((a.azimuthDeg - b.azimuthDeg) * r);
  return Math.acos(clamp(cos, -1, 1)) / r;
}

/**
 * How visible the moon is, from 0 to 1. At night the moon is fully visible. In a bright sky, a
 * thin moon or a moon near the sun is not visible, and a large moon far from the sun is pale.
 */
function moonAlpha(sun: Horizontal, moon: Horizontal, fraction: number): number {
  const day = clamp((sun.altitudeDeg + 6) / 10);
  const inDaylight =
    0.6 * clamp((fraction - 0.12) / 0.3) * clamp((separation(sun, moon) - 15) / 25);
  return 1 + (inDaylight - 1) * day;
}

// --- The frame ----------------------------------------------------------------------------------

const DEFAULTS: Target = {
  time: 0,
  guides: 0,
  band: 0,
  window: 0,
  moon: false,
  hideMoon: false,
  tap: 0,
  dim: 0,
};

/** The values on the screen move to the values of the chapter, so a change of chapter is smooth. */
const shown = {
  /** The start of the shown day. It moves smoothly, so it can be between two days. */
  day: startOfDay(now()),
  /** The shown time of the day, in hours. */
  hours: (now() - startOfDay(now())) / 3600,
  guides: 0,
  band: 0,
  window: 0,
  dim: 0,
  center: 180,
  /** The visibility of the moon, from 0 to 1. It fades while the date moves. */
  moon: 0,
};
let lastMs = performance.now();
let lastChapter = "";
/**
 * After a change of chapter, the time of the day moves in the direction of the scroll: forward
 * (1) after a scroll down, back (-1) after a scroll up. 0 takes the shorter way round the clock.
 */
let direction = 0;

/** The start of the day after or before the day that starts at `start`. */
const nextDay = (start: number, n: number) => startOfDay(start + n * DAY + DAY / 2);

function frame(ms: number) {
  const dt = Math.min(0.1, (ms - lastMs) / 1000);
  lastMs = ms;
  const { name, progress } = scrollState();
  document.body.dataset.chapter = name;
  const target: Target = { ...DEFAULTS, ...chapters[name](progress) };

  const k = reducedMotion.matches ? 1 : 1 - Math.exp(-dt * 7);
  if (name !== lastChapter) {
    const index = (chapter: string) => sections.findIndex((s) => s.dataset.chapter === chapter);
    direction = lastChapter === "" ? 0 : Math.sign(index(name) - index(lastChapter));
    lastChapter = name;
  }
  // The date and the time of the day move separately, so a change of the date does not turn the
  // sun round the sky. The hours count from the start of the shown day and can go past 24, so
  // a move across midnight is continuous and does not change the date.
  const base = startOfDay(shown.day + DAY / 2);
  const dh = (target.time - base) / 3600 - shown.hours;
  const round = ((dh % 24) + 24) % 24;
  // The part of the move inside one day. A direction accepts a small step the other way, so the
  // sun does not go round the whole day for a target that is a little behind.
  let h = round >= 12 ? round - 24 : round;
  if (direction > 0 && h < -1) h += 24;
  if (direction < 0 && h > 1) h -= 24;
  // The rest of the move is whole days: a change of the date.
  const days = Math.round((dh - h) / 24);
  // A direction can need one day more or less, for example forward from the evening to the
  // morning of the same day. Then the hours make the whole move, and the date changes by one day
  // at the end. The sun is at almost the same place on the next day, so this change is not seen.
  const carry = direction !== 0 && Math.abs(days) === 1;
  if (Math.abs(h) < 0.02) {
    direction = 0;
    if (carry) shown.day += nextDay(base, days) - base;
  }
  const targetDay = days === 0 || carry ? base : nextDay(base, days);
  const targetHours = carry ? shown.hours + h : (target.time - targetDay) / 3600;
  // A jump of more than 40 days is not animated, because the paths would flicker through the year.
  if (Math.abs(targetDay - shown.day) > 40 * DAY) shown.day = targetDay;
  else shown.day += (targetDay - shown.day) * k;
  shown.hours += (targetHours - shown.hours) * k;
  // While the date moves, the moon jumps across the sky (13° per day), so it is not shown. It
  // fades in when the date stops. During a carry, the moon moves smoothly, so it fades out before
  // the date changes. This check comes before the hours go back into one day, because that
  // changes the date.
  const settled = !carry && Math.abs(targetDay - shown.day) < DAY / 2;
  if (!settled && !carry) shown.moon = 0;
  else shown.moon += ((settled && !target.hideMoon ? 1 : 0) - shown.moon) * k;
  // Keep the hours in one day. The date changes by the same amount, so the time stays the same.
  const shownBase = startOfDay(shown.day + DAY / 2);
  if (shown.hours >= 24) {
    const next = nextDay(shownBase, 1);
    shown.hours -= (next - shownBase) / 3600;
    shown.day += next - shownBase;
  } else if (shown.hours < 0) {
    const previous = nextDay(shownBase, -1);
    shown.hours += (shownBase - previous) / 3600;
    shown.day -= shownBase - previous;
  }
  shown.guides += (target.guides - shown.guides) * k;
  shown.band += (target.band - shown.band) * k;
  shown.window += (target.window - shown.window) * k;
  shown.dim += (target.dim - shown.dim) * k;

  // The nearest whole day. A day between two days would add part of a day to the time of the
  // day, and the sun would flash up during a change of the date.
  const time = startOfDay(shown.day + DAY / 2) + shown.hours * 3600;
  const day = dayAt(time);
  const sun = sunradar.sunPosition(time, observer);
  // In the south, or in the north on the southern hemisphere. A narrow screen follows the sun.
  const south = observer.latitudeDeg >= 0 ? 180 : 0;
  const offset = ((((sun.azimuthDeg - south) % 360) + 540) % 360) - 180;
  const center = innerWidth < 700 ? south + clamp(offset * 0.8, -60, 60) : south;
  shown.center += (center - shown.center) * k;

  const moon = shown.moon > 0.01 ? sunradar.moonPosition(time, observer) : null;
  const moonFraction = moon ? sunradar.moonIlluminatedFraction(time) : 0;
  const moonPath = target.moon ? moonArc().path : [];
  const frameData: Frame = {
    sun,
    path: day.path,
    hours: day.hours,
    guides: solsticeGuides(localYear(time)),
    guideAlpha: shown.guides,
    moon,
    moonFraction,
    moonAlpha: moon ? shown.moon * moonAlpha(sun, moon, moonFraction) : 0,
    moonPath,
    bandDeg: shown.band,
    window: shown.window > 0.01 ? { ...WINDOW, alpha: shown.window } : null,
    tap: target.tap,
    dim: shown.dim,
    centerAzimuthDeg: shown.center,
  };
  sky.draw(frameData, ms);
  readouts(name, time, sun, shown.band);
  requestAnimationFrame(frame);
}

document.documentElement.classList.add("ready");
requestAnimationFrame(frame);
