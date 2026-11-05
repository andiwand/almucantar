package at.stefl.starradar

import at.stefl.almucantar.core.HorizonProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class SkyTest {
    @Test
    fun theHorizonInterpolatesTheTerrainAndIsZeroWithoutData() {
        // Four directions, 90° apart: north 2°, east 4°, south without data, west 0°.
        val profile =
            HorizonProfile(90.0, doubleArrayOf(2.0, 4.0, Double.NaN, 0.0), DoubleArray(4) { 1e4 })
        assertEquals(0.0, horizonAt(null, 45.0), 0.0)
        assertEquals(2.0, horizonAt(profile, 0.0), 1e-12)
        assertEquals(3.0, horizonAt(profile, 45.0), 1e-12)
        assertEquals(3.0, horizonAt(profile, 45.0 - 360), 1e-12)
        // West to north goes across 360°.
        assertEquals(1.0, horizonAt(profile, 315.0), 1e-12)
        assertEquals(2.0, horizonAt(profile, 360.0), 1e-12)
        // A neighbor without data gives no terrain.
        assertEquals(0.0, horizonAt(profile, 100.0), 0.0)
        assertEquals(0.0, horizonAt(profile, 200.0), 0.0)
    }
}
