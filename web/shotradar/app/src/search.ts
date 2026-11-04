// The searches of shotradar, without the screens: the subjects, the dates for a spot and a landmark
// ("I know my spot"), the moments for the line of spots ("Find me a spot"), and the possible shots
// from a place ("I am here"). The core finds the alignments; this module chooses and sorts.

import { Body } from "@almucantar/core";
import type { Almucantar, Horizontal, Observer, Site } from "@almucantar/core";
import type { Horizon, Peak } from "./horizon";
import { findPeaks } from "./horizon";
import { destination } from "./mapview";

export type SubjectId = "moon" | "milkyway" | "sun" | "venus" | "jupiter" | "saturn" | "mars";

export interface Subject {
  id: SubjectId;
  name: string;
  body: number;
  /** A match puts the center of the subject this near to the landmark. */
  maxSeparationDeg: number;
  /** The sky must be at least this dark (the airless altitude of the sun), or null for any sky. */
  maxSunDeg: number | null;
}

export const SUBJECTS: Subject[] = [
  { id: "moon", name: "Moon", body: Body.moon, maxSeparationDeg: 0.3, maxSunDeg: null },
  {
    id: "milkyway",
    name: "Milky Way",
    body: Body.galacticCenter,
    maxSeparationDeg: 2,
    maxSunDeg: -15,
  },
  { id: "sun", name: "Sun", body: Body.sun, maxSeparationDeg: 0.3, maxSunDeg: null },
  { id: "venus", name: "Venus", body: Body.venus, maxSeparationDeg: 0.4, maxSunDeg: -4 },
  { id: "jupiter", name: "Jupiter", body: Body.jupiter, maxSeparationDeg: 0.4, maxSunDeg: -6 },
  { id: "saturn", name: "Saturn", body: Body.saturn, maxSeparationDeg: 0.4, maxSunDeg: -8 },
  { id: "mars", name: "Mars", body: Body.mars, maxSeparationDeg: 0.4, maxSunDeg: -6 },
];

export const subjectOf = (id: SubjectId) => SUBJECTS.find((s) => s.id === id) ?? SUBJECTS[0];

type Core = Pick<
  Almucantar,
  | "bodyPosition"
  | "findAlignments"
  | "moonIlluminatedFraction"
  | "sight"
  | "sunPosition"
  | "darkWindows"
  | "terrainEvents"
>;

/** The landmark of a shot: its direction from the spot, its name, and its distance. */
export interface Target {
  direction: Horizontal;
  name: string;
  distanceM: number;
  elevationM: number | null;
  place: Observer;
}

/**
 * A possible photo: a subject from a spot at a time, behind a landmark or alone over the horizon.
 * The photo shows the path of the subject from `pathStartUtc` to `pathEndUtc`.
 */
export interface Shot {
  subject: SubjectId;
  unixUtc: number;
  spot: Observer;
  /** The landmark in front of the subject, or null for the subject alone. */
  target: Target | null;
  separationDeg: number;
  body: Horizontal;
  pathStartUtc: number;
  pathEndUtc: number;
  /** For the subject alone: the times when it comes out of and goes behind the terrain. */
  riseUtc?: number | null;
  setUtc?: number | null;
}

/** The path of an alignment: an hour before and after it. */
const AROUND_S = 3600;

/** The light of the sky from the airless altitude of the sun. */
export function light(core: Core, unixUtc: number, at: Observer) {
  const sun = core.sunPosition(unixUtc, at, { refraction: false }).altitudeDeg;
  const label =
    sun >= 0
      ? "Daylight"
      : sun >= -6
        ? "Blue hour"
        : sun >= -12
          ? "Nautical twilight"
          : sun >= -18
            ? "Astronomical twilight"
            : "Night";
  return { sunDeg: sun, label, dark: sun < -12 };
}

/** The smallest lit part of the moon that a photo shows. A new moon is not visible. */
const MIN_MOON_FRACTION = 0.1;

/** True if the subject is visible at the shot: a dark enough sky, and a moon that is lit. */
export function darkEnough(core: Core, s: Shot): boolean {
  if (s.subject === "moon" && core.moonIlluminatedFraction(s.unixUtc) < MIN_MOON_FRACTION) {
    return false;
  }
  const limit = subjectOf(s.subject).maxSunDeg;
  return limit === null || light(core, s.unixUtc, s.spot).sunDeg <= limit;
}

const COMPASS = [
  "N",
  "NNE",
  "NE",
  "ENE",
  "E",
  "ESE",
  "SE",
  "SSE",
  "S",
  "SSW",
  "SW",
  "WSW",
  "W",
  "WNW",
  "NW",
  "NNW",
];

/** The point of the compass of an azimuth, for example "ENE". */
export function compass(azimuthDeg: number): string {
  return COMPASS[Math.round((((azimuthDeg % 360) + 360) % 360) / 22.5) % 16];
}

/** "I know my spot": the dates of the next `days` days when the subject is behind the landmark. */
export function datesFor(
  core: Core,
  subject: SubjectId,
  eye: Site,
  landmark: Site,
  name: string,
  startUtc: number,
  days = 365,
): Shot[] {
  const s = subjectOf(subject);
  const sight = core.sight(eye, landmark);
  const spot = { latitudeDeg: eye.latitudeDeg, longitudeDeg: eye.longitudeDeg };
  const target: Target = {
    direction: sight.direction,
    name,
    distanceM: sight.distanceM,
    elevationM: landmark.elevationM,
    place: { latitudeDeg: landmark.latitudeDeg, longitudeDeg: landmark.longitudeDeg },
  };
  return core
    .findAlignments(s.body, spot, sight.direction, startUtc, startUtc + days * 86_400, {
      maxSeparationDeg: s.maxSeparationDeg,
    })
    .map((a) => ({
      subject,
      unixUtc: a.unixUtc,
      spot,
      target,
      separationDeg: a.separationDeg,
      body: a.body,
      pathStartUtc: a.unixUtc - AROUND_S,
      pathEndUtc: a.unixUtc + AROUND_S,
    }))
    .filter((shot) => darkEnough(core, shot));
}

/** A moment for the line of spots: the subject low over the horizon, after its rise or before its set. */
export interface Moment {
  unixUtc: number;
  rising: boolean;
  /** For the moon: the lit fraction. */
  fraction: number | null;
}

/**
 * "Find me a spot": the next moments when the subject is a few degrees over the horizon, so a
 * landmark can stand in front of it: after the rise and before the set. For the moon, only near the
 * full moon; for the Milky Way and the planets, only in a dark sky.
 */
export function momentsFor(
  core: Core,
  subject: SubjectId,
  at: Observer,
  startUtc: number,
  count = 8,
  days = 200,
): Moment[] {
  const s = subjectOf(subject);
  const moments: Moment[] = [];
  const step = 600;
  const up = (t: number) => core.bodyPosition(s.body, t, at).altitudeDeg > 0;
  let before = up(startUtc);
  for (let t = startUtc + step; t < startUtc + days * 86_400 && moments.length < count; t += step) {
    const now = up(t);
    if (now !== before) {
      // About 3° over the horizon: 20 to 30 minutes after the rise or before the set.
      const when = now ? t + 1500 : t - 1500;
      const fraction = s.body === Body.moon ? core.moonIlluminatedFraction(when) : null;
      const dark =
        s.maxSunDeg === null ||
        core.sunPosition(when, at, { refraction: false }).altitudeDeg <= s.maxSunDeg;
      if ((fraction === null || fraction > 0.8) && dark) {
        moments.push({ unixUtc: when, rising: now, fraction });
      }
      // The next crossing is hours away; skip the rest of the night or the day.
      t += 4 * 3600;
      before = up(t);
      continue;
    }
    before = now;
  }
  return moments;
}

/** The landmarks of a place: the peaks on its skyline, with their place and height. */
export function peakTargets(horizon: Horizon, refractionCoefficient = 0.13): Target[] {
  const eye = horizon.groundM + 1.6;
  return findPeaks(horizon.skyline).map((p: Peak) => {
    const d = p.distanceM;
    // The height from the altitude: the inverse of the model of sight().
    const drop = (d * d * (1 - refractionCoefficient)) / (2 * 6_371_008.8);
    const elevation = eye + d * Math.tan((p.altitudeDeg * Math.PI) / 180) + drop;
    return {
      direction: { azimuthDeg: p.azimuthDeg, altitudeDeg: p.altitudeDeg },
      name: `Peak to the ${compass(p.azimuthDeg)}`,
      distanceM: d,
      elevationM: elevation,
      place: destination(horizon.observer, p.azimuthDeg, d),
    };
  });
}

/**
 * "I am here": all subjects behind all peaks of the skyline between `startUtc` and `endUtc`, in
 * time order, only in a sky that is dark enough for the subject.
 */
export function shotsFromPlace(
  core: Core,
  horizon: Horizon,
  targets: Target[],
  startUtc: number,
  endUtc: number,
  subjects: SubjectId[] = SUBJECTS.map((s) => s.id),
): Shot[] {
  const shots: Shot[] = [];
  for (const target of targets) {
    for (const id of subjects) {
      const s = subjectOf(id);
      for (const a of core.findAlignments(
        s.body,
        horizon.observer,
        target.direction,
        startUtc,
        endUtc,
        { maxSeparationDeg: s.maxSeparationDeg },
      )) {
        const shot: Shot = {
          subject: id,
          unixUtc: a.unixUtc,
          spot: horizon.observer,
          target,
          separationDeg: a.separationDeg,
          body: a.body,
          pathStartUtc: a.unixUtc - AROUND_S,
          pathEndUtc: a.unixUtc + AROUND_S,
        };
        if (darkEnough(core, shot)) shots.push(shot);
      }
    }
  }
  return shots.sort((a, b) => a.unixUtc - b.unixUtc);
}

/** The declination of a direction, seen from the latitude `latitudeDeg`. */
export function declinationDeg(direction: Horizontal, latitudeDeg: number): number {
  const rad = Math.PI / 180;
  const phi = latitudeDeg * rad;
  return (
    Math.asin(
      Math.sin(phi) * Math.sin(direction.altitudeDeg * rad) +
        Math.cos(phi) *
          Math.cos(direction.altitudeDeg * rad) *
          Math.cos(direction.azimuthDeg * rad),
    ) / rad
  );
}

/** The lowest and the highest declination of a subject between two times, from samples. */
export function declinationRange(
  core: Pick<Almucantar, "bodyPosition">,
  subject: SubjectId,
  at: Observer,
  startUtc: number,
  endUtc: number,
): [number, number] {
  const body = subjectOf(subject).body;
  let low = Infinity;
  let high = -Infinity;
  // Every 3 hours: the moon changes its declination by at most 1.5° in that time.
  for (let t = startUtc; t <= endUtc; t += 3 * 3600) {
    const dec = declinationDeg(
      core.bodyPosition(body, t, at, { refraction: false }),
      at.latitudeDeg,
    );
    low = Math.min(low, dec);
    high = Math.max(high, dec);
  }
  return [low, high];
}

/** Why "I know my spot" found nothing, if the direction is out of the path of the subject. */
export function noMatchReason(
  core: Pick<Almucantar, "bodyPosition">,
  subject: SubjectId,
  direction: Horizontal,
  at: Observer,
  startUtc: number,
  days = 365,
): string {
  const dec = declinationDeg(direction, at.latitudeDeg);
  const [low, high] = declinationRange(core, subject, at, startUtc, startUtc + days * 86_400);
  const name = subjectOf(subject).name.toLowerCase();
  const range = `${low.toFixed(1)}° to ${high.toFixed(1)}°`;
  const margin = subjectOf(subject).maxSeparationDeg;
  if (dec < low - margin || dec > high + margin) {
    return `The ${name} never comes to this direction in the next 12 months: the landmark is at the declination ${dec.toFixed(1)}°, and the ${name} stays between ${range}. Look from another side of the landmark, or from higher up.`;
  }
  if (subject === "milkyway") {
    return "No dark night with the center of the Milky Way behind the landmark in the next 12 months.";
  }
  return `No match in the next 12 months, although the landmark is in the path of the ${name} (${range}). Another spot, a little to the side, can work.`;
}

/**
 * "I am here", without a landmark: each subject while it is over the terrain of the place and the
 * sky is dark enough for it. The core gives the rise and the set over the terrain
 * (terrainEvents with the skyline of the place) and the dark hours (darkWindows); a shot is one
 * interval of both, at least 15 minutes long.
 */
export function skyFromPlace(
  core: Core,
  horizon: Horizon,
  startUtc: number,
  endUtc: number,
  subjects: SubjectId[] = SUBJECTS.map((s) => s.id),
): Shot[] {
  const at = horizon.observer;
  const shots: Shot[] = [];
  for (const id of subjects) {
    const s = subjectOf(id);
    // The intervals over the terrain. The limb of the sun and the moon counts; a point does not.
    const limbDeg = s.body === Body.sun || s.body === Body.moon ? 0.27 : 0;
    const events = core.terrainEvents(at, horizon.skyline, startUtc, endUtc, {
      body: s.body,
      limbDeg,
    });
    const visible: [number, number, boolean, boolean][] = [];
    let open: number | null = events.kind === 1 ? startUtc : null;
    let openedByRise = false;
    if (events.crossings.length > 0 && !events.crossings[0].rising) open = startUtc;
    for (const c of events.crossings) {
      if (c.rising) {
        open = c.unixUtc;
        openedByRise = true;
      } else if (open !== null) {
        visible.push([open, c.unixUtc, openedByRise, true]);
        open = null;
        openedByRise = false;
      }
    }
    if (open !== null) visible.push([open, endUtc, openedByRise, false]);
    // The dark hours of the subject: the sun low enough; the moon does not matter here.
    const dark =
      s.maxSunDeg === null
        ? [{ startUtc, endUtc }]
        : core.darkWindows(at, startUtc, endUtc, {
            maxSunAltitudeDeg: s.maxSunDeg,
            maxMoonFraction: 1,
          });
    for (const [a, b, rose, sets] of visible) {
      for (const w of dark) {
        const from = Math.max(a, w.startUtc);
        const to = Math.min(b, w.endUtc);
        if (to - from < 900) continue;
        // The best moment: low over the terrain, so the photo has the horizon. Soon after a rise,
        // else soon before a set, else in the middle.
        const rise = rose && from === a;
        const set = sets && to === b;
        const lead = Math.min(1200, (to - from) / 2);
        const best = rise ? from + lead : set ? to - lead : (from + to) / 2;
        if (s.body === Body.moon && core.moonIlluminatedFraction(best) < MIN_MOON_FRACTION)
          continue;
        shots.push({
          subject: id,
          unixUtc: best,
          spot: at,
          target: null,
          separationDeg: 0,
          body: core.bodyPosition(s.body, best, at),
          pathStartUtc: from,
          pathEndUtc: to,
          riseUtc: rose && from === a ? a : null,
          setUtc: sets && to === b ? b : null,
        });
      }
    }
  }
  return shots.sort((p, q) => p.unixUtc - q.unixUtc);
}
