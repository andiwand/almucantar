import Foundation
import XCTest

import AlmucantarC

@testable import Almucantar

/// Tests of the Swift API. The C++ tests check the precision; these tests check the binding.
final class AlmucantarTests: XCTestCase {
    let vienna = Observer(latitudeDeg: 48.2082, longitudeDeg: 16.3738)
    let dayStart = 1_781_992_800.0  // 2026-06-21T00:00 CEST
    let day = 86400.0
    // A device upright in portrait, with the back camera to the north.
    let northUpright = Quaternion(w: cos(Double.pi / 4), x: sin(Double.pi / 4))

    func testVersion() {
        // Almucantar.version(): XCTestCase has a class property named version.
        XCTAssertGreaterThanOrEqual(Almucantar.version().split(separator: ".").count, 3)
    }

    func testSunPositionAndModels() throws {
        let sun = try sunPosition(1_782_039_420, observer: vienna)
        XCTAssertEqual(sun.azimuthDeg, 180, accuracy: 1)
        XCTAssertEqual(sun.altitudeDeg, 65.25, accuracy: 0.25)
        let airless = try sunPosition(1_782_039_420, observer: vienna, options: SunOptions(refraction: false))
        XCTAssertGreaterThan(sun.altitudeDeg, airless.altitudeDeg)
        let ae = try sunPosition(1_782_039_420, observer: vienna, options: SunOptions(model: .astronomyEngine))
        XCTAssertEqual(ae.altitudeDeg, sun.altitudeDeg, accuracy: 0.03)
    }

    func testEventsAndPath() throws {
        let events = try sunEvents(observer: vienna, startUtc: dayStart, endUtc: dayStart + day)
        XCTAssertEqual(events.kind, .normal)
        XCTAssertEqual(events.crossings.map(\.rising), [true, false])
        let path = try sunPath(observer: vienna, startUtc: dayStart, endUtc: dayStart + day, options: PathOptions(stepS: 3600))
        XCTAssertEqual(path.count, 25)
        XCTAssertEqual(path[1].unixUtc, dayStart + 3600)
    }

    func testProjection() throws {
        let view = View(deviceToEnu: northUpright)
        let center = try project(view, Horizontal(azimuthDeg: 0, altitudeDeg: 0))
        XCTAssertTrue(center.valid)
        XCTAssertEqual(center.point.x, 500, accuracy: 1e-9)
        let back = try unproject(view, ScreenPoint(x: 700, y: 400))
        XCTAssertEqual(try project(view, back).point.x, 700, accuracy: 1e-6)
        let horizon = try horizonLine(view)
        XCTAssertEqual(horizon.count, 1)
        for p in horizon[0] { XCTAssertEqual(p.y, 500, accuracy: 1e-6) }
    }

    func testSearchMoonAndHeading() throws {
        let t = dayStart + 10 * 3600
        let direction = try sunPosition(t, observer: vienna)
        XCTAssertEqual(try findTime(observer: vienna, direction: direction, startUtc: dayStart, endUtc: dayStart + day).unixUtc, t, accuracy: 1)
        XCTAssertEqual(try findAzimuthTimes(observer: vienna, azimuthDeg: direction.azimuthDeg, startUtc: dayStart, endUtc: dayStart + day).count, 1)
        XCTAssertGreaterThan(try moonIlluminatedFraction(1_767_441_600), 0.99)
        let e = try headingUpdate(HeadingEstimate(offsetDeg: 10, varianceDeg2: 4), HeadingMeasurement(offsetDeg: 20, varianceDeg2: 4))
        XCTAssertEqual(e.offsetDeg, 15, accuracy: 1e-12)
        let m = try headingFromDirection(View(deviceToEnu: northUpright), point: ScreenPoint(x: 500, y: 500), trueAzimuthDeg: 70, sigmaDeg: 0.5)
        XCTAssertEqual(m.offsetDeg, 70, accuracy: 1e-9)
    }

    func testInvalidArgumentsThrow() {
        XCTAssertThrowsError(try sunPosition(0, observer: Observer(latitudeDeg: 91, longitudeDeg: 0))) { error in
            let e = error as? AlmucantarError
            XCTAssertEqual(e?.kind, .invalidArgument)
            XCTAssertTrue(e?.message.contains("latitude_deg") ?? false)
        }
        XCTAssertThrowsError(try sunEvents(observer: vienna, startUtc: 10, endUtc: 10))
    }

    func testDefaultsComeFromCpp() {
        // The Swift defaults convert back to the C defaults, field by field.
        XCTAssertEqual(SunOptions().c.model, sr_default_sun_options().model)
        XCTAssertEqual(SunOptions().c.atmosphere.pressure_hpa, sr_default_sun_options().atmosphere.pressure_hpa)
        XCTAssertEqual(EventOptions().c.search_step_s, sr_default_event_options().search_step_s)
        XCTAssertEqual(PathOptions().c.step_s, sr_default_path_options().step_s)
        XCTAssertEqual(SearchOptions().c.max_separation_deg, sr_default_search_options().max_separation_deg)
        XCTAssertEqual(MoonOptions().c.refraction, sr_default_moon_options().refraction)
        XCTAssertEqual(View().c.device_to_enu.w, sr_default_view().device_to_enu.w)
        XCTAssertEqual(View().c.camera.fx, sr_default_view().camera.fx)
        XCTAssertEqual(View().c.screen_rotation, sr_default_view().screen_rotation)
        XCTAssertEqual(HeadingEstimate().varianceDeg2, unknownHeadingVarianceDeg2)
        // Independent values, so a wrong wiring of the constants fails.
        XCTAssertEqual(unknownHeadingVarianceDeg2, 180 * 180)
        XCTAssertEqual(defaultHeadingDriftDeg2PerS, 1.0 / 60.0)
        XCTAssertEqual(sunriseAltitudeDeg, -0.8333)
    }

    func testDatesLinesCameraAndMagnetometer() throws {
        let direction = try sunPosition(dayStart - 50 * day + 10 * 3600, observer: vienna)
        let dates = try findDates(observer: vienna, direction: direction, startUtc: dayStart - 100 * day, endUtc: dayStart + 100 * day)
        XCTAssertEqual(dates.count, 2)
        let moon = try moonPosition(dayStart, observer: vienna)
        XCTAssertTrue((0..<360).contains(moon.azimuthDeg))
        let camera = try cameraFromFov(width: 1000, height: 2000, horizontalFovDeg: 90)
        XCTAssertEqual(camera.fx, 500, accuracy: 1e-9)
        let lines = try projectLine(
            View(deviceToEnu: northUpright, camera: camera),
            [Horizontal(azimuthDeg: -10, altitudeDeg: 0), Horizontal(azimuthDeg: 10, altitudeDeg: 0)])
        XCTAssertEqual(lines.count, 1)
        XCTAssertEqual(lines[0][0].y, 1000, accuracy: 1e-6)
        XCTAssertEqual(try headingPredict(HeadingEstimate(offsetDeg: 0, varianceDeg2: 1), dtS: 60).varianceDeg2, 2, accuracy: 1e-12)
        // A field to magnetic north in a frame with y to true north: the offset is the declination.
        let m = try headingFromMagnetometer(
            deviceToFrame: Quaternion(), fieldUt: Vector3(x: 0, y: 20, z: -44), observer: vienna, unixUtc: dayStart)
        let declination = try magneticDeclinationDeg(observer: vienna, unixUtc: dayStart)
        XCTAssertEqual(m.offsetDeg, (declination + 360).truncatingRemainder(dividingBy: 360), accuracy: 1e-6)
    }

    func testHeadingFilterWithBias() throws {
        var f = try headingFilterStart(biasUt: Vector3(x: 1, y: 2, z: 3), biasSigmaUt: 0.5)
        XCTAssertEqual(f.varianceDeg2, unknownHeadingVarianceDeg2)
        XCTAssertEqual(f.covariance.count, headingFilterSize * headingFilterSize)
        XCTAssertEqual(f.biasUt.y, 2)
        // A field to the north of the frame (device -z) and down (device -y), plus the bias.
        let t = 1_767_225_600.0
        f = try headingFilterPredict(f, dtS: 1)
        f = try headingFilterUpdateMagnetometer(
            f, deviceToFrame: northUpright, fieldUt: Vector3(x: 1, y: -38, z: -17), observer: vienna, unixUtc: t)
        XCTAssertEqual(f.offsetDeg, try magneticDeclinationDeg(observer: vienna, unixUtc: t), accuracy: 5)
        f = try headingFilterUpdate(f, HeadingMeasurement(offsetDeg: 10, varianceDeg2: 0.01))
        XCTAssertEqual(f.offsetDeg, 10, accuracy: 0.1)
        XCTAssertEqual(f.estimate.offsetDeg, f.offsetDeg)
        XCTAssertThrowsError(try headingFilterStart(biasUt: Vector3(x: 0, y: 0, z: 0), biasSigmaUt: 0))
        var bad = f
        bad.covariance = [1]
        XCTAssertThrowsError(try headingFilterPredict(bad, dtS: 1))
    }

    // MARK: - The terrain horizon

    /// The GeoTIFF test files of the core (core/test/data/geotiff).
    private func geotiff(_ name: String) throws -> Data {
        let core = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../..").standardized
        return try Data(contentsOf: core.appendingPathComponent("test/data/geotiff/\(name)"))
    }

    private func flatGrid(height: Float) -> ElevationGrid {
        let (rows, columns) = (61, 91)
        return ElevationGrid(
            northLatitudeDeg: vienna.latitudeDeg + 0.25, westLongitudeDeg: vienna.longitudeDeg - 0.375,
            latitudeStepDeg: 1.0 / 120, longitudeStepDeg: 1.0 / 120, rows: rows, columns: columns,
            heightsM: [Float](repeating: height, count: rows * columns))
    }

    func testReadElevationGeotiff() throws {
        let data = try geotiff("float_tiles_deflate_p3.tif")
        let grid = try readElevationGeotiff(data)
        XCTAssertEqual(grid.rows, 7)
        XCTAssertEqual(grid.columns, 5)
        XCTAssertEqual(grid.heightsM.count, 35)
        XCTAssertEqual(grid.northLatitudeDeg, 47.75, accuracy: 1e-9)
        XCTAssertEqual(grid.heightsM[0], 1000.25, accuracy: 1e-3)
        XCTAssertEqual(try readElevationGeotiff(data, level: 1).rows, 4)
        XCTAssertThrowsError(try readElevationGeotiff(Data([0x49, 0x49, 0x2A, 0]))) { error in
            XCTAssertEqual((error as? AlmucantarError)?.kind, .invalidArgument)
        }
        XCTAssertThrowsError(try readElevationGeotiff(Data()))

        let prefix = try elevationGeotiffPrefixSize(data, level: 1)
        XCTAssertTrue(prefix > 0 && prefix <= data.count)
        XCTAssertEqual(try readElevationGeotiff(data.prefix(prefix), level: 1).rows, 4)
        XCTAssertEqual(try elevationGeotiffPrefixSize(data.prefix(16), level: 0), 0)
    }

    func testElevationTiles() throws {
        let tiles = try elevationTiles(observer: vienna, maxDistanceM: 100_000)
        XCTAssertEqual(tiles.first, ElevationTile(latitudeDeg: 48, longitudeDeg: 16, distanceM: 0))
        XCTAssertEqual(tiles.map(\.distanceM), tiles.map(\.distanceM).sorted())
        XCTAssertGreaterThanOrEqual(tiles.count, 4)
        XCTAssertThrowsError(try elevationTiles(observer: vienna, maxDistanceM: -1))
    }

    func testHorizonProfileOverFlatGround() throws {
        let profile = try horizonProfile(
            observer: vienna, tiles: [flatGrid(height: 150)], options: HorizonOptions(azimuthStepDeg: 10))
        XCTAssertEqual(profile.altitudeDeg.count, 36)
        XCTAssertEqual(profile.distanceM.count, 36)
        XCTAssertEqual(profile.observerElevationM, 150, accuracy: 1e-3)
        for altitude in profile.altitudeDeg { XCTAssertTrue(altitude < 0 && altitude > -0.1) }
        XCTAssertEqual(
            try terrainAltitudeDeg(profile, azimuthDeg: 5), (profile.altitudeDeg[0] + profile.altitudeDeg[1]) / 2,
            accuracy: 1e-12)
        // A grid without data at the observer.
        var empty = flatGrid(height: .nan)
        XCTAssertThrowsError(try horizonProfile(observer: vienna, tiles: [empty])) { error in
            XCTAssertTrue((error as? AlmucantarError)?.message.contains("no data at the observer") ?? false)
        }
        empty.heightsM = ElevationHeights([1, 2, 3])
        XCTAssertThrowsError(try horizonProfile(observer: vienna, tiles: [empty]))
    }

    func testTerrainEvents() throws {
        let flat = HorizonProfile(
            azimuthStepDeg: 1, altitudeDeg: Array(repeating: 0, count: 360), distanceM: Array(repeating: 1000, count: 360))
        let events = try terrainEvents(observer: vienna, profile: flat, startUtc: dayStart, endUtc: dayStart + day)
        XCTAssertEqual(events.kind, .normal)
        XCTAssertEqual(events.crossings.map(\.rising), [true, false])
        let sun = try sunEvents(observer: vienna, startUtc: dayStart, endUtc: dayStart + day)
        XCTAssertEqual(events.crossings[0].unixUtc, sun.crossings[0].unixUtc, accuracy: 60)

        let wall = HorizonProfile(
            azimuthStepDeg: 1, altitudeDeg: Array(repeating: 80, count: 360), distanceM: Array(repeating: 1000, count: 360))
        let night = try terrainEvents(
            observer: vienna, profile: wall, startUtc: dayStart, endUtc: dayStart + day,
            options: TerrainEventOptions(limbDeg: 0))
        XCTAssertEqual(night.kind, .polarNight)
        XCTAssertTrue(night.crossings.isEmpty)

        // The moon: the upper limb is on the horizon at each crossing.
        let moon = try terrainEvents(
            observer: vienna, profile: flat, startUtc: dayStart, endUtc: dayStart + 3 * day,
            options: TerrainEventOptions(body: .moon))
        XCTAssertTrue((5...7).contains(moon.crossings.count))
        let rise = try XCTUnwrap(moon.crossings.first(where: \.rising))
        XCTAssertGreaterThan(try moonPosition(rise.unixUtc + 2, observer: vienna).altitudeDeg + 0.2667, 0)
        XCTAssertLessThan(try moonPosition(rise.unixUtc - 2, observer: vienna).altitudeDeg + 0.2667, 0)

        var bad = flat
        bad.distanceM = [1]
        XCTAssertThrowsError(try terrainAltitudeDeg(bad, azimuthDeg: 0))
        XCTAssertThrowsError(try terrainEvents(observer: vienna, profile: flat, startUtc: 10, endUtc: 10))
    }

    // MARK: - The sky

    /// The angle between two directions, in degrees.
    private func angleDeg(_ a: Horizontal, _ b: Horizontal) -> Double {
        let r = Double.pi / 180
        let c = sin(a.altitudeDeg * r) * sin(b.altitudeDeg * r)
            + cos(a.altitudeDeg * r) * cos(b.altitudeDeg * r) * cos((a.azimuthDeg - b.azimuthDeg) * r)
        return acos(min(1, max(-1, c))) / r
    }

    func testPlanetsAndMilkyWay() throws {
        let venus = try planetPosition(.venus, unixUtc: dayStart, observer: vienna)
        XCTAssertTrue((0..<360).contains(venus.azimuthDeg))
        let airless = try planetPosition(.venus, unixUtc: dayStart, observer: vienna, options: SkyOptions(refraction: false))
        XCTAssertGreaterThanOrEqual(venus.altitudeDeg, airless.altitudeDeg)
        XCTAssertLessThan(try planetMagnitude(.venus, unixUtc: dayStart), -3)
        XCTAssertEqual(Planet.allCases.count, 7)
        let center = try galacticPosition(
            longitudeDeg: galacticCenterLongitudeDeg, latitudeDeg: galacticCenterLatitudeDeg, unixUtc: dayStart,
            observer: vienna)
        let plane = try galacticPlane(unixUtc: dayStart, observer: vienna, stepDeg: 2)
        XCTAssertEqual(plane.count, 180)
        XCTAssertEqual(plane[0].azimuthDeg, center.azimuthDeg, accuracy: 1e-9)
        XCTAssertThrowsError(try galacticPlane(unixUtc: dayStart, observer: vienna, stepDeg: 0))
        XCTAssertEqual(SkyOptions().refraction, sr_default_sky_options().refraction)
        XCTAssertEqual(SkyOptions().atmosphere.pressureHpa, sr_default_sky_options().atmosphere.pressure_hpa)
    }

    func testStarPositionsAndProjectPoints() throws {
        // The north celestial pole is at the latitude of the observer; Sirius is a normal star.
        let catalog = StarCatalog(raDeg: [0, 101.28708], decDeg: [90, -16.71611])
        let stars = try starPositions(catalog, unixUtc: dayStart, observer: vienna, options: SkyOptions(refraction: false))
        XCTAssertEqual(stars.count, 2)
        XCTAssertEqual(stars[0].altitudeDeg, vienna.latitudeDeg, accuracy: 0.5)
        // Sirius is at l = 227.23°, b = -8.89°. Both are airless, because the refraction is large near the horizon.
        let sirius = try galacticPosition(
            longitudeDeg: 227.23, latitudeDeg: -8.89, unixUtc: dayStart, observer: vienna, options: SkyOptions(refraction: false))
        XCTAssertEqual(angleDeg(stars[1], sirius), 0, accuracy: 0.1)
        // The proper motion moves a star; an empty column is 0.
        let moving = StarCatalog(raDeg: [101.28708], decDeg: [-16.71611], pmRaArcsecPerYear: [-0.546], pmDecArcsecPerYear: [-1.223])
        let moved = try starPositions(moving, unixUtc: dayStart, observer: vienna)
        let still = try starPositions(StarCatalog(raDeg: [101.28708], decDeg: [-16.71611]), unixUtc: dayStart, observer: vienna)
        XCTAssertGreaterThan(angleDeg(moved[0], still[0]), 0.005)
        XCTAssertThrowsError(try starPositions(StarCatalog(raDeg: [1, 2], decDeg: [1]), unixUtc: dayStart, observer: vienna)) {
            XCTAssertEqual(($0 as? AlmucantarError)?.kind, .invalidArgument)
        }
        XCTAssertThrowsError(
            try starPositions(
                StarCatalog(raDeg: [1], decDeg: [1], pmRaArcsecPerYear: [0]), unixUtc: dayStart, observer: vienna))
        XCTAssertEqual(try starPositions(StarCatalog(raDeg: [], decDeg: []), unixUtc: dayStart, observer: vienna).count, 0)

        let view = View(deviceToEnu: northUpright)
        let points = try projectPoints(
            view, [Horizontal(azimuthDeg: 0, altitudeDeg: 0), Horizontal(azimuthDeg: 180, altitudeDeg: 0)])
        XCTAssertEqual(points.count, 2)
        XCTAssertTrue(points[0].valid)
        XCTAssertEqual(points[0].point.x, try project(view, Horizontal(azimuthDeg: 0, altitudeDeg: 0)).point.x, accuracy: 1e-9)
        XCTAssertFalse(points[1].valid)
        XCTAssertEqual(try projectPoints(view, []).count, 0)
        XCTAssertThrowsError(try projectPoints(view, [Horizontal(azimuthDeg: .nan, altitudeDeg: 0)]))
    }

    func testTerrainDefaultsComeFromCpp() {
        XCTAssertEqual(HorizonOptions().c.azimuth_step_deg, sr_default_horizon_options().azimuth_step_deg)
        XCTAssertEqual(HorizonOptions().c.max_distance_m, sr_default_horizon_options().max_distance_m)
        XCTAssertEqual(HorizonOptions().c.refraction_coefficient, sr_default_horizon_options().refraction_coefficient)
        XCTAssertEqual(HorizonOptions().minDistanceM, 0)
        XCTAssertEqual(HorizonOptions(minDistanceM: 2000).c.min_distance_m, 2000)
        XCTAssertEqual(TerrainEventOptions().c.limb_deg, sr_default_terrain_event_options().limb_deg)
        XCTAssertEqual(TerrainEventOptions().body, .sun)
        XCTAssertEqual(TerrainEventOptions().c.atmosphere.pressure_hpa, sr_default_terrain_event_options().atmosphere.pressure_hpa)
        // Independent values, so a wrong wiring fails.
        XCTAssertEqual(HorizonOptions().azimuthStepDeg, 0.25)
        XCTAssertEqual(HorizonOptions().maxDistanceM, 100_000)
        XCTAssertEqual(HorizonOptions().eyeHeightM, 1.6)
        XCTAssertTrue(HorizonOptions().useGridElevation)
        XCTAssertEqual(TerrainEventOptions().limbDeg, 0.2667)
        XCTAssertEqual(TerrainEventOptions().searchStepS, 60)
    }
}
