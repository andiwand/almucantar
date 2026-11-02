package at.stefl.sunradar

import java.io.File
import java.io.IOException
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

    /** A server with a file for each tile in [files], and 404 for the others (the sea). */
    private inner class FakeServer : TileHttp {
        val files = mutableMapOf<String, ByteArray>()
        val etags = mutableMapOf<String, String>()
        var online = true
        val requests = mutableListOf<Pair<String, String?>>()

        override fun get(url: String, etag: String?, lastModified: String?): HttpResponse {
            val name = url.substringAfterLast("/").removePrefix("Copernicus_DSM_COG_30_")
            val tile = name.removeSuffix("_DEM.tif")
            requests += tile to etag
            if (!online) throw IOException("offline")
            val body = files[tile] ?: return HttpResponse(404)
            val tag = etags[tile] ?: "\"${body.contentHashCode()}\""
            if (etag == tag) return HttpResponse(304, etag = tag)
            return HttpResponse(200, body, tag, "Mon, 09 May 2022 13:03:36 GMT")
        }
    }

    private val server = FakeServer()

    private fun cache(maxBytes: Long = 1000) =
        DemCache(dir, server, clock = { now }, maxBytes = maxBytes, maxAgeMs = 180 * day)

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
        assertTrue(server.requests[1].second != null)
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
}
