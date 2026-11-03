// Tests of the npm package. CTest runs them with ALMUCANTAR_WASM_DIST set to the build output.
import { test } from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { pathToFileURL } from "node:url";

const dist = process.env.ALMUCANTAR_WASM_DIST;
assert.ok(dist, "ALMUCANTAR_WASM_DIST is not set");
const { load, SolarModel, DayKind, Body, Planet, GALACTIC_CENTER } = await import(pathToFileURL(path.join(dist, "index.js")).href);
const almucantar = await load();

const vienna = { latitudeDeg: 48.2082, longitudeDeg: 16.3738 };
const tromso = { latitudeDeg: 69.6492, longitudeDeg: 18.9553 };
const dayStart = 1781992800; // 2026-06-21T00:00 CEST
const day = 86400;
// A device upright in portrait, with the back camera to the north.
const northUpright = { w: Math.cos(Math.PI / 4), x: Math.sin(Math.PI / 4), y: 0, z: 0 };

const near = (actual, expected, tolerance) =>
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} is not within ${tolerance} of ${expected}`);

test("version", () => {
  assert.equal(almucantar.version().split(".").length >= 3, true);
});

test("sun position and refraction", () => {
  const sun = almucantar.sunPosition(1782039420, vienna);
  near(sun.azimuthDeg, 180, 1);
  near(sun.altitudeDeg, 65.25, 0.25);
  const airless = almucantar.sunPosition(1782039420, vienna, { refraction: false });
  assert.ok(sun.altitudeDeg > airless.altitudeDeg);
  const ae = almucantar.sunPosition(1782039420, vienna, { model: SolarModel.astronomyEngine });
  near(ae.altitudeDeg, sun.altitudeDeg, 0.03);
});

test("events, polar day, and path", () => {
  const events = almucantar.sunEvents(vienna, dayStart, dayStart + day);
  assert.equal(events.kind, DayKind.normal);
  assert.deepEqual(events.crossings.map((c) => c.rising), [true, false]);
  assert.equal(almucantar.sunEvents(tromso, dayStart, dayStart + day).kind, DayKind.polarDay);
  const path = almucantar.sunPath(vienna, dayStart, dayStart + day, { stepS: 3600 });
  assert.equal(path.length, 25);
  assert.equal(path[1].unixUtc, dayStart + 3600);
});

test("projection and horizon", () => {
  const view = { deviceToEnu: northUpright };
  const center = almucantar.project(view, { azimuthDeg: 0, altitudeDeg: 0 });
  assert.equal(center.valid, true);
  near(center.point.x, 500, 1e-9);
  near(center.point.y, 500, 1e-9);
  const back = almucantar.unproject(view, { x: 700, y: 400 });
  const again = almucantar.project(view, back);
  near(again.point.x, 700, 1e-6);
  const horizon = almucantar.horizonLine(view);
  assert.equal(horizon.length, 1);
  for (const p of horizon[0]) near(p.y, 500, 1e-6);
});

test("search, moon, and declination", () => {
  const t = dayStart + 10 * 3600;
  const match = almucantar.findTime(vienna, almucantar.sunPosition(t, vienna), dayStart, dayStart + day);
  near(match.unixUtc, t, 1);
  const crossings = almucantar.findAzimuthTimes(vienna, almucantar.sunPosition(t, vienna).azimuthDeg, dayStart, dayStart + day);
  assert.equal(crossings.length, 1);
  assert.ok(almucantar.moonIlluminatedFraction(1767441600) > 0.99);
  // The WMM2025 test value at height 0 km: 2027.5, 13 S, 59 W: -17.49 degrees.
  near(almucantar.magneticDeclinationDeg({ latitudeDeg: -13, longitudeDeg: -59 }, 1798761600 + 0.5 * 365 * day), -17.49, 0.006);
});

test("heading filter", () => {
  const e = almucantar.headingUpdate({ offsetDeg: 10, varianceDeg2: 4 }, { offsetDeg: 20, varianceDeg2: 4 });
  near(e.offsetDeg, 15, 1e-12);
  const m = almucantar.headingFromDirection({ deviceToEnu: northUpright }, { x: 500, y: 500 }, 70, 0.5);
  near(m.offsetDeg, 70, 1e-9);
});

test("heading filter with the magnetometer", () => {
  let f = almucantar.headingFilterStart({ x: 1, y: 2, z: 3 }, 0.5);
  assert.equal(f.covariance.length, almucantar.defaults.headingFilterSize ** 2);
  assert.equal(f.covariance[0], 180 * 180);
  assert.deepEqual(structuredClone(f), f);
  // A field to the north of the frame (device -z) and down (device -y), plus the bias.
  const t = 1767225600;
  f = almucantar.headingFilterPredict(f, 1);
  f = almucantar.headingFilterUpdateMagnetometer(f, northUpright, { x: 1, y: -38, z: -17 }, vienna, t);
  near(f.offsetDeg, almucantar.magneticDeclinationDeg(vienna, t), 5);
  f = almucantar.headingFilterUpdate(f, { offsetDeg: 10, varianceDeg2: 0.01 });
  near(f.offsetDeg, 10, 0.1);
  assert.throws(() => almucantar.headingFilterStart({ x: 0, y: 0, z: 0 }, 0), { name: "RangeError", message: /bias_sigma_ut/ });
  assert.throws(() => almucantar.headingFilterPredict({ ...f, covariance: [1] }, 1), RangeError);
  near(almucantar.defaults.headingFilterOptions.disturbanceTimeS, 120, 0);
});

test("values are plain objects that survive structuredClone", () => {
  const path = almucantar.sunPath(vienna, dayStart, dayStart + day, { stepS: 3600 });
  assert.deepEqual(structuredClone(path), path);
  const lines = almucantar.horizonLine({ deviceToEnu: northUpright });
  assert.deepEqual(structuredClone(lines), lines);
});

test("invalid arguments throw RangeError", () => {
  assert.throws(() => almucantar.sunPosition(0, { latitudeDeg: 91, longitudeDeg: 0 }), {
    name: "RangeError",
    message: /latitude_deg/,
  });
  assert.throws(() => almucantar.sunEvents(vienna, 10, 10), RangeError);
  assert.throws(() => almucantar.sunPosition(0, vienna, { model: 7 }), RangeError);
});

test("the defaults are frozen and come from C++", () => {
  const d = almucantar.defaults;
  assert.ok(Object.isFrozen(d) && Object.isFrozen(d.sunOptions.atmosphere));
  assert.throws(() => {
    "use strict";
    d.sunOptions.refraction = false;
  }, TypeError);
  near(d.headingDriftDeg2PerS, 1 / 60, 1e-15);
  near(almucantar.headingPredict({ offsetDeg: 0, varianceDeg2: 1 }, 60).varianceDeg2, 2, 1e-12);
});

test("dates, moon, camera, lines, and magnetometer", () => {
  const direction = almucantar.sunPosition(dayStart - 50 * day + 10 * 3600, vienna);
  const dates = almucantar.findDates(vienna, direction, dayStart - 100 * day, dayStart + 100 * day);
  assert.equal(dates.length, 2);
  const moon = almucantar.moonPosition(dayStart, vienna);
  assert.ok(moon.azimuthDeg >= 0 && moon.azimuthDeg < 360);
  const camera = almucantar.cameraFromFov(1000, 2000, 90);
  near(camera.fx, 500, 1e-9);
  const lines = almucantar.projectLine({ deviceToEnu: northUpright, camera }, [
    { azimuthDeg: -10, altitudeDeg: 0 },
    { azimuthDeg: 10, altitudeDeg: 0 },
  ]);
  assert.equal(lines.length, 1);
  near(lines[0][0].y, 1000, 1e-6);
  // A field to magnetic north, in a frame with y to true north: the offset is the declination.
  const m = almucantar.headingFromMagnetometer({ w: 1, x: 0, y: 0, z: 0 }, { x: 0, y: 20, z: -44 }, vienna, dayStart);
  near(m.offsetDeg, (almucantar.magneticDeclinationDeg(vienna, dayStart) + 360) % 360, 1e-6);
});

test("terrain: GeoTIFF, tiles, profile, and events", async () => {
  const { readFile } = await import("node:fs/promises");
  const file = new URL("../../test/data/geotiff/float_tiles_deflate_p3.tif", import.meta.url);
  const grid = almucantar.readElevationGeotiff(new Uint8Array(await readFile(file)));
  assert.equal(grid.rows, 7);
  assert.equal(grid.columns, 5);
  assert.ok(grid.heightsM instanceof Float32Array);
  near(grid.heightsM[0], 1000.25, 1e-6);
  near(grid.northLatitudeDeg, 47.75, 1e-12);
  assert.equal(almucantar.readElevationGeotiff(new Uint8Array(await readFile(file)), 1).rows, 4);
  const bytes = new Uint8Array(await readFile(file));
  const prefix = almucantar.elevationGeotiffPrefixSize(bytes, 1);
  assert.ok(prefix > 0 && prefix <= bytes.length);
  assert.equal(almucantar.readElevationGeotiff(bytes.slice(0, prefix), 1).rows, 4);
  assert.equal(almucantar.elevationGeotiffPrefixSize(bytes.slice(0, 16), 0), 0);
  assert.throws(() => almucantar.readElevationGeotiff(new Uint8Array([1, 2, 3])), RangeError);

  const tiles = almucantar.elevationTiles(vienna, 20000);
  assert.deepEqual([tiles[0].latitudeDeg, tiles[0].longitudeDeg, tiles[0].distanceM], [48, 16, 0]);

  // A flat grid of 150 m around Vienna: the horizon is a little below 0° in all directions.
  const rows = 61, columns = 91;
  const flat = {
    northLatitudeDeg: vienna.latitudeDeg + 0.25, westLongitudeDeg: vienna.longitudeDeg - 0.375,
    latitudeStepDeg: 1 / 120, longitudeStepDeg: 1 / 120, rows, columns,
    heightsM: new Float32Array(rows * columns).fill(150),
  };
  const profile = almucantar.horizonProfile(vienna, [flat], { azimuthStepDeg: 10 });
  assert.equal(profile.altitudeDeg.length, 36);
  near(profile.observerElevationM, 150, 1e-6);
  for (const a of profile.altitudeDeg) assert.ok(a < 0 && a > -0.1);
  near(almucantar.terrainAltitudeDeg(profile, 5), (profile.altitudeDeg[0] + profile.altitudeDeg[1]) / 2, 1e-12);
  assert.throws(() => almucantar.horizonProfile(vienna, []), RangeError);
  assert.equal(almucantar.defaults.horizonOptions.minDistanceM, 0);
  assert.throws(() => almucantar.horizonProfile(vienna, [flat], { minDistanceM: -1 }), RangeError);
  const cleared = almucantar.horizonProfile(vienna, [flat], { azimuthStepDeg: 10, minDistanceM: 2000 });
  assert.equal(cleared.altitudeDeg.length, 36);

  const events = almucantar.terrainEvents(vienna, profile, dayStart, dayStart + day);
  assert.equal(events.kind, DayKind.normal);
  assert.deepEqual(events.crossings.map((c) => c.rising), [true, false]);
  const wall = { ...profile, altitudeDeg: new Array(36).fill(80) };
  assert.equal(almucantar.terrainEvents(vienna, wall, dayStart, dayStart + day).kind, DayKind.polarNight);
  // The results go through postMessage.
  structuredClone(profile);
  structuredClone(grid);
});

test("terrain: Web Mercator tiles and Terrarium pixels", () => {
  const tiles = almucantar.mapTiles(vienna, 100000, 10);
  assert.deepEqual([tiles[0].zoom, tiles[0].x, tiles[0].distanceM], [10, 558, 0]);
  // A tile of 2 x 2 pixels, all at 500 m: red 129, green 244 (129 * 256 + 244 - 32768 = 500).
  const rgba = new Uint8ClampedArray([129, 244, 0, 255, 129, 244, 0, 255, 129, 244, 0, 255, 129, 244, 0, 255]);
  const grid = almucantar.terrariumGrid(rgba, 2, 2, tiles[0]);
  assert.deepEqual(Array.from(grid.heightsM), [500, 500, 500, 500]);
  assert.throws(() => almucantar.mapTiles(vienna, 1000, 13), RangeError);
});

test("terrain: the moon behind the terrain", () => {
  const flat = { azimuthStepDeg: 10, altitudeDeg: new Array(36).fill(0), distanceM: new Array(36).fill(1000), observerElevationM: 0 };
  const events = almucantar.terrainEvents(vienna, flat, dayStart, dayStart + 3 * day, { body: Body.moon });
  assert.ok(events.crossings.length >= 5 && events.crossings.length <= 7);
  const rise = events.crossings.find((c) => c.rising);
  assert.ok(almucantar.moonPosition(rise.unixUtc + 2, vienna).altitudeDeg + 0.2667 > 0);
  assert.throws(() => almucantar.terrainEvents(vienna, flat, dayStart, dayStart + day, { body: 9 }), RangeError);
});

test("planets, Milky Way, stars, and projectPoints", () => {
  const t = 1782000000;
  const saturn = almucantar.planetPosition(Planet.saturn, t, vienna);
  const airless = almucantar.planetPosition(Planet.saturn, t, vienna, { refraction: false });
  assert.equal(saturn.azimuthDeg, airless.azimuthDeg);
  assert.ok(almucantar.planetMagnitude(Planet.venus, t) < -3.5);
  const plane = almucantar.galacticPlane(t, vienna, 10);
  assert.equal(plane.length, 36);
  const center = almucantar.galacticPosition(GALACTIC_CENTER.longitudeDeg, GALACTIC_CENTER.latitudeDeg, t, vienna);
  assert.ok(Math.abs(center.azimuthDeg - plane[0].azimuthDeg) < 1e-9);

  // Sirius and Vega, as typed arrays and as plain arrays.
  const catalog = { raDeg: new Float64Array([101.2871, 279.2346]), decDeg: [-16.7161, 38.7837] };
  const stars = almucantar.starPositions(catalog, t, vienna);
  assert.ok(stars instanceof Float64Array);
  assert.equal(stars.length, 4);
  const moving = almucantar.starPositions(
    { ...catalog, pmRaArcsecPerYear: [-0.553, 0.201], pmDecArcsecPerYear: [-1.205, 0.286] },
    t,
    vienna,
  );
  assert.notEqual(moving[1], stars[1]);
  assert.throws(() => almucantar.starPositions({ raDeg: [1, 2], decDeg: [1] }, t, vienna), RangeError);

  const view = { camera: almucantar.cameraFromFov(1000, 1000, 60) };
  const points = almucantar.projectPoints(view, [0, 0, 180, 0]);
  assert.equal(points.length, 4);
  const one = almucantar.project(view, { azimuthDeg: 0, altitudeDeg: 0 });
  assert.equal(one.valid, !Number.isNaN(points[0]));
  if (one.valid) assert.equal(points[0], one.point.x);
  assert.ok(Number.isNaN(points[2]) || Number.isNaN(points[0]));
});
