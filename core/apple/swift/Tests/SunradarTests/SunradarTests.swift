import Foundation
import XCTest

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
        XCTAssertEqual(PathOptions().stepS, 300)
        XCTAssertEqual(SearchOptions().maxSeparationDeg, 2)
        XCTAssertEqual(Camera().fx, 866.0254037844386, accuracy: 1e-12)
        XCTAssertEqual(HeadingEstimate().varianceDeg2, 180 * 180)
        XCTAssertEqual(sunriseAltitudeDeg, -0.8333)
    }
}
