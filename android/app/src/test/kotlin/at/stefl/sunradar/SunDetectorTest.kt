package at.stefl.sunradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunDetectorTest {
    private val width = 640
    private val height = 480
    private val stride = 672 // a row stride larger than the width, as in real frames

    private fun image(background: Int = 90): ByteArray =
        ByteArray(stride * height) { background.toByte() }

    private fun disc(luma: ByteArray, cx: Int, cy: Int, r: Int, value: Int = 255) {
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            if (
                x in 0 until width &&
                    y in 0 until height &&
                    (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
            ) {
                luma[y * stride + x] = value.toByte()
            }
        }
    }

    @Test
    fun findsTheCenterOfADisc() {
        val luma = image()
        disc(luma, 400, 150, 12)
        val blobs = SunDetector.findBlobs(luma, width, height, stride)
        assertEquals(1, blobs.size)
        // The sampling grid has a step of 4 px, so the center is within about 2 px.
        assertEquals(400.0, blobs[0].centerX, 2.5)
        assertEquals(150.0, blobs[0].centerY, 2.5)
        assertEquals(12.0, blobs[0].radius, 4.0)
    }

    @Test
    fun separatesTwoRegionsAndIgnoresDarkerPixels() {
        val luma = image()
        disc(luma, 100, 100, 10)
        disc(luma, 500, 300, 20)
        disc(luma, 300, 400, 15, value = 240) // bright, but not saturated
        val blobs = SunDetector.findBlobs(luma, width, height, stride).sortedBy { it.pixels }
        assertEquals(2, blobs.size)
        assertEquals(100.0, blobs[0].centerX, 2.5)
        assertEquals(500.0, blobs[1].centerX, 2.5)
        assertTrue(blobs[1].pixels > 3 * blobs[0].pixels)
    }

    @Test
    fun aRegionAtTheRightEdgeDoesNotWrapToTheNextRow() {
        val luma = image()
        disc(luma, width - 3, 200, 6)
        disc(luma, 2, 204, 6)
        assertEquals(2, SunDetector.findBlobs(luma, width, height, stride).size)
    }

    @Test
    fun aDarkImageHasNoRegions() {
        assertTrue(SunDetector.findBlobs(image(), width, height, stride).isEmpty())
    }
}
