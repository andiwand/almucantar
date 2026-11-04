import { expect, test } from "vitest";
import { heightAt, landColor, shadeTile, terrariumHeight, tileLatitude } from "../src/relief";

/** Terrarium pixels of a tile where the height is `f(x, y)` in meters. */
function tile(size: number, f: (x: number, y: number) => number): Uint8ClampedArray {
  const rgba = new Uint8ClampedArray(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const v = f(x, y) + 32768;
      const i = 4 * (y * size + x);
      rgba[i] = Math.floor(v / 256);
      rgba[i + 1] = Math.floor(v % 256);
      rgba[i + 2] = Math.round((v - Math.floor(v)) * 256);
      rgba[i + 3] = 255;
    }
  }
  return rgba;
}

test("the Terrarium height decodes red, green, and blue", () => {
  expect(
    terrariumHeight(
      tile(2, () => 1234.5),
      0,
    ),
  ).toBeCloseTo(1234.5, 3);
  expect(
    terrariumHeight(
      tile(2, () => -20),
      3,
    ),
  ).toBeCloseTo(-20, 3);
});

test("the height between pixels is linear", () => {
  const rgba = tile(4, (x) => 100 * x);
  // The center of pixel 1 is at 1.5, of pixel 2 at 2.5.
  expect(heightAt(rgba, 4, 4, 1.5, 2)).toBeCloseTo(100, 6);
  expect(heightAt(rgba, 4, 4, 2, 2)).toBeCloseTo(150, 6);
  // Outside the tile, the edge.
  expect(heightAt(rgba, 4, 4, -5, 2)).toBeCloseTo(0, 6);
});

test("the tile latitude is the Web Mercator latitude", () => {
  expect(tileLatitude(0, 0)).toBeCloseTo(85.0511, 3);
  expect(tileLatitude(0.5, 0)).toBeCloseTo(0, 9);
});

test("a slope that faces the light in the north-west is lighter", () => {
  const size = 8;
  // Both slopes have the height 1500 m at the center, so the color of the height is the same.
  // A slope that rises to the south-east faces the north-west.
  const facingLight = shadeTile(
    tile(size, (x, y) => 1500 + 50 * (x + y - 8)),
    size,
    size,
    12,
    1400,
  );
  const facingAway = shadeTile(
    tile(size, (x, y) => 1500 - 50 * (x + y - 8)),
    size,
    size,
    12,
    1400,
  );
  const i = 4 * (4 * size + 4);
  expect(facingLight[i]).toBeGreaterThan(facingAway[i]);
  expect(facingLight[i + 3]).toBe(255);
  // Snow is lighter than the rock below it.
  expect(landColor(3300)[0]).toBeGreaterThan(landColor(2400)[0]);
});
