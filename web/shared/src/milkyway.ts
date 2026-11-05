// A picture of the Milky Way as many soft points ("dust") in the galactic system. The points are
// denser and brighter toward the galactic center and in the star clouds of Cygnus, Scutum, and
// Carina, and the Great Rift is a dark lane. The positions are J2000, so the core moves them like
// stars (starPositions). This is a picture for the eye, not a measurement.

import { random } from "./panorama";

const DEG = Math.PI / 180;

// The north galactic pole and the galactic longitude of the north celestial pole (IAU 1958, J2000).
const POLE_RA = 192.85948 * DEG;
const POLE_DEC = 27.12825 * DEG;
const NCP_L = 122.93192 * DEG;

/** Converts a galactic direction to J2000 right ascension and declination, in degrees. */
export function galacticToJ2000(lDeg: number, bDeg: number): [number, number] {
  const l = lDeg * DEG;
  const b = bDeg * DEG;
  const sinDec =
    Math.sin(POLE_DEC) * Math.sin(b) + Math.cos(POLE_DEC) * Math.cos(b) * Math.cos(NCP_L - l);
  const dec = Math.asin(Math.max(-1, Math.min(1, sinDec)));
  const y = Math.cos(b) * Math.sin(NCP_L - l);
  const x =
    Math.cos(POLE_DEC) * Math.sin(b) - Math.sin(POLE_DEC) * Math.cos(b) * Math.cos(NCP_L - l);
  const ra = ((((POLE_RA + Math.atan2(y, x)) / DEG) % 360) + 360) % 360;
  return [ra, dec / DEG];
}

/** A bell curve around `center` on the circle of the longitude. */
function bump(l: number, center: number, width: number): number {
  const d = ((((l - center) % 360) + 540) % 360) - 180;
  return Math.exp(-(d * d) / (2 * width * width));
}

/** The brightness of the band at a galactic longitude, from about 0.3 to 1. */
export function bandBrightness(lDeg: number): number {
  return Math.min(
    1,
    0.3 +
      0.7 * bump(lDeg, 0, 32) + // the bulge in Sagittarius
      0.35 * bump(lDeg, 27, 9) + // the Scutum star cloud
      0.4 * bump(lDeg, 75, 16) + // the Cygnus star clouds
      0.3 * bump(lDeg, -75, 14) + // Carina
      0.15 * bump(lDeg, 180, 30), // the anticenter, faint
  );
}

/** The half width of the band (one standard deviation of the latitude), in degrees. */
export function bandWidth(lDeg: number): number {
  return 3.5 + 5.5 * bump(lDeg, 0, 28) + 1.5 * bump(lDeg, 75, 20);
}

/**
 * How much the Great Rift, the dark lane of dust from Cygnus to Sagittarius, takes away at a
 * galactic direction: 0 (nothing) to 1 (all).
 */
export function riftDarkness(lDeg: number, bDeg: number): number {
  const l = (((lDeg % 360) + 540) % 360) - 180;
  if (l < -15 || l > 80) return 0;
  // The lane is a little north of the plane, and it gets wider toward the center.
  const center = 1.2 + 0.03 * Math.max(0, 40 - l);
  const width = 1.2 + 0.04 * Math.max(0, 45 - l);
  const along = Math.min(1, (l + 15) / 10, (80 - l) / 15);
  return 0.85 * along * Math.exp(-((bDeg - center) ** 2) / (2 * width * width));
}

export interface MilkyWayDust {
  /** The J2000 directions, for starPositions() of the core. */
  catalog: { raDeg: Float64Array; decDeg: Float64Array };
  /** The brightness of each point, from 0 to 1. */
  weight: Float32Array;
  /** True for a soft cloud, false for a fine grain. */
  cloud: Uint8Array;
}

/**
 * Makes the points of the Milky Way: `clouds` soft clouds and `grains` fine grains. The density
 * follows the brightness and the width of the band, without the Great Rift.
 */
export function milkyWayDust(clouds = 3200, grains = 4800, seed = 11): MilkyWayDust {
  const next = random(seed);
  const gauss = () => {
    const u = Math.max(1e-9, next());
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * next());
  };
  const total = clouds + grains;
  const raDeg = new Float64Array(total);
  const decDeg = new Float64Array(total);
  const weight = new Float32Array(total);
  const cloud = new Uint8Array(total);
  let n = 0;
  while (n < total) {
    const l = next() * 360 - 180;
    const isCloud = n < clouds;
    const b = gauss() * bandWidth(l) * (isCloud ? 1 : 1.25);
    const brightness = bandBrightness(l) * (1 - riftDarkness(l, b));
    if (next() > brightness) continue;
    const [ra, dec] = galacticToJ2000(l, b);
    raDeg[n] = ra;
    decDeg[n] = dec;
    weight[n] = brightness * Math.exp(-(b * b) / (2 * bandWidth(l) ** 2));
    cloud[n] = isCloud ? 1 : 0;
    n++;
  }
  return { catalog: { raDeg, decDeg }, weight, cloud };
}
