import { describe, expect, test, vi } from "vitest";
import type { ElevationGrid, HorizonProfile, MapTile } from "@sunradar/core";
import { MAX_AGE_MS, Terrain, TileCache, distanceM, tileUrl } from "../src/terrain";
import type { BlobStore, TerrainDeps, TerrainState, TileEntry } from "../src/terrain";

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

function setup(responses: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  const { blobs, store } = fakeStore();
  let index: Record<string, TileEntry> = {};
  let now = 1_000 * DAY;
  const fetch = vi.fn(async (url: string, init?: RequestInit) => responses(url, init));
  const cache = new TileCache({
    store,
    loadIndex: () => index,
    saveIndex: (i) => (index = JSON.parse(JSON.stringify(i))),
    fetch,
    now: () => now,
  });
  return {
    cache,
    blobs,
    fetch,
    index: () => index,
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
    { zoom: 10, x: 1, y: 2, distanceM: 0 },
    { zoom: 10, x: 2, y: 2, distanceM: 1000 },
  ];
  const profile = { azimuthStepDeg: 90 } as HorizonProfile;

  function terrain(decode: (blob: Blob) => Promise<never> | Promise<object>) {
    const t = setup(() => png(10));
    const states: TerrainState[] = [];
    const horizonProfile = vi.fn((_o: unknown, grids: ElevationGrid[]) => {
      expect(grids).toHaveLength(2);
      return profile;
    });
    const sunradar = {
      mapTiles: vi.fn(() => tiles),
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
    expect(states.at(-1)).toEqual({ kind: "ready", profile, missing: 0 });
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
    subject.update({ latitudeDeg: 47.001, longitudeDeg: 11 }); // about 110 m
    expect(sunradar.mapTiles).toHaveBeenCalledTimes(1);
    subject.update({ latitudeDeg: 47.01, longitudeDeg: 11 }); // about 1.1 km
    expect(sunradar.mapTiles).toHaveBeenCalledTimes(2);
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
