import { expect, test } from "vitest";
import { fullHours, separationDeg } from "../src/search";

const hour = 3600;
// 2026-11-24 00:00 UTC.
const day = Date.UTC(2026, 10, 24) / 1000;

test("the full hours of a zone with a whole offset are the full hours of UTC", () => {
  expect(fullHours(day + 600, day + 3 * hour, "Europe/Vienna")).toEqual([
    day + hour,
    day + 2 * hour,
    day + 3 * hour,
  ]);
});

test("the full hours of a zone with an offset of 30 or 45 minutes", () => {
  // India is UTC+5:30, so 06:00 there is 00:30 UTC.
  expect(fullHours(day, day + 2 * hour, "Asia/Kolkata")).toEqual([day + 1800, day + hour + 1800]);
  // Nepal is UTC+5:45.
  expect(fullHours(day, day + hour, "Asia/Kathmandu")).toEqual([day + 900]);
});

test("the full hours across a change of the daylight saving time", () => {
  // Vienna goes from UTC+2 to UTC+1 at 01:00 UTC on 25 October 2026.
  const change = Date.UTC(2026, 9, 25, 1) / 1000;
  expect(fullHours(change - hour, change + hour, "Europe/Vienna")).toEqual([
    change - hour,
    change,
    change + hour,
  ]);
  expect(fullHours(day, day, "Europe/Vienna")).toEqual([day]);
  expect(fullHours(day + 1, day + 1800, "Europe/Vienna")).toEqual([]);
});

test("the separation of two directions", () => {
  expect(
    separationDeg({ azimuthDeg: 10, altitudeDeg: 0 }, { azimuthDeg: 40, altitudeDeg: 0 }),
  ).toBeCloseTo(30, 9);
  expect(
    separationDeg({ azimuthDeg: 359, altitudeDeg: 0 }, { azimuthDeg: 1, altitudeDeg: 0 }),
  ).toBeCloseTo(2, 9);
  expect(
    separationDeg({ azimuthDeg: 0, altitudeDeg: 89 }, { azimuthDeg: 180, altitudeDeg: 89 }),
  ).toBeCloseTo(2, 9);
});
