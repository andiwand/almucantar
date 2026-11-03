package at.stefl.starradar

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StarDataTest {
    private val stars = StarData.decode(File("../../assets/stars/stars.json").readText())

    @Test
    fun theStarDataDecodesIntoColumnsOfTheSameLength() {
        val n = stars.size
        assertTrue(n > 5000)
        for (column in
            listOf(
                stars.catalog.raDeg.size,
                stars.catalog.decDeg.size,
                stars.catalog.pmRaArcsecPerYear.size,
                stars.catalog.pmDecArcsecPerYear.size,
                stars.bv.size,
            )) {
            assertEquals(n, column)
        }
        // Sorted by magnitude: Sirius first.
        assertEquals("Sirius", stars.names[0])
        assertEquals(0, stars.indexOf("Sirius"))
        assertTrue(stars.mag[0] < stars.mag[1])
        assertEquals(89, stars.constellations.size)
        for (c in stars.constellations) for (line in c.lines) for (i in line) assertTrue(i < n)
        val orion = stars.constellations.first { it.id == "Ori" }
        assertEquals("Orion", orion.name)
        val names = orion.lines.flatMap { it.toList() }.mapNotNull { stars.names[it] }.toSet()
        assertTrue("Betelgeuse" in names)
        assertTrue("Rigel" in names)
    }

    @Test
    fun anUnknownVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StarData.decode("""{"version": 2}""")
        }
    }

    @Test
    fun theSkyShowsFainterStarsAsItGetsDarker() {
        assertTrue(StarLook.limitingMagnitude(10.0) < StarLook.limitingMagnitude(-3.0))
        assertTrue(StarLook.limitingMagnitude(-3.0) < StarLook.limitingMagnitude(-9.0))
        assertTrue(StarLook.limitingMagnitude(-9.0) < StarLook.limitingMagnitude(-15.0))
        assertEquals(6.0, StarLook.limitingMagnitude(-30.0), 0.0)
        // Inside the twilight, the limit has no jumps (the same rule as the web app).
        for (limit in listOf(-6.0, -12.0, -18.0)) {
            val below = StarLook.limitingMagnitude(limit - 1e-9)
            val above = StarLook.limitingMagnitude(limit + 1e-9)
            assertTrue(abs(below - above) < 1e-6)
        }
        assertEquals("Night", StarLook.darkness(-20.0))
        assertEquals("Nautical twilight", StarLook.darkness(-7.0))
    }

    @Test
    fun aBrighterStarIsLargerAndTheColorsGoFromBlueToOrange() {
        assertTrue(StarLook.radiusDp(-1.46f) > StarLook.radiusDp(2f))
        assertTrue(StarLook.radiusDp(6f) > 0)
        val blue = StarLook.color(-0.3f)
        val orange = StarLook.color(1.85f)
        assertTrue(blue.blue > blue.red)
        assertTrue(orange.red > orange.blue)
        assertEquals(StarLook.color(-0.4f), StarLook.color(-5f))
    }
}
