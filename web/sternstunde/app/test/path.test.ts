import { expect, test } from "vitest";
import { bodyPath, fullHours, splitAtHorizon } from "../src/path";

const h = (azimuthDeg: number, altitudeDeg: number) => ({ azimuthDeg, altitudeDeg });
const flat = () => 0;

test("a path splits at the horizon without a gap", () => {
  // A path that rises over the horizon at azimuth 90°, and sets at 270° (over north).
  const line = [
    h(60, -6),
    h(80, -2),
    h(100, 2),
    h(180, 30),
    h(260, 2),
    h(280, -2),
    h(350, -10),
    h(10, -10),
  ];
  const { visible, hidden } = splitAtHorizon(line, flat);
  expect(visible).toHaveLength(1);
  expect(hidden).toHaveLength(2);
  // The crossings are halfway between the samples, on the horizon.
  expect(visible[0][0].azimuthDeg).toBeCloseTo(90, 9);
  expect(visible[0][0].altitudeDeg).toBeCloseTo(0, 9);
  expect(visible[0].at(-1)!.azimuthDeg).toBeCloseTo(270, 9);
  expect(hidden[0].at(-1)).toEqual(visible[0][0]);
  expect(hidden[1][0]).toEqual(visible[0].at(-1));
  expect(hidden[1].at(-1)!.azimuthDeg).toBeCloseTo(10, 9);
});

test("a crossing over north keeps the azimuth in range", () => {
  const { visible, hidden } = splitAtHorizon([h(350, -1), h(10, 1)], flat);
  expect(visible[0][0].azimuthDeg).toBeCloseTo(0, 9);
  expect(hidden[0].at(-1)!.azimuthDeg).toBeCloseTo(0, 9);
});

test("the terrain moves the crossing", () => {
  // A wall of 4° hides the first two samples.
  const { visible, hidden } = splitAtHorizon([h(100, 2), h(110, 6)], () => 4);
  expect(hidden).toHaveLength(1);
  expect(visible[0][0].azimuthDeg).toBeCloseTo(105, 9);
  expect(visible[0][0].altitudeDeg).toBeCloseTo(4, 9);
});

test("the full hours follow a time zone with an offset of 45 minutes", () => {
  // Nepal (UTC+5:45): 2026-01-01 00:00 UTC is 05:45 local, so the first full hour is 00:15 UTC.
  const start = Date.UTC(2026, 0, 1) / 1000;
  const hours = fullHours(start, start + 24 * 3600, "Asia/Kathmandu");
  expect(hours).toHaveLength(24);
  expect(hours[0]).toEqual({ time: start + 900, label: "06" });
  expect(hours[23].label).toBe("05");
});

test("the full hours exclude the start and the end", () => {
  const start = Date.UTC(2026, 0, 1) / 1000;
  const hours = fullHours(start, start + 24 * 3600, "UTC");
  expect(hours).toHaveLength(23);
  expect(hours[0].label).toBe("01");
});

test("a body path has 145 samples and marks the hidden hours", () => {
  const start = Date.UTC(2026, 0, 1) / 1000;
  let calls = 0;
  // A body that turns once in 24 hours: up to 7° over the horizon in the east, below it in the west.
  const directionAt = (t: number) => {
    calls++;
    const azimuthDeg = (((((t - start) / 86400) * 360) % 360) + 360) % 360;
    return h(azimuthDeg, 10 * Math.sin((azimuthDeg * Math.PI) / 180) - 3);
  };
  const path = bodyPath(start + 12 * 3600, directionAt, flat, "UTC");
  expect(calls).toBe(145 + path.hours.length);
  expect(path.visible).toHaveLength(1);
  expect(path.hours.length).toBe(23);
  for (const hour of path.hours) {
    expect(hour.hidden).toBe(hour.direction.altitudeDeg < 0);
  }
  expect(path.hours.some((hour) => hour.hidden)).toBe(true);
  expect(path.hours.some((hour) => !hour.hidden)).toBe(true);
});
