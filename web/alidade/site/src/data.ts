// The scene of the website: the full moon of 24 November 2026 behind the summit of the
// Großglockner, seen from a ridge above Kals. scripts/site/build_shot.py calculates the terrain with
// the Python binding of the core, so the page downloads no elevation tiles. This module decodes it.

import type { ElevationGrid, HorizonProfile, Observer, Site } from "@almucantar/core";
import shot from "./shot.json";

export const ZONE = shot.spot.zone;

export const PEAK = {
  name: shot.peak.label,
  site: {
    latitudeDeg: shot.peak.latitude_deg,
    longitudeDeg: shot.peak.longitude_deg,
    elevationM: shot.peak.elevation_m,
  } satisfies Site,
};

/** The place of the photographer: the ground, and the eye above it. */
export const SPOT = {
  observer: {
    latitudeDeg: shot.spot.latitude_deg,
    longitudeDeg: shot.spot.longitude_deg,
  } satisfies Observer,
  groundM: shot.spot.ground_m,
  eye: {
    latitudeDeg: shot.spot.latitude_deg,
    longitudeDeg: shot.spot.longitude_deg,
    elevationM: shot.spot.ground_m + shot.spot.eye_height_m,
  } satisfies Site,
};

/** The direction of the summit from the eye, and the distance. */
export const SIGHT = {
  azimuthDeg: shot.sight.azimuth_deg,
  altitudeDeg: shot.sight.altitude_deg,
  distanceM: shot.sight.distance_m,
};

/** The moment of the story: the center of the moon on the summit. */
export const SHOT = {
  unixUtc: shot.shot.unix_utc,
  moonFraction: shot.shot.moon_fraction,
};

// --- The horizon in layers ----------------------------------------------------------------------

const h = shot.horizon;
/** The number of layers. Layer 0 is the nearest ground, the last layer has all terrain. */
export const LAYERS = h.layers.length;
export const LAYER_DISTANCES_M = h.layers.map((l) => l.max_distance_m);
const fine = h.layers.map((l) => Float32Array.from(l.fine, (v) => v / 100));
const wide = h.layers.map((l) => Float32Array.from(l.wide, (v) => v / 100));
const fineSpan = (fine[0].length - 1) * h.fine_step_deg;

const wrap = (deg: number) => ((deg % 360) + 360) % 360;

function lerp(values: Float32Array, index: number, circular: boolean): number {
  const n = values.length;
  let i0 = Math.floor(index);
  let t = index - i0;
  let i1 = i0 + 1;
  if (circular) {
    i0 = ((i0 % n) + n) % n;
    i1 = ((i1 % n) + n) % n;
  } else if (i1 >= n) {
    i1 = i0 = n - 1;
    t = 0;
  }
  return values[i0] * (1 - t) + values[i1] * t;
}

/** The altitude of the terrain of a layer at an azimuth: fine near the summit, coarse elsewhere. */
export function terrainAltitude(layer: number, azimuthDeg: number): number {
  const d = wrap(azimuthDeg - h.fine_first_azimuth_deg);
  if (d <= fineSpan) return lerp(fine[layer], d / h.fine_step_deg, false);
  return lerp(wide[layer], wrap(azimuthDeg) / h.wide_step_deg, true);
}

/**
 * The whole horizon as a profile of the core: the skyline, and the distance of the terrain that
 * makes it. darkWindows() and terrainAltitudeDeg() read it, and the peaks of way A come from it.
 */
export const HORIZON: HorizonProfile = {
  azimuthStepDeg: h.wide_step_deg,
  altitudeDeg: Float64Array.from(wide[LAYERS - 1]),
  distanceM: Float64Array.from(h.skyline_distance_m),
  observerElevationM: shot.spot.ground_m,
};

/** The window of the fine horizon around the summit: its first azimuth and its width. */
export const FINE_WINDOW = { firstAzimuthDeg: h.fine_first_azimuth_deg, spanDeg: fineSpan };

// --- The map ------------------------------------------------------------------------------------

const m = shot.map;
const bytes = Uint8Array.from(atob(m.heights), (c) => c.charCodeAt(0));

/** The heights around the summit and the spot, as a grid of the core. */
export const MAP: ElevationGrid & { heightsM: Float32Array } = {
  northLatitudeDeg: m.north_latitude_deg,
  westLongitudeDeg: m.west_longitude_deg,
  latitudeStepDeg: m.step_deg,
  longitudeStepDeg: m.step_deg,
  rows: m.rows,
  columns: m.columns,
  heightsM: Float32Array.from(bytes, (b) => m.min_m + b * m.step_m),
};

// --- The profile from the spot to the summit -------------------------------------------------------

/** The ground at equal steps from the spot (index 0) to the summit (the last index). */
export const PROFILE = {
  groundM: Float32Array.from(shot.profile.ground_m),
  visible: shot.profile.visible,
};

// --- The map of the Earth for the eclipses ----------------------------------------------------------

/** The coastlines of the base map: each line is lon0, lat0, lon1, lat1, ... in degrees. */
export const WORLD: Float32Array[] = shot.world.coastline.map((steps) => {
  const points = new Float32Array(steps.length);
  let lon = 0;
  let lat = 0;
  for (let i = 0; i < steps.length; i += 2) {
    lon += steps[i];
    lat += steps[i + 1];
    points[i] = lon / 100;
    points[i + 1] = lat / 100;
  }
  return points;
});

export const ATTRIBUTION = shot.attribution;
