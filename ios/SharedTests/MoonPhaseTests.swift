import Almucantar
import XCTest

@testable import AlmucantarShared

final class MoonPhaseTests: XCTestCase {
    /// The area of a polygon (shoelace formula).
    private func area(_ points: [CGPoint]) -> Double {
        var sum = 0.0
        for i in points.indices {
            let (a, b) = (points[i], points[(i + 1) % points.count])
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum / 2)
    }

    func testTheLitAreaIsTheIlluminatedFraction() {
        for fraction in [0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0] {
            // The polygon with 24 steps is a little smaller than the disc: 0.3 % at most.
            XCTAssertEqual(area(MoonPhase.litOutline(fraction)), .pi * fraction, accuracy: 0.01)
        }
    }

    func testACrescentIsOnTheSideOfTheSun() {
        XCTAssertTrue(MoonPhase.litOutline(0.2).allSatisfy { $0.x >= -1e-12 })
    }

    func testTheDirectionTowardTheSunIsOnTheGreatCircle() {
        let step = MoonPhase.towardSun(
            Horizontal(azimuthDeg: 180, altitudeDeg: 0), Horizontal(azimuthDeg: 90, altitudeDeg: 0))
        XCTAssertEqual(step.azimuthDeg, 179, accuracy: 1e-9)
        XCTAssertEqual(step.altitudeDeg, 0, accuracy: 1e-9)
        let down = MoonPhase.towardSun(
            Horizontal(azimuthDeg: 200, altitudeDeg: 30), Horizontal(azimuthDeg: 200, altitudeDeg: -20))
        XCTAssertEqual(down.azimuthDeg, 200, accuracy: 1e-9)
        XCTAssertEqual(down.altitudeDeg, 29, accuracy: 1e-9)
    }

    func testASunOppositeTheMoonGivesUp() {
        let step = MoonPhase.towardSun(
            Horizontal(azimuthDeg: 0, altitudeDeg: 10), Horizontal(azimuthDeg: 180, altitudeDeg: -10))
        XCTAssertEqual(step.azimuthDeg, 0, accuracy: 1e-9)
        XCTAssertEqual(step.altitudeDeg, 11, accuracy: 1e-9)
    }
}
