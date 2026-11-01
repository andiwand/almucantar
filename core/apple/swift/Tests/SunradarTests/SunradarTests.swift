import Foundation
import XCTest

import SunradarC

@testable import Sunradar

/// Tests of the Swift API. The C++ tests check the precision; these tests check the binding.
final class SunradarTests: XCTestCase {
    let vienna = Observer(latitudeDeg: 48.2082, longitudeDeg: 16.3738)
    let dayStart = 1_781_992_800.0  // 2026-06-21T00:00 CEST
    let day = 86400.0
    // A device upright in portrait, with the back camera to the north.
    let northUpright = Quaternion(w: cos(Double.pi / 4), x: sin(Double.pi / 4))

    func testVersion() {
        // Sunradar.version(): XCTestCase has a class property named version.
        XCTAssertGreaterThanOrEqual(Sunradar.version().split(separator: ".").count, 3)
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
            let e = error as? SunradarError
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
}
