// The horizon of a spot for the first-person photo: the terrain in bands of distance (the near
// ground, the hills, the mountains, and the far ridges), the skyline of all of them, and the
// prominent peaks on the skyline. The core calculates each band (horizonProfile with a minimum and
// a maximum distance); this module combines them.

import type { Almucantar, HorizonProfile, Observer } from "@almucantar/core";
import type { PhotoTerrain } from "@almucantar/web-shared/photo";
import { gridsAround } from "./tiles";

/** The bands of the terrain, from near to far: the minimum and the maximum distance in meters. */
export const BANDS: [number, number][] = [
  [250, 2_500],
  [2_500, 9_000],
  [9_000, 30_000],
  [30_000, 90_000],
];

/** The step of the azimuth of the bands. 0.2° is about 6 samples across a lens of 600 mm. */
export const STEP_DEG = 0.2;

export interface Horizon {
  observer: Observer;
  /** The ground at the spot, from the near grid. */
  groundM: number;
  bands: HorizonProfile[];
  /** The highest terrain of all bands, and its distance: the profile for darkWindows(). */
  skyline: HorizonProfile;
  terrain: PhotoTerrain;
}

/** The value of a circular array at a fractional index, NaN if a neighbor has no data. */
function interpolate(values: ArrayLike<number>, position: number): number {
  const n = values.length;
  const i = Math.floor(position);
  const t = position - i;
  const a = values[((i % n) + n) % n];
  const b = values[(((i + 1) % n) + n) % n];
  return a + t * (b - a);
}

/** The altitude of a profile at an azimuth, with the linear interpolation of the core. */
export function profileAltitude(profile: HorizonProfile, azimuthDeg: number): number {
  const az = ((azimuthDeg % 360) + 360) % 360;
  return interpolate(profile.altitudeDeg, az / profile.azimuthStepDeg);
}

/** The skyline of the bands: for each azimuth the highest altitude, and its distance. */
export function combine(bands: HorizonProfile[], observerElevationM: number): HorizonProfile {
  const n = bands[0].altitudeDeg.length;
  const altitude = new Float64Array(n).fill(NaN);
  const distance = new Float64Array(n).fill(NaN);
  for (const band of bands) {
    for (let i = 0; i < n; i++) {
      const a = band.altitudeDeg[i];
      if (Number.isFinite(a) && !(altitude[i] >= a)) {
        altitude[i] = a;
        distance[i] = band.distanceM[i];
      }
    }
  }
  return {
    azimuthStepDeg: bands[0].azimuthStepDeg,
    altitudeDeg: altitude,
    distanceM: distance,
    observerElevationM,
  };
}

/** The tiles of each band: fine near the spot, coarse far away, so a band reads few tiles. */
const BAND_TILES: [number, number][][] = [
  [[12, 3_000]],
  [[11, 10_000]],
  [
    [11, 12_000],
    [10, 31_000],
  ],
  [
    [10, 40_000],
    [8, 91_000],
  ],
];

/**
 * Calculates the horizon of a spot. It takes a few hundred milliseconds per band in WebAssembly, so
 * it yields between the bands and reports the progress.
 */
export async function computeHorizon(
  almucantar: Pick<Almucantar, "mapTiles" | "terrariumGrid" | "horizonProfile">,
  observer: Observer,
  onProgress: (done: number, total: number) => void = () => {},
): Promise<Horizon> {
  const bands: HorizonProfile[] = [];
  let groundM = NaN;
  for (let b = 0; b < BANDS.length; b++) {
    onProgress(b, BANDS.length);
    const tiles = (
      await Promise.all(
        BAND_TILES[b].map(([zoom, radius]) => gridsAround(almucantar, observer, radius, zoom)),
      )
    ).flat();
    // The near grid has the ground at the spot; the other bands use the same eye.
    const ground = b === 0 ? null : groundM;
    await new Promise((resolve) => setTimeout(resolve, 0));
    const [minDistanceM, maxDistanceM] = BANDS[b];
    let profile: HorizonProfile;
    try {
      profile = almucantar.horizonProfile(observer, tiles, {
        azimuthStepDeg: STEP_DEG,
        minDistanceM,
        maxDistanceM,
        useGridElevation: ground === null || !Number.isFinite(ground),
        observerElevationM: Number.isFinite(ground ?? NaN) ? ground! : 0,
      });
    } catch {
      // No tile has data here (the sea, or offline): an empty band.
      const n = Math.round(360 / STEP_DEG);
      profile = {
        azimuthStepDeg: STEP_DEG,
        altitudeDeg: new Float64Array(n).fill(NaN),
        distanceM: new Float64Array(n).fill(NaN),
        observerElevationM: Number.isFinite(groundM) ? groundM : 0,
      };
    }
    if (b === 0) groundM = profile.observerElevationM;
    bands.push(profile);
  }
  onProgress(BANDS.length, BANDS.length);
  const skyline = combine(bands, groundM);
  return {
    observer,
    groundM,
    bands,
    skyline,
    terrain: {
      layers: bands.length,
      altitude: (layer, azimuthDeg) => profileAltitude(bands[layer], azimuthDeg),
    },
  };
}

/** A prominent peak on the skyline of a spot. */
export interface Peak {
  azimuthDeg: number;
  altitudeDeg: number;
  distanceM: number;
  /** How far the peak stands out of the skyline next to it. */
  prominenceDeg: number;
}

export interface PeakOptions {
  /** The skyline on each side that the peak must stand out of, in degrees of azimuth. */
  windowDeg: number;
  minProminenceDeg: number;
  /** A peak nearer than this is a hill next to the spot, not a landmark. */
  minDistanceM: number;
  /** The most peaks, the most prominent first. */
  count: number;
}

/**
 * The peaks of a skyline: the azimuths whose altitude is the highest within `windowDeg` on both
 * sides, and that stand out of the lowest point on each side by at least `minProminenceDeg`.
 */
export function findPeaks(skyline: HorizonProfile, options: Partial<PeakOptions> = {}): Peak[] {
  const { windowDeg = 5, minProminenceDeg = 0.6, minDistanceM = 1_500, count = 10 } = options;
  const a = skyline.altitudeDeg;
  const n = a.length;
  const w = Math.max(1, Math.round(windowDeg / skyline.azimuthStepDeg));
  const at = (i: number) => a[((i % n) + n) % n];
  const peaks: Peak[] = [];
  for (let i = 0; i < n; i++) {
    const h = a[i];
    if (!Number.isFinite(h) || !(skyline.distanceM[i] >= minDistanceM)) continue;
    let highest = true;
    let lowLeft = h;
    let lowRight = h;
    for (let k = 1; k <= w && highest; k++) {
      const left = at(i - k);
      const right = at(i + k);
      // A plateau counts once: the first sample of equal values wins.
      if (left >= h || right > h) highest = false;
      if (Number.isFinite(left)) lowLeft = Math.min(lowLeft, left);
      if (Number.isFinite(right)) lowRight = Math.min(lowRight, right);
    }
    if (!highest) continue;
    const prominence = h - Math.max(lowLeft, lowRight);
    if (prominence < minProminenceDeg) continue;
    peaks.push({
      azimuthDeg: i * skyline.azimuthStepDeg,
      altitudeDeg: h,
      distanceM: skyline.distanceM[i],
      prominenceDeg: prominence,
    });
  }
  return peaks.sort((p, q) => q.prominenceDeg - p.prominenceDeg).slice(0, count);
}
