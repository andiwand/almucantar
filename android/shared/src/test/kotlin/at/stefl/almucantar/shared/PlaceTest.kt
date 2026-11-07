package at.stefl.almucantar.shared

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

    /** The zone.tab of the app, from the root of the repository. */
    private val zones =
        TimeZonePlaces.parse(java.io.File("../../assets/tzdata/zone.tab").readLines().asSequence())
            .toList()

    @Test
    fun aTimeZoneGivesItsMainCity() {
        assertEquals("Vienna", TimeZonePlaces.byZone(zones, "Europe/Vienna")?.city)
        assertEquals("New York", TimeZonePlaces.byZone(zones, "America/New_York")?.city)
        assertNull(TimeZonePlaces.byZone(zones, "Etc/UTC"))
    }

    @Test
    fun aCountryGivesTheCityWithTheOffsetOfTheDevice() {
        assertEquals("Vienna", TimeZonePlaces.byCountry(zones, "at", 0)?.city)
        // The United States have many zones: the offset of the device chooses one.
        val now = java.time.Instant.now()
        val pacific = java.time.ZoneId.of("America/Los_Angeles").rules.getOffset(now).totalSeconds
        assertEquals("Los Angeles", TimeZonePlaces.byCountry(zones, "US", pacific)?.city)
        assertNull(TimeZonePlaces.byCountry(zones, "XX", 0))
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
