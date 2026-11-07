import Foundation
import Almucantar
import XCTest

@testable import AlmucantarShared

/// The base map of the test bundle, decoded once for all tests.
private let baseMap: BaseMap = {
    do {
        return try BaseMap.bundled(in: Bundle(for: BaseMapTests.self))
    } catch {
        fatalError("The base map does not decode: \(error)")
    }
}()

final class BaseMapTests: XCTestCase {
    func testHasTheLayersAndThePlaces() throws {
        XCTAssertGreaterThan(baseMap.layers[.coastline]?.count ?? 0, 1000)
        XCTAssertGreaterThan(baseMap.layers[.borders]?.count ?? 0, 1000)
        XCTAssertGreaterThan(baseMap.places.count, 7000)
        XCTAssertEqual(baseMap.attribution, "Map: Natural Earth (public domain)")
        let vienna = try XCTUnwrap(baseMap.places.first { $0.name == "Vienna" })
        XCTAssertEqual(vienna.latitudeDeg, 48.2, accuracy: 0.05)
        XCTAssertEqual(vienna.longitudeDeg, 16.37, accuracy: 0.05)
        XCTAssertEqual(vienna.timeZone, "Europe/Vienna")
        // The points of a line are in world units, inside its box.
        let line = try XCTUnwrap(baseMap.layers[.coastline]?.first)
        for p in line.points {
            XCTAssert(p.x >= line.min.x && p.x <= line.max.x && p.y >= line.min.y && p.y <= line.max.y)
            XCTAssert(p.x >= 0 && p.x <= 1 && p.y >= 0 && p.y <= 1)
        }
    }

    func testTheSearchFindsNamesWithoutAccentsTheLargestFirst() {
        XCTAssertEqual(baseMap.search("zurich").first?.name, "Zürich")
        XCTAssertEqual(baseMap.search("Vien").first?.name, "Vienna")
        XCTAssertEqual(baseMap.search("SYDNEY").first?.country, "Australia")
        XCTAssertEqual(baseMap.search("  "), [])
        XCTAssertLessThanOrEqual(baseMap.search("a").count, 8)
        // The places whose name starts with the text come before the places that contain it.
        let results = baseMap.search("york")
        let firstContains = results.firstIndex { !$0.folded.hasPrefix("york") } ?? results.count
        XCTAssert(results[firstContains...].allSatisfy { !$0.folded.hasPrefix("york") })
        XCTAssert(results.contains { $0.name == "New York" })
    }

    func testADroppedPlaceGetsTheTimeZoneOfTheNearestPlace() {
        let tyrol = Observer(latitudeDeg: 47.1, longitudeDeg: 11.6)
        XCTAssertEqual(baseMap.nearestPlace(to: tyrol)?.country, "Austria")
        XCTAssertEqual(baseMap.timeZone(at: tyrol, fallback: TimeZone(identifier: "UTC")!).identifier, "Europe/Vienna")
        let fiji = Observer(latitudeDeg: -17.8, longitudeDeg: 178.0)
        XCTAssertEqual(baseMap.timeZone(at: fiji, fallback: TimeZone(identifier: "UTC")!).identifier, "Pacific/Fiji")
        // The nearest place with a time zone can be farther than the nearest place.
        let any = baseMap.places.first { $0.timeZone.isEmpty }!
        XCTAssertEqual(baseMap.nearestPlace(to: any.observer, withTimeZone: true)?.timeZone.isEmpty, false)
        XCTAssertNotEqual(baseMap.nearestPlace(to: any.observer, withTimeZone: true), any)
    }

    func testTheWebMercatorConversionsAreInverse() {
        let p = Mercator.world(latitudeDeg: 47.27, longitudeDeg: 11.39)
        let back = Mercator.observer(world: p)
        XCTAssertEqual(back.latitudeDeg, 47.27, accuracy: 1e-9)
        XCTAssertEqual(back.longitudeDeg, 11.39, accuracy: 1e-9)
        // The longitude wraps for a point beyond the date line.
        XCTAssertEqual(Mercator.observer(world: SIMD2(p.x + 1, p.y)).longitudeDeg, 11.39, accuracy: 1e-9)
        let viewport = MapViewport(center: Observer(latitudeDeg: 47.27, longitudeDeg: 11.39), zoom: 6)
        let center = viewport.observer(at: CGPoint(x: 200, y: 150), size: CGSize(width: 400, height: 300))
        XCTAssertEqual(center.latitudeDeg, 47.27, accuracy: 1e-9)
        XCTAssertEqual(center.longitudeDeg, 11.39, accuracy: 1e-9)
        // 256 points to the east at zoom 0 is the whole world.
        var moved = MapViewport(center: Observer(latitudeDeg: 0, longitudeDeg: 170), zoom: 3)
        moved.pan(by: CGSize(width: -256.0 * 8 * 20 / 360, height: 0))
        XCTAssertEqual(Mercator.observer(world: moved.center).longitudeDeg, -170, accuracy: 1e-6)
    }
}

final class PlaceTimeTests: XCTestCase {
    private func utc(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        PlaceTime.calendar(TimeZone(identifier: "UTC")!).date(
            from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    func testADayWithAChangeOfTheDaylightSavingTimeHas23Or25Hours() {
        let vienna = PlaceTime.calendar(TimeZone(identifier: "Europe/Vienna")!)
        let spring = PlaceTime.day(of: utc(2026, 3, 29, 12), calendar: vienna)
        XCTAssertEqual((spring.end - spring.start) / 3600, 23)
        let autumn = PlaceTime.day(of: utc(2026, 10, 25, 12), calendar: vienna)
        XCTAssertEqual((autumn.end - autumn.start) / 3600, 25)
        XCTAssertEqual(autumn.start, utc(2026, 10, 24, 22).timeIntervalSince1970)
        let normal = PlaceTime.day(of: utc(2026, 7, 15, 12), calendar: vienna)
        XCTAssertEqual((normal.end - normal.start) / 3600, 24)
    }

    func testTheDayStartsAtMidnightOfTheZoneAlsoAcrossTheDateLine() {
        let now = utc(2026, 11, 2, 20)
        // In Auckland (UTC+13) it is already 2026-11-03 09:00.
        let auckland = PlaceTime.calendar(TimeZone(identifier: "Pacific/Auckland")!)
        XCTAssertEqual(PlaceTime.day(of: now, calendar: auckland).start, utc(2026, 11, 2, 11).timeIntervalSince1970)
        XCTAssertEqual(auckland.component(.day, from: now), 3)
        // In Honolulu (UTC-10) it is still 2026-11-02 10:00.
        let honolulu = PlaceTime.calendar(TimeZone(identifier: "Pacific/Honolulu")!)
        XCTAssertEqual(PlaceTime.day(of: now, calendar: honolulu).start, utc(2026, 11, 2, 10).timeIntervalSince1970)
        XCTAssertEqual(honolulu.component(.day, from: now), 2)
    }

    func testTheTextUsesTheZoneOfThePlace() {
        let t = utc(2026, 11, 2, 20).timeIntervalSince1970
        let sydney = TimeZone(identifier: "Australia/Sydney")!
        let kolkata = TimeZone(identifier: "Asia/Kolkata")!
        // 07:00 in Sydney (UTC+11) and 01:30 in Kolkata (UTC+5:30); the format of the locale varies.
        XCTAssert(PlaceTime.time(t, sydney).contains("7:00"), PlaceTime.time(t, sydney))
        XCTAssert(PlaceTime.time(t, kolkata).contains("1:30"), PlaceTime.time(t, kolkata))
        XCTAssert(PlaceTime.date(Date(timeIntervalSince1970: t), sydney).contains("3"))
    }

    func testTheStoredPlaceRoundTrips() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "PlaceTimeTests"))
        defaults.removePersistentDomain(forName: "PlaceTimeTests")
        XCTAssertNil(StoredPlace.load(from: defaults))
        let place = StoredPlace(
            latitudeDeg: -33.87, longitudeDeg: 151.21, name: "Sydney · -33.870°, 151.210°",
            timeZone: "Australia/Sydney", manual: true)
        place.save(to: defaults)
        XCTAssertEqual(StoredPlace.load(from: defaults), place)
        // A time zone that Foundation does not know makes the entry invalid.
        var bad = place
        bad.timeZone = "Mars/Olympus"
        bad.save(to: defaults)
        XCTAssertNil(StoredPlace.load(from: defaults))
        defaults.removePersistentDomain(forName: "PlaceTimeTests")
    }
}

final class ProbablePlaceTests: XCTestCase {
    private let lines = ProbablePlace.bundledLines(in: Bundle(for: ProbablePlaceTests.self))
    private let now = Date(timeIntervalSince1970: 1_794_052_800)  // 2026-11-07 12:00 UTC

    func testParsesIso6709() throws {
        let vienna = try XCTUnwrap(ProbablePlace.parseIso6709("+4813+01620"))
        XCTAssertEqual(vienna.latitudeDeg, 48 + 13 / 60.0, accuracy: 1e-12)
        let ny = try XCTUnwrap(ProbablePlace.parseIso6709("+404251-0740023"))
        XCTAssertEqual(ny.longitudeDeg, -(74 + 23 / 3600.0), accuracy: 1e-12)
        XCTAssertNil(ProbablePlace.parseIso6709("+48+016"))
    }

    func testTheTimeZoneGivesItsMainCity() {
        XCTAssertGreaterThan(lines.count, 400)
        let place = ProbablePlace.find(lines: lines, timeZone: TimeZone(identifier: "America/New_York")!, country: "AT", now: now)
        XCTAssertEqual(place.city, "New York")
    }

    func testWithoutACityOfTheTimeZoneTheCountryGivesIt() {
        let utc = TimeZone(identifier: "UTC")!
        XCTAssertEqual(ProbablePlace.find(lines: lines, timeZone: utc, country: "AT", now: now).city, "Vienna")
        let pacific = TimeZone(secondsFromGMT: -8 * 3600)!
        XCTAssertEqual(ProbablePlace.find(lines: lines, timeZone: pacific, country: "US", now: now).city, "Los Angeles")
    }

    func testWithoutBothTheMeridianOfTheOffset() {
        let place = ProbablePlace.find(lines: lines, timeZone: TimeZone(secondsFromGMT: 3 * 3600)!, country: nil, now: now)
        XCTAssertNil(place.city)
        XCTAssertEqual(place.observer.longitudeDeg, 45, accuracy: 1e-9)
        XCTAssertEqual(place.observer.latitudeDeg, 0, accuracy: 1e-9)
    }
}
