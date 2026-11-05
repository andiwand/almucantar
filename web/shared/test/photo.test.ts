import { expect, test } from "vitest";
import { discCoverage, lensSpanDeg } from "../src/photo";

test("the coverage of the sun is the overlap of the two discs", () => {
  // Apart, touching, and the moon on the center.
  expect(discCoverage(1, 0.27, 0.26)).toBe(0);
  expect(discCoverage(0.53, 0.27, 0.26)).toBe(0);
  expect(discCoverage(0, 0.27, 0.28)).toBe(1);
  // An annular eclipse leaves a ring: the area of the moon over the area of the sun.
  expect(discCoverage(0, 0.27, 0.25)).toBeCloseTo((0.25 * 0.25) / (0.27 * 0.27), 12);
  // Two equal discs half a radius apart: the lens formula.
  const r = 0.25;
  const d = r;
  const lens = 2 * r * r * Math.acos(d / (2 * r)) - (d / 2) * Math.sqrt(4 * r * r - d * d);
  expect(discCoverage(d, r, r)).toBeCloseTo(lens / (Math.PI * r * r), 12);
  // Just inside the touching distance, rounding must not give NaN.
  for (const d of [0.53 - 1e-15, 0.01 + 1e-15]) {
    expect(Number.isFinite(discCoverage(d, 0.27, 0.26))).toBe(true);
  }
  // More overlap covers more.
  expect(discCoverage(0.2, 0.27, 0.26)).toBeGreaterThan(discCoverage(0.3, 0.27, 0.26));
});

test("the lens spans the long side of a full-frame camera", () => {
  expect(lensSpanDeg(50)).toBeCloseTo(39.6, 1);
  expect(lensSpanDeg(600)).toBeCloseTo(3.44, 2);
});
