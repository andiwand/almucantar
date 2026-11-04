// The offline base map (assets/basemap/basemap.json, Natural Earth): the decode, the search of
// the places, the time zone of a dropped place, and the drawing in Web Mercator.

import type { Observer } from "@almucantar/core";

export interface Line {
  /** lon0, lat0, lon1, lat1, ... in degrees. */
  points: Float32Array;
  west: number;
  east: number;
  south: number;
  north: number;
}

export interface Place {
  name: string;
  lon: number;
  lat: number;
  /** 0 is the largest. */
  rank: number;
  country: string;
  /** An IANA time zone, or "". */
  timeZone: string;
}

export interface BaseMap {
  layers: Record<"coastline" | "borders" | "lakes" | "rivers", Line[]>;
  places: Place[];
  attribution: string;
}

interface RawMap {
  version: number;
  scale: number;
  attribution: string;
  layers: Record<string, number[][]>;
  places: [string, number, number, number, string, string][];
}

export function decodeBaseMap(raw: RawMap): BaseMap {
  if (raw.version !== 1) throw new Error(`base map version ${raw.version} is not supported`);
  const s = raw.scale;
  const decodeLine = (flat: number[]): Line => {
    const points = new Float32Array(flat.length);
    let lon = 0;
    let lat = 0;
    let west = Infinity;
    let east = -Infinity;
    let south = Infinity;
    let north = -Infinity;
    for (let i = 0; i < flat.length; i += 2) {
      lon += flat[i];
      lat += flat[i + 1];
      points[i] = lon / s;
      points[i + 1] = lat / s;
      west = Math.min(west, lon / s);
      east = Math.max(east, lon / s);
      south = Math.min(south, lat / s);
      north = Math.max(north, lat / s);
    }
    return { points, west, east, south, north };
  };
  const layer = (name: string) => (raw.layers[name] ?? []).map(decodeLine);
  return {
    layers: {
      coastline: layer("coastline"),
      borders: layer("borders"),
      lakes: layer("lakes"),
      rivers: layer("rivers"),
    },
    places: raw.places.map(([name, lon, lat, rank, country, timeZone]) => ({
      name,
      lon: lon / s,
      lat: lat / s,
      rank,
      country,
      timeZone,
    })),
    attribution: raw.attribution,
  };
}

/** The places whose name starts with the text (or contains it), the largest first. */
export function searchPlaces(places: Place[], text: string, limit = 8): Place[] {
  const query = fold(text.trim());
  if (!query) return [];
  const starts = places.filter((p) => fold(p.name).startsWith(query));
  const contains = places.filter(
    (p) => !fold(p.name).startsWith(query) && fold(p.name).includes(query),
  );
  return [...starts, ...contains].slice(0, limit);
}

/** Lower case without accents, so "wien" finds "Wien" and "zurich" finds "Zürich". */
function fold(text: string): string {
  return text.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();
}

/** The distance in km on the sphere. */
function distanceKm(a: Observer, lon: number, lat: number): number {
  const rad = Math.PI / 180;
  const dLat = (lat - a.latitudeDeg) * rad;
  const dLon = (lon - a.longitudeDeg) * rad;
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(a.latitudeDeg * rad) * Math.cos(lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** The nearest place to `at`, with a time zone if `withTimeZone`. */
export function nearestPlace(places: Place[], at: Observer, withTimeZone = false): Place | null {
  let best: Place | null = null;
  let bestKm = Infinity;
  for (const p of places) {
    if (withTimeZone && !p.timeZone) continue;
    const d = distanceKm(at, p.lon, p.lat);
    if (d < bestKm) {
      best = p;
      bestKm = d;
    }
  }
  return best;
}

/**
 * The time zone of a dropped place: the zone of the nearest place that has one, if the zone is
 * valid in this browser. The fallback is the zone of the browser.
 */
export function timeZoneAt(places: Place[], at: Observer, fallback: string): string {
  const zone = nearestPlace(places, at, true)?.timeZone;
  if (!zone) return fallback;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: zone });
    return zone;
  } catch {
    return fallback;
  }
}

// --- The view ---------------------------------------------------------------------------------

/** A Web Mercator view: the center and the zoom (256 px for the world at zoom 0). */
export interface MapView {
  lon: number;
  lat: number;
  zoom: number;
}

const MAX_LAT = 85.0511287798;

/** The world coordinates (0 to 256 * 2^zoom) of a place. */
export function toWorld(lon: number, lat: number, zoom: number): [number, number] {
  const size = 256 * 2 ** zoom;
  const phi = (Math.max(-MAX_LAT, Math.min(MAX_LAT, lat)) * Math.PI) / 180;
  return [
    ((lon + 180) / 360) * size,
    ((1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2) * size,
  ];
}

export function fromWorld(x: number, y: number, zoom: number): [number, number] {
  const size = 256 * 2 ** zoom;
  const lon = (x / size) * 360 - 180;
  const lat = (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / size))) * 180) / Math.PI;
  return [((((lon + 180) % 360) + 360) % 360) - 180, lat];
}

/** The place at a screen point of a canvas of width x height. */
export function screenToPlace(
  view: MapView,
  width: number,
  height: number,
  x: number,
  y: number,
): Observer {
  const [cx, cy] = toWorld(view.lon, view.lat, view.zoom);
  const [lon, lat] = fromWorld(cx + x - width / 2, cy + y - height / 2, view.zoom);
  return { latitudeDeg: lat, longitudeDeg: lon };
}

/** The colors of the base map. The default is the dark map of the place panel. */
export interface BaseMapStyle {
  /** The fill of the whole map (the sea), or null to draw over what is already there. */
  background: string | null;
  rivers: string;
  lakes: string;
  coastline: string;
  borders: string;
  label: string;
  /** The halo around a name, so it stays readable over the lines. */
  halo: string;
}

export const DARK_MAP: BaseMapStyle = {
  background: "#0e2440",
  rivers: "rgba(110, 170, 230, 0.5)",
  lakes: "rgba(110, 170, 230, 0.8)",
  coastline: "rgba(220, 230, 240, 0.9)",
  borders: "rgba(255, 200, 61, 0.6)",
  label: "rgba(255, 255, 255, 0.85)",
  halo: "#0e2440",
};

/**
 * Draws the base map into a 2D context of width x height (CSS pixels). Near the date line, and
 * at a small zoom, the screen shows more than one copy of the world, so each copy is drawn.
 */
export function drawBaseMap(
  ctx: CanvasRenderingContext2D,
  map: BaseMap,
  view: MapView,
  width: number,
  height: number,
  pin: Observer | null,
  style: BaseMapStyle = DARK_MAP,
) {
  const [cx, cy] = toWorld(view.lon, view.lat, view.zoom);
  const size = 256 * 2 ** view.zoom;
  const left = cx - width / 2;
  const top = cy - height / 2;
  const [, north] = fromWorld(0, top, view.zoom);
  const [, south] = fromWorld(0, top + height, view.zoom);
  // The copies of the world on the screen: copy k starts at the world x k * size.
  const copies: number[] = [];
  for (let k = Math.floor(left / size); k * size < left + width; k++) copies.push(k);

  if (style.background !== null) {
    ctx.fillStyle = style.background;
    ctx.fillRect(0, 0, width, height);
  }

  const stroke = (lines: Line[], color: string, lineWidth: number, dash: number[] = []) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = lineWidth;
    ctx.setLineDash(dash);
    ctx.beginPath();
    for (const k of copies) {
      const dx = k * size - left;
      // The box of the screen in the degrees of this copy, for the culling of lines.
      const west = (-dx / size) * 360 - 180;
      const east = ((width - dx) / size) * 360 - 180;
      for (const line of lines) {
        if (line.east < west || line.west > east || line.north < south || line.south > north)
          continue;
        const p = line.points;
        for (let i = 0; i < p.length; i += 2) {
          const [x, y] = toWorld(p[i], p[i + 1], view.zoom);
          if (i === 0) ctx.moveTo(x + dx, y - top);
          else ctx.lineTo(x + dx, y - top);
        }
      }
    }
    ctx.stroke();
    ctx.setLineDash([]);
  };
  stroke(map.layers.rivers, style.rivers, 1);
  stroke(map.layers.lakes, style.lakes, 1);
  stroke(map.layers.coastline, style.coastline, 1.2);
  stroke(map.layers.borders, style.borders, 1, [4, 3]);

  // The place names: more of them at a larger zoom.
  const maxRank = Math.min(10, Math.round(view.zoom * 1.6));
  ctx.font = "12px system-ui";
  ctx.textAlign = "left";
  // A halo, so a name stays readable over the lines of the map.
  ctx.strokeStyle = style.halo;
  ctx.lineWidth = 3;
  ctx.lineJoin = "round";
  ctx.setLineDash([]);
  const used: [number, number][] = [];
  for (const place of map.places) {
    if (place.rank > maxRank) continue;
    const [x, y] = toWorld(place.lon, place.lat, view.zoom);
    for (const k of copies) {
      const sx = x + k * size - left;
      const sy = y - top;
      if (sx < 0 || sy < 0 || sx > width || sy > height) continue;
      // Skip a label that is too near to one that is already drawn.
      if (used.some(([ux, uy]) => Math.abs(ux - sx) < 90 && Math.abs(uy - sy) < 22)) continue;
      used.push([sx, sy]);
      ctx.fillStyle = style.label;
      ctx.beginPath();
      ctx.arc(sx, sy, 2, 0, 2 * Math.PI);
      ctx.fill();
      ctx.strokeText(place.name, sx + 5, sy - 4);
      ctx.fillText(place.name, sx + 5, sy - 4);
    }
  }

  if (pin) {
    const [x, y] = toWorld(pin.longitudeDeg, pin.latitudeDeg, view.zoom);
    for (const k of copies) drawPin(ctx, x + k * size - left, y - top);
  }
}

/** A map pin with its tip at (x, y): a yellow drop with a dark outline, and a shadow. */
function drawPin(ctx: CanvasRenderingContext2D, x: number, y: number) {
  const r = 11;
  const headY = y - 1.75 * r;
  ctx.fillStyle = "rgba(0, 0, 0, 0.4)";
  ctx.beginPath();
  ctx.ellipse(x, y, 5, 2, 0, 0, 2 * Math.PI);
  ctx.fill();
  ctx.beginPath();
  ctx.moveTo(x, y);
  // The two sides touch the round head (at 150° and 30° from the top); the arc closes the top.
  ctx.lineTo(x - r * 0.87, headY + r * 0.5);
  ctx.arc(x, headY, r, (150 * Math.PI) / 180, (30 * Math.PI) / 180);
  ctx.closePath();
  ctx.fillStyle = "#ffc83d";
  ctx.fill();
  ctx.strokeStyle = "#3a2a00";
  ctx.lineWidth = 1.5;
  ctx.stroke();
  ctx.fillStyle = "#3a2a00";
  ctx.beginPath();
  ctx.arc(x, headY, 4, 0, 2 * Math.PI);
  ctx.fill();
}
