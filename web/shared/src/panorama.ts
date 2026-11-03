// The panorama of the websites: the sky with the azimuth on x and the altitude on y, so a whole
// path of the sun or a wide part of the night sky fits on the screen. And the colors of the sky
// for an altitude of the sun. The websites of sunradar and starradar use this module.

export type Rgb = [number, number, number];

/** The colors of the sky at the top and at the horizon, for a sun altitude. */
const SKY: [number, Rgb, Rgb][] = [
  [-18, [3, 6, 15], [10, 18, 38]],
  [-8, [10, 20, 46], [52, 40, 86]],
  [-2, [24, 42, 86], [196, 104, 92]],
  [3, [34, 70, 132], [242, 160, 96]],
  [12, [44, 96, 168], [236, 200, 150]],
  [30, [40, 106, 190], [150, 202, 238]],
];

export function mix(a: Rgb, b: Rgb, t: number): Rgb {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}

export function css([r, g, b]: Rgb, alpha = 1): string {
  return `rgba(${Math.round(r)}, ${Math.round(g)}, ${Math.round(b)}, ${alpha})`;
}

export function skyColors(altitudeDeg: number): [Rgb, Rgb] {
  if (altitudeDeg <= SKY[0][0]) return [SKY[0][1], SKY[0][2]];
  for (let i = 1; i < SKY.length; i++) {
    const [a1, top1, low1] = SKY[i];
    const [a0, top0, low0] = SKY[i - 1];
    if (altitudeDeg <= a1) {
      const t = (altitudeDeg - a0) / (a1 - a0);
      return [mix(top0, top1, t), mix(low0, low1, t)];
    }
  }
  const last = SKY[SKY.length - 1];
  return [last[1], last[2]];
}

/** A small random generator with a seed, so the stars and the buildings stay the same. */
export function random(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** The panorama: the mapping from a direction in the sky to a point on the screen. */
export interface PanoramaOptions {
  /** The azimuth range across the width of the screen, in degrees. */
  spanDeg: number;
  /** The height of the horizon, as a fraction of the height of the screen from the top. */
  horizonFraction: number;
  /** The altitude that fits between the horizon and 86% of the way to the top. */
  topAltitudeDeg: number;
}

/** The layout of the sunradar website: wide and low, for the path of the sun. */
export function sunPanorama(width: number): PanoramaOptions {
  return width < 700
    ? { spanDeg: 170, horizonFraction: 0.5, topAltitudeDeg: 68 }
    : { spanDeg: 250, horizonFraction: 0.74, topAltitudeDeg: 68 };
}

export class Panorama {
  readonly pxPerDegX: number;
  readonly pxPerDegY: number;
  readonly horizonY: number;

  constructor(
    readonly width: number,
    readonly height: number,
    readonly centerAzimuthDeg: number,
    options: PanoramaOptions = sunPanorama(width),
  ) {
    this.pxPerDegX = width / options.spanDeg;
    this.horizonY = height * options.horizonFraction;
    this.pxPerDegY = Math.min(
      (this.horizonY * 0.86) / options.topAltitudeDeg,
      this.pxPerDegX * 2.4,
    );
  }

  x(azimuthDeg: number): number {
    const d = ((((azimuthDeg - this.centerAzimuthDeg) % 360) + 540) % 360) - 180;
    return this.width / 2 + d * this.pxPerDegX;
  }

  y(altitudeDeg: number): number {
    return this.horizonY - altitudeDeg * this.pxPerDegY;
  }
}
