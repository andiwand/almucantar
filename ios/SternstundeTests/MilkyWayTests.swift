import XCTest

@testable import SternstundeApp

final class MilkyWayTests: XCTestCase {
    func testGalacticDirectionsConvertToJ2000() {
        // The galactic center and the north galactic pole (IAU 1958, J2000).
        let center = MilkyWay.galacticToJ2000(0, 0)
        XCTAssertEqual(center.raDeg, 266.405, accuracy: 0.01)
        XCTAssertEqual(center.decDeg, -28.936, accuracy: 0.01)
        XCTAssertEqual(MilkyWay.galacticToJ2000(123, 90).decDeg, 27.128, accuracy: 0.01)
        // Deneb is at about l = 84.3°, b = 2.0°.
        let deneb = MilkyWay.galacticToJ2000(84.28, 1.99)
        XCTAssertEqual(deneb.raDeg, 310.358, accuracy: 0.05)
        XCTAssertEqual(deneb.decDeg, 45.28, accuracy: 0.05)
    }

    func testTheBandIsBrightestAtTheCenterAndTheRiftIsDark() {
        XCTAssertGreaterThan(MilkyWay.bandBrightness(0), MilkyWay.bandBrightness(120))
        XCTAssertGreaterThan(MilkyWay.bandBrightness(75), MilkyWay.bandBrightness(120))
        XCTAssertGreaterThan(MilkyWay.riftDarkness(30, 2), 0.4)
        XCTAssertEqual(MilkyWay.riftDarkness(150, 2), 0)
    }

    func testTheDustHasTheRequestedPoints() {
        let dust = MilkyWay.create(clouds: 300, grains: 200, seed: 3)
        XCTAssertEqual(dust.catalog.count, 500)
        XCTAssertEqual(dust.cloud.filter { $0 }.count, 300)
        for w in dust.weight { XCTAssertTrue(w > 0 && w <= 1) }
        // The same seed gives the same picture.
        XCTAssertEqual(dust.catalog.raDeg[17], MilkyWay.create(clouds: 300, grains: 200, seed: 3).catalog.raDeg[17])
    }

    func testTheRandomNumbersAreThoseOfJava() {
        // new java.util.Random(0).nextDouble() and new java.util.Random(42).nextDouble().
        var zero = JavaRandom(seed: 0)
        XCTAssertEqual(zero.nextDouble(), 0.730967787376657, accuracy: 1e-15)
        var answer = JavaRandom(seed: 42)
        XCTAssertEqual(answer.nextDouble(), 0.7275636800328681, accuracy: 1e-15)
    }
}
