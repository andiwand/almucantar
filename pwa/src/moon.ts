// The shape of the lit part of the moon. The bright side of the moon faces the sun, along the great
// circle from the moon to the sun. The Android and the iOS app use the same calculation.

import type { Horizontal } from "@sunradar/core";

const DEG = Math.PI / 180;

/** The moon at the selected time: its direction and the illuminated fraction, from 0 to 1. */
export interface MoonAt {
  direction: Horizontal;
  fraction: number;
}

type Vec = [number, number, number];

function enu(h: Horizontal): Vec {
  const az = h.azimuthDeg * DEG;
  const alt = h.altitudeDeg * DEG;
  return [Math.cos(alt) * Math.sin(az), Math.cos(alt) * Math.cos(az), Math.sin(alt)];
}

/**
 * The direction at `angleDeg` from `moon` toward `sun`, on the great circle through both. The app
 * projects the moon and this direction to get the direction of the sun on the screen. The two
 * points are near each other, so the projection works also when the sun is behind the camera. If
 * the sun is exactly opposite the moon, the direction goes up.
 */
export function towardSun(moon: Horizontal, sun: Horizontal, angleDeg = 1): Horizontal {
  const m = enu(moon);
  const s = enu(sun);
  const dot = m[0] * s[0] + m[1] * s[1] + m[2] * s[2];
  let t: Vec = [s[0] - dot * m[0], s[1] - dot * m[1], s[2] - dot * m[2]];
  let norm = Math.hypot(...t);
  if (norm < 1e-9) {
    // Up, without the part along the moon.
    t = [-m[2] * m[0], -m[2] * m[1], 1 - m[2] * m[2]];
    norm = Math.hypot(...t);
    if (norm < 1e-9) return moon;
  }
  const a = angleDeg * DEG;
  const p = m.map((v, i) => v * Math.cos(a) + (t[i] / norm) * Math.sin(a));
  return {
    azimuthDeg: (((Math.atan2(p[0], p[1]) / DEG) % 360) + 360) % 360,
    altitudeDeg: Math.asin(Math.max(-1, Math.min(1, p[2]))) / DEG,
  };
}

/**
 * The outline of the lit part on a unit disc, as points [x, y]. The x axis points to the sun. The
 * outline is the half of the limb that faces the sun, then the terminator back: a half ellipse
 * with the semi-axis 1 - 2 * `fraction` along x. So the area is pi * `fraction`.
 */
export function litOutline(fraction: number, steps = 24): [number, number][] {
  const k = 1 - 2 * Math.max(0, Math.min(1, fraction));
  const points: [number, number][] = [];
  for (let i = 0; i <= steps; i++) {
    const angle = -Math.PI / 2 + (Math.PI * i) / steps;
    points.push([Math.cos(angle), Math.sin(angle)]);
  }
  for (let i = 0; i <= steps; i++) {
    const angle = Math.PI / 2 - (Math.PI * i) / steps;
    points.push([k * Math.cos(angle), Math.sin(angle)]);
  }
  return points;
}
