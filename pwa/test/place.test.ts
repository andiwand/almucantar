import { readFileSync } from "node:fs";
import { describe, expect, test } from "vitest";
import {
  decodeBaseMap,
  fromWorld,
  nearestPlace,
  screenToPlace,
  searchPlaces,
  timeZoneAt,
  toWorld,
} from "../src/basemap";
import { fromWallTime, offsetMs, startOfDay, wallTime } from "../src/zone";

const map = decodeBaseMap(
  JSON.parse(readFileSync(new URL("../../assets/basemap/basemap.json", import.meta.url), "utf8")),
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
});
