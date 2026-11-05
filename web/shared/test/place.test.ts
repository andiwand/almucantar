import { readFileSync } from "node:fs";
import { afterEach, describe, expect, test, vi } from "vitest";
import {
  decodeBaseMap,
  drawBaseMap,
  fromWorld,
  nearestPlace,
  screenToPlace,
  searchPlaces,
  timeZoneAt,
  toWorld,
} from "../src/basemap";
import { DEFAULT_PLACE, loadPlace } from "../src/place";
import { fromWallTime, offsetMs, startOfDay, wallTime } from "../src/zone";

const map = decodeBaseMap(
  JSON.parse(
    readFileSync(new URL("../../../assets/basemap/basemap.json", import.meta.url), "utf8"),
  ),
);

describe("zone", () => {
  test("the offset follows daylight saving time", () => {
    // 2026-01-15 and 2026-07-15, 12:00 UTC.
    expect(offsetMs("Europe/Vienna", Date.UTC(2026, 0, 15, 12)) / 3_600_000).toBe(1);
    expect(offsetMs("Europe/Vienna", Date.UTC(2026, 6, 15, 12)) / 3_600_000).toBe(2);
    expect(offsetMs("Asia/Kolkata", Date.UTC(2026, 6, 15, 12)) / 60_000).toBe(330);
  });

  test("a wall clock time maps to UTC and back", () => {
    const t = fromWallTime("America/New_York", 2026, 3, 8, 12, 30); // after the change to EDT
    expect(t).toBe(Date.UTC(2026, 2, 8, 16, 30));
    expect(wallTime("America/New_York", t)).toEqual({
      year: 2026,
      month: 3,
      day: 8,
      hour: 12,
      minute: 30,
    });
  });

  test("the day starts at midnight of the zone, also across the date line", () => {
    const now = Date.UTC(2026, 10, 2, 20); // 2026-11-02 20:00 UTC
    // In Auckland (UTC+13) it is already 2026-11-03 09:00.
    expect(startOfDay("Pacific/Auckland", now, 0) * 1000).toBe(Date.UTC(2026, 10, 2, 11));
    // A day with the change to winter time in Vienna (2026-10-25) has 25 hours.
    const before = startOfDay("Europe/Vienna", Date.UTC(2026, 9, 25, 12), 0);
    const after = startOfDay("Europe/Vienna", Date.UTC(2026, 9, 25, 12), 1);
    expect((after - before) / 3600).toBe(25);
  });

  test("a day without midnight starts after the gap, west and east of UTC", () => {
    // Chile changes to summer time on 2026-09-06 at 00:00, so that day starts at 01:00 (UTC-3).
    const santiago = startOfDay("America/Santiago", Date.UTC(2026, 8, 6, 15), 0) * 1000;
    expect(santiago).toBe(Date.UTC(2026, 8, 6, 4));
    expect(wallTime("America/Santiago", santiago)).toMatchObject({ day: 6, hour: 1 });
    // Vienna changes on 2026-03-29 at 02:00, so 02:30 does not exist and gives 03:30.
    expect(fromWallTime("Europe/Vienna", 2026, 3, 29, 2, 30)).toBe(Date.UTC(2026, 2, 29, 1, 30));
  });
});

describe("base map", () => {
  test("has the layers and the places", () => {
    expect(map.layers.coastline.length).toBeGreaterThan(1000);
    expect(map.places.length).toBeGreaterThan(7000);
    const vienna = map.places.find((p) => p.name === "Vienna")!;
    expect(vienna.lat).toBeCloseTo(48.2, 1);
    expect(vienna.timeZone).toBe("Europe/Vienna");
  });

  test("the search finds names without accents, the largest first", () => {
    expect(searchPlaces(map.places, "zurich")[0].name).toBe("Zürich");
    const vi = searchPlaces(map.places, "Vien");
    expect(vi[0].name).toBe("Vienna");
    expect(searchPlaces(map.places, "  ")).toEqual([]);
  });

  test("a dropped place gets the time zone of the nearest place", () => {
    const tyrol = { latitudeDeg: 47.1, longitudeDeg: 11.6 };
    expect(nearestPlace(map.places, tyrol)?.country).toBe("Austria");
    expect(timeZoneAt(map.places, tyrol, "UTC")).toBe("Europe/Vienna");
    const fiji = { latitudeDeg: -17.8, longitudeDeg: 178.0 };
    expect(timeZoneAt(map.places, fiji, "UTC")).toBe("Pacific/Fiji");
  });

  test("the Web Mercator conversions are inverse", () => {
    const [x, y] = toWorld(11.39, 47.27, 6);
    const [lon, lat] = fromWorld(x, y, 6);
    expect(lon).toBeCloseTo(11.39, 9);
    expect(lat).toBeCloseTo(47.27, 9);
    const center = screenToPlace({ lon: 11.39, lat: 47.27, zoom: 6 }, 400, 300, 200, 150);
    expect(center.longitudeDeg).toBeCloseTo(11.39, 9);
    expect(center.latitudeDeg).toBeCloseTo(47.27, 9);
  });

  test("the map continues across the date line", () => {
    // A coastline just east of the date line, and a view just west of it.
    const line = decodeBaseMap({
      version: 1,
      scale: 1,
      attribution: "",
      layers: { coastline: [[-179.5, -17, 0, 1]] },
      places: [["Island", -179.5, -16.5, 0, "", ""]],
    });
    const points: [number, number][] = [];
    const record = (x: number, y: number) => void points.push([x, y]);
    const ctx = new Proxy(
      { moveTo: record, lineTo: record, fillRect: record, arc: record },
      { get: (target, key) => target[key as keyof typeof target] ?? (() => {}) },
    ) as unknown as CanvasRenderingContext2D;
    const pin = { latitudeDeg: -16.5, longitudeDeg: -179.5 };
    drawBaseMap(ctx, line, { lon: 179.9, lat: -16.5, zoom: 6 }, 400, 300, pin);
    const inside = points.filter(([x, y]) => x > 0 && x < 400 && y > 0 && y < 300);
    // The two points of the line, the place, and the pin; x is right of the middle.
    expect(inside.length).toBeGreaterThanOrEqual(4);
    expect(inside.every(([x]) => x > 200)).toBe(true);
  });
});

describe("stored place", () => {
  afterEach(() => vi.unstubAllGlobals());
  const storeWith = (value: string | null) =>
    vi.stubGlobal("localStorage", { getItem: () => value });

  test("a valid entry loads", () => {
    const place = {
      observer: { latitudeDeg: 47.27, longitudeDeg: 11.39 },
      name: "Innsbruck",
      timeZone: "Europe/Vienna",
      manual: true,
    };
    storeWith(JSON.stringify(place));
    expect(loadPlace("key")).toEqual(place);
  });

  test("a missing, damaged, or incomplete entry gives the default place", () => {
    for (const value of [
      null,
      "{",
      JSON.stringify({ observer: { latitudeDeg: 47 }, name: "x", timeZone: "UTC" }),
      JSON.stringify({ observer: { latitudeDeg: 47, longitudeDeg: 11 }, name: "x" }),
      JSON.stringify({
        observer: { latitudeDeg: 47, longitudeDeg: 11 },
        name: "x",
        timeZone: "Not/AZone",
      }),
    ]) {
      storeWith(value);
      expect(loadPlace("key")).toBe(DEFAULT_PLACE);
    }
  });
});
