import type { LunarEclipse } from "@almucantar/core";
import { expect, test } from "vitest";
import { lunarUmbra } from "../src/search";

const hour = 3600;
const total: LunarEclipse = {
  kind: 3,
  peakUtc: 10 * hour,
  obscuration: 1,
  penumbralBeginUtc: 7 * hour,
  partialBeginUtc: 8 * hour,
  totalBeginUtc: 9 * hour,
  totalEndUtc: 11 * hour,
  partialEndUtc: 12 * hour,
  penumbralEndUtc: 13 * hour,
};

test("the moon goes into the umbra and out again", () => {
  expect(lunarUmbra(total, 7.5 * hour)).toBe(0);
  expect(lunarUmbra(total, 8.5 * hour)).toBeCloseTo(0.5, 9);
  expect(lunarUmbra(total, 10 * hour)).toBe(1);
  expect(lunarUmbra(total, 11.5 * hour)).toBeCloseTo(0.5, 9);
  expect(lunarUmbra(total, 12.5 * hour)).toBe(0);
});

test("a partial eclipse reaches its obscuration at the peak, a penumbral one only dims", () => {
  const partial = { ...total, kind: 1, obscuration: 0.6, totalBeginUtc: NaN, totalEndUtc: NaN };
  expect(lunarUmbra(partial, 10 * hour)).toBeCloseTo(0.6, 9);
  expect(lunarUmbra(partial, 9 * hour)).toBeCloseTo(0.3, 9);
  const penumbral = {
    ...partial,
    kind: 0,
    obscuration: 0,
    partialBeginUtc: NaN,
    partialEndUtc: NaN,
  };
  expect(lunarUmbra(penumbral, 10 * hour)).toBeCloseTo(0.12, 9);
  expect(lunarUmbra(penumbral, 7 * hour)).toBe(0);
});
