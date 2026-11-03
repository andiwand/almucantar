import { expect, test } from "vitest";
import { bandBrightness, galacticToJ2000, milkyWayDust, riftDarkness } from "../src/milkyway";

const near = (a: number, b: number, tolerance: number) =>
  expect(Math.abs(a - b)).toBeLessThan(tolerance);

test("galactic directions convert to J2000", () => {
  // The galactic center and the north galactic pole (IAU 1958, J2000).
  const [ra, dec] = galacticToJ2000(0, 0);
  near(ra, 266.405, 0.01);
  near(dec, -28.936, 0.01);
  const [, poleDec] = galacticToJ2000(123, 90);
  near(poleDec, 27.128, 0.01);
  // Deneb is at about l = 84.3°, b = 2.0°.
  const [denebRa, denebDec] = galacticToJ2000(84.28, 1.99);
  near(denebRa, 310.358, 0.05);
  near(denebDec, 45.28, 0.05);
});

test("the band is brightest at the center, and the rift is dark", () => {
  expect(bandBrightness(0)).toBeGreaterThan(bandBrightness(120));
  expect(bandBrightness(75)).toBeGreaterThan(bandBrightness(120));
  expect(riftDarkness(30, 2)).toBeGreaterThan(0.4);
  expect(riftDarkness(150, 2)).toBe(0);
});

test("the dust has the requested points, near the galactic plane", () => {
  const dust = milkyWayDust(300, 200, 3);
  expect(dust.catalog.raDeg.length).toBe(500);
  expect(dust.cloud.filter((c) => c === 1).length).toBe(300);
  for (const w of dust.weight) {
    expect(w).toBeGreaterThan(0);
    expect(w).toBeLessThanOrEqual(1);
  }
  // The same seed gives the same picture.
  expect(milkyWayDust(300, 200, 3).catalog.raDeg[17]).toBe(dust.catalog.raDeg[17]);
});
