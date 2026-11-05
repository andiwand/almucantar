import XCTest

@testable import SternstundeApp

final class StarScreenTests: XCTestCase {
    func testATapTakesTheNearestObjectAndABrighterObjectWinsANearTie() {
        let list = HitList()
        list.hits = [
            Hit(target: .star(5), at: CGPoint(x: 100, y: 100), mag: 4),
            Hit(target: .star(1), at: CGPoint(x: 108, y: 100), mag: 1),
            Hit(target: .constellation(0), at: CGPoint(x: 200, y: 200), mag: 7),
        ]
        // Star 1 is 8 points farther, but 3 magnitudes brighter.
        XCTAssertEqual(list.nearest(CGPoint(x: 100, y: 100), radius: 32)?.target, .star(1))
        XCTAssertEqual(list.nearest(CGPoint(x: 100, y: 100), radius: 32) { $0.mag > 2 }?.target, .star(5))
        XCTAssertEqual(list.nearest(CGPoint(x: 195, y: 200), radius: 32)?.target, .constellation(0))
        XCTAssertNil(list.nearest(CGPoint(x: 400, y: 400), radius: 32))
    }

    func testTheRiseAndSetTextHasTheEarlierEventFirst() {
        let zone = TimeZone(identifier: "Europe/Vienna")!
        let rise = 1_781_992_800.0
        let set = rise - 3600
        let text = RiseSet(rise: rise, set: set, always: nil).text(zone, overTerrain: true)
        XCTAssertEqual(text, "Sets \(PlaceTime.time(set, zone)) · rises \(PlaceTime.time(rise, zone)) over the terrain")
        XCTAssertEqual(RiseSet(rise: rise, set: nil, always: nil).text(zone, overTerrain: false), "Rises \(PlaceTime.time(rise, zone))")
        XCTAssertEqual(RiseSet(rise: nil, set: nil, always: true).text(zone, overTerrain: false), "Up all day")
        XCTAssertEqual(RiseSet(rise: nil, set: nil, always: false).text(zone, overTerrain: true), "Below the horizon all day over the terrain")
    }

    func testTheSkyColorGetsBrighterWithTheSunAndStaysInItsRange() {
        XCTAssertEqual(StarScreen.skyColor(-40), StarScreen.skyColor(-18))
        XCTAssertEqual(StarScreen.skyColor(40), StarScreen.skyColor(10))
        XCTAssertLessThan(StarScreen.skyColor(-15).b, StarScreen.skyColor(-3).b)
        XCTAssertEqual(StarScreen.skyColor(-6), RGB(r: 24, g: 40, b: 82))
    }

    func testTheSearchFoldsAccentsAndCase() {
        XCTAssertEqual(FindPanel.fold("Alnilam"), FindPanel.fold("alnilam"))
        XCTAssertEqual(FindPanel.fold("Zürich"), "zurich")
    }
}
