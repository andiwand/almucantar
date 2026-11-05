package at.stefl.almucantar.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MilkyWayTest {
    @Test
    fun galacticDirectionsConvertToJ2000() {
        // The galactic center and the north galactic pole (IAU 1958, J2000).
        val (ra, dec) = MilkyWay.galacticToJ2000(0.0, 0.0)
        assertEquals(266.405, ra, 0.01)
        assertEquals(-28.936, dec, 0.01)
        assertEquals(27.128, MilkyWay.galacticToJ2000(123.0, 90.0).second, 0.01)
        // Deneb is at about l = 84.3°, b = 2.0°.
        val (denebRa, denebDec) = MilkyWay.galacticToJ2000(84.28, 1.99)
        assertEquals(310.358, denebRa, 0.05)
        assertEquals(45.28, denebDec, 0.05)
    }

    @Test
    fun theBandIsBrightestAtTheCenterAndTheRiftIsDark() {
        assertTrue(MilkyWay.bandBrightness(0.0) > MilkyWay.bandBrightness(120.0))
        assertTrue(MilkyWay.bandBrightness(75.0) > MilkyWay.bandBrightness(120.0))
        assertTrue(MilkyWay.riftDarkness(30.0, 2.0) > 0.4)
        assertEquals(0.0, MilkyWay.riftDarkness(150.0, 2.0), 0.0)
    }

    @Test
    fun theDustHasTheRequestedPoints() {
        val dust = MilkyWay.create(300, 200, 3)
        assertEquals(500, dust.catalog.size)
        assertEquals(300, dust.cloud.count { it })
        for (w in dust.weight) assertTrue(w > 0f && w <= 1f)
        // The same seed gives the same picture.
        assertEquals(dust.catalog.raDeg[17], MilkyWay.create(300, 200, 3).catalog.raDeg[17], 0.0)
    }
}
