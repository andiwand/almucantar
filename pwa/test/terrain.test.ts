import { describe, expect, test, vi } from "vitest";
import type { ElevationGrid, HorizonProfile, MapTile } from "@sunradar/core";
import { MAX_AGE_MS, Terrain, TileCache, distanceM, keepOffline, tileUrl } from "../src/terrain";
import type { BlobStore, KeptPlace, TerrainDeps, TerrainState, TileEntry } from "../src/terrain";

const DAY = 86_400_000;

function fakeStore() {
  const blobs = new Map<string, Blob>();
  const store: BlobStore = {
    get: async (url) => blobs.get(url) ?? null,
    put: async (url, blob) => void blobs.set(url, blob),
    delete: async (url) => void blobs.delete(url),
  };
  return { blobs, store };
}

function setup(
  responses: (url: string, init?: RequestInit) => Response | Promise<Response>,
  kept: KeptPlace[] = [],
) {
  const { blobs, store } = fakeStore();
  let index: Record<string, TileEntry> = {};
  let storedKept = kept;
  let now = 1_000 * DAY;
  const fetch = vi.fn(async (url: string, init?: RequestInit) => responses(url, init));
  const cache = new TileCache({
    store,
    loadIndex: () => index,
    saveIndex: (i) => (index = JSON.parse(JSON.stringify(i))),
    loadKept: () => storedKept,
    saveKept: (k) => (storedKept = JSON.parse(JSON.stringify(k))),
    fetch,
    now: () => now,
  });
  return {
    cache,
    blobs,
    fetch,
    index: () => index,
    kept: () => storedKept,
    advance: (ms: number) => (now += ms),
  };
}

const png = (size: number) =>
  new Response(new Blob([new Uint8Array(size)]), {
    status: 200,
    headers: { ETag: '"abc"', "Last-Modified": "Wed, 15 Nov 2017 23:44:38 GMT" },
  });

describe("TileCache", () => {
  test("downloads a tile once and serves it from the cache", async () => {
    const t = setup(() => png(10));
    expect((await t.cache.get("a"))?.size).toBe(10);
    expect((await t.cache.get("a"))?.size).toBe(10);
    expect(t.fetch).toHaveBeenCalledTimes(1);
    expect(t.index().a.etag).toBe('"abc"');
  });

  test("checks an old tile again with its ETag and keeps it on 304", async () => {
    const t = setup((_, init) =>
      (init?.headers as Record<string, string>)?.["If-None-Match"]
        ? new Response(null, { status: 304 })
        : png(10),
    );
    await t.cache.get("a");
    t.advance(MAX_AGE_MS + DAY);
    expect((await t.cache.get("a"))?.size).toBe(10);
    expect(t.fetch).toHaveBeenCalledTimes(2);
    expect(t.fetch.mock.calls[1][1]?.headers).toMatchObject({ "If-None-Match": '"abc"' });
    // The check renews the age, so the next use needs no request.
    await t.cache.get("a");
    expect(t.fetch).toHaveBeenCalledTimes(2);
  });

  test("replaces an old tile when the server has a new one", async () => {
    let size = 10;
    const t = setup(() => png(size));
    await t.cache.get("a");
    t.advance(MAX_AGE_MS + DAY);
    size = 20;
    expect((await t.cache.get("a"))?.size).toBe(20);
    expect(t.index().a.bytes).toBe(20);
  });

  test("keeps an old tile when the network fails", async () => {
    let online = true;
    const t = setup(() => {
      if (!online) throw new TypeError("offline");
      return png(10);
    });
    await t.cache.get("a");
    t.advance(MAX_AGE_MS + DAY);
    online = false;
    expect((await t.cache.get("a"))?.size).toBe(10);
    expect(await t.cache.get("b")).toBeNull();
  });

  test("removes the tiles that were used least recently, but not the kept ones", async () => {
    const t = setup(() => png(10));
    for (const url of ["a", "b", "c", "d"]) {
      await t.cache.get(url);
      t.advance(1000);
    }
    await t.cache.get("a"); // a is now the most recent
    await t.cache.evict(new Set(["b"]), 20);
    expect([...t.blobs.keys()].sort()).toEqual(["a", "b"]);
    expect(Object.keys(t.index()).sort()).toEqual(["a", "b"]);
    expect(t.cache.bytes).toBe(20);
  });
});

describe("Terrain", () => {
  const tiles: MapTile[] = [
    { zoom: 8, x: 1, y: 2, distanceM: 0 },
    { zoom: 8, x: 2, y: 2, distanceM: 1000 },
  ];
  const fineTiles: MapTile[] = [{ zoom: 10, x: 4, y: 8, distanceM: 0 }];
  // A flat profile: below the threshold, so the app loads only the coarse tiles.
  const profile = { azimuthStepDeg: 90, altitudeDeg: [0, 0.5, 0, 0] } as unknown as HorizonProfile;

  function terrain(
    decode: (blob: Blob) => Promise<never> | Promise<object>,
    coarseProfile: HorizonProfile = profile,
  ) {
    const t = setup(() => png(10));
    const states: TerrainState[] = [];
    const horizonProfile = vi.fn((_o: unknown, grids: ElevationGrid[]) =>
      grids.length === 2 ? coarseProfile : profile,
    );
    const sunradar = {
      mapTiles: vi.fn((_o: unknown, _d: number, zoom: number) => (zoom === 8 ? tiles : fineTiles)),
      terrariumGrid: vi.fn(() => ({}) as ElevationGrid),
      horizonProfile,
    };
    const subject = new Terrain({
      sunradar: sunradar as unknown as TerrainDeps["sunradar"],
      cache: t.cache,
      decode: decode as never,
      onChange: (s) => states.push(s),
    });
    return { subject, states, sunradar, t };
  }

  const done = (states: TerrainState[]) =>
    vi.waitFor(() => expect(["ready", "failed"]).toContain(states.at(-1)?.kind));

  test("loads all tiles and gives the profile", async () => {
    const { subject, states, t } = terrain(async () => ({
      rgba: new Uint8ClampedArray(16),
      width: 2,
      height: 2,
    }));
    subject.update({ latitudeDeg: 47, longitudeDeg: 11 });
    await done(states);
    expect(states.at(-1)).toEqual({
      kind: "ready",
      profile,
      missing: 0,
      observer: { latitudeDeg: 47, longitudeDeg: 11 },
      fine: false,
    });
    expect(t.fetch.mock.calls.map((c) => c[0]).sort()).toEqual(tiles.map(tileUrl).sort());
  });

  test("does not calculate again for a small move", async () => {
    const { subject, states, sunradar } = terrain(async () => ({
      rgba: new Uint8ClampedArray(16),
      width: 2,
      height: 2,
    }));
    subject.update({ latitudeDeg: 47, longitudeDeg: 11 });
    await done(states);
    const calls = sunradar.mapTiles.mock.calls.length;
    subject.update({ latitudeDeg: 47.001, longitudeDeg: 11 }); // about 110 m
    expect(sunradar.mapTiles).toHaveBeenCalledTimes(calls);
    subject.update({ latitudeDeg: 47.01, longitudeDeg: 11 }); // about 1.1 km
    expect(sunradar.mapTiles.mock.calls.length).toBeGreaterThan(calls);
  });

  test("downloads a damaged tile again one time", async () => {
    let calls = 0;
    const { subject, states, t } = terrain(async () => {
      calls++;
      if (calls === 1) throw new Error("damaged");
      return { rgba: new Uint8ClampedArray(16), width: 2, height: 2 };
    });
    subject.update({ latitudeDeg: 47, longitudeDeg: 11 });
    await done(states);
    expect(states.at(-1)?.kind).toBe("ready");
    expect(t.fetch).toHaveBeenCalledTimes(3);
  });
});

test("distanceM", () => {
  expect(
    distanceM({ latitudeDeg: 47, longitudeDeg: 11 }, { latitudeDeg: 48, longitudeDeg: 11 }),
  ).toBeCloseTo(111_195, -1);
});

describe("kept places", () => {
  const place = (urls: string[]): KeptPlace => ({
    id: "trip",
    name: "Trip",
    observer: { latitudeDeg: 47, longitudeDeg: 11 },
    urls,
  });

  test("the eviction does not remove or count the tiles of a kept place", async () => {
    const t = setup(() => png(10));
    for (const url of ["a", "b", "c", "d"]) {
      await t.cache.get(url);
      t.advance(1000);
    }
    t.cache.keep(place(["a", "b"]));
    // Without the kept tiles, the cache has 20 bytes, so a limit of 20 removes nothing.
    await t.cache.evict(new Set(), 20);
    expect([...t.blobs.keys()].sort()).toEqual(["a", "b", "c", "d"]);
    // A limit of 10 removes the oldest normal tile (c), not the older kept tiles.
    await t.cache.evict(new Set(), 10);
    expect([...t.blobs.keys()].sort()).toEqual(["a", "b", "d"]);
  });

  test("release makes the tiles normal cache tiles again", async () => {
    const t = setup(() => png(10));
    await t.cache.get("a");
    t.cache.keep(place(["a"]));
    t.cache.release("trip");
    expect(t.kept()).toEqual([]);
    await t.cache.evict(new Set(), 0);
    expect(t.blobs.size).toBe(0);
  });

  test("the kept places survive a restart", () => {
    const t = setup(() => png(10), [place(["x"])]);
    expect(t.cache.kept.map((p) => p.id)).toEqual(["trip"]);
    expect(t.cache.keptUrls()).toEqual(new Set(["x"]));
  });

  test("keepOffline downloads all tiles and reports the missing ones", async () => {
    const t = setup((url) =>
      url.endsWith("/2/2.png") ? new Response(null, { status: 500 }) : png(10),
    );
    const tiles: MapTile[] = [
      { zoom: 10, x: 1, y: 2, distanceM: 0 },
      { zoom: 10, x: 2, y: 2, distanceM: 1 },
      { zoom: 10, x: 3, y: 2, distanceM: 2 },
    ];
    const progress: number[] = [];
    const result = await keepOffline(
      { mapTiles: () => tiles },
      t.cache,
      { latitudeDeg: 47, longitudeDeg: 11 },
      "Innsbruck",
      (done) => progress.push(done),
      false, // only the coarse tiles, as for a flat place
    );
    expect(result).toEqual({ tiles: 3, bytes: 20, missing: 1 });
    expect(progress).toEqual([1, 2, 3]);
    expect(t.kept()[0].name).toBe("Innsbruck");
    expect(t.kept()[0].urls).toEqual(tiles.map(tileUrl));
  });
});

describe("the threshold of the terrain", () => {
  test("a high coarse profile loads the fine tiles near the observer", async () => {
    const t = setup(() => png(10));
    const states: TerrainState[] = [];
    const coarse: MapTile[] = [{ zoom: 8, x: 1, y: 2, distanceM: 0 }];
    const fine: MapTile[] = [
      { zoom: 10, x: 4, y: 8, distanceM: 0 },
      { zoom: 10, x: 5, y: 8, distanceM: 100 },
    ];
    const high = { azimuthStepDeg: 90, altitudeDeg: [0, 5, 0, 0] } as unknown as HorizonProfile;
    const detailed = {
      azimuthStepDeg: 90,
      altitudeDeg: [0, 5.5, 0, 0],
    } as unknown as HorizonProfile;
    const horizonProfile = vi.fn((_o: unknown, grids: ElevationGrid[], _options?: object) =>
      grids.length === 1 ? high : detailed,
    );
    const subject = new Terrain({
      sunradar: {
        mapTiles: (_o: unknown, distance: number, zoom: number) => {
          expect(zoom === 8 ? distance : 25_000).toBe(zoom === 8 ? 100_000 : distance);
          return zoom === 8 ? coarse : fine;
        },
        terrariumGrid: () => ({}) as ElevationGrid,
        horizonProfile,
      } as unknown as TerrainDeps["sunradar"],
      cache: t.cache,
      decode: async () => ({ rgba: new Uint8ClampedArray(16), width: 2, height: 2 }),
      onChange: (s) => states.push(s),
    });
    subject.update({ latitudeDeg: 47, longitudeDeg: 11 });
    await vi.waitFor(() => {
      const last = states.at(-1);
      expect(last?.kind === "ready" && last.profile === detailed).toBe(true);
    });
    // First the coarse profile, then the detailed one from the coarse and the fine tiles.
    const ready = states.filter((s) => s.kind === "ready");
    expect(ready.map((s) => s.kind === "ready" && s.profile)).toEqual([high, detailed]);
    expect(horizonProfile.mock.calls.at(-1)?.[1]).toHaveLength(3);
    // Terrarium is a surface model, so the profile clears the near path.
    expect(horizonProfile.mock.calls.at(-1)?.[2]).toEqual({ minDistanceM: 2000 });
    expect(t.fetch).toHaveBeenCalledTimes(3);
  });
});
