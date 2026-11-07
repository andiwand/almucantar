package at.stefl.alidade

import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.Observer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The favorites and the cache of the results, without the native library. A shot with an eclipse
// needs the core to load, so these tests use shots without one.

class FavoritesTest {
    private val spot = Observer(47.045689, 12.607105)

    private fun shot(target: Target? = null) =
        Shot(
            SubjectId.MOON,
            1795536480.1,
            spot,
            target,
            0.12,
            Horizontal(77.5, 1.25),
            1795536480.1 - 3600,
            1795536480.1 + 3600,
            riseUtc = 1795535000.0,
        )

    @Test
    fun aShotComesBackFromJson() {
        val target =
            Target(
                Horizontal(71.2, 9.8),
                "Landmark near Großglockner",
                7350.0,
                3798.0,
                Observer(47.07457, 12.69434),
            )
        for (original in listOf(shot(), shot(target))) {
            val back = shotOf(shotJson(original))
            assertEquals(original, back)
            assertEquals(shotKey(original), shotKey(back!!))
        }
    }

    @Test
    fun aDamagedShotIsLeftOut() {
        val json = shotJson(shot()).apply { remove("spot") }
        assertNull(shotOf(json))
        val data = favoritesOf("""{"places":[{"lat":1}],"shots":[${json}]}""")
        assertTrue(data.places.isEmpty())
        assertTrue(data.shots.isEmpty())
        assertTrue(favoritesOf("not json").shots.isEmpty())
    }

    @Test
    fun theFavoritesComeBackInTheirOrder() {
        val places =
            listOf(
                Point(47.0, 12.0, "Kals am Großglockner", 2482.1),
                Point(48.2, 16.37, "", null),
            )
        val shots = listOf(shot(), shot().copy(subject = SubjectId.SUN))
        val data = favoritesOf(favoritesJson(places, shots))
        assertEquals(places, data.places)
        assertEquals(shots, data.shots)
    }

    @Test
    fun theCacheKeepsTheRecentResultsForTheirTime() {
        var now = 0.0
        val cache = ResultCache(2, 60.0) { now }
        cache.put(listOf("a", 1), "first")
        cache.put(listOf("b", 2), "second")
        // An equal key finds the entry.
        assertEquals("first", cache.get<String>(listOf("a", 1)))
        // The third entry pushes out the least recently used one, which is now "b".
        cache.put(listOf("c", 3), "third")
        assertNull(cache.get<String>(listOf("b", 2)))
        assertEquals("first", cache.get<String>(listOf("a", 1)))
        // An old entry goes.
        now = 61.0
        assertNull(cache.get<String>(listOf("a", 1)))
    }
}
