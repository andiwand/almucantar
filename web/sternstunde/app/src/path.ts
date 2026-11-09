// The path of the sun, the moon, or a planet in the 24 hours around a time. The functions here are
// pure: the app gives the positions and the horizon as functions, so the tests need no core.

import type { Horizontal } from "@almucantar/core";
import { wallTime } from "@almucantar/web-shared/zone";

/** The step of the samples of a path: 10 minutes. */
export const PATH_STEP_S = 600;
/** A path starts 12 hours before the time and stops 12 hours after it. */
export const PATH_HALF_SPAN_S = 12 * 3600;

/** A full hour on a path: the direction, the hour ("21"), and true if the horizon hides it. */
export interface PathHour {
  direction: Horizontal;
  label: string;
  hidden: boolean;
}

/**
 * The path of a body: the parts over the horizon or the terrain (`visible`), the parts below it
 * (`hidden`), and the full hours in the time zone of the place.
 */
export interface BodyPath {
  visible: Horizontal[][];
  hidden: Horizontal[][];
  hours: PathHour[];
}

/**
 * The path of a body in the 24 hours around `time` (Unix seconds). `directionAt` gives the
 * direction of the body at a time, and `horizonAt` gives the altitude of the visible horizon at
 * an azimuth.
 */
export function bodyPath(
  time: number,
  directionAt: (time: number) => Horizontal,
  horizonAt: (azimuthDeg: number) => number,
  zone: string,
): BodyPath {
  const start = time - PATH_HALF_SPAN_S;
  const end = time + PATH_HALF_SPAN_S;
  const samples: Horizontal[] = [];
  for (let at = start; at <= end; at += PATH_STEP_S) samples.push(directionAt(at));
  const { visible, hidden } = splitAtHorizon(samples, horizonAt);
  const hours = fullHours(start, end, zone).map((h) => {
    const direction = directionAt(h.time);
    return {
      direction,
      label: h.label,
      hidden: direction.altitudeDeg < horizonAt(direction.azimuthDeg),
    };
  });
  return { visible, hidden, hours };
}

/**
 * The full hours of the wall clock in `zone` after `start` and before `end` (Unix seconds), with
 * the hour as a label ("07"). The function tests each quarter hour, because some time zones have
 * an offset of 30 or 45 minutes.
 */
export function fullHours(
  start: number,
  end: number,
  zone: string,
): { time: number; label: string }[] {
  const quarter = 900;
  const hours: { time: number; label: string }[] = [];
  for (let t = Math.floor(start / quarter) * quarter + quarter; t < end; t += quarter) {
    if (t <= start) continue;
    const w = wallTime(zone, t * 1000);
    if (w.minute === 0) hours.push({ time: t, label: String(w.hour).padStart(2, "0") });
  }
  return hours;
}

/**
 * Splits a line of directions into the parts over the visible horizon (`visible`) and the parts
 * below it (`hidden`). The two parts meet at the crossing, which is interpolated between the
 * samples, so the line has no gap. The interpolation takes the short way over north.
 */
export function splitAtHorizon(
  line: Horizontal[],
  horizonAt: (azimuthDeg: number) => number,
): { visible: Horizontal[][]; hidden: Horizontal[][] } {
  const visible: Horizontal[][] = [];
  const hidden: Horizontal[][] = [];
  let part: Horizontal[] = [];
  let up: boolean | null = null;
  let previous: Horizontal | null = null;
  let previousHeight = 0;
  for (const d of line) {
    const height = d.altitudeDeg - horizonAt(d.azimuthDeg);
    const isUp = height > 0;
    if (up !== null && previous && isUp !== up) {
      const t = previousHeight / (previousHeight - height);
      const turn = ((d.azimuthDeg - previous.azimuthDeg + 540) % 360) - 180;
      const crossing = {
        azimuthDeg: (((previous.azimuthDeg + t * turn) % 360) + 360) % 360,
        altitudeDeg: previous.altitudeDeg + t * (d.altitudeDeg - previous.altitudeDeg),
      };
      part.push(crossing);
      (up ? visible : hidden).push(part);
      part = [crossing];
    }
    part.push(d);
    up = isUp;
    previous = d;
    previousHeight = height;
  }
  if (up !== null && part.length > 1) (up ? visible : hidden).push(part);
  return { visible, hidden };
}
