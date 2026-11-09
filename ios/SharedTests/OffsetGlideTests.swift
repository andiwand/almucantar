import XCTest

@testable import AlmucantarShared

final class OffsetGlideTests: XCTestCase {
    /// Follows `target` for `seconds` at 60 Hz.
    private func run(_ glide: inout OffsetGlide, _ target: Double, _ seconds: Double) {
        for _ in 0..<Int(seconds * 60) { glide.follow(target, dtS: 1 / 60.0) }
    }

    func testTheFirstOffsetJumps() {
        var glide = OffsetGlide()
        glide.follow(30, dtS: 1 / 60.0)
        XCTAssertEqual(glide.offsetDeg, 30, accuracy: 1e-9)
    }

    func testASmallStepGlides() {
        var glide = OffsetGlide()
        glide.jump(10)
        run(&glide, 20, 0.1)
        XCTAssertTrue(glide.offsetDeg > 10.5 && glide.offsetDeg < 13.0)
        run(&glide, 20, 2.0)
        XCTAssertEqual(glide.offsetDeg, 20, accuracy: 0.1)
    }

    func testAGlideTakesTheShortWayOverNorth() {
        var glide = OffsetGlide()
        glide.jump(355)
        run(&glide, 5, 0.2)
        XCTAssertTrue(glide.offsetDeg > 355 || glide.offsetDeg < 5)
        run(&glide, 5, 3.0)
        XCTAssertEqual(glide.offsetDeg, 5, accuracy: 0.1)
    }

    func testALargeStepJumps() {
        var glide = OffsetGlide()
        glide.jump(10)
        glide.follow(100, dtS: 1 / 60.0)
        XCTAssertEqual(glide.offsetDeg, 100, accuracy: 1e-9)
    }

    func testAPauseOfTheSensorsDoesNotJump() {
        var glide = OffsetGlide()
        glide.jump(10)
        glide.follow(20, dtS: 5.0)
        XCTAssertLessThan(glide.offsetDeg, 13)
    }
}
