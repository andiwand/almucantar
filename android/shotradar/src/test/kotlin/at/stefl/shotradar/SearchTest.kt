package at.stefl.shotradar

import at.stefl.almucantar.core.EclipseKind
import at.stefl.almucantar.core.HorizonProfile
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.LunarEclipse
import at.stefl.almucantar.core.Observer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The pure parts of the search and the terrain, without the native library. The core has its own
// tests; the instrumented tests of the AAR check the binding.

class SearchTest {
    private fun skyline(
        altitude: (Double) -> Double,
        distanceM: Double = 20_000.0,
    ): HorizonProfile {
        val n = (360 / STEP_DEG).toInt()
        return HorizonProfile(
            STEP_DEG,
            DoubleArray(n) { altitude(it * STEP_DEG) },
            DoubleArray(n) { distanceM },
        )
    }

    @Test
    fun findPeaksFindsTheProminentSummits() {
        // Two summits on a flat skyline: a high one at 90°, a low one at 200°, and a bump that is
        // too small to count at 300°.
        val profile =
            skyline({ az ->
                1.0 +
                    3.0 * kotlin.math.exp(-((az - 90) * (az - 90)) / 4) +
                    1.0 * kotlin.math.exp(-((az - 200) * (az - 200)) / 4) +
                    0.2 * kotlin.math.exp(-((az - 300) * (az - 300)) / 4)
            })
        val peaks = findPeaks(profile)
        assertEquals(2, peaks.size)
        assertEquals(90.0, peaks[0].azimuthDeg, 0.01)
        assertEquals(200.0, peaks[1].azimuthDeg, 0.01)
        assertTrue(peaks[0].prominenceDeg > peaks[1].prominenceDeg)
        // A peak next to the spot is a hill, not a landmark.
        assertEquals(0, findPeaks(skyline({ if (it == 90.0) 5.0 else 1.0 }, 800.0)).size)
    }

    @Test
    fun combineKeepsTheHighestBand() {
        val near = skyline({ 1.0 }, 1_000.0)
        val far = skyline({ az -> if (az < 180) 2.0 else Double.NaN }, 50_000.0)
        val sky = combine(listOf(near, far), 500.0)
        assertEquals(2.0, profileAltitude(sky, 90.0), 1e-9)
        assertEquals(50_000.0, sky.distanceM[(90 / STEP_DEG).toInt()], 1e-9)
        assertEquals(1.0, profileAltitude(sky, 270.0), 1e-9)
        assertEquals(500.0, sky.observerElevationM, 1e-9)
    }

    @Test
    fun discCoverageGivesTheAreaOfTheOverlap() {
        assertEquals(0.0, discCoverage(0.6, 0.27, 0.27), 1e-12)
        assertEquals(1.0, discCoverage(0.0, 0.27, 0.28), 1e-12)
        // An annular eclipse: the smaller moon in the center covers the ratio of the areas.
        assertEquals(0.25 * 0.25 / (0.27 * 0.27), discCoverage(0.0, 0.27, 0.25), 1e-12)
        // Two equal discs with the centers one radius apart cover 39.1 % of each other.
        assertEquals(0.391, discCoverage(0.27, 0.27, 0.27), 0.001)
    }

    @Test
    fun lunarUmbraRisesToTheTotalPhase() {
        val total =
            LunarEclipse(EclipseKind.TOTAL, 1000.0, 1.0, 0.0, 200.0, 500.0, 1500.0, 1800.0, 2000.0)
        assertEquals(0.0, lunarUmbra(total, 100.0), 1e-12)
        assertEquals(0.5, lunarUmbra(total, 350.0), 1e-12)
        assertEquals(1.0, lunarUmbra(total, 1000.0), 1e-12)
        assertEquals(0.5, lunarUmbra(total, 1650.0), 1e-12)
        val nan = Double.NaN
        val penumbral =
            LunarEclipse(EclipseKind.PENUMBRAL, 1000.0, 0.0, 0.0, nan, nan, nan, nan, 2000.0)
        assertEquals(0.12, lunarUmbra(penumbral, 1000.0), 1e-12)
    }

    @Test
    fun destinationAndCompass() {
        val vienna = Observer(48.2082, 16.3738)
        val north = destination(vienna, 0.0, 111_195.0)
        assertEquals(49.2082, north.latitudeDeg, 0.001)
        assertEquals(16.3738, north.longitudeDeg, 1e-6)
        // Across the date line, the longitude stays in -180 to 180.
        assertEquals(-179.0, destination(Observer(0.0, 179.5), 90.0, 166_793.0).longitudeDeg, 0.01)
        assertEquals("ENE", compass(67.5))
        assertEquals("N", compass(359.0))
        assertEquals(30.0, separationDeg(Horizontal(0.0, 0.0), Horizontal(30.0, 0.0)), 1e-9)
    }
}
