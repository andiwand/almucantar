package at.stefl.sunradar

import at.stefl.sunradar.core.Observer
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Test

class MapTest {
    @Test
    fun webMercatorIsInvertible() {
        for (zoom in listOf(3, 12, 19)) {
            for ((lat, lon) in
                listOf(48.2082 to 16.3738, -33.8688 to 151.2093, 69.6492 to 18.9553)) {
                assertEquals(lat, latitude(worldY(lat, zoom), zoom), 1e-9)
                assertEquals(lon, longitude(worldX(lon, zoom), zoom), 1e-9)
            }
        }
    }

    @Test
    fun theTileOfVienna() {
        // The OSM tile formula: x = floor((lon + 180) / 360 * 2^z), and y from the Mercator
        // formula.
        // Vienna (48.2082 N, 16.3738 E) at zoom 16 is the tile 16/35748/22724 (computed with the
        // formula of the OSM wiki, independently of this code).
        assertEquals(35748, floor(worldX(16.3738, 16) / 256).toInt())
        assertEquals(22724, floor(worldY(48.2082, 16) / 256).toInt())
    }

    @Test
    fun bearingAndDistance() {
        val here = Observer(48.0, 16.0)
        assertEquals(0.0, Geo.bearingDeg(here, Observer(48.01, 16.0)), 1e-6)
        assertEquals(180.0, Geo.bearingDeg(here, Observer(47.99, 16.0)), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(here, Observer(48.0, 16.01)), 0.01)
        assertEquals(270.0, Geo.bearingDeg(here, Observer(48.0, 15.99)), 0.01)
        // 0.01° of latitude is about 1112 m.
        assertEquals(1112.0, Geo.distanceM(here, Observer(48.01, 16.0)), 1.0)
    }
}
