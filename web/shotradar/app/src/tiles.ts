// The Terrarium elevation tiles of shotradar: the cache, the decoded tiles in memory, the height of
// the ground at a place, the summit near a tap, and the elevation grids of the core.

import type { Almucantar, ElevationGrid, Observer } from "@almucantar/core";
import { toWorld, fromWorld } from "@almucantar/web-shared/basemap";
import {
  TileCache,
  cacheStorageStore,
  decodePng,
  localStorageIndex,
  tileUrl,
} from "@almucantar/web-shared/terrain";
import { heightAt } from "./relief";

/** The zoom of the height of a tapped point (about 10 m per pixel). */
export const POINT_ZOOM = 13;

export interface Decoded {
  rgba: Uint8ClampedArray;
  width: number;
  height: number;
}

export const tileCache = new TileCache({
  store: cacheStorageStore("shotradar-terrain-v1"),
  ...localStorageIndex("shotradar-terrain"),
  fetch: (url, init) => fetch(url, init),
  now: () => Date.now(),
});

/** The decoded tiles in memory, the oldest first, so the oldest go first when there are many. */
const decoded = new Map<string, Promise<Decoded | null>>();

export function decodedTile(zoom: number, x: number, y: number): Promise<Decoded | null> {
  const url = tileUrl({ zoom, x, y });
  let tile = decoded.get(url);
  if (!tile) {
    tile = tileCache
      .get(url)
      .then((blob) => (blob ? decodePng(blob) : null))
      .catch(() => null);
    decoded.set(url, tile);
    if (decoded.size > 300) decoded.delete(decoded.keys().next().value!);
  }
  return tile;
}

/** The meters per pixel of a 256-pixel tile scheme at a zoom and a latitude. */
export function metersPerPixel(zoom: number, latitudeDeg: number): number {
  return (40_075_016.686 * Math.cos((latitudeDeg * Math.PI) / 180)) / (256 * 2 ** zoom);
}

/** The tile of POINT_ZOOM at a place, and the pixel of the place in it. */
async function pointTile(at: Observer) {
  const n = 2 ** POINT_ZOOM;
  const [wx, wy] = toWorld(at.longitudeDeg, at.latitudeDeg, POINT_ZOOM);
  const x = Math.floor(wx / 256);
  const y = Math.floor(wy / 256);
  const tile = await decodedTile(POINT_ZOOM, ((x % n) + n) % n, y);
  if (!tile) return null;
  const scale = tile.width / 256;
  return { tile, x, y, scale, px: (wx - x * 256) * scale, py: (wy - y * 256) * scale };
}

/** The height of the ground at a place, or null if the tile is not available (offline). */
export async function groundAt(at: Observer): Promise<number | null> {
  const t = await pointTile(at);
  return t ? heightAt(t.tile.rgba, t.tile.width, t.tile.height, t.px, t.py) : null;
}

/** The highest point within `radiusM` of `at`, or null offline. */
export async function summitNear(at: Observer, radiusM: number): Promise<Observer | null> {
  const t = await pointTile(at);
  if (!t) return null;
  const { tile, scale } = t;
  const r = Math.max(1, Math.round(radiusM / (metersPerPixel(POINT_ZOOM, at.latitudeDeg) / scale)));
  const px = Math.floor(t.px);
  const py = Math.floor(t.py);
  let best = -Infinity;
  let bestX = px;
  let bestY = py;
  for (let yy = Math.max(0, py - r); yy <= Math.min(tile.height - 1, py + r); yy++) {
    for (let xx = Math.max(0, px - r); xx <= Math.min(tile.width - 1, px + r); xx++) {
      if ((xx - px) ** 2 + (yy - py) ** 2 > r * r) continue;
      const h = heightAt(tile.rgba, tile.width, tile.height, xx + 0.5, yy + 0.5);
      if (h > best) {
        best = h;
        bestX = xx;
        bestY = yy;
      }
    }
  }
  const [lon, lat] = fromWorld(
    t.x * 256 + (bestX + 0.5) / scale,
    t.y * 256 + (bestY + 0.5) / scale,
    POINT_ZOOM,
  );
  return { latitudeDeg: lat, longitudeDeg: lon };
}

/** The elevation grids of the core in memory, by the URL of the tile. */
const grids = new Map<string, Promise<ElevationGrid | null>>();

/** The grids of the core of `zoom` within `radiusM` of a place. Tiles without data are left out. */
export async function gridsAround(
  almucantar: Pick<Almucantar, "mapTiles" | "terrariumGrid">,
  center: Observer,
  radiusM: number,
  zoom: number,
): Promise<ElevationGrid[]> {
  const tiles = almucantar.mapTiles(center, radiusM, zoom);
  const loaded = await Promise.all(
    tiles.map((t) => {
      const url = tileUrl(t);
      let grid = grids.get(url);
      if (!grid) {
        grid = decodedTile(t.zoom, t.x, t.y).then((tile) =>
          tile ? almucantar.terrariumGrid(tile.rgba, tile.width, tile.height, t) : null,
        );
        grids.set(url, grid);
        if (grids.size > 200) grids.delete(grids.keys().next().value!);
      }
      return grid;
    }),
  );
  return loaded.filter((g): g is ElevationGrid => g !== null);
}
