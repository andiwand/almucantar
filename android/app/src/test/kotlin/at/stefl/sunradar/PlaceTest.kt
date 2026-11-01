package at.stefl.sunradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceTest {
    @Test
    fun parsesIso6709WithMinutes() {
        // Europe/Vienna in zone.tab.
        val o = TimeZonePlaces.parseIso6709("+4813+01620")!!
        assertEquals(48 + 13 / 60.0, o.latitudeDeg, 1e-12)
        assertEquals(16 + 20 / 60.0, o.longitudeDeg, 1e-12)
    }

    @Test
    fun parsesIso6709WithSecondsAndNegativeValues() {
        // America/New_York in zone.tab.
        val o = TimeZonePlaces.parseIso6709("+404251-0740023")!!
        assertEquals(40 + 42 / 60.0 + 51 / 3600.0, o.latitudeDeg, 1e-12)
        assertEquals(-(74 + 0 / 60.0 + 23 / 3600.0), o.longitudeDeg, 1e-12)
        val south = TimeZonePlaces.parseIso6709("-3352+15113")!!
        assertEquals(-(33 + 52 / 60.0), south.latitudeDeg, 1e-12)
    }

    @Test
    fun rejectsInvalidIso6709() {
        assertNull(TimeZonePlaces.parseIso6709("+48"))
        assertNull(TimeZonePlaces.parseIso6709("+481+0162"))
        assertNull(TimeZonePlaces.parseIso6709("+48xx+01620"))
    }

    @Test
    fun errorScaleRoundsUpToAPowerOfTen() {
        assertEquals("±1 m (sky < 0.01°)", errorScale(0.3))
        assertEquals("±10 m (sky < 0.01°)", errorScale(4.0))
        assertEquals("±100 m (sky < 0.01°)", errorScale(100.0))
        assertEquals("±1 km (sky < 0.01°)", errorScale(101.0))
        assertEquals("±10 km (sky 0.09°)", errorScale(2000.0))
        assertEquals("±100 km (sky 0.90°)", errorScale(5e6))
    }
}
