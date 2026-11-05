import { readFileSync } from "node:fs";
import { expect, test } from "vitest";
import { darkness, decodeStars, limitingMagnitude, starColor, starRadius } from "../src/stars";
import type { StarsJson } from "../src/stars";

const json = JSON.parse(
  readFileSync(new URL("../../../assets/stars/stars.json", import.meta.url), "utf8"),
) as StarsJson;

test("the star data decodes into columns of the same length", () => {
  const stars = decodeStars(json);
  const n = stars.catalog.raDeg.length;
  expect(n).toBeGreaterThan(5000);
  for (const column of [
    stars.catalog.decDeg,
    stars.catalog.pmRaArcsecPerYear,
    stars.catalog.pmDecArcsecPerYear,
    stars.mag,
    stars.bv,
    stars.hr,
  ]) {
    expect(column.length).toBe(n);
  }
  // Sorted by magnitude: Sirius first.
  expect(stars.names.get(0)).toBe("Sirius");
  // The HR number is not the index: Sirius is HR 2491.
  expect(stars.hr[0]).toBe(2491);
  expect(stars.mag[0]).toBeLessThan(stars.mag[1]);
  // Every line points to a star.
  expect(stars.constellations.length).toBe(89);
  for (const c of stars.constellations) {
    for (const line of c.lines) for (const i of line) expect(i).toBeLessThan(n);
  }
  const orion = stars.constellations.find((c) => c.id === "Ori")!;
  expect(orion.name).toBe("Orion");
  const names = new Set(orion.lines.flat().map((i) => stars.names.get(i)));
  expect(names.has("Betelgeuse")).toBe(true);
  expect(names.has("Rigel")).toBe(true);
});

test("an unknown version is rejected", () => {
  expect(() => decodeStars({ ...json, version: 2 })).toThrow("version");
});

test("the sky shows fainter stars as it gets darker", () => {
  expect(limitingMagnitude(10)).toBeLessThan(limitingMagnitude(-3));
  expect(limitingMagnitude(-3)).toBeLessThan(limitingMagnitude(-9));
  expect(limitingMagnitude(-9)).toBeLessThan(limitingMagnitude(-15));
  expect(limitingMagnitude(-18)).toBe(6);
  expect(limitingMagnitude(-40)).toBe(6);
  // Inside the twilight, the limit has no jumps. At sunset, it steps from -2 (daylight) to -1.
  for (const limit of [-6, -12, -18]) {
    expect(
      Math.abs(limitingMagnitude(limit - 1e-9) - limitingMagnitude(limit + 1e-9)),
    ).toBeLessThan(1e-6);
  }
  expect(limitingMagnitude(-1e-9) - limitingMagnitude(0)).toBeCloseTo(1, 6);
  expect(darkness(-20)).toBe("Night");
  expect(darkness(-7)).toBe("Nautical twilight");
});

test("a brighter star is larger, and the colors go from blue to orange", () => {
  expect(starRadius(-1.46)).toBeGreaterThan(starRadius(2));
  expect(starRadius(6)).toBeGreaterThan(0);
  const blue = starColor(-0.3).split(", ").map(Number);
  const orange = starColor(1.85).split(", ").map(Number);
  expect(blue[2]).toBeGreaterThan(blue[0]);
  expect(orange[0]).toBeGreaterThan(orange[2]);
  expect(starColor(-5)).toBe(starColor(-0.4));
});
