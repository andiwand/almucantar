import type { HorizonProfile } from "@almucantar/core";
import { expect, test } from "vitest";
import { combine, findPeaks, profileAltitude } from "../src/horizon";

/** A skyline of 1° steps with the altitude `f(azimuth)` at 5 km. */
function skyline(f: (az: number) => number, distance = 5000): HorizonProfile {
  const altitude = Float64Array.from({ length: 360 }, (_, i) => f(i));
  return {
    azimuthStepDeg: 1,
    altitudeDeg: altitude,
    distanceM: new Float64Array(360).fill(distance),
    observerElevationM: 0,
  };
}

/** A smooth peak of `height` degrees at `center`, `width` degrees wide. */
const bump = (az: number, center: number, height: number, width: number) => {
  const d = ((((az - center) % 360) + 540) % 360) - 180;
  return height * Math.exp(-(d * d) / (2 * width * width));
};

test("the peaks are the prominent summits of the skyline, the most prominent first", () => {
  const s = skyline((az) => 1 + bump(az, 64, 6, 3) + bump(az, 200, 2, 2) + bump(az, 300, 0.3, 2));
  const peaks = findPeaks(s);
  expect(peaks.map((p) => p.azimuthDeg)).toEqual([64, 200]);
  expect(peaks[0].altitudeDeg).toBeCloseTo(7, 6);
  // Within the window of ±5°, a peak 3° wide falls to 1.5° above its base.
  expect(peaks[0].prominenceDeg).toBeCloseTo(4.5, 1);
  expect(peaks[0].distanceM).toBe(5000);
});

test("a peak near the spot, a flat skyline, and missing data give no peaks", () => {
  expect(findPeaks(skyline((az) => bump(az, 90, 5, 3), 500))).toEqual([]);
  expect(findPeaks(skyline(() => 2))).toEqual([]);
  expect(findPeaks(skyline(() => NaN))).toEqual([]);
});

test("the skyline of the bands is the highest band at each azimuth", () => {
  const near = skyline((az) => (az < 180 ? 3 : NaN), 1000);
  const far = skyline(() => 2, 40_000);
  const all = combine([near, far], 600);
  expect(all.altitudeDeg[10]).toBe(3);
  expect(all.distanceM[10]).toBe(1000);
  expect(all.altitudeDeg[200]).toBe(2);
  expect(all.distanceM[200]).toBe(40_000);
  expect(all.observerElevationM).toBe(600);
  // The interpolation goes across north.
  expect(profileAltitude(far, 359.5)).toBeCloseTo(2, 9);
  expect(
    profileAltitude(
      skyline((az) => az),
      10.5,
    ),
  ).toBeCloseTo(10.5, 9);
});
