// The terrain horizon of the PWA: it downloads the elevation tiles, keeps them in a cache, and
// calculates the horizon profile with the core.
//
// The tiles are the AWS Terrain Tiles (Terrarium PNG, Web Mercator). The Copernicus tiles of the
// native apps are not possible here, because their bucket sends no CORS headers.
//
// The cache must not grow without limit and must not keep old data forever:
// - It removes the tiles that were used least recently when it is larger than MAX_CACHE_BYTES,
//   but never a tile that the current place needs.
// - It checks a tile again (a conditional request with the ETag) when the last check is older
//   than MAX_AGE_MS. A network error keeps the old tile, so the app works offline.

import type { ElevationGrid, HorizonProfile, MapTile, Observer, Sunradar } from "@sunradar/core";

export const TERRAIN_ZOOM = 10;
export const TERRAIN_DISTANCE_M = 100_000;
export const MAX_CACHE_BYTES = 100 * 1024 * 1024;
export const MAX_AGE_MS = 180 * 86_400_000;
/** The profile is calculated again only if the place moves more than this distance. */
export const MOVE_THRESHOLD_M = 500;
export const ATTRIBUTION_URL = "https://github.com/tilezen/joerd/blob/master/docs/attribution.md";

export function tileUrl(tile: Pick<MapTile, "zoom" | "x" | "y">): string {
  return `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${tile.zoom}/${tile.x}/${tile.y}.png`;
}

/** What the cache knows about one tile. */
export interface TileEntry {
  etag: string | null;
  lastModified: string | null;
  /** The time of the download or of the last check, in ms since 1970. */
  checkedAt: number;
  usedAt: number;
  bytes: number;
}

/** The part of the Cache API that the cache uses, so a test can give a fake. */
export interface BlobStore {
  get(url: string): Promise<Blob | null>;
  put(url: string, blob: Blob): Promise<void>;
  delete(url: string): Promise<void>;
}

/**
 * A place whose tiles the user keeps offline, for example for a trip. Its tiles are never evicted
 * and do not count toward MAX_CACHE_BYTES.
 */
export interface KeptPlace {
  id: string;
  name: string;
  observer: Observer;
  urls: string[];
}

export interface TileCacheDeps {
  store: BlobStore;
  loadIndex(): Record<string, TileEntry>;
  saveIndex(index: Record<string, TileEntry>): void;
  loadKept?(): KeptPlace[];
  saveKept?(kept: KeptPlace[]): void;
  fetch(url: string, init?: RequestInit): Promise<Response>;
  now(): number;
}

export class TileCache {
  private index: Record<string, TileEntry>;
  private keptPlaces: KeptPlace[];

  constructor(private readonly deps: TileCacheDeps) {
    this.index = deps.loadIndex();
    this.keptPlaces = deps.loadKept?.() ?? [];
  }

  /** The total size of the cached tiles in bytes. */
  get bytes(): number {
    return Object.values(this.index).reduce((sum, e) => sum + e.bytes, 0);
  }

  /** The size of the cached tiles of `urls` in bytes. */
  bytesOf(urls: Iterable<string>): number {
    let sum = 0;
    for (const url of new Set(urls)) sum += this.index[url]?.bytes ?? 0;
    return sum;
  }

  /** The places that the user keeps offline. */
  get kept(): readonly KeptPlace[] {
    return this.keptPlaces;
  }

  /** The tiles of all kept places. */
  keptUrls(): Set<string> {
    return new Set(this.keptPlaces.flatMap((p) => p.urls));
  }

  /** Adds a kept place, or replaces the kept place with the same id. */
  keep(place: KeptPlace): void {
    this.keptPlaces = [...this.keptPlaces.filter((p) => p.id !== place.id), place];
    this.deps.saveKept?.(this.keptPlaces);
  }

  /** Releases a kept place: its tiles become normal cache tiles again. */
  release(id: string): void {
    this.keptPlaces = this.keptPlaces.filter((p) => p.id !== id);
    this.deps.saveKept?.(this.keptPlaces);
  }

  entry(url: string): TileEntry | undefined {
    return this.index[url];
  }

  /**
   * Returns the tile from the cache or the network, or null if neither has it. A cached tile that
   * is older than MAX_AGE_MS is checked again first.
   */
  async get(url: string): Promise<Blob | null> {
    const now = this.deps.now();
    const entry = this.index[url];
    const cached = entry ? await this.deps.store.get(url) : null;
    if (entry && cached && now - entry.checkedAt < MAX_AGE_MS) {
      this.touch(url, now);
      return cached;
    }
    const headers: Record<string, string> = {};
    if (entry && cached) {
      if (entry.etag) headers["If-None-Match"] = entry.etag;
      if (entry.lastModified) headers["If-Modified-Since"] = entry.lastModified;
    }
    let response: Response;
    try {
      response = await this.deps.fetch(url, { headers });
    } catch {
      // Offline: an old tile is better than no tile.
      if (cached) this.touch(url, now);
      return cached;
    }
    if (response.status === 304 && entry && cached) {
      this.index[url] = { ...entry, checkedAt: now, usedAt: now };
      this.save();
      return cached;
    }
    if (!response.ok) return cached;
    const blob = await response.blob();
    await this.deps.store.put(url, blob);
    this.index[url] = {
      etag: response.headers.get("ETag"),
      lastModified: response.headers.get("Last-Modified"),
      checkedAt: now,
      usedAt: now,
      bytes: blob.size,
    };
    this.save();
    return blob;
  }

  /** Removes a tile, for example one that cannot be decoded. */
  async remove(url: string): Promise<void> {
    delete this.index[url];
    await this.deps.store.delete(url);
    this.save();
  }

  /**
   * Removes the tiles that were used least recently until the cache is not larger than maxBytes.
   * The tiles in keep stay.
   */
  async evict(keep: Set<string>, maxBytes = MAX_CACHE_BYTES): Promise<void> {
    // The tiles of the kept places stay and do not count toward the limit.
    const kept = this.keptUrls();
    let total = this.bytes - this.bytesOf(kept);
    const candidates = Object.entries(this.index)
      .filter(([url]) => !keep.has(url) && !kept.has(url))
      .sort(([, a], [, b]) => a.usedAt - b.usedAt);
    for (const [url, entry] of candidates) {
      if (total <= maxBytes) break;
      total -= entry.bytes;
      delete this.index[url];
      await this.deps.store.delete(url);
    }
    this.save();
  }

  private touch(url: string, now: number) {
    const entry = this.index[url];
    if (entry) {
      entry.usedAt = now;
      this.save();
    }
  }

  private save() {
    this.deps.saveIndex(this.index);
  }
}

export interface KeepResult {
  tiles: number;
  bytes: number;
  missing: number;
}

/**
 * Downloads all tiles of a place and keeps them offline. The place counts as kept before the
 * download, so an eviction during the download does not remove its tiles. `onProgress` gets the
 * number of tiles that are done.
 */
export async function keepOffline(
  sunradar: Pick<Sunradar, "mapTiles">,
  cache: TileCache,
  observer: Observer,
  name: string,
  onProgress: (done: number, total: number) => void,
): Promise<KeepResult> {
  const urls = sunradar.mapTiles(observer, TERRAIN_DISTANCE_M, TERRAIN_ZOOM).map(tileUrl);
  const id = `${observer.latitudeDeg.toFixed(4)},${observer.longitudeDeg.toFixed(4)}`;
  cache.keep({ id, name, observer, urls });
  let next = 0;
  let done = 0;
  let missing = 0;
  const worker = async () => {
    while (next < urls.length) {
      const url = urls[next++];
      if (!(await cache.get(url))) missing++;
      onProgress(++done, urls.length);
    }
  };
  await Promise.all(Array.from({ length: 6 }, worker));
  return { tiles: urls.length, bytes: cache.bytesOf(urls), missing };
}

/** The distance between two places in meters (haversine). */
export function distanceM(a: Observer, b: Observer): number {
  const rad = Math.PI / 180;
  const dLat = (b.latitudeDeg - a.latitudeDeg) * rad;
  const dLon = (b.longitudeDeg - a.longitudeDeg) * rad;
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(a.latitudeDeg * rad) * Math.cos(b.latitudeDeg * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6_371_008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Decodes a Terrarium PNG into its RGBA pixels, without color management. */
export async function decodePng(
  blob: Blob,
): Promise<{ rgba: Uint8ClampedArray; width: number; height: number }> {
  const bitmap = await createImageBitmap(blob, {
    colorSpaceConversion: "none",
    premultiplyAlpha: "none",
  });
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  const context = canvas.getContext("2d", { willReadFrequently: true })!;
  context.drawImage(bitmap, 0, 0);
  const data = context.getImageData(0, 0, bitmap.width, bitmap.height).data;
  bitmap.close();
  return { rgba: data, width: canvas.width, height: canvas.height };
}

export type TerrainState =
  | { kind: "off" }
  | { kind: "loading"; done: number; total: number }
  | { kind: "ready"; profile: HorizonProfile; missing: number }
  | { kind: "failed"; message: string };

export interface TerrainDeps {
  sunradar: Pick<Sunradar, "mapTiles" | "terrariumGrid" | "horizonProfile">;
  cache: TileCache;
  decode(blob: Blob): Promise<{ rgba: Uint8ClampedArray; width: number; height: number }>;
  onChange(state: TerrainState): void;
}

/** Loads the tiles for a place and calculates its profile. A newer place cancels an older one. */
export class Terrain {
  private place: Observer | null = null;
  private generation = 0;
  state: TerrainState = { kind: "off" };

  constructor(private readonly deps: TerrainDeps) {}

  /** Starts a calculation if the place moved more than MOVE_THRESHOLD_M since the last one. */
  update(observer: Observer): void {
    if (this.place && distanceM(this.place, observer) <= MOVE_THRESHOLD_M) return;
    this.place = observer;
    void this.load(observer, ++this.generation);
  }

  /** Stops the terrain, for example when the user switches it off. */
  stop(): void {
    this.place = null;
    this.generation++;
    this.set({ kind: "off" });
  }

  private set(state: TerrainState) {
    this.state = state;
    this.deps.onChange(state);
  }

  private async load(observer: Observer, generation: number) {
    const { sunradar, cache } = this.deps;
    const tiles = sunradar.mapTiles(observer, TERRAIN_DISTANCE_M, TERRAIN_ZOOM);
    const urls = tiles.map(tileUrl);
    let done = 0;
    let missing = 0;
    const grids: ElevationGrid[] = [];
    this.set({ kind: "loading", done, total: tiles.length });

    const loadTile = async (tile: MapTile, url: string, retry = true): Promise<void> => {
      const blob = await cache.get(url);
      if (generation !== this.generation) return;
      if (!blob) {
        missing++;
        return;
      }
      try {
        const { rgba, width, height } = await this.deps.decode(blob);
        grids.push(sunradar.terrariumGrid(rgba, width, height, tile));
      } catch {
        // A damaged file: remove it and download it again one time.
        await cache.remove(url);
        if (retry) return loadTile(tile, url, false);
        missing++;
      }
    };

    // A few downloads at the same time, the nearest tiles first.
    let next = 0;
    const worker = async () => {
      while (next < tiles.length && generation === this.generation) {
        const i = next++;
        await loadTile(tiles[i], urls[i]);
        done++;
        if (generation === this.generation)
          this.set({ kind: "loading", done, total: tiles.length });
      }
    };
    await Promise.all(Array.from({ length: 6 }, worker));
    if (generation !== this.generation) return;
    await cache.evict(new Set(urls));
    if (grids.length === 0) {
      this.set({ kind: "failed", message: "no terrain data (offline?)" });
      return;
    }
    try {
      const profile = sunradar.horizonProfile(observer, grids);
      this.set({ kind: "ready", profile, missing });
    } catch (e) {
      // For example, no tile has data at the place itself.
      this.set({ kind: "failed", message: e instanceof Error ? e.message : String(e) });
    }
  }
}

/** The Cache API store of the tiles. The name does not start with the prefix of the app cache. */
export function cacheStorageStore(name = "sunradar-terrain-v1"): BlobStore {
  const open = () => caches.open(name);
  return {
    async get(url) {
      const response = await (await open()).match(url);
      return response ? response.blob() : null;
    },
    async put(url, blob) {
      await (await open()).put(url, new Response(blob));
    },
    async delete(url) {
      await (await open()).delete(url);
    },
  };
}

const INDEX_KEY = "sunradar-terrain-index-v1";
const KEPT_KEY = "sunradar-terrain-kept-v1";

export function localStorageIndex(): Pick<
  TileCacheDeps,
  "loadIndex" | "saveIndex" | "loadKept" | "saveKept"
> {
  return {
    loadKept() {
      try {
        const kept = JSON.parse(localStorage.getItem(KEPT_KEY) ?? "[]") as KeptPlace[];
        return Array.isArray(kept) ? kept : [];
      } catch {
        return [];
      }
    },
    saveKept(kept) {
      localStorage.setItem(KEPT_KEY, JSON.stringify(kept));
    },
    loadIndex() {
      try {
        return JSON.parse(localStorage.getItem(INDEX_KEY) ?? "{}") as Record<string, TileEntry>;
      } catch {
        return {};
      }
    },
    saveIndex(index) {
      localStorage.setItem(INDEX_KEY, JSON.stringify(index));
    },
  };
}
