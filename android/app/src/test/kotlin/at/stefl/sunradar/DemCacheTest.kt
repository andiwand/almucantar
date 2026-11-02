package at.stefl.sunradar

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemCacheTest {
    private val dir: File = Files.createTempDirectory("dem").toFile()
    private var now = 1_000_000L
    private val day = 24L * 3600 * 1000

    private val server = FakeTileServer()

    private fun cache(maxBytes: Long = 1000) =
        DemCache(
            dir,
            server,
            clock = { now },
            maxBytes = maxBytes,
            maxAgeMs = 180 * day,
            prefixSize = ::fakePrefixSize,
        )

    /** The decoder of the tests: the text of the file. "bad" is a file that cannot be read. */
    private val decode = { bytes: ByteArray ->
        String(bytes).also { require(!it.startsWith("bad")) { "not a GeoTIFF" } }
    }

    @After
    fun deleteDir() {
        dir.deleteRecursively()
    }

    @Test
    fun namesAndUrls() {
        assertEquals("N47_00_E011_00", DemCache.tileName(47, 11))
        assertEquals("S05_00_W080_00", DemCache.tileName(-5, -80))
        assertEquals(
            "https://copernicus-dem-90m.s3.amazonaws.com/Copernicus_DSM_COG_30_N47_00_E011_00_DEM/" +
                "Copernicus_DSM_COG_30_N47_00_E011_00_DEM.tif",
            DemCache.tileUrl(47, 11),
        )
    }

    @Test
    fun downloadsOnceAndThenUsesTheFile() {
        server.files["N47_00_E011_00"] = "alps".toByteArray()
        val c = cache()
        assertEquals(TileLoad.Data("alps"), c.load(47, 11, decode = decode))
        assertEquals(TileLoad.Data("alps"), c.load(47, 11, decode = decode))
        assertEquals(1, server.requests.size)
        // A new cache reads the index from the disk.
        server.online = false
        assertEquals(TileLoad.Data("alps"), cache().load(47, 11, decode = decode))
        assertEquals(4L, cache().entry("N47_00_E011_00")?.size)
    }

    @Test
    fun notFoundIsTheSea() {
        val c = cache()
        assertEquals(TileLoad.Sea, c.load(40, -30, decode = decode))
        assertTrue(c.entry("N40_00_W030_00")!!.sea)
        // The sea flag is kept, so the next load needs no request.
        assertEquals(TileLoad.Sea, c.load(40, -30, decode = decode))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun offlineGivesMissingAndKeepsNothing() {
        server.online = false
        val c = cache()
        assertEquals(TileLoad.Missing, c.load(47, 11, decode = decode))
        assertNull(c.entry("N47_00_E011_00"))
        server.online = true
        server.files["N47_00_E011_00"] = "alps".toByteArray()
        assertEquals(TileLoad.Data("alps"), c.load(47, 11, decode = decode))
    }

    @Test
    fun evictsTheLeastRecentlyUsedButNotTheNeededTiles() {
        for (lon in 0 until 4) server.files[DemCache.tileName(47, lon)] = ByteArray(300)
        val c = cache(maxBytes = 1000)
        for (lon in 0 until 3) {
            now += 1000
            c.load(47, lon, decode = decode)
        }
        // Tile 0 is the oldest, but the current place needs it; so tile 1 goes.
        now += 1000
        c.load(47, 3, keep = setOf(DemCache.tileName(47, 0)), decode = decode)
        assertTrue(c.entry("N47_00_E000_00") != null)
        assertNull(c.entry("N47_00_E001_00"))
        assertTrue(c.entry("N47_00_E002_00") != null)
        assertTrue(c.entry("N47_00_E003_00") != null)
        assertFalse(File(dir, "N47_00_E001_00.tif").exists())
        assertEquals(900L, c.totalBytes())

        // A use makes a tile recent: tile 2 is used now, so tile 0 goes next.
        now += 1000
        c.load(47, 2, decode = decode)
        c.evict(emptySet())
        server.files[DemCache.tileName(47, 5)] = ByteArray(300)
        now += 1000
        c.load(47, 5, decode = decode)
        assertNull(c.entry("N47_00_E000_00"))
        assertTrue(c.entry("N47_00_E002_00") != null)
    }

    @Test
    fun oldTilesAreCheckedWithTheServer() {
        server.files["N47_00_E011_00"] = "alps".toByteArray()
        val c = cache()
        c.load(47, 11, decode = decode)
        val downloaded = c.entry("N47_00_E011_00")!!.checkedMs

        // Younger than 180 days: no request.
        now += 179 * day
        c.load(47, 11, decode = decode)
        assertEquals(1, server.requests.size)

        // Older: a conditional request, and 304 updates the time of the check.
        now += 2 * day
        assertEquals(TileLoad.Data("alps"), c.load(47, 11, decode = decode))
        assertEquals(2, server.requests.size)
        assertTrue(server.requests[1].etag != null)
        assertEquals(now, c.entry("N47_00_E011_00")!!.checkedMs)
        assertTrue(c.entry("N47_00_E011_00")!!.checkedMs > downloaded)

        // A new version on the server: 200 replaces the file.
        now += 181 * day
        server.files["N47_00_E011_00"] = "alps 2".toByteArray()
        assertEquals(TileLoad.Data("alps 2"), c.load(47, 11, decode = decode))

        // Offline: the old tile stays, and the next load tries again.
        now += 181 * day
        server.online = false
        assertEquals(TileLoad.Data("alps 2"), c.load(47, 11, decode = decode))
        assertEquals(TileLoad.Data("alps 2"), c.load(47, 11, decode = decode))
        assertEquals(5, server.requests.size)
    }

    @Test
    fun oldSeaFlagsAreCheckedWithTheServer() {
        val c = cache()
        assertEquals(TileLoad.Sea, c.load(40, -30, decode = decode))
        now += 181 * day
        server.files["N40_00_W030_00"] = "island".toByteArray()
        assertEquals(TileLoad.Data("island"), c.load(40, -30, decode = decode))
        assertFalse(c.entry("N40_00_W030_00")!!.sea)
    }

    @Test
    fun aFileThatCannotBeReadIsDownloadedAgainOnce() {
        server.files["N47_00_E011_00"] = "alps".toByteArray()
        val c = cache()
        c.load(47, 11, decode = decode)
        File(dir, "N47_00_E011_00.tif").writeText("bad file")
        assertEquals(TileLoad.Data("alps"), c.load(47, 11, decode = decode))
        assertEquals(2, server.requests.size)

        // The server gives a bad file too: one new download, then the cache gives up.
        server.files["N47_00_E011_00"] = "bad tile".toByteArray()
        File(dir, "N47_00_E011_00.tif").writeText("bad file")
        assertEquals(TileLoad.Unreadable, c.load(47, 11, decode = decode))
        assertEquals(3, server.requests.size)
        assertNull(c.entry("N47_00_E011_00"))
        assertFalse(File(dir, "N47_00_E011_00.tif").exists())
    }

    @Test
    fun aDamagedIndexStartsAnEmptyCache() {
        server.files["N47_00_E011_00"] = "alps".toByteArray()
        cache().load(47, 11, decode = decode)
        File(dir, "index.json").writeText("{not json")
        val c = cache()
        assertNull(c.entry("N47_00_E011_00"))
        assertFalse(File(dir, "N47_00_E011_00.tif").exists())
        assertEquals(TileLoad.Data("alps"), c.load(47, 11, decode = decode))
    }

    private fun keptPlace(id: String, vararg tiles: String) =
        KeptPlace(id, "Innsbruck · 47.263°, 11.394°", 47.263, 11.394, tiles.toList())

    @Test
    fun aKeptTileSurvivesTheEviction() {
        for (lon in 0 until 5) server.files[DemCache.tileName(47, lon)] = ByteArray(300)
        val c = cache(maxBytes = 1000)
        c.addKeptPlace(keptPlace("a", "N47_00_E000_00"))
        assertEquals(TileFetch.READY, c.fetch(47, 0))
        // Tile 0 is the oldest, but it is kept; so tile 1 goes when the cache is full.
        for (lon in 1 until 5) {
            now += 1000
            c.load(47, lon, decode = decode)
        }
        assertTrue(c.entry("N47_00_E000_00") != null)
        assertTrue(File(dir, "N47_00_E000_00.tif").exists())
        assertNull(c.entry("N47_00_E001_00"))
    }

    @Test
    fun keptTilesDoNotCountTowardTheLimit() {
        for (lon in 0 until 6) server.files[DemCache.tileName(47, lon)] = ByteArray(300)
        val c = cache(maxBytes = 1000)
        c.addKeptPlace(keptPlace("a", "N47_00_E000_00", "N47_00_E001_00", "N47_00_E002_00"))
        for (lon in 0 until 3) assertEquals(TileFetch.READY, c.fetch(47, lon))
        // Three normal tiles (900 bytes) fit next to the three kept tiles.
        for (lon in 3 until 6) {
            now += 1000
            c.load(47, lon, decode = decode)
        }
        for (lon in 0 until 6) assertTrue(c.entry(DemCache.tileName(47, lon)) != null)
        assertEquals(900L, c.cacheBytes())
        assertEquals(1800L, c.totalBytes())
        val status = c.keptPlaces().single()
        assertEquals(3, status.ready)
        assertEquals(900L, status.bytes)
        assertEquals(0, status.missing)
    }

    @Test
    fun removeReleasesTheTiles() {
        for (lon in 0 until 5) server.files[DemCache.tileName(47, lon)] = ByteArray(300)
        val c = cache(maxBytes = 1000)
        c.addKeptPlace(keptPlace("a", "N47_00_E000_00", "N47_00_E001_00"))
        c.addKeptPlace(keptPlace("b", "N47_00_E001_00"))
        c.fetch(47, 0)
        c.fetch(47, 1)
        c.removeKeptPlace("a")
        // The tiles stay on the disk as normal tiles.
        assertEquals(listOf("b"), c.keptPlaces().map { it.place.id })
        assertTrue(c.entry("N47_00_E000_00") != null)
        assertEquals(300L, c.cacheBytes())
        // Now the eviction can delete tile 0, but tile 1 is still kept by place b.
        for (lon in 2 until 5) {
            now += 1000
            c.load(47, lon, decode = decode)
        }
        assertNull(c.entry("N47_00_E000_00"))
        assertTrue(c.entry("N47_00_E001_00") != null)
    }

    @Test
    fun theKeptPlacesSurviveARestart() {
        server.files["N47_00_E011_00"] = "alps".toByteArray()
        val c = cache()
        c.addKeptPlace(keptPlace("a", "N47_00_E011_00", "N46_00_E011_00", "N48_00_E011_00"))
        assertEquals(TileFetch.READY, c.fetch(47, 11))
        assertEquals(TileFetch.SEA, c.fetch(46, 11))
        server.online = false
        assertEquals(TileFetch.MISSING, c.fetch(48, 11))

        val again = cache()
        val status = again.keptPlaces().single()
        assertEquals(
            keptPlace("a", "N47_00_E011_00", "N46_00_E011_00", "N48_00_E011_00"),
            status.place,
        )
        // The sea tile counts as ready.
        assertEquals(2, status.ready)
        assertEquals(1, status.missing)
        assertEquals(4L, status.bytes)
        assertEquals(TileLoad.Data("alps"), again.load(47, 11, decode = decode))
    }

    @Test
    fun anOldIndexWithoutKeptPlacesLoads() {
        File(dir, "N47_00_E011_00.tif").writeText("alps")
        File(dir, "index.json")
            .writeText(
                """{"version":1,"tiles":{"N47_00_E011_00":{"etag":"\"x\"",""" +
                    """"lastModified":"","checked":$now,"used":$now,"size":4,"sea":false}}}"""
            )
        server.online = false
        val c = cache()
        assertTrue(c.keptPlaces().isEmpty())
        assertEquals(TileLoad.Data("alps"), c.load(47, 11, decode = decode))
        assertEquals("\"x\"", c.entry("N47_00_E011_00")?.etag)
    }

    @Test
    fun aKeptTileThatIsGoneIsDownloadedAgain() {
        server.files["N47_00_E011_00"] = "alps".toByteArray()
        val c = cache()
        c.addKeptPlace(keptPlace("a", "N47_00_E011_00"))
        c.fetch(47, 11)
        File(dir, "N47_00_E011_00.tif").delete()
        assertEquals(0, c.keptPlaces().single().ready)
        assertEquals(TileFetch.READY, c.fetch(47, 11))
        assertEquals(2, server.requests.size)
        assertEquals(1, c.keptPlaces().single().ready)
    }

    @Test
    fun oldKeptTilesAreCheckedWithTheServer() {
        server.files["N47_00_E011_00"] = "alps".toByteArray()
        val c = cache()
        c.addKeptPlace(keptPlace("a", "N47_00_E011_00"))
        c.fetch(47, 11)
        now += 181 * day
        assertEquals(TileFetch.READY, c.fetch(47, 11))
        assertEquals(2, server.requests.size)
        assertTrue(server.requests[1].etag != null)
        assertEquals(now, c.entry("N47_00_E011_00")!!.checkedMs)
    }

    private val alps = "N47_00_E011_00"

    private fun coarse(c: DemCache, lat: Int = 47, lon: Int = 11) =
        c.loadCoarse(lat, lon) { readFakeTile(it, COARSE_LEVEL) }

    @Test
    fun theCoarsePartComesInTwoSteps() {
        server.files[alps] = fakeTile("alps", prefix = 30_000, total = 100_000)
        val c = cache(maxBytes = 1_000_000)
        assertEquals(TileLoad.Data("alps/L2"), coarse(c))
        // First 16 KB, then the rest of the part.
        assertEquals(listOf(0L..16_383L, 16_384L..29_999L), server.rangesOf(alps))
        assertEquals(30_000L, c.entry("$alps.l2")?.size)
        assertEquals(30_000L, File(dir, "$alps.l2.tif").length())
        assertNull(c.entry(alps))
        assertEquals("$alps.l2", c.coarseEntryName(47, 11))
        // The part is in the cache, also for a new cache.
        assertEquals(TileLoad.Data("alps/L2"), coarse(cache(maxBytes = 1_000_000)))
        assertEquals(2, server.requests.size)
    }

    @Test
    fun aShortPartNeedsOneRequest() {
        server.files[alps] = fakeTile("alps", prefix = 10_000, total = 100_000)
        val c = cache(maxBytes = 1_000_000)
        assertEquals(TileLoad.Data("alps/L2"), coarse(c))
        assertEquals(listOf(0L..16_383L), server.rangesOf(alps))
        assertEquals(10_000L, c.entry("$alps.l2")?.size)
    }

    @Test
    fun aHeaderThatIsTooShortGetsALongerHeader() {
        // The size of the part is after 20 000 bytes, so the first 16 KB cannot tell it.
        server.files[alps] = fakeTile("alps", prefix = 30_000, total = 100_000, pad = 20_000)
        val c = cache(maxBytes = 1_000_000)
        assertEquals(TileLoad.Data("alps/L2"), coarse(c))
        assertEquals(listOf(0L..16_383L, 16_384L..65_535L), server.rangesOf(alps))
        assertEquals(30_000L, c.entry("$alps.l2")?.size)
    }

    @Test
    fun theCoarsePartOfASeaTileIsNotFound() {
        val c = cache()
        assertEquals(TileLoad.Sea, coarse(c, 40, -30))
        assertTrue(c.entry("N40_00_W030_00.l2")!!.sea)
        assertEquals(TileLoad.Sea, coarse(c, 40, -30))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aServerWithoutRangeSendsTheWholeFile() {
        server.files[alps] = fakeTile("alps", prefix = 30_000, total = 100_000)
        server.ignoreRange = true
        val c = cache(maxBytes = 1_000_000)
        assertEquals(TileLoad.Data("alps/L2"), coarse(c))
        assertEquals(1, server.requests.size)
        // The cache keeps only the part.
        assertEquals(30_000L, c.entry("$alps.l2")?.size)
    }

    @Test
    fun partsOfTwoDifferentFilesFail() {
        server.files[alps] = fakeTile("alps", prefix = 30_000, total = 100_000)
        server.nextEtags += listOf("\"a\"", "\"b\"")
        val c = cache(maxBytes = 1_000_000)
        assertEquals(TileLoad.Missing, coarse(c))
        assertNull(c.entry("$alps.l2"))
        // The next try gets two parts of the same file.
        assertEquals(TileLoad.Data("alps/L2"), coarse(c))
    }

    @Test
    fun theFullFileReplacesTheCoarsePart() {
        server.files[alps] = fakeTile("alps", prefix = 30_000, total = 100_000)
        val c = cache(maxBytes = 1_000_000)
        c.addKeptPlace(keptPlace("a", "$alps.l2"))
        coarse(c)
        assertEquals(30_000L, c.keptPlaces().single().bytes)
        assertEquals(TileLoad.Data("alps/L0"), c.load(47, 11) { readFakeTile(it, 0) })
        assertNull(c.entry("$alps.l2"))
        assertFalse(File(dir, "$alps.l2.tif").exists())
        // The full file serves the kept coarse part, and the eviction does not delete it.
        val status = c.keptPlaces().single()
        assertEquals(1, status.ready)
        assertEquals(100_000L, status.bytes)
        assertEquals(0L, c.cacheBytes())
        server.files["N47_00_E012_00"] = fakeTile("x", prefix = 30_000, total = 100_000)
        cache(maxBytes = 1_000).load(47, 12) { readFakeTile(it, 0) }
        assertTrue(cache().entry(alps) != null)
    }

    @Test
    fun aFullFileServesTheCoarseLevel() {
        server.files[alps] = fakeTile("alps", prefix = 30_000, total = 100_000)
        val c = cache(maxBytes = 1_000_000)
        assertEquals(TileLoad.Data("alps/L0"), c.load(47, 11) { readFakeTile(it, 0) })
        assertEquals(TileLoad.Data("alps/L2"), coarse(c))
        // No Range request: the full file serves the coarse level.
        assertEquals(listOf<LongRange?>(null), server.rangesOf(alps))
        assertNull(c.entry("$alps.l2"))
        assertEquals(alps, c.coarseEntryName(47, 11))
    }

    @Test
    fun oldCoarsePartsAreCheckedWithTheServer() {
        server.files[alps] = fakeTile("alps", prefix = 30_000, total = 100_000)
        val c = cache(maxBytes = 1_000_000)
        coarse(c)
        now += 179 * day
        coarse(c)
        assertEquals(2, server.requests.size)

        // Older than 180 days: a conditional Range request, and 304 keeps the file.
        now += 2 * day
        assertEquals(TileLoad.Data("alps/L2"), coarse(c))
        assertEquals(3, server.requests.size)
        val check = server.requests[2]
        assertTrue(check.etag != null)
        assertEquals(0L..16_383L, check.range)
        assertEquals(now, c.entry("$alps.l2")!!.checkedMs)

        // A new version on the server: 206 replaces the part.
        now += 181 * day
        server.files[alps] = fakeTile("alps2", prefix = 20_000, total = 90_000)
        assertEquals(TileLoad.Data("alps2/L2"), coarse(c))
        assertEquals(20_000L, c.entry("$alps.l2")?.size)
        assertEquals(5, server.requests.size)

        // Offline: the old part stays.
        now += 181 * day
        server.online = false
        assertEquals(TileLoad.Data("alps2/L2"), coarse(c))
    }

    @Test
    fun coarsePartsAreEvictedAsOtherFiles() {
        for (lon in 0 until 4) {
            server.files[DemCache.tileName(47, lon)] = fakeTile("t$lon", 300, 1_000)
        }
        val c = cache(maxBytes = 1_000)
        for (lon in 0 until 4) {
            now += 1000
            coarse(c, 47, lon)
        }
        // Four parts of 300 bytes: the oldest one goes.
        assertNull(c.entry("N47_00_E000_00.l2"))
        assertEquals(900L, c.cacheBytes())
        assertFalse(File(dir, "N47_00_E000_00.l2.tif").exists())
    }

    @Test
    fun aPartOfAFileFromACrashIsDeleted() {
        server.files[alps] = fakeTile("alps", prefix = 300, total = 1_000)
        coarse(cache())
        File(dir, "$alps.l2.part").writeText("half")
        File(dir, "$alps.part").writeText("half")
        cache()
        assertFalse(File(dir, "$alps.l2.part").exists())
        assertFalse(File(dir, "$alps.part").exists())
        assertTrue(File(dir, "$alps.l2.tif").exists())
    }
}
