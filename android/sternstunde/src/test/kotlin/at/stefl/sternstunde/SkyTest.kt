package at.stefl.sternstunde

import at.stefl.almucantar.core.HorizonProfile
import at.stefl.almucantar.core.Horizontal
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

    @Test
    fun aPathSplitsAtTheHorizonWithoutAGap() {
        // A path that rises over the horizon at azimuth 90°, and sets at 270° (over north).
        val line =
            listOf(
                Horizontal(60.0, -6.0),
                Horizontal(80.0, -2.0),
                Horizontal(100.0, 2.0),
                Horizontal(180.0, 30.0),
                Horizontal(260.0, 2.0),
                Horizontal(280.0, -2.0),
                Horizontal(350.0, -10.0),
                Horizontal(10.0, -10.0),
            )
        val (visible, hidden) = splitAtHorizon(line, null)
        assertEquals(1, visible.size)
        assertEquals(2, hidden.size)
        // The crossings are halfway between the samples, on the horizon.
        assertEquals(90.0, visible[0].first().azimuthDeg, 1e-9)
        assertEquals(0.0, visible[0].first().altitudeDeg, 1e-9)
        assertEquals(270.0, visible[0].last().azimuthDeg, 1e-9)
        assertEquals(visible[0].first(), hidden[0].last())
        assertEquals(visible[0].last(), hidden[1].first())
        assertEquals(10.0, hidden[1].last().azimuthDeg, 1e-9)
    }

    @Test
    fun aCrossingOverNorthKeepsTheAzimuthInRange() {
        val (visible, hidden) =
            splitAtHorizon(listOf(Horizontal(350.0, -1.0), Horizontal(10.0, 1.0)), null)
        assertEquals(0.0, visible[0].first().azimuthDeg, 1e-9)
        assertEquals(0.0, hidden[0].last().azimuthDeg, 1e-9)
    }
}
