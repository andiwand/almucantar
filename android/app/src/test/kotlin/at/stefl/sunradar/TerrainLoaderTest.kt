package at.stefl.sunradar

import at.stefl.almucantar.core.ElevationTile
import at.stefl.almucantar.core.HorizonOptions
import at.stefl.almucantar.core.Observer
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainLoaderTest {
    private val dir: File = Files.createTempDirectory("dem").toFile()
    private val server = FakeTileServer()
    private val cache = DemCache(dir, server, maxBytes = 100_000_000, source = FakeSource)

    // The own tile, a near tile, a far tile, and a tile in the sea (no file on the server).
    private val own = ElevationTile(47, 11, 0.0)
    private val near = ElevationTile(47, 12, 10_000.0)
    private val far = ElevationTile(48, 11, 60_000.0)
    private val sea = ElevationTile(46, 10, 80_000.0)
    private val tiles = listOf(own, near, far, sea)
    private val place = Observer(47.26, 11.39)

    private fun name(tile: ElevationTile) = DemCache.tileName(tile.latitudeDeg, tile.longitudeDeg)

    /**
     * Puts the tiles on the server. The label of a tile is the highest altitude of its terrain, so
     * the fake profile is the highest label.
     */
    private fun serve(altitudeDeg: String) {
        for (tile in listOf(own, far)) server.files[name(tile)] = fakeTile("0.2", 30_000, 100_000)
        server.files[name(near)] = fakeTile(altitudeDeg, 30_000, 100_000)
    }

    /** The options of each call of the threshold check. */
    private val checkOptions = mutableListOf<HorizonOptions>()

    private fun loader(readFull: Boolean = true, cache: DemCache = this.cache) =
        TerrainLoader(
            cache,
            tiles,
            read = ::readFakeTile,
            maxAltitudeDeg = { grids, _, options ->
                checkOptions += options
                grids.maxOfOrNull { it.substringBefore('/').toDouble() } ?: Double.NaN
            },
            readFull = readFull,
        )

    /** The tiles of the requests for a full file (without Range). */
    private fun fullRequests() = server.requests.filter { it.range == null }.map { it.tile }

    @After
    fun deleteDir() {
        dir.deleteRecursively()
    }

    @Test
    fun aSurfaceModelClearsTheNearPath() {
        assertEquals(HorizonOptions(minDistanceM = 2_000.0), horizonOptions(Copernicus))
        assertEquals(NEAR_CLEARANCE_M, horizonOptions(Copernicus).minDistanceM, 0.0)
    }

    @Test
    fun aBareEarthModelUsesAllTerrain() {
        assertEquals(HorizonOptions(minDistanceM = 0.0), horizonOptions(FakeSource))
    }

    @Test
    fun theThresholdCheckUsesTheOptionsOfTheSource() {
        serve("19")
        val bare = loader()
        assertEquals(0.0, bare.options.minDistanceM, 0.0)
        bare.loadOnce({ _, _, _ -> }, {})
        assertEquals(listOf(bare.options), checkOptions)

        // The same files and URLs, but the source is a surface model.
        checkOptions.clear()
        val surface =
            object : ElevationSource by FakeSource {
                override val isSurfaceModel = true
            }
        val dir2 = Files.createTempDirectory("dem").toFile()
        try {
            val c = DemCache(dir2, server, maxBytes = 100_000_000, source = surface)
            val l = loader(cache = c)
            assertEquals(NEAR_CLEARANCE_M, l.options.minDistanceM, 0.0)
            l.loadOnce({ _, _, _ -> }, {})
            assertEquals(listOf(l.options), checkOptions)
            assertEquals(true, l.full)
        } finally {
            dir2.deleteRecursively()
        }
    }

    @Test
    fun aFlatPlaceGetsTheCoarseLevelOnly() {
        serve("0.9")
        val l = loader()
        val steps = mutableListOf<Int>()
        assertEquals(0, l.loadOnce({ step, _, _ -> steps += step }, {}))
        assertEquals(false, l.full)
        assertEquals(listOf(1, 1, 1, 1), steps)
        assertTrue(fullRequests().isEmpty())
        assertEquals(
            mapOf(name(own) to "0.2/L2", name(near) to "0.9/L2", name(far) to "0.2/L2"),
            l.grids.mapValues { it.value.second },
        )
        // Nothing more to do, also on the next pass.
        val requests = server.requests.size
        assertEquals(0, l.loadOnce({ _, _, _ -> }, {}))
        assertEquals(requests, server.requests.size)
        for (tile in listOf(own, near, far)) assertNull(cache.entry(name(tile)))
    }

    @Test
    fun aPlaceInTheMountainsGetsTheFullFilesOfTheNearTiles() {
        serve("1.0")
        val l = loader()
        assertEquals(0, l.loadOnce({ _, _, _ -> }, {}))
        assertEquals(true, l.full)
        // Only the tiles nearer than 25 km get the full file.
        assertEquals(listOf(name(own), name(near)), fullRequests())
        assertEquals(
            mapOf(name(own) to "0.2/L0", name(near) to "1.0/L0", name(far) to "0.2/L2"),
            l.grids.mapValues { it.value.second },
        )
    }

    @Test
    fun offlineTheDecisionWaitsForAllCoarseParts() {
        serve("19")
        server.online = false
        val l = loader()
        assertEquals(4, l.loadOnce({ _, _, _ -> }, {}))
        assertNull(l.full)
        server.online = true
        assertEquals(0, l.loadOnce({ _, _, _ -> }, {}))
        assertEquals(true, l.full)
    }

    @Test
    fun theGridsOfThePlaceBeforeGetTheRightLevel() {
        serve("19")
        val first = loader()
        first.loadOnce({ _, _, _ -> }, {})
        // The same grids, now with a profile below the threshold: the full grids go back to the
        // coarse level, from the full files in the cache, without a download.
        val requests = server.requests.size
        val second =
            TerrainLoader(
                cache,
                tiles,
                ::readFakeTile,
                { _, _, _ -> 0.5 },
                grids = first.grids,
            )
        assertEquals(0, second.loadOnce({ _, _, _ -> }, {}))
        assertEquals(false, second.full)
        assertEquals(requests, server.requests.size)
        assertEquals(setOf(FakeSource.coarseLevel), second.grids.values.map { it.first }.toSet())
    }

    @Test
    fun keepOfflineAtAFlatPlaceKeepsTheCoarseParts() {
        serve("0.5")
        val jobs = mutableListOf<KeepJob>()
        val job = keepTerrain(cache, loader(readFull = false), place, "Vienna") { jobs += it }
        assertTrue(fullRequests().isEmpty())
        val kept = cache.keptPlaces().single()
        assertEquals(tiles.map { cache.coarseName(name(it)) }, kept.place.tiles)
        assertEquals(4, kept.place.tileCount)
        assertEquals(0, kept.missing)
        assertEquals(90_000L, kept.bytes)
        assertEquals(KeepJob("Vienna", 4, 4, true, 0, 90_000L, 4), job)
        assertEquals("Ready offline: 4 tiles, 0 MB", job.text)
        assertEquals("Downloading 0/4", jobs.first().text)
    }

    @Test
    fun keepOfflineInTheMountainsKeepsTheFullFilesOfTheNearTiles() {
        serve("19")
        val job = keepTerrain(cache, loader(readFull = false), place, "Innsbruck") {}
        assertEquals(listOf(name(own), name(near)), fullRequests())
        // The full files also serve the coarse level of the near tiles, so the kept place lists
        // them in place of their coarse parts.
        val kept = cache.keptPlaces().single()
        val entries =
            listOf(name(own), name(near), cache.coarseName(name(far)), cache.coarseName(name(sea)))
        assertEquals(entries, kept.place.tiles)
        assertEquals(4, kept.place.tileCount)
        assertEquals(0, kept.missing)
        assertEquals(2 * 100_000L + 30_000L, kept.bytes)
        assertEquals(KeepJob("Innsbruck", 6, 6, true, 0, 230_000L, 4), job)

        // Again: nothing is downloaded again, and the place keeps its id.
        val requests = server.requests.size
        keepTerrain(cache, loader(readFull = false), place, "Innsbruck") {}
        assertEquals(requests, server.requests.size)
        val again = cache.keptPlaces().single()
        assertEquals(entries, again.place.tiles)
        assertEquals(kept.place.id, again.place.id)
    }
}
