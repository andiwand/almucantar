// The star data of starradar (assets/stars/stars.json, made by scripts/stars/build_stars.py): the
// stars as typed columns for the core, their names, and the constellations. And how a star looks:
// its size from the magnitude, its color from the B-V color index, and the faintest magnitude that
// the eye sees in a sky of a given darkness. The app and the website of starradar use this module.

import type { Planet, StarCatalog } from "@almucantar/core";

/** The format of stars.json, version 1. */
export interface StarsJson {
  version: number;
  stars: {
    ra: number[];
    dec: number[];
    pm_ra: number[];
    pm_dec: number[];
    mag: number[];
    bv: number[];
    hr: number[];
  };
  names: [number, string][];
  constellations: { id: string; name: string; label: [number, number]; lines: number[][] }[];
  attribution: string;
}

export interface Constellation {
  id: string;
  name: string;
  /** Where the name goes: right ascension and declination (J2000), in degrees. */
  labelRaDeg: number;
  labelDecDeg: number;
  /** The lines: each line is a list of indices of stars. */
  lines: number[][];
}

/** The decoded star data. The stars are sorted by magnitude, the brightest first. */
export interface Stars {
  catalog: StarCatalog & {
    raDeg: Float64Array;
    decDeg: Float64Array;
    pmRaArcsecPerYear: Float64Array;
    pmDecArcsecPerYear: Float64Array;
  };
  mag: Float32Array;
  bv: Float32Array;
  /** The proper name of a star, by its index. */
  names: Map<number, string>;
  constellations: Constellation[];
  attribution: string;
}

export function decodeStars(json: StarsJson): Stars {
  if (json.version !== 1) throw new Error(`stars.json has the unknown version ${json.version}`);
  const s = json.stars;
  return {
    catalog: {
      raDeg: Float64Array.from(s.ra),
      decDeg: Float64Array.from(s.dec),
      pmRaArcsecPerYear: Float64Array.from(s.pm_ra),
      pmDecArcsecPerYear: Float64Array.from(s.pm_dec),
    },
    mag: Float32Array.from(s.mag),
    bv: Float32Array.from(s.bv),
    names: new Map(json.names),
    constellations: json.constellations.map((c) => ({
      id: c.id,
      name: c.name,
      labelRaDeg: c.label[0],
      labelDecDeg: c.label[1],
      lines: c.lines,
    })),
    attribution: json.attribution,
  };
}

/**
 * The faintest magnitude that the eye sees, from the altitude of the sun (degrees): about 6 in the
 * astronomical night (sun below -18°), and only the brightest stars and planets in the twilight.
 * A city sky shows less; this is the value for a dark place.
 */
export function limitingMagnitude(sunAltitudeDeg: number): number {
  if (sunAltitudeDeg >= 0) return -2; // in daylight only Venus and Jupiter, at best
  if (sunAltitudeDeg >= -6) return -1 + (sunAltitudeDeg / -6) * 2; // civil twilight: -1 to 1
  if (sunAltitudeDeg >= -12) return 1 + ((sunAltitudeDeg + 6) / -6) * 2.5; // nautical: 1 to 3.5
  if (sunAltitudeDeg >= -18) return 3.5 + ((sunAltitudeDeg + 12) / -6) * 2.5; // astronomical
  return 6;
}

/** The radius of a star on the screen, in CSS pixels: a brighter star is larger. */
export function starRadius(mag: number): number {
  return Math.max(0.6, Math.min(4.5, 3.2 - 0.45 * mag));
}

/**
 * The color of a star as "r, g, b" (for rgba()), from its B-V color index: blue-white for hot
 * stars (B-V below 0), white for B-V about 0.3, yellow for the sun (0.65), and orange to red for
 * cool stars (above 1.2). The colors are pale, as the eye sees them.
 */
export function starColor(bv: number): string {
  const stops: [number, [number, number, number]][] = [
    [-0.4, [170, 191, 255]],
    [0.0, [202, 215, 255]],
    [0.3, [248, 247, 255]],
    [0.65, [255, 244, 234]],
    [1.0, [255, 222, 180]],
    [1.5, [255, 196, 140]],
    [2.0, [255, 170, 110]],
  ];
  const x = Math.max(stops[0][0], Math.min(stops[stops.length - 1][0], bv));
  for (let i = 1; i < stops.length; i++) {
    if (x <= stops[i][0]) {
      const [x0, c0] = stops[i - 1];
      const [x1, c1] = stops[i];
      const t = (x - x0) / (x1 - x0);
      return c0.map((c, k) => Math.round(c + (c1[k] - c) * t)).join(", ");
    }
  }
  return stops[stops.length - 1][1].join(", ");
}

/** The phase of the twilight from the altitude of the sun, for the status of the app. */
export function darkness(sunAltitudeDeg: number): string {
  if (sunAltitudeDeg >= 0) return "Day";
  if (sunAltitudeDeg >= -6) return "Civil twilight";
  if (sunAltitudeDeg >= -12) return "Nautical twilight";
  if (sunAltitudeDeg >= -18) return "Astronomical twilight";
  return "Night";
}

/** The planets with their names and colors. The numbers are the values of `Planet` of the core. */
export const PLANETS: readonly { planet: Planet; name: string; color: string }[] = [
  { planet: 0, name: "Mercury", color: "255, 236, 210" },
  { planet: 1, name: "Venus", color: "255, 252, 236" },
  { planet: 2, name: "Mars", color: "255, 170, 120" },
  { planet: 3, name: "Jupiter", color: "255, 240, 214" },
  { planet: 4, name: "Saturn", color: "255, 230, 170" },
  { planet: 5, name: "Uranus", color: "200, 240, 255" },
  { planet: 6, name: "Neptune", color: "170, 200, 255" },
];
