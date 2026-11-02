package at.stefl.sunradar

import at.stefl.sunradar.core.Observer
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseMapTest {
    companion object {
        /** The real asset, decoded once for all tests. The tests run in `android/app`. */
        private val map: BaseMap by lazy {
            BaseMap.decode(File("../../assets/basemap/basemap.json").readBytes())
        }
        private val utc: ZoneId = ZoneId.of("UTC")
    }

    @Test
    fun decodesTheLayersAndThePlaces() {
        assertTrue(map.coastline.size > 1000)
        assertTrue(map.borders.isNotEmpty() && map.lakes.isNotEmpty() && map.rivers.isNotEmpty())
        assertTrue(map.places.size > 7000)
        assertEquals("Map: Natural Earth (public domain)", map.attribution)
        val vienna = map.places.first { it.name == "Vienna" }
        assertEquals(48.2, vienna.latitudeDeg, 0.05)
        assertEquals(16.37, vienna.longitudeDeg, 0.05)
        assertEquals("Europe/Vienna", vienna.timeZone)
        assertEquals("Austria", vienna.country)
        // The delta decode: all points of all lines are on the world (0 to 1).
        for (line in map.coastline) {
            assertTrue(line.left >= 0f && line.right <= 1f && line.top >= 0f && line.bottom <= 1f)
        }
    }

    @Test
    fun decodesTheDeltasOfASmallMap() {
        val json =
            """{"version":1,"scale":10,"attribution":"A","source":"x","layers":
            {"coastline":[[0,0,100,0,0,-50]],"borders":[],"lakes":[],"rivers":[]},
            "places":[["Zürich",85,474,3,"Switzerland","Europe/Zurich"]]}"""
        val small = BaseMap.decode(json.toByteArray())
        val p = small.coastline.single().points
        assertEquals(mercatorX(0.0), p[0].toDouble(), 1e-6)
        assertEquals(mercatorX(10.0), p[2].toDouble(), 1e-6)
        assertEquals(mercatorY(-5.0), p[5].toDouble(), 1e-6)
        assertEquals("Zürich", small.places.single().name)
        assertEquals(47.4, small.places.single().latitudeDeg, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnotherVersion() {
        BaseMap.decode("""{"version":2,"scale":1,"layers":{},"places":[]}""".toByteArray())
    }

    @Test
    fun theSearchIgnoresAccentsAndGivesTheLargestFirst() {
        assertEquals("Zürich", map.search("zurich").first().name)
        assertEquals("Vienna", map.search("Vien").first().name)
        assertEquals("Sydney", map.search("sydney").first().name)
        assertTrue(map.search("  ").isEmpty())
        // A place whose name contains the text comes after the places whose name starts with it.
        val results = map.search("burg")
        val firstContains = results.indexOfFirst { !fold(it.name).startsWith("burg") }
        if (firstContains >= 0) {
            assertTrue(results.drop(firstContains).none { fold(it.name).startsWith("burg") })
        }
        assertTrue(map.search("a", limit = 3).size == 3)
    }

    @Test
    fun aDroppedPlaceGetsTheNameAndTheZoneOfTheNearestPlace() {
        val tyrol = Observer(47.1, 11.6)
        assertEquals("Austria", map.nearest(tyrol)?.country)
        assertEquals(ZoneId.of("Europe/Vienna"), map.timeZoneAt(tyrol, utc))
        assertTrue(droppedPlaceName(map, tyrol, null).startsWith("near "))
        assertTrue(droppedPlaceName(map, tyrol, null).endsWith("47.100°, 11.600°"))
        assertEquals(
            "Sydney · -33.870°, 151.210°",
            droppedPlaceName(map, Observer(-33.87, 151.21), "Sydney"),
        )
        // Fiji is next to the date line.
        assertEquals(ZoneId.of("Pacific/Fiji"), map.timeZoneAt(Observer(-17.8, 178.0), utc))
        assertEquals(ZoneId.of("Australia/Sydney"), map.timeZoneAt(Observer(-33.87, 151.21), utc))
    }

    @Test
    fun aZoneThatJavaTimeDoesNotKnowGivesTheFallback() {
        val json =
            """{"version":1,"scale":1,"layers":{},"places":[["X",0,0,0,"C","Mars/Olympus"],
            ["Y",50,0,0,"C",""]]}"""
        val small = BaseMap.decode(json.toByteArray())
        assertEquals(utc, small.timeZoneAt(Observer(0.0, 1.0), utc))
        assertEquals("Y", small.nearest(Observer(0.0, 49.0))?.name)
        assertEquals(null, zoneOrNull("Mars/Olympus"))
        assertEquals(null, zoneOrNull(""))
    }

    @Test
    fun webMercatorIsInvertibleAndWraps() {
        assertEquals(11.39, mercatorLongitude(mercatorX(11.39)), 1e-9)
        assertEquals(47.27, mercatorLatitude(mercatorY(47.27)), 1e-9)
        // A point one world to the east is the same longitude.
        assertEquals(-179.0, mercatorLongitude(mercatorX(-179.0) + 1), 1e-9)
        assertEquals(179.0, mercatorLongitude(mercatorX(179.0) - 1), 1e-9)
    }

    @Test
    fun aDayWithAChangeOfDstHas23Or25Hours() {
        val vienna = ZoneId.of("Europe/Vienna")
        val (autumnStart, autumnEnd) = dayBounds(LocalDate.of(2026, 10, 25), vienna)
        assertEquals(25 * 3600.0, autumnEnd - autumnStart, 0.0)
        val (springStart, springEnd) = dayBounds(LocalDate.of(2027, 3, 28), vienna)
        assertEquals(23 * 3600.0, springEnd - springStart, 0.0)
        // Sydney changes on other dates: to summer time on 2026-10-04.
        val sydney = ZoneId.of("Australia/Sydney")
        val (s, e) = dayBounds(LocalDate.of(2026, 10, 4), sydney)
        assertEquals(23 * 3600.0, e - s, 0.0)
    }

    @Test
    fun theDayOfThePlaceCanDifferFromTheDayOfTheDeviceAcrossTheDateLine() {
        // 2026-11-02 20:00 UTC: in Vienna it is 2026-11-02, in Auckland already 2026-11-03.
        val now = ZonedDateTime.of(2026, 11, 2, 20, 0, 0, 0, utc).toEpochSecond().toDouble()
        assertEquals(LocalDate.of(2026, 11, 2), localDate(now, ZoneId.of("Europe/Vienna")))
        val auckland = ZoneId.of("Pacific/Auckland")
        val date = localDate(now, auckland)
        assertEquals(LocalDate.of(2026, 11, 3), date)
        // The day starts at midnight of Auckland (UTC+13): 2026-11-02 11:00 UTC.
        val (start, _) = dayBounds(date, auckland)
        assertEquals(Instant.parse("2026-11-02T11:00:00Z").epochSecond.toDouble(), start, 0.0)
        // Samoa and American Samoa are near each other, but one day apart.
        val apia = localDate(now, ZoneId.of("Pacific/Apia"))
        val pagoPago = localDate(now, ZoneId.of("Pacific/Pago_Pago"))
        assertEquals(1L, pagoPago.until(apia).days.toLong())
        assertFalse(apia == pagoPago)
    }

    @Test
    fun theWallClockTimeIsInTheZoneOfThePlace() {
        val sydney = ZoneId.of("Australia/Sydney")
        val t = wallClockTime(LocalDate.of(2026, 11, 3), 7 * 60, sydney)
        // 07:00 in Sydney (UTC+11 in summer) is 20:00 UTC on the day before.
        assertEquals(Instant.parse("2026-11-02T20:00:00Z").epochSecond.toDouble(), t, 0.0)
    }
}
