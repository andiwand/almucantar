package at.stefl.sunradar

/** A connected region of saturated pixels, in the coordinates of the image buffer. */
data class BrightBlob(
    val centerX: Double,
    val centerY: Double,
    /** The half size of the bounding box, in buffer pixels. */
    val radius: Double,
    /** The number of saturated pixels (on the full grid, estimated from the sampled grid). */
    val pixels: Int,
)

/**
 * Finds the connected regions of saturated pixels in a luminance image (the Y plane of a camera
 * frame). The sun saturates the sensor, so it is one of these regions. The function samples every
 * [step]-th pixel in both directions, which is sufficient for the sun and fast enough for each
 * frame. Pure Kotlin, so it is testable without Android.
 */
object SunDetector {
    fun findBlobs(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        threshold: Int = 250,
        step: Int = 4,
    ): List<BrightBlob> {
        val gw = width / step
        val gh = height / step
        if (gw == 0 || gh == 0) return emptyList()
        val bright =
            BooleanArray(gw * gh) { i ->
                val x = (i % gw) * step
                val y = (i / gw) * step
                (luma[y * rowStride + x].toInt() and 0xFF) >= threshold
            }
        val seen = BooleanArray(gw * gh)
        val blobs = mutableListOf<BrightBlob>()
        val queue = IntArray(gw * gh)
        for (start in bright.indices) {
            if (!bright[start] || seen[start]) continue
            // A breadth-first search over the 4-neighbours.
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var count = 0
            var sumX = 0.0
            var sumY = 0.0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            while (head < tail) {
                val i = queue[head++]
                val x = i % gw
                val y = i / gw
                count++
                sumX += x
                sumY += y
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
                for (n in
                    intArrayOf(
                        if (x > 0) i - 1 else -1,
                        if (x < gw - 1) i + 1 else -1,
                        i - gw,
                        i + gw,
                    )) {
                    if (n < 0 || n >= bright.size || seen[n] || !bright[n]) continue
                    seen[n] = true
                    queue[tail++] = n
                }
            }
            blobs +=
                BrightBlob(
                    centerX = (sumX / count + 0.5) * step,
                    centerY = (sumY / count + 0.5) * step,
                    radius = (maxOf(maxX - minX, maxY - minY) + 1) * step / 2.0,
                    pixels = count * step * step,
                )
        }
        return blobs
    }
}
