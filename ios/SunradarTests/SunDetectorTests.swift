import Almucantar
import XCTest

@testable import SunradarApp

final class SunDetectorTests: XCTestCase {
    private let width = 640
    private let height = 480
    private let stride = 672  // a row stride larger than the width, as in real frames

    private func image(_ background: UInt8 = 90) -> [UInt8] { [UInt8](repeating: background, count: stride * height) }

    private func disc(_ luma: inout [UInt8], _ cx: Int, _ cy: Int, _ r: Int, value: UInt8 = 255) {
        for y in cy - r...cy + r {
            for x in cx - r...cx + r
            where x >= 0 && x < width && y >= 0 && y < height && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r {
                luma[y * stride + x] = value
            }
        }
    }

    private func blobs(_ luma: [UInt8]) -> [BrightBlob] {
        luma.withUnsafeBufferPointer { SunDetector.findBlobs($0, width: width, height: height, rowStride: stride) }
    }

    func testFindsTheCenterOfADisc() {
        var luma = image()
        disc(&luma, 400, 150, 12)
        let found = blobs(luma)
        XCTAssertEqual(found.count, 1)
        // The sampling grid has a step of 4 px, so the center is within about 2 px.
        XCTAssertEqual(found[0].centerX, 400, accuracy: 2.5)
        XCTAssertEqual(found[0].centerY, 150, accuracy: 2.5)
        XCTAssertEqual(found[0].radius, 12, accuracy: 4)
    }

    func testSeparatesTwoRegionsAndIgnoresDarkerPixels() {
        var luma = image()
        disc(&luma, 100, 100, 10)
        disc(&luma, 500, 300, 20)
        disc(&luma, 300, 400, 15, value: 240)  // bright, but not saturated
        let found = blobs(luma).sorted { $0.pixels < $1.pixels }
        XCTAssertEqual(found.count, 2)
        XCTAssertEqual(found[0].centerX, 100, accuracy: 2.5)
        XCTAssertEqual(found[1].centerX, 500, accuracy: 2.5)
        XCTAssertGreaterThan(found[1].pixels, 3 * found[0].pixels)
    }

    func testARegionAtTheRightEdgeDoesNotWrapToTheNextRow() {
        var luma = image()
        disc(&luma, width - 3, 200, 6)
        disc(&luma, 2, 204, 6)
        XCTAssertEqual(blobs(luma).count, 2)
    }

    func testADarkImageHasNoRegions() { XCTAssertTrue(blobs(image()).isEmpty) }

    func testBearingAndDistance() {
        let here = Observer(latitudeDeg: 48, longitudeDeg: 16)
        XCTAssertEqual(Geo.bearingDeg(from: here, to: Observer(latitudeDeg: 48.01, longitudeDeg: 16)), 0, accuracy: 1e-6)
        XCTAssertEqual(Geo.bearingDeg(from: here, to: Observer(latitudeDeg: 47.99, longitudeDeg: 16)), 180, accuracy: 1e-6)
        XCTAssertEqual(Geo.bearingDeg(from: here, to: Observer(latitudeDeg: 48, longitudeDeg: 16.01)), 90, accuracy: 0.01)
        XCTAssertEqual(Geo.bearingDeg(from: here, to: Observer(latitudeDeg: 48, longitudeDeg: 15.99)), 270, accuracy: 0.01)
        // 0.01° of latitude is about 1112 m.
        XCTAssertEqual(Geo.distanceM(from: here, to: Observer(latitudeDeg: 48.01, longitudeDeg: 16)), 1112, accuracy: 1)
    }

    func testAngleBetween() {
        let a = Horizontal(azimuthDeg: 10, altitudeDeg: 20)
        XCTAssertEqual(Geo.angleBetweenDeg(a, a), 0, accuracy: 1e-6)
        XCTAssertEqual(Geo.angleBetweenDeg(a, Horizontal(azimuthDeg: 10, altitudeDeg: 25)), 5, accuracy: 1e-9)
        XCTAssertEqual(
            Geo.angleBetweenDeg(Horizontal(azimuthDeg: 0, altitudeDeg: 0), Horizontal(azimuthDeg: 90, altitudeDeg: 0)), 90,
            accuracy: 1e-9)
    }
}
