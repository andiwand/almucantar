import { expect, test } from "vitest";
import { litOutline, towardSun } from "../src/moon";

/** The area of a polygon (shoelace formula). */
function area(points: [number, number][]): number {
  let sum = 0;
  points.forEach(([x0, y0], i) => {
    const [x1, y1] = points[(i + 1) % points.length];
    sum += x0 * y1 - x1 * y0;
  });
  return Math.abs(sum / 2);
}

test("the lit area is the illuminated fraction", () => {
  for (const fraction of [0, 0.1, 0.25, 0.5, 0.75, 0.9, 1]) {
    // The polygon with 24 steps is a little smaller than the disc: 0.3 % at most.
    expect(Math.abs(area(litOutline(fraction)) - Math.PI * fraction)).toBeLessThan(0.01);
  }
});

test("a crescent is on the side of the sun", () => {
  expect(litOutline(0.2).every(([x]) => x >= -1e-12)).toBe(true);
});

test("the direction toward the sun is on the great circle", () => {
  const step = towardSun({ azimuthDeg: 180, altitudeDeg: 0 }, { azimuthDeg: 90, altitudeDeg: 0 });
  expect(step.azimuthDeg).toBeCloseTo(179, 9);
  expect(step.altitudeDeg).toBeCloseTo(0, 9);
  const down = towardSun(
    { azimuthDeg: 200, altitudeDeg: 30 },
    { azimuthDeg: 200, altitudeDeg: -20 },
  );
  expect(down.azimuthDeg).toBeCloseTo(200, 9);
  expect(down.altitudeDeg).toBeCloseTo(29, 9);
});

test("a sun opposite the moon gives up", () => {
  const step = towardSun({ azimuthDeg: 0, altitudeDeg: 10 }, { azimuthDeg: 180, altitudeDeg: -10 });
  expect(step.azimuthDeg).toBeCloseTo(0, 9);
  expect(step.altitudeDeg).toBeCloseTo(11, 9);
});
