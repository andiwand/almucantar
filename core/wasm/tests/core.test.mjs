// Tests of the npm package. CTest runs them with SUNRADAR_WASM_DIST set to the build output.
import { test } from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { pathToFileURL } from "node:url";

const dist = process.env.SUNRADAR_WASM_DIST;
assert.ok(dist, "SUNRADAR_WASM_DIST is not set");
const { load, SolarModel, DayKind } = await import(pathToFileURL(path.join(dist, "index.js")).href);
const sunradar = await load();

const vienna = { latitudeDeg: 48.2082, longitudeDeg: 16.3738 };
const tromso = { latitudeDeg: 69.6492, longitudeDeg: 18.9553 };
const dayStart = 1781992800; // 2026-06-21T00:00 CEST
const day = 86400;
// A device upright in portrait, with the back camera to the north.
const northUpright = { w: Math.cos(Math.PI / 4), x: Math.sin(Math.PI / 4), y: 0, z: 0 };

const near = (actual, expected, tolerance) =>
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} is not within ${tolerance} of ${expected}`);

test("version", () => {
  assert.equal(sunradar.version().split(".").length >= 3, true);
});

test("sun position and refraction", () => {
  const sun = sunradar.sunPosition(1782039420, vienna);
  near(sun.azimuthDeg, 180, 1);
  near(sun.altitudeDeg, 65.25, 0.25);
  const airless = sunradar.sunPosition(1782039420, vienna, { refraction: false });
  assert.ok(sun.altitudeDeg > airless.altitudeDeg);
  const ae = sunradar.sunPosition(1782039420, vienna, { model: SolarModel.astronomyEngine });
  near(ae.altitudeDeg, sun.altitudeDeg, 0.03);
});

test("events, polar day, and path", () => {
  const events = sunradar.sunEvents(vienna, dayStart, dayStart + day);
  assert.equal(events.kind, DayKind.normal);
  assert.deepEqual(events.crossings.map((c) => c.rising), [true, false]);
  assert.equal(sunradar.sunEvents(tromso, dayStart, dayStart + day).kind, DayKind.polarDay);
  const path = sunradar.sunPath(vienna, dayStart, dayStart + day, { stepS: 3600 });
  assert.equal(path.length, 25);
  assert.equal(path[1].unixUtc, dayStart + 3600);
});

test("projection and horizon", () => {
  const view = { deviceToEnu: northUpright };
  const center = sunradar.project(view, { azimuthDeg: 0, altitudeDeg: 0 });
  assert.equal(center.valid, true);
  near(center.point.x, 500, 1e-9);
  near(center.point.y, 500, 1e-9);
  const back = sunradar.unproject(view, { x: 700, y: 400 });
  const again = sunradar.project(view, back);
  near(again.point.x, 700, 1e-6);
  const horizon = sunradar.horizonLine(view);
  assert.equal(horizon.length, 1);
  for (const p of horizon[0]) near(p.y, 500, 1e-6);
});

test("search, moon, and declination", () => {
  const t = dayStart + 10 * 3600;
  const match = sunradar.findTime(vienna, sunradar.sunPosition(t, vienna), dayStart, dayStart + day);
  near(match.unixUtc, t, 1);
  const crossings = sunradar.findAzimuthTimes(vienna, sunradar.sunPosition(t, vienna).azimuthDeg, dayStart, dayStart + day);
  assert.equal(crossings.length, 1);
  assert.ok(sunradar.moonIlluminatedFraction(1767441600) > 0.99);
  // The WMM2025 test value at height 0 km: 2027.5, 13 S, 59 W: -17.49 degrees.
  near(sunradar.magneticDeclinationDeg({ latitudeDeg: -13, longitudeDeg: -59 }, 1798761600 + 0.5 * 365 * day), -17.49, 0.006);
});

test("heading filter", () => {
  const e = sunradar.headingUpdate({ offsetDeg: 10, varianceDeg2: 4 }, { offsetDeg: 20, varianceDeg2: 4 });
  near(e.offsetDeg, 15, 1e-12);
  const m = sunradar.headingFromDirection({ deviceToEnu: northUpright }, { x: 500, y: 500 }, 70, 0.5);
  near(m.offsetDeg, 70, 1e-9);
});

test("values are plain objects that survive structuredClone", () => {
  const path = sunradar.sunPath(vienna, dayStart, dayStart + day, { stepS: 3600 });
  assert.deepEqual(structuredClone(path), path);
  const lines = sunradar.horizonLine({ deviceToEnu: northUpright });
  assert.deepEqual(structuredClone(lines), lines);
});

test("invalid arguments throw RangeError", () => {
  assert.throws(() => sunradar.sunPosition(0, { latitudeDeg: 91, longitudeDeg: 0 }), {
    name: "RangeError",
    message: /latitude_deg/,
  });
  assert.throws(() => sunradar.sunEvents(vienna, 10, 10), RangeError);
  assert.throws(() => sunradar.sunPosition(0, vienna, { model: 7 }), RangeError);
});
