import { expect, test } from "vitest";
import { Panorama, skyColors } from "../src/panorama";

test("the panorama puts the center azimuth in the middle and wraps across north", () => {
  const p = new Panorama(1000, 600, 10, { spanDeg: 200, horizonFraction: 0.8, topAltitudeDeg: 72 });
  expect(p.x(10)).toBe(500);
  // 350° is 20° left of 10°, not 340° right of it.
  expect(p.x(350)).toBeCloseTo(500 - 20 * p.pxPerDegX, 9);
  expect(p.x(370)).toBeCloseTo(p.x(10), 9);
  expect(p.y(0)).toBe(480);
  expect(p.y(10)).toBeLessThan(p.y(0));
});

test("the sky colors stay at the ends of the table", () => {
  expect(skyColors(-90)).toEqual(skyColors(-18));
  expect(skyColors(90)).toEqual(skyColors(30));
  for (const c of skyColors(0).flat()) expect(Number.isFinite(c)).toBe(true);
});
