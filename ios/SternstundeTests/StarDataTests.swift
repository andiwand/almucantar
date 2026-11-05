import Almucantar
import XCTest

@testable import SternstundeApp

final class StarDataTests: XCTestCase {
    private static let stars: StarData = {
        let url = Bundle(for: StarDataTests.self).url(forResource: "stars", withExtension: "json")!
        return try! StarData.decode(Data(contentsOf: url))
    }()
    private var stars: StarData { Self.stars }

    func testTheStarDataDecodesIntoColumnsOfTheSameLength() {
        let n = stars.count
        XCTAssertGreaterThan(n, 5000)
        for column in [
            stars.catalog.raDeg.count, stars.catalog.decDeg.count, stars.catalog.pmRaArcsecPerYear.count,
            stars.catalog.pmDecArcsecPerYear.count, stars.bv.count,
        ] {
            XCTAssertEqual(column, n)
        }
        // Sorted by magnitude: Sirius first.
        XCTAssertEqual(stars.names[0], "Sirius")
        XCTAssertEqual(stars.index(of: "Sirius"), 0)
        XCTAssertLessThan(stars.mag[0], stars.mag[1])
        XCTAssertEqual(stars.constellations.count, 89)
        XCTAssertEqual(stars.labels.count, 89)
        let orion = stars.constellations.first { $0.id == "Ori" }!
        XCTAssertEqual(orion.name, "Orion")
        let names = Set(orion.lines.flatMap { $0 }.compactMap { stars.names[$0] })
        XCTAssertTrue(names.contains("Betelgeuse"))
        XCTAssertTrue(names.contains("Rigel"))
    }

    func testAnUnknownVersionIsRejected() {
        XCTAssertThrowsError(try StarData.decode(Data(#"{"version": 2}"#.utf8)))
        XCTAssertThrowsError(try StarData.decode(Data(#"{"version": 1}"#.utf8)))
    }

    func testTheSkyShowsFainterStarsAsItGetsDarker() {
        XCTAssertLessThan(StarLook.limitingMagnitude(10), StarLook.limitingMagnitude(-3))
        XCTAssertLessThan(StarLook.limitingMagnitude(-3), StarLook.limitingMagnitude(-9))
        XCTAssertLessThan(StarLook.limitingMagnitude(-9), StarLook.limitingMagnitude(-15))
        XCTAssertEqual(StarLook.limitingMagnitude(-30), 6)
        // Inside the twilight, the limit has no jumps (the same rule as the web app).
        for limit in [-6.0, -12.0, -18.0] {
            XCTAssertEqual(StarLook.limitingMagnitude(limit - 1e-9), StarLook.limitingMagnitude(limit + 1e-9), accuracy: 1e-6)
        }
        XCTAssertEqual(StarLook.darkness(-20), "Night")
        XCTAssertEqual(StarLook.darkness(-7), "Nautical twilight")
    }

    func testABrighterStarIsLargerAndTheColorsGoFromBlueToOrange() {
        XCTAssertGreaterThan(StarLook.radius(-1.46), StarLook.radius(2))
        XCTAssertGreaterThan(StarLook.radius(6), 0)
        let blue = StarLook.color(-0.3)
        let orange = StarLook.color(1.85)
        XCTAssertGreaterThan(blue.b, blue.r)
        XCTAssertGreaterThan(orange.r, orange.b)
        XCTAssertEqual(StarLook.color(-0.4), StarLook.color(-5))
    }

    func testTheSkyHasAllObjectsAndARiseAndSet() throws {
        let vienna = Observer(latitudeDeg: 48.2082, longitudeDeg: 16.3738)
        let t = 1_781_992_800.0  // 2026-06-21T00:00 CEST
        let sky = try XCTUnwrap(SkyAt.compute(data: stars, milkyWay: MilkyWay.create(clouds: 10, grains: 10), unixUtc: t, observer: vienna))
        XCTAssertEqual(sky.stars.count, stars.count)
        XCTAssertEqual(sky.labels.count, stars.constellations.count)
        XCTAssertEqual(sky.dust.count, 20)
        XCTAssertEqual(sky.planets.count, 7)
        // The direction at the time of the sky is the direction of the sky.
        let vega = Target.star(try XCTUnwrap(stars.index(of: "Vega")))
        XCTAssertEqual(try vega.direction(stars, unixUtc: t, observer: vienna).azimuthDeg, vega.direction(sky).azimuthDeg, accuracy: 1e-9)
        XCTAssertEqual(vega.name(stars), "Vega")
        // Polaris never sets in Vienna; the sun rises and sets.
        let polaris = Target.star(try XCTUnwrap(stars.index(of: "Polaris")))
        XCTAssertEqual(try polaris.riseAndSet(stars, unixUtc: t, observer: vienna, profile: nil).always, true)
        let sun = try Target.sun.riseAndSet(stars, unixUtc: t, observer: vienna, profile: nil)
        XCTAssertNil(sun.always)
        // The refraction lifts the center of the sun by about 0.57° at the horizon.
        let events = try sunEvents(observer: vienna, startUtc: t, endUtc: t + 86400, options: EventOptions(altitudeDeg: -0.57))
        // The samples are 10 minutes apart; the interpolation is good to about a minute.
        XCTAssertEqual(try XCTUnwrap(sun.rise), events.crossings[0].unixUtc, accuracy: 120)
        // A wall of 80° hides the sun all day.
        let wall = HorizonProfile(
            azimuthStepDeg: 1, altitudeDeg: Array(repeating: 80, count: 360), distanceM: Array(repeating: 1000, count: 360))
        XCTAssertEqual(try Target.sun.riseAndSet(stars, unixUtc: t, observer: vienna, profile: wall).always, false)
    }

    func testTheHorizonIsTheInterpolationOfTheCore() throws {
        let altitude = (0..<36).map { Double($0 % 7) - 1 }
        let profile = HorizonProfile(azimuthStepDeg: 10, altitudeDeg: altitude, distanceM: Array(repeating: 1000, count: 36))
        for azimuth in [0.0, 3.3, 17, 355, 359.99, 360, -5, 725] {
            XCTAssertEqual(horizonAt(profile, azimuthDeg: azimuth), try terrainAltitudeDeg(profile, azimuthDeg: azimuth), accuracy: 1e-9)
        }
        XCTAssertEqual(horizonAt(nil, azimuthDeg: 10), 0)
        var gap = profile
        gap.altitudeDeg[1] = .nan
        XCTAssertEqual(horizonAt(gap, azimuthDeg: 5), 0)
    }
}
