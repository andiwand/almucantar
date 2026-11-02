// Tests of the npm package. CTest runs them with SUNRADAR_WASM_DIST set to the build output.
import { test } from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { pathToFileURL } from "node:url";

const dist = process.env.SUNRADAR_WASM_DIST;
assert.ok(dist, "SUNRADAR_WASM_DIST is not set");
const { load, SolarModel, DayKind, Body } = await import(pathToFileURL(path.join(dist, "index.js")).href);
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

test("heading filter with the magnetometer", () => {
  let f = sunradar.headingFilterStart({ x: 1, y: 2, z: 3 }, 0.5);
  assert.equal(f.covariance.length, sunradar.defaults.headingFilterSize ** 2);
  assert.equal(f.covariance[0], 180 * 180);
  assert.deepEqual(structuredClone(f), f);
  // A field to the north of the frame (device -z) and down (device -y), plus the bias.
  const t = 1767225600;
  f = sunradar.headingFilterPredict(f, 1);
  f = sunradar.headingFilterUpdateMagnetometer(f, northUpright, { x: 1, y: -38, z: -17 }, vienna, t);
  near(f.offsetDeg, sunradar.magneticDeclinationDeg(vienna, t), 5);
  f = sunradar.headingFilterUpdate(f, { offsetDeg: 10, varianceDeg2: 0.01 });
  near(f.offsetDeg, 10, 0.1);
  assert.throws(() => sunradar.headingFilterStart({ x: 0, y: 0, z: 0 }, 0), { name: "RangeError", message: /bias_sigma_ut/ });
  assert.throws(() => sunradar.headingFilterPredict({ ...f, covariance: [1] }, 1), RangeError);
  near(sunradar.defaults.headingFilterOptions.disturbanceTimeS, 120, 0);
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

test("the defaults are frozen and come from C++", () => {
  const d = sunradar.defaults;
  assert.ok(Object.isFrozen(d) && Object.isFrozen(d.sunOptions.atmosphere));
  assert.throws(() => {
    "use strict";
    d.sunOptions.refraction = false;
  }, TypeError);
  near(d.headingDriftDeg2PerS, 1 / 60, 1e-15);
  near(sunradar.headingPredict({ offsetDeg: 0, varianceDeg2: 1 }, 60).varianceDeg2, 2, 1e-12);
});

test("dates, moon, camera, lines, and magnetometer", () => {
  const direction = sunradar.sunPosition(dayStart - 50 * day + 10 * 3600, vienna);
  const dates = sunradar.findDates(vienna, direction, dayStart - 100 * day, dayStart + 100 * day);
  assert.equal(dates.length, 2);
  const moon = sunradar.moonPosition(dayStart, vienna);
  assert.ok(moon.azimuthDeg >= 0 && moon.azimuthDeg < 360);
  const camera = sunradar.cameraFromFov(1000, 2000, 90);
  near(camera.fx, 500, 1e-9);
  const lines = sunradar.projectLine({ deviceToEnu: northUpright, camera }, [
    { azimuthDeg: -10, altitudeDeg: 0 },
    { azimuthDeg: 10, altitudeDeg: 0 },
  ]);
  assert.equal(lines.length, 1);
  near(lines[0][0].y, 1000, 1e-6);
  // A field to magnetic north, in a frame with y to true north: the offset is the declination.
  const m = sunradar.headingFromMagnetometer({ w: 1, x: 0, y: 0, z: 0 }, { x: 0, y: 20, z: -44 }, vienna, dayStart);
  near(m.offsetDeg, (sunradar.magneticDeclinationDeg(vienna, dayStart) + 360) % 360, 1e-6);
});

test("terrain: GeoTIFF, tiles, profile, and events", async () => {
  const { readFile } = await import("node:fs/promises");
  const file = new URL("../../test/data/geotiff/float_tiles_deflate_p3.tif", import.meta.url);
  const grid = sunradar.readElevationGeotiff(new Uint8Array(await readFile(file)));
  assert.equal(grid.rows, 7);
  assert.equal(grid.columns, 5);
  assert.ok(grid.heightsM instanceof Float32Array);
  near(grid.heightsM[0], 1000.25, 1e-6);
  near(grid.northLatitudeDeg, 47.75, 1e-12);
  assert.equal(sunradar.readElevationGeotiff(new Uint8Array(await readFile(file)), 1).rows, 4);
  assert.throws(() => sunradar.readElevationGeotiff(new Uint8Array([1, 2, 3])), RangeError);

  const tiles = sunradar.elevationTiles(vienna, 20000);
  assert.deepEqual([tiles[0].latitudeDeg, tiles[0].longitudeDeg, tiles[0].distanceM], [48, 16, 0]);

  // A flat grid of 150 m around Vienna: the horizon is a little below 0° in all directions.
  const rows = 61, columns = 91;
  const flat = {
    northLatitudeDeg: vienna.latitudeDeg + 0.25, westLongitudeDeg: vienna.longitudeDeg - 0.375,
    latitudeStepDeg: 1 / 120, longitudeStepDeg: 1 / 120, rows, columns,
    heightsM: new Float32Array(rows * columns).fill(150),
  };
  const profile = sunradar.horizonProfile(vienna, [flat], { azimuthStepDeg: 10 });
  assert.equal(profile.altitudeDeg.length, 36);
  near(profile.observerElevationM, 150, 1e-6);
  for (const a of profile.altitudeDeg) assert.ok(a < 0 && a > -0.1);
  near(sunradar.terrainAltitudeDeg(profile, 5), (profile.altitudeDeg[0] + profile.altitudeDeg[1]) / 2, 1e-12);
  assert.throws(() => sunradar.horizonProfile(vienna, []), RangeError);

  const events = sunradar.terrainEvents(vienna, profile, dayStart, dayStart + day);
  assert.equal(events.kind, DayKind.normal);
  assert.deepEqual(events.crossings.map((c) => c.rising), [true, false]);
  const wall = { ...profile, altitudeDeg: new Array(36).fill(80) };
  assert.equal(sunradar.terrainEvents(vienna, wall, dayStart, dayStart + day).kind, DayKind.polarNight);
  // The results go through postMessage.
  structuredClone(profile);
  structuredClone(grid);
});

test("terrain: Web Mercator tiles and Terrarium pixels", () => {
  const tiles = sunradar.mapTiles(vienna, 100000, 10);
  assert.deepEqual([tiles[0].zoom, tiles[0].x, tiles[0].distanceM], [10, 558, 0]);
  // A tile of 2 x 2 pixels, all at 500 m: red 129, green 244 (129 * 256 + 244 - 32768 = 500).
  const rgba = new Uint8ClampedArray([129, 244, 0, 255, 129, 244, 0, 255, 129, 244, 0, 255, 129, 244, 0, 255]);
  const grid = sunradar.terrariumGrid(rgba, 2, 2, tiles[0]);
  assert.deepEqual(Array.from(grid.heightsM), [500, 500, 500, 500]);
  assert.throws(() => sunradar.mapTiles(vienna, 1000, 13), RangeError);
});

test("terrain: the moon behind the terrain", () => {
  const flat = { azimuthStepDeg: 10, altitudeDeg: new Array(36).fill(0), distanceM: new Array(36).fill(1000), observerElevationM: 0 };
  const events = sunradar.terrainEvents(vienna, flat, dayStart, dayStart + 3 * day, { body: Body.moon });
  assert.ok(events.crossings.length >= 5 && events.crossings.length <= 7);
  const rise = events.crossings.find((c) => c.rising);
  assert.ok(sunradar.moonPosition(rise.unixUtc + 2, vienna).altitudeDeg + 0.2667 > 0);
  assert.throws(() => sunradar.terrainEvents(vienna, flat, dayStart, dayStart + day, { body: 9 }), RangeError);
});
