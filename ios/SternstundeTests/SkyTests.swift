import Almucantar
import XCTest

@testable import SternstundeApp

final class SkyTests: XCTestCase {
    private static let stars: StarData = {
        let url = Bundle(for: SkyTests.self).url(forResource: "stars", withExtension: "json")!
        return try! StarData.decode(Data(contentsOf: url))
    }()

    private func h(_ azimuthDeg: Double, _ altitudeDeg: Double) -> Horizontal {
        Horizontal(azimuthDeg: azimuthDeg, altitudeDeg: altitudeDeg)
    }

    func testAPathSplitsAtTheHorizonWithoutAGap() {
        // A path that rises over the horizon at azimuth 90°, and sets at 270° (over north).
        let line = [h(60, -6), h(80, -2), h(100, 2), h(180, 30), h(260, 2), h(280, -2), h(350, -10), h(10, -10)]
        let (visible, hidden) = splitAtHorizon(line, profile: nil)
        XCTAssertEqual(visible.count, 1)
        XCTAssertEqual(hidden.count, 2)
        // The crossings are halfway between the samples, on the horizon.
        XCTAssertEqual(visible[0].first!.azimuthDeg, 90, accuracy: 1e-9)
        XCTAssertEqual(visible[0].first!.altitudeDeg, 0, accuracy: 1e-9)
        XCTAssertEqual(visible[0].last!.azimuthDeg, 270, accuracy: 1e-9)
        XCTAssertEqual(visible[0].first, hidden[0].last)
        XCTAssertEqual(visible[0].last, hidden[1].first)
        XCTAssertEqual(hidden[1].last!.azimuthDeg, 10, accuracy: 1e-9)
    }

    func testACrossingOverNorthKeepsTheAzimuthInRange() {
        let (visible, hidden) = splitAtHorizon([h(350, -1), h(10, 1)], profile: nil)
        XCTAssertEqual(visible[0].first!.azimuthDeg, 0, accuracy: 1e-9)
        XCTAssertEqual(hidden[0].last!.azimuthDeg, 0, accuracy: 1e-9)
    }

    func testThePathHasTheFullHoursOfThePlace() throws {
        // India has an offset of 5:30, so a full hour of the place is at 30 minutes past a full UTC hour.
        let zone = try XCTUnwrap(TimeZone(identifier: "Asia/Kolkata"))
        let delhi = Observer(latitudeDeg: 28.61, longitudeDeg: 77.21)
        let t = 1_781_992_800.0  // 2026-06-20T22:00Z
        let path = try Target.sun.path(Self.stars, unixUtc: t, observer: delhi, profile: nil, timeZone: zone)
        XCTAssertEqual(path.target, .sun)
        XCTAssertEqual(path.hours.count, 24)
        XCTAssertFalse(path.visible.isEmpty)
        XCTAssertFalse(path.hidden.isEmpty)
        XCTAssertFalse(Target.star(0).hasPath)
        XCTAssertTrue(Target.planet(.mars).hasPath)
        // The path starts at 2026-06-20T10:00Z, which is 15:30 in India, so the first full hour of
        // the place is 16:00.
        XCTAssertEqual(path.hours.first?.label, "16")
    }
}
