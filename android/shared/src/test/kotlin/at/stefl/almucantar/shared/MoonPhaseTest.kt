package at.stefl.almucantar.shared

import at.stefl.almucantar.core.Horizontal
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonPhaseTest {
    /** The area of a polygon (shoelace formula). */
    private fun area(points: List<Pair<Double, Double>>): Double =
        abs(
            points.indices.sumOf {
                val (x0, y0) = points[it]
                val (x1, y1) = points[(it + 1) % points.size]
                x0 * y1 - x1 * y0
            } / 2
        )

    @Test
    fun theLitAreaIsTheIlluminatedFraction() {
        for (fraction in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0)) {
            // The polygon with 24 steps is a little smaller than the disc: 0.3 % at most.
            assertEquals(PI * fraction, area(MoonPhase.litOutline(fraction)), 0.01)
        }
    }

    @Test
    fun aCrescentIsOnTheSideOfTheSun() {
        val outline = MoonPhase.litOutline(0.2)
        assertTrue(outline.all { (x, _) -> x >= -1e-12 })
    }

    @Test
    fun theDirectionTowardTheSunIsOnTheGreatCircle() {
        // The sun is due east of the moon on the horizon: the step goes east along the horizon.
        val step = MoonPhase.towardSun(Horizontal(180.0, 0.0), Horizontal(90.0, 0.0))
        assertEquals(179.0, step.azimuthDeg, 1e-9)
        assertEquals(0.0, step.altitudeDeg, 1e-9)
        // The sun is below the moon on the same azimuth: the step goes down.
        val down = MoonPhase.towardSun(Horizontal(200.0, 30.0), Horizontal(200.0, -20.0))
        assertEquals(200.0, down.azimuthDeg, 1e-9)
        assertEquals(29.0, down.altitudeDeg, 1e-9)
    }

    @Test
    fun aSunOppositeTheMoonGivesUp() {
        val step = MoonPhase.towardSun(Horizontal(0.0, 10.0), Horizontal(180.0, -10.0))
        assertEquals(0.0, step.azimuthDeg, 1e-9)
        assertEquals(11.0, step.altitudeDeg, 1e-9)
    }
}
