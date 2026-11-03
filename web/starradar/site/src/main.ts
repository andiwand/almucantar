// The website of starradar: a night sky in the background that follows the scroll position. Each
// chapter sets the place, the time, and the parts of the sky. The core (the same WebAssembly
// package as the app) calculates the stars, the planets, the moon, and the Milky Way.

import { GALACTIC_CENTER, load } from "@almucantar/core";
import type { Horizontal, Observer, Planet } from "@almucantar/core";
import { milkyWayDust } from "@almucantar/web-shared/milkyway";
import { PLANETS, decodeStars, limitingMagnitude } from "@almucantar/web-shared/stars";
import type { StarsJson } from "@almucantar/web-shared/stars";
import { fromWallTime, startOfDay, wallTime } from "@almucantar/web-shared/zone";
import starsUrl from "../../../../assets/stars/stars.json?url";
import innsbruck from "./horizon-innsbruck.json";
import { Sky } from "./sky";
import type { Alignment, Frame, Ground } from "./sky";

const almucantar = await load();
const stars = decodeStars((await (await fetch(starsUrl)).json()) as StarsJson);
const dust = milkyWayDust();

const HOUR = 3600;
const DAY = 86400;
const SIDEREAL_DAY = 86164.0905;
const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const ease = (t: number) => t * t * (3 - 2 * t);
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");

// --- The places ---------------------------------------------------------------------------------

interface Place {
  name: string;
  observer: Observer;
  zone: string;
}

const VIENNA: Place = {
  name: "Vienna",
  observer: { latitudeDeg: 48.2082, longitudeDeg: 16.3738 },
  zone: "Europe/Vienna",
};
const INNSBRUCK: Place = {
  name: "Innsbruck",
  observer: { latitudeDeg: innsbruck.latitude_deg, longitudeDeg: innsbruck.longitude_deg },
  zone: "Europe/Vienna",
};
const TERRAIN: Ground = {
  stepDeg: innsbruck.azimuth_step_deg,
  altitudeDeg: innsbruck.altitude_deg,
};

/** Low hills for a place without terrain data: a few waves of 0.2° to 2.5°, the same each time. */
const HILLS: Ground = {
  stepDeg: 1,
  altitudeDeg: Array.from({ length: 360 }, (_, az) => {
    const a = (az * Math.PI) / 180;
    const h =
      1.0 +
      0.55 * Math.sin(3 * a + 0.7) +
      0.4 * Math.sin(7 * a + 2.1) +
      0.22 * Math.sin(17 * a + 4.0) +
      0.1 * Math.sin(41 * a + 1.3);
    return Math.max(0.2, h);
  }),
};

/** The place of the page; "Use my location" changes it. */
let home: Place = VIENNA;

const now = () => Date.now() / 1000;
const hhmm = (t: number, zone = home.zone) =>
  new Date(t * 1000).toLocaleTimeString([], { timeZone: zone, hour: "2-digit", minute: "2-digit" });
const dayMonth = (t: number, zone = home.zone) =>
  new Date(t * 1000).toLocaleDateString([], { timeZone: zone, day: "numeric", month: "long" });

/** A wall clock time on the day `offsetDays` after today, in the zone of the place. */
function today(place: Place, hour: number, offsetDays = 0): number {
  const w = wallTime(place.zone, Date.now());
  return (
    fromWallTime(
      place.zone,
      w.year,
      w.month,
      w.day + offsetDays,
      Math.floor(hour),
      (hour % 1) * 60,
    ) / 1000
  );
}

const sunAlt = (place: Place, t: number) => almucantar.sunPosition(t, place.observer).altitudeDeg;

/** The first time from `from` on (in steps of `step`) when `test` is true, or null. */
function first(
  from: number,
  to: number,
  step: number,
  test: (t: number) => boolean,
): number | null {
  for (let t = from; t <= to; t += step) if (test(t)) return t;
  return null;
}

/** Tonight: the evening of today, or now if it is night already. */
function tonight(place: Place): number {
  const t = now();
  if (sunAlt(place, t) < -15) return t;
  return today(place, 22);
}

/** The sunset of today and the start of the astronomical night (sun 18° below the horizon). */
function dusk(place: Place): { sunset: number; dark: number } {
  const noon = today(place, 12);
  const sunset =
    first(noon, noon + 14 * HOUR, 120, (t) => sunAlt(place, t) < -0.83) ?? noon + 6 * HOUR;
  const dark =
    first(sunset, sunset + 6 * HOUR, 120, (t) => sunAlt(place, t) < -18) ?? sunset + 2 * HOUR;
  return { sunset, dark };
}

// --- One star as a small catalogue, for the searches ------------------------------------------------

function starDirection(index: number, t: number, observer: Observer): Horizontal {
  const c = stars.catalog;
  const d = almucantar.starPositions(
    { raDeg: [c.raDeg[index]], decDeg: [c.decDeg[index]] },
    t,
    observer,
  );
  return { azimuthDeg: d[0], altitudeDeg: d[1] };
}

const starIndex = (name: string) => [...stars.names].find(([, n]) => n === name)?.[0] ?? 0;
const constellationIndex = (id: string) => stars.constellations.findIndex((c) => c.id === id);

const terrainAt = (azimuthDeg: number) =>
  TERRAIN.altitudeDeg[
    Math.round((((azimuthDeg % 360) + 360) % 360) / TERRAIN.stepDeg) % TERRAIN.altitudeDeg.length
  ];

// --- The chapters -------------------------------------------------------------------------------

interface Target {
  place: Place;
  time: number;
  center: number;
  lines: number;
  names: number;
  highlight: number;
  highlightAlpha: number;
  milkyWay: number;
  terrain: boolean;
  /** The sky is dark whatever the time: for a time lapse over weeks. */
  forceNight: boolean;
  starTracks: { from: number; to: number } | null;
  /**
   * The planets over weeks: at `from` plus `nights` sidereal days. The stars stay at `from`, and
   * a planet moves smoothly between its places of two whole nights.
   */
  planetNights: { from: number; nights: number } | null;
  hideMoon: boolean;
  alignment: Omit<Alignment, "alpha"> | null;
  dim: number;
}

const DEFAULTS: Omit<Target, "place" | "time" | "center"> = {
  lines: 0.6,
  names: 0.6,
  highlight: -1,
  highlightAlpha: 0,
  milkyWay: 1,
  terrain: false,
  forceNight: false,
  starTracks: null,
  planetNights: null,
  hideMoon: false,
  alignment: null,
  dim: 0,
};

let dragHours = 0;

/** The bright constellations that a chapter can put in front, in this order. */
const FAMOUS = ["Ori", "UMa", "Cyg", "Sco", "Cas", "Leo", "Gem", "Tau", "Lyr", "Sgr"];

/** The famous constellation highest in the sky at `t`, for the constellation chapter. */
function famousAt(t: number): number {
  let best = 0;
  let bestAlt = -90;
  for (const id of FAMOUS) {
    const i = constellationIndex(id);
    const d = almucantar.starPositions(
      {
        raDeg: [stars.constellations[i].labelRaDeg],
        decDeg: [stars.constellations[i].labelDecDeg],
      },
      t,
      home.observer,
    );
    // A little bonus for the order, so Orion wins a near tie.
    const score = d[1] - FAMOUS.indexOf(id) * 2;
    if (score > bestAlt) {
      bestAlt = score;
      best = i;
    }
  }
  return best;
}

/** The brightest object that rises in the evening in Innsbruck, with its rise at 0° and over the terrain. */
interface Riser {
  name: string;
  direction: (t: number) => Horizontal;
  flat: number;
  terrain: number;
}

let riserCache: { key: string; value: Riser | null } = { key: "", value: null };

function riser(): Riser | null {
  const start = today(INNSBRUCK, 17);
  const key = `${start}`;
  if (riserCache.key === key) return riserCache.value;
  const candidates: { name: string; mag: number; direction: (t: number) => Horizontal }[] = [
    ...PLANETS.slice(0, 5).map((p) => ({
      name: p.name,
      mag: almucantar.planetMagnitude(p.planet, start),
      direction: (t: number) => almucantar.planetPosition(p.planet, t, INNSBRUCK.observer),
    })),
    ...[
      "Sirius",
      "Arcturus",
      "Vega",
      "Capella",
      "Rigel",
      "Procyon",
      "Betelgeuse",
      "Aldebaran",
      "Antares",
      "Spica",
      "Altair",
      "Deneb",
      "Regulus",
    ].map((name) => {
      const i = starIndex(name);
      return {
        name,
        mag: stars.mag[i],
        direction: (t: number) => starDirection(i, t, INNSBRUCK.observer),
      };
    }),
  ].sort((a, b) => a.mag - b.mag);
  // Of the objects that rise between 17:00 and 01:00 at 0° in a dark sky, the one that the
  // mountains hide the longest: the story of the chapter.
  let value: Riser | null = null;
  for (const c of candidates) {
    const flat = first(
      start,
      start + 8 * HOUR,
      300,
      (t) => c.direction(t).altitudeDeg > 0 && c.direction(t - 300).altitudeDeg <= 0,
    );
    if (flat === null || sunAlt(INNSBRUCK, flat) > -10) continue;
    const terrain = first(flat, flat + 4 * HOUR, 120, (t) => {
      const d = c.direction(t);
      return d.altitudeDeg > terrainAt(d.azimuthDeg);
    });
    if (terrain === null) continue;
    if (!value || terrain - flat > value.terrain - value.flat) {
      value = { name: c.name, direction: c.direction, flat, terrain };
    }
  }
  riserCache = { key, value };
  return value;
}

/** The best night of the next year for the Milky Way: its center high, the sky dark, no moon. */
let milkyWayNight: { time: number; altitude: number } | null = null;

function bestMilkyWayNight(): { time: number; altitude: number } {
  if (milkyWayNight) return milkyWayNight;
  let best = { time: today(home, 23, 200), altitude: -90 };
  for (let d = 0; d < 365; d += 2) {
    for (const hour of [21, 22.5, 24, 25.5]) {
      const t = today(home, hour, d);
      if (sunAlt(home, t) > -18) continue;
      const moon = almucantar.moonPosition(t, home.observer).altitudeDeg;
      if (moon > 0 && almucantar.moonIlluminatedFraction(t) > 0.25) continue;
      const center = almucantar.galacticPosition(
        GALACTIC_CENTER.longitudeDeg,
        GALACTIC_CENTER.latitudeDeg,
        t,
        home.observer,
      ).altitudeDeg;
      if (center > best.altitude) best = { time: t, altitude: center };
    }
  }
  milkyWayNight = best;
  return best;
}

const chapters: Record<string, (p: number) => Partial<Target>> = {
  hero: () => ({ time: tonight(home) + dragHours * HOUR, center: 180 }),
  dusk: (p) => {
    const { sunset, dark } = dusk(home);
    const time = sunset - 20 * 60 + (dark - sunset + 20 * 60) * ease(p);
    const sun = almucantar.sunPosition(sunset, home.observer).azimuthDeg;
    return { time, center: sun + 50, lines: 0, names: 0, milkyWay: 0.6 };
  },
  constellations: (p) => {
    const time = tonight(home);
    const highlight = famousAt(time);
    const d = almucantar.starPositions(
      {
        raDeg: [stars.constellations[highlight].labelRaDeg],
        decDeg: [stars.constellations[highlight].labelDecDeg],
      },
      time,
      home.observer,
    );
    return {
      time,
      center: d[0],
      lines: clamp(p * 2.5),
      names: clamp(p * 2.5 - 0.6),
      highlight,
      highlightAlpha: clamp(Math.sin(Math.PI * clamp((p - 0.25) / 0.6))),
    };
  },
  turns: (p) => {
    const from = today(home, 20);
    return {
      time: from + 9 * HOUR * p,
      center: home.observer.latitudeDeg >= 0 ? 0 : 180,
      lines: 0.25,
      names: 0.4,
      starTracks: { from, to: from + 9 * HOUR * p },
    };
  },
  planets: (p) => {
    // The same sidereal time every night, so the stars stay and the planets move.
    const from = tonight(home);
    const bright = PLANETS.slice(0, 5)
      .map((x) => ({ x, d: almucantar.planetPosition(x.planet, from, home.observer) }))
      .filter((y) => y.d.altitudeDeg > 5)
      .sort(
        (a, b) =>
          almucantar.planetMagnitude(a.x.planet, from) -
          almucantar.planetMagnitude(b.x.planet, from),
      );
    return {
      time: from,
      center: bright[0]?.d.azimuthDeg ?? 180,
      forceNight: true,
      hideMoon: true,
      lines: 0.5,
      planetNights: { from, nights: 150 * p },
    };
  },
  mountain: (p) => {
    const r = riser();
    const from = r ? r.flat - 30 * 60 : today(INNSBRUCK, 20);
    const to = r ? r.terrain + 40 * 60 : from + 3 * HOUR;
    const time = from + (to - from) * p;
    return {
      place: INNSBRUCK,
      time,
      center: r ? r.direction(r.terrain).azimuthDeg : 90,
      terrain: true,
      lines: 0.4,
    };
  },
  milkyway: (p) => {
    const best = bestMilkyWayNight();
    const time = best.time - 1.5 * HOUR + 3 * HOUR * p;
    const center = almucantar.galacticPosition(0, 0, best.time, home.observer).azimuthDeg;
    return { time, center, lines: 0.15, names: 0.3, milkyWay: 1.6 };
  },
  compass: (p) => {
    const time = tonight(home);
    const scene = alignmentScene(time);
    const aligned = ease(clamp((p - 0.45) / 0.12));
    return {
      time,
      center: scene ? starDirection(scene.star, time, home.observer).azimuthDeg : 180,
      lines: 0.2,
      names: 0.2,
      milkyWay: 0.5,
      alignment: scene
        ? { ...scene, offsetDeg: 12 - 12 * aligned, tap: clamp((p - 0.45) / 0.1) }
        : null,
    };
  },
  play: () => ({ time: playTime(), center: playCenter }),
  rest: () => ({ time: tonight(home), center: 180, dim: 0.62 }),
};

/**
 * The star and the constellation of the compass chapter: a large, famous constellation whose
 * brightest star is between 20° and 55° high tonight, so the phone shows a clear figure.
 */
let sceneCache: { key: string; value: { star: number; constellation: number } | null } = {
  key: "",
  value: null,
};

function alignmentScene(t: number): { star: number; constellation: number } | null {
  const key = `${Math.round(t / 3600)}:${home.observer.latitudeDeg}:${home.observer.longitudeDeg}`;
  if (sceneCache.key === key) return sceneCache.value;
  let value = null;
  for (const id of ["Cyg", "Ori", "UMa", "Leo", "Sco", "Cas", "Gem", "Tau", "Peg", "Lyr"]) {
    const constellation = constellationIndex(id);
    // The brightest star of the lines: the smallest index, because the stars are sorted.
    const star = Math.min(...stars.constellations[constellation].lines.flat());
    const d = starDirection(star, t, home.observer);
    if (d.altitudeDeg >= 20 && d.altitudeDeg <= 55) {
      value = { star, constellation };
      break;
    }
  }
  sceneCache = { key, value };
  return value;
}

// --- The playground -----------------------------------------------------------------------------

const playDate = $<HTMLInputElement>("play-date");
const playHour = $<HTMLInputElement>("play-hour");
const playDirection = $<HTMLSelectElement>("play-direction");
let playCenter = 180;

function playTime(): number {
  const w = wallTime(home.zone, Date.now());
  const minutes = Number(playHour.value);
  return (
    fromWallTime(
      home.zone,
      w.year,
      1,
      1 + Number(playDate.value),
      Math.floor(minutes / 60),
      minutes % 60,
    ) / 1000
  );
}

function resetPlay() {
  const w = wallTime(home.zone, Date.now());
  const january = fromWallTime(home.zone, w.year, 1, 1) / 1000;
  playDate.value = String(
    Math.floor((startOfDay(home.zone, Date.now(), 0) - january + 7200) / DAY),
  );
  playHour.value = "1320";
}
resetPlay();
playDirection.addEventListener("change", () => (playCenter = Number(playDirection.value)));

$("play-location").addEventListener("click", () => {
  const button = $<HTMLButtonElement>("play-location");
  if (!navigator.geolocation) {
    button.textContent = "No location in this browser";
    return;
  }
  button.textContent = "Finding you …";
  navigator.geolocation.getCurrentPosition(
    (position) => {
      const { latitude, longitude } = position.coords;
      home = {
        name: `${latitude.toFixed(2)}°, ${longitude.toFixed(2)}°`,
        observer: { latitudeDeg: latitude, longitudeDeg: longitude },
        zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      };
      milkyWayNight = null;
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

// --- The rail: one star for each chapter, a click scrolls to it ------------------------------------

const rail = $("rail");
const railStops = sections
  .filter((section) => section.dataset.chapter !== "rest")
  .map((section) => {
    const step = section.querySelector(".step");
    const label =
      section.dataset.chapter === "hero" ? "Starradar" : (step?.lastChild?.textContent ?? "");
    const button = document.createElement("button");
    button.type = "button";
    button.setAttribute("aria-label", label);
    const text = document.createElement("span");
    text.textContent = label;
    button.append(text);
    button.addEventListener("click", () => {
      const top = section.getBoundingClientRect().top + scrollY;
      // The middle of a chapter: there its card and its sky are in place.
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

// --- The input: drag the sky in the hero to change the time ----------------------------------------

const canvas = $<HTMLCanvasElement>("sky");
const sky = new Sky(canvas, stars, dust);
let drag: { x: number; hours: number } | null = null;
canvas.addEventListener("pointerdown", (e) => {
  if (scrollState().name !== "hero") return;
  drag = { x: e.clientX, hours: dragHours };
  canvas.setPointerCapture(e.pointerId);
  document.body.classList.add("dragging");
});
canvas.addEventListener("pointermove", (e) => {
  if (!drag) return;
  // One screen width is six hours: the sky turns 90°.
  dragHours = drag.hours - ((e.clientX - drag.x) / innerWidth) * 6;
});
const endDrag = () => {
  drag = null;
  document.body.classList.remove("dragging");
};
canvas.addEventListener("pointerup", endDrag);
canvas.addEventListener("pointercancel", endDrag);

// --- The readouts -------------------------------------------------------------------------------

function setText(id: string, text: string) {
  const element = document.getElementById(id);
  if (element && element.textContent !== text) element.textContent = text;
}

function visiblePlanets(t: number, place: Place): string[] {
  return PLANETS.slice(0, 5)
    .filter((p) => almucantar.planetPosition(p.planet, t, place.observer).altitudeDeg > 5)
    .map((p) => p.name);
}

function phase(sun: number): string {
  if (sun >= 0) return "the sun is up";
  if (sun >= -6) return "civil twilight";
  if (sun >= -12) return "nautical twilight";
  if (sun >= -18) return "astronomical twilight";
  return "the night is dark";
}

function readouts(name: string, t: number, target: Target, limit: number) {
  const place = target.place;
  if (name === "hero") {
    const planets = visiblePlanets(t, place);
    setText(
      "hero-readout",
      `${place.name} · ${dayMonth(t)} · ${hhmm(t)}` +
        (planets.length ? ` · ${planets.join(", ")} up` : ""),
    );
  } else if (name === "dusk") {
    const sun = sunAlt(place, t);
    setText("dusk-time", hhmm(t));
    setText(
      "dusk-detail",
      sun >= 0
        ? `The sun is ${sun.toFixed(0)}° above the horizon.`
        : `The sun is ${(-sun).toFixed(0)}° below the horizon: ${phase(sun)}.`,
    );
    setText(
      "dusk-stars",
      limit < 0 ? "No stars yet." : `The eye sees stars down to magnitude ${limit.toFixed(1)}.`,
    );
  } else if (name === "constellations") {
    setText("const-name", stars.constellations[target.highlight]?.name ?? "");
    setText(
      "const-detail",
      `${stars.constellations.length - 1} constellations, ${stars.mag.length} stars`,
    );
  } else if (name === "turns") {
    setText("turns-time", hhmm(t));
    setText(
      "turns-detail",
      "The sky turns 15° in an hour around the celestial pole, near Polaris.",
    );
  } else if (name === "planets") {
    const nights = target.planetNights;
    const date = nights ? nights.from + Math.round(nights.nights) * SIDEREAL_DAY : t;
    setText("planets-date", dayMonth(date));
    const planets = visiblePlanets(date, place);
    setText(
      "planets-detail",
      planets.length ? `Up at this sidereal time: ${planets.join(", ")}` : "",
    );
  } else if (name === "mountain") {
    const r = riser();
    setText("mountain-time", hhmm(t, INNSBRUCK.zone));
    setText(
      "mountain-detail",
      r
        ? `${r.name} rises at ${hhmm(r.flat, INNSBRUCK.zone)} at the horizon, but at ${hhmm(r.terrain, INNSBRUCK.zone)} over the mountains.`
        : "Tonight nothing bright rises behind the mountains.",
    );
  } else if (name === "milkyway") {
    const best = bestMilkyWayNight();
    setText("milkyway-date", `${dayMonth(best.time)}, ${hhmm(t)}`);
    setText(
      "milkyway-detail",
      `The best night of the next year in ${place.name}: the center of the Milky Way is ${best.altitude.toFixed(0)}° high, the sky is dark, and the moon is down.`,
    );
  } else if (name === "compass") {
    const a = target.alignment;
    const error = a?.offsetDeg ?? 0;
    setText("compass-heading", error > 0.05 ? `${error.toFixed(0)}° off` : "Aligned");
    setText(
      "compass-detail",
      !a
        ? ""
        : error > 6
          ? `The compass of the phone draws ${stars.constellations[a.constellation].name} in the wrong place (dashed).`
          : `One tap on ${stars.names.get(a.star) ?? "a bright star"}, and the phone draws the sky where it is.`,
    );
  } else if (name === "play") {
    const planets = visiblePlanets(t, place);
    setText(
      "play-readout",
      `${place.name} · ${dayMonth(t)} · ${hhmm(t)} · ${phase(sunAlt(place, t))}` +
        (planets.length ? ` · ${planets.join(", ")} up` : ""),
    );
  }
}

// --- The frame ----------------------------------------------------------------------------------

/** The values on the screen move to the values of the chapter, so a change of chapter is smooth. */
const shown = {
  time: tonight(home),
  center: 180,
  lines: 0,
  names: 0,
  milkyWay: 1,
  highlight: 0,
  alignment: 0,
  dim: 0,
  fade: 0,
  /** The scroll progress of the chapter, smoothed, so the wheel does not move the sky in steps. */
  progress: 0,
};
let shownChapter = "";
let shownPlace: Place = home;
let lastMs = performance.now();

/** The tracks of the bright stars over a night, calculated once for each night. */
let tracksCache: { key: string; samples: { t: number; stars: Float64Array }[] } = {
  key: "",
  samples: [],
};

function starTracks(place: Place, from: number, to: number): Horizontal[][] {
  const key = `${from}:${place.observer.latitudeDeg}:${place.observer.longitudeDeg}`;
  if (tracksCache.key !== key) {
    const bright = {
      raDeg: stars.catalog.raDeg.subarray(0, 150),
      decDeg: stars.catalog.decDeg.subarray(0, 150),
    };
    const samples = [];
    for (let t = from; t <= from + 9 * HOUR; t += 10 * 60) {
      samples.push({ t, stars: almucantar.starPositions(bright, t, place.observer) });
    }
    tracksCache = { key, samples };
  }
  const shownSamples = tracksCache.samples.filter((s) => s.t <= to);
  const tracks: Horizontal[][] = [];
  for (let i = 0; i < 150; i++) {
    const track = shownSamples
      .map((s) => ({ azimuthDeg: s.stars[2 * i], altitudeDeg: s.stars[2 * i + 1] }))
      .filter((d) => d.altitudeDeg > 0);
    if (track.length > 1) tracks.push(track);
  }
  return tracks;
}

/** The direction of a planet `nights` sidereal days after `from`, between two whole nights. */
function planetAfter(planet: Planet, from: number, nights: number, observer: Observer): Horizontal {
  const n = Math.floor(nights);
  const a = almucantar.planetPosition(planet, from + n * SIDEREAL_DAY, observer);
  const b = almucantar.planetPosition(planet, from + (n + 1) * SIDEREAL_DAY, observer);
  const t = nights - n;
  const daz = ((((b.azimuthDeg - a.azimuthDeg) % 360) + 540) % 360) - 180;
  return {
    azimuthDeg: a.azimuthDeg + daz * t,
    altitudeDeg: a.altitudeDeg + (b.altitudeDeg - a.altitudeDeg) * t,
  };
}

/** The tracks of the bright planets from `from` over `nights` sidereal days. */
function planetTracks(place: Place, from: number, nights: number): Horizontal[][] {
  const tracks: Horizontal[][] = [];
  for (const p of PLANETS.slice(0, 5)) {
    const track: Horizontal[] = [];
    for (let n = 0; n < nights; n += 3) {
      track.push(almucantar.planetPosition(p.planet, from + n * SIDEREAL_DAY, place.observer));
    }
    track.push(planetAfter(p.planet, from, nights, place.observer));
    if (track.length > 1) tracks.push(track);
  }
  return tracks;
}

function frame(ms: number) {
  const dt = Math.min(0.1, (ms - lastMs) / 1000);
  lastMs = ms;
  const { name, progress } = scrollState();
  if (document.body.dataset.chapter !== name) updateRail(name);
  document.body.dataset.chapter = name;
  const k = reducedMotion.matches ? 1 : 1 - Math.exp(-dt * 6);
  // Inside a chapter the progress moves smoothly; a new chapter starts at its own progress.
  if (name !== shownChapter) {
    shownChapter = name;
    shown.progress = progress;
  } else shown.progress += (progress - shown.progress) * Math.min(1, k * 1.5);
  const target: Target = {
    place: home,
    ...DEFAULTS,
    time: tonight(home),
    center: 180,
    ...chapters[name](shown.progress),
  } as Target;

  // A new place or a jump of more than eight hours fades. A shorter jump (for example from the
  // night of the hero to the sunset of the dusk chapter) turns the sky smoothly, without a cut.
  if (target.place !== shownPlace || Math.abs(target.time - shown.time) > 8 * HOUR) {
    if (shown.fade >= 0.98 || reducedMotion.matches) {
      shownPlace = target.place;
      shown.time = target.time;
      shown.center = target.center;
    }
    shown.fade += (1 - shown.fade) * Math.min(1, k * 3);
  } else {
    shown.fade += (0 - shown.fade) * k;
    shown.time += (target.time - shown.time) * k;
  }
  const dc = ((((target.center - shown.center) % 360) + 540) % 360) - 180;
  shown.center += dc * k;
  shown.lines += (target.lines - shown.lines) * k;
  shown.names += (target.names - shown.names) * k;
  shown.milkyWay += (target.milkyWay - shown.milkyWay) * k;
  shown.highlight += (target.highlightAlpha - shown.highlight) * k;
  shown.alignment += ((target.alignment ? 1 : 0) - shown.alignment) * k;
  shown.dim += (target.dim - shown.dim) * k;

  const place = shownPlace;
  const t = shown.time;
  const sun = almucantar.sunPosition(t, place.observer);
  const skySun = target.forceNight ? { ...sun, altitudeDeg: -30 } : sun;
  const limit = limitingMagnitude(skySun.altitudeDeg);
  const nights = target.planetNights;
  const frameData: Frame = {
    sun: skySun,
    moon: almucantar.moonPosition(t, place.observer),
    moonFraction: target.hideMoon ? 0 : almucantar.moonIlluminatedFraction(t),
    stars: almucantar.starPositions(stars.catalog, t, place.observer),
    limitMag: limit,
    labels: almucantar.starPositions(
      {
        raDeg: stars.constellations.map((c) => c.labelRaDeg),
        decDeg: stars.constellations.map((c) => c.labelDecDeg),
      },
      t,
      place.observer,
    ),
    linesAlpha: shown.lines * clamp((limit - 1) / 2),
    namesAlpha: shown.names * clamp((limit - 1) / 2),
    highlight: target.highlight,
    highlightAlpha: shown.highlight,
    planets: PLANETS.map((p) => ({
      name: p.name,
      color: p.color,
      direction: nights
        ? planetAfter(p.planet, nights.from, nights.nights, place.observer)
        : almucantar.planetPosition(p.planet, t, place.observer),
      mag: almucantar.planetMagnitude(p.planet, t),
    })),
    planetTracks: nights ? planetTracks(place, nights.from, nights.nights) : [],
    starTracks: target.starTracks ? starTracks(place, target.starTracks.from, t) : [],
    dust: almucantar.starPositions(dust.catalog, t, place.observer),
    milkyWayAlpha: shown.milkyWay * clamp((limit - 3.5) / 2.5),
    ground: target.terrain && place === INNSBRUCK ? TERRAIN : HILLS,
    realTerrain: target.terrain && place === INNSBRUCK,
    alignment: target.alignment ? { ...target.alignment, alpha: shown.alignment } : null,
    dim: Math.max(shown.dim, shown.fade * 0.95),
    centerAzimuthDeg: shown.center,
  };
  sky.draw(frameData, ms);
  readouts(name, t, target, limit);
  requestAnimationFrame(frame);
}

document.documentElement.classList.add("ready");
requestAnimationFrame(frame);
