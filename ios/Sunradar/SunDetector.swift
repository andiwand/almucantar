import Foundation

/// A connected region of saturated pixels, in the coordinates of the image buffer.
struct BrightBlob: Equatable {
    var centerX: Double
    var centerY: Double
    /// The half size of the bounding box, in buffer pixels.
    var radius: Double
    /// The number of saturated pixels (on the full grid, estimated from the sampled grid).
    var pixels: Int
}

/// Finds the connected regions of saturated pixels in a luminance image (the Y plane of a camera
/// frame). The sun saturates the sensor, so it is one of these regions. The function samples each
/// `step`-th pixel in both directions, which is sufficient for the sun and fast enough for each
/// frame. The same algorithm as SunDetector.kt of the Android app.
enum SunDetector {
    static func findBlobs(
        _ luma: UnsafeBufferPointer<UInt8>, width: Int, height: Int, rowStride: Int, threshold: UInt8 = 250,
        step: Int = 4
    ) -> [BrightBlob] {
        let gw = width / step
        let gh = height / step
        guard gw > 0, gh > 0 else { return [] }
        let bright = (0..<gw * gh).map { luma[($0 / gw) * step * rowStride + ($0 % gw) * step] >= threshold }
        var seen = [Bool](repeating: false, count: gw * gh)
        var queue = [Int](repeating: 0, count: gw * gh)
        var blobs: [BrightBlob] = []
        for start in bright.indices where bright[start] && !seen[start] {
            // A breadth-first search over the 4-neighbours.
            var head = 0
            var tail = 1
            queue[0] = start
            seen[start] = true
            var sumX = 0
            var sumY = 0
            var (minX, maxX, minY, maxY) = (Int.max, Int.min, Int.max, Int.min)
            while head < tail {
                let i = queue[head]
                head += 1
                let (x, y) = (i % gw, i / gw)
                sumX += x
                sumY += y
                (minX, maxX, minY, maxY) = (min(minX, x), max(maxX, x), min(minY, y), max(maxY, y))
                // No wrap from the end of one row to the start of the next.
                let neighbours = [x > 0 ? i - 1 : -1, x < gw - 1 ? i + 1 : -1, i - gw, i + gw]
                for n in neighbours where n >= 0 && n < bright.count && bright[n] && !seen[n] {
                    seen[n] = true
                    queue[tail] = n
                    tail += 1
                }
            }
            let count = Double(tail)
            blobs.append(
                BrightBlob(
                    centerX: (Double(sumX) / count + 0.5) * Double(step),
                    centerY: (Double(sumY) / count + 0.5) * Double(step),
                    radius: Double(max(maxX - minX, maxY - minY) + 1) * Double(step) / 2,
                    pixels: tail * step * step))
        }
        return blobs
    }
}
