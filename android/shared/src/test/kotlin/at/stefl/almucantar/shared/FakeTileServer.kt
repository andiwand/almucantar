package at.stefl.almucantar.shared

import at.stefl.almucantar.core.ElevationGrid
import at.stefl.almucantar.core.ElevationTile
import java.io.IOException

/** One request to [FakeTileServer]. */
data class TileRequest(
    val tile: String,
    val etag: String?,
    val range: LongRange?,
    val url: String = "",
)

/**
 * A source for the tests: a bare-earth model with other URLs than Copernicus, and the files of
 * [fakeTile]. It has no reader, because the tests read the files with [readFakeTile].
 */
object FakeSource : ElevationSource {
    override val name = "Fake bare earth"
    override val attribution = "Fake data"
    override val isSurfaceModel = false
    override val coarseLevel = 2
    override val fullLevel = 0
    override val fullDistanceM = 25_000.0

    override fun tileUrl(tile: ElevationTile) =
        "https://dem.example.org/bare/${DemCache.tileName(tile.latitudeDeg, tile.longitudeDeg)}.tif"

    override fun prefixSize(header: ByteArray, level: Int) = fakePrefixSize(header, level)

    override fun readGrid(bytes: ByteArray, level: Int): ElevationGrid =
        throw UnsupportedOperationException("the tests use readFakeTile")
}

/**
 * A tile server for the tests: a file for each tile in [files], and 404 for the others (the sea).
 * It supports Range (206 with Content-Range) and If-None-Match (304).
 */
class FakeTileServer : TileHttp {
    val files = mutableMapOf<String, ByteArray>()
    val etags = mutableMapOf<String, String>()
    var online = true

    /** If true, the server ignores Range and sends the whole file (200). */
    var ignoreRange = false
    val requests = mutableListOf<TileRequest>()

    /** If not empty, the next answers have these ETags (a file that changes between requests). */
    val nextEtags = ArrayDeque<String>()

    override fun get(
        url: String,
        etag: String?,
        lastModified: String?,
        range: LongRange?,
    ): HttpResponse {
        // The URLs of Copernicus and of FakeSource.
        val tile =
            url.substringAfterLast("/")
                .removePrefix("Copernicus_DSM_COG_30_")
                .removeSuffix(".tif")
                .removeSuffix("_DEM")
        requests += TileRequest(tile, etag, range, url)
        if (!online) throw IOException("offline")
        val body = files[tile] ?: return HttpResponse(404)
        val tag = nextEtags.removeFirstOrNull() ?: etags[tile] ?: "\"${body.contentHashCode()}\""
        val modified = "Mon, 09 May 2022 13:03:36 GMT"
        if (etag == tag) return HttpResponse(304, etag = tag)
        if (range == null || ignoreRange) {
            return HttpResponse(200, body, tag, modified, body.size.toLong())
        }
        val end = minOf(range.last + 1, body.size.toLong()).toInt()
        val part = body.copyOfRange(range.first.toInt(), end)
        return HttpResponse(206, part, tag, modified, body.size.toLong())
    }

    /** The bytes that the server sent for [tile] with code 200 or 206 (the sizes of the parts). */
    fun rangesOf(tile: String): List<LongRange?> =
        requests.filter { it.tile == tile }.map { it.range }
}

/**
 * A fake GeoTIFF for the tests: the text `<prefix> <total>\n<label>`, after [pad] spaces, filled
 * with `#` to [total] bytes. The coarse level needs the first [prefix] bytes. A large [pad] makes
 * the first 16 KB too short to tell the size.
 */
fun fakeTile(label: String, prefix: Int, total: Int, pad: Int = 0): ByteArray {
    val text = " ".repeat(pad) + "$prefix $total\n$label"
    require(text.length <= prefix && prefix <= total)
    return (text + "#".repeat(total - text.length)).toByteArray()
}

/** The prefix size of [fakeTile]: 0 if the header has no end of line yet. */
fun fakePrefixSize(header: ByteArray, @Suppress("UNUSED_PARAMETER") level: Int): Long {
    val text = String(header)
    val end = text.indexOf('\n')
    if (end < 0) return 0
    return text.substring(0, end).trim().split(" ").first().toLongOrNull()
        ?: throw IllegalArgumentException("not a GeoTIFF")
}

/**
 * Reads a [fakeTile] at [level]: the coarse level needs the prefix, level 0 the whole file. Gives
 * `<label>/L<level>`.
 */
fun readFakeTile(bytes: ByteArray, level: Int): String {
    val text = String(bytes)
    val end = text.indexOf('\n')
    require(end >= 0) { "not a GeoTIFF" }
    val (prefix, total) = text.substring(0, end).trim().split(" ").map { it.toInt() }
    require(bytes.size >= (if (level == 0) total else prefix)) { "too short" }
    return text.substring(end + 1).substringBefore('#') + "/L$level"
}
