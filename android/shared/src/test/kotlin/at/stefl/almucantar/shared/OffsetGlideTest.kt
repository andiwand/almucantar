package at.stefl.almucantar.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OffsetGlideTest {
    /** Follows [target] for [seconds] at 60 Hz. */
    private fun OffsetGlide.run(target: Double, seconds: Double) {
        repeat((seconds * 60).toInt()) { follow(target, 1 / 60.0) }
    }

    @Test
    fun theFirstOffsetJumps() {
        val glide = OffsetGlide()
        glide.follow(30.0, 1 / 60.0)
        assertEquals(30.0, glide.offsetDeg, 1e-9)
    }

    @Test
    fun aSmallStepGlides() {
        val glide = OffsetGlide().apply { jump(10.0) }
        glide.run(20.0, 0.1)
        assertTrue(glide.offsetDeg > 10.5 && glide.offsetDeg < 13.0)
        glide.run(20.0, 2.0)
        assertEquals(20.0, glide.offsetDeg, 0.1)
    }

    @Test
    fun aGlideTakesTheShortWayOverNorth() {
        val glide = OffsetGlide().apply { jump(355.0) }
        glide.run(5.0, 0.2)
        assertTrue(glide.offsetDeg > 355.0 || glide.offsetDeg < 5.0)
        glide.run(5.0, 3.0)
        assertEquals(5.0, glide.offsetDeg, 0.1)
    }

    @Test
    fun aLargeStepJumps() {
        val glide = OffsetGlide().apply { jump(10.0) }
        glide.follow(100.0, 1 / 60.0)
        assertEquals(100.0, glide.offsetDeg, 1e-9)
    }

    @Test
    fun aPauseOfTheSensorsDoesNotJump() {
        val glide = OffsetGlide().apply { jump(10.0) }
        glide.follow(20.0, 5.0)
        assertTrue(glide.offsetDeg < 13.0)
    }
}
