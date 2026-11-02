package at.stefl.sunradar

import at.stefl.sunradar.core.elevationGeotiffPrefixSize
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A response of the tile server. [body] is set only for the codes 200 and 206. [totalSize] is the
 * size of the whole file: from Content-Range for 206, from Content-Length for 200.
 */
class HttpResponse(
    val code: Int,
    val body: ByteArray? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val totalSize: Long? = null,
)

/** The HTTP layer of [DemCache], so the JVM tests can replace it. */
fun interface TileHttp {
    /**
     * Sends a GET request. If [etag] or [lastModified] is not null, the request is conditional
     * (If-None-Match, If-Modified-Since). If [range] is not null, the request asks only for these
     * bytes (Range), and the server answers 206 (or 200 with the whole file). Throws [IOException]
     * if the network fails.
     */
    @Throws(IOException::class)
    fun get(url: String, etag: String?, lastModified: String?, range: LongRange?): HttpResponse
}

/** The result of [DemCache.load]. */
sealed interface TileLoad<out T> {
    /** The tile, read with the decoder. */
    data class Data<T>(val value: T) : TileLoad<T>

    /** The tile is in the sea: the server has no file for it. */
    data object Sea : TileLoad<Nothing>

    /** The tile is not in the cache, and the download failed (for example offline). */
    data object Missing : TileLoad<Nothing>

    /** The decoder cannot read the tile, also after one new download. */
    data object Unreadable : TileLoad<Nothing>
}

/** The result of [DemCache.fetch]. */
enum class TileFetch {
    /** The file of the tile is on the disk. */
    READY,
    /** The tile is in the sea: the server has no file for it. */
    SEA,
    /** The tile is not on the disk, and the download failed (for example offline). */
    MISSING,
}

/**
 * A place that the user keeps offline ("Keep offline"). The cache never evicts its [tiles] (names
 * of entries: [DemCache.tileName] for a full file, [DemCache.coarseName] for a coarse part), and
 * their size does not count toward the limit of the cache.
 */
data class KeptPlace(
    val id: String,
    val name: String,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val tiles: List<String>,
)

/** A kept place with the state of its tiles on the disk. */
data class KeptPlaceStatus(
    val place: KeptPlace,
    /** The number of entries that are on the disk or in the sea. */
    val ready: Int,
    /** The size of the files of the tiles on the disk, in bytes. */
    val bytes: Long,
) {
    /** The number of entries that are not on the disk. */
    val missing: Int
        get() = place.tiles.size - ready
}

/**
 * The number of tiles of the place. [KeptPlace.tiles] are names of entries of the cache, and a tile
 * can have two entries (its coarse part and its full file).
 */
val KeptPlace.tileCount: Int
    get() = tiles.map { DemCache.tileOfEntry(it) }.distinct().size

/**
 * The record of one entry in the index of the cache: the full file or the coarse part of a tile.
 */
data class DemEntry(
    val etag: String?,
    val lastModified: String?,
    /** The time of the download or of the last check with the server, in ms. */
    val checkedMs: Long,
    /** The time of the last use, in ms. */
    val usedMs: Long,
    /** The size of the file in bytes, 0 for a sea tile. */
    val size: Long,
    /** True if the server has no file for the tile (HTTP 404). */
    val sea: Boolean,
)

/**
 * A persistent cache of the elevation tiles of Copernicus GLO-90, and an index (`index.json`) with
 * the ETag, the Last-Modified value, the time of the last check, the time of the last use, the
 * size, and a flag for a sea tile. A tile can have two entries, each with its own file:
 * - The full file (`N47_00_E011_00.tif`, 2–5 MB), from [load] and [fetch].
 * - The coarse part (`N47_00_E011_00.l2.tif`, about 300 KB), from [loadCoarse]: the first bytes of
 *   the file, which have the directories and the overview at [coarseLevel]. The cache downloads 16
 *   KB with a Range request, gets the size of the part from [prefixSize], and downloads the rest of
 *   the part. All parts must have the ETag of the first answer. If the server ignores the Range and
 *   sends the whole file, the cache keeps only the part. A full file in the cache also serves the
 *   coarse level, so [loadCoarse] uses it and downloads no part, and the cache deletes the coarse
 *   part of a tile when it stores the full file.
 *
 * The rules are the same for the two kinds of entry:
 * - An entry or a sea flag that is older than [maxAgeMs] gets a conditional request (with Range for
 *   a coarse part). 304 updates the time of the check, 200 or 206 replaces the file. A network
 *   error keeps the old file, so the cache works offline.
 * - When the files are larger than [maxBytes] in total, [load] deletes the tiles that were used
 *   least recently, but not the tiles in its argument `keep`.
 * - The index also has the kept places ([KeptPlace]). The cache never deletes an entry of a tile of
 *   a kept place (the coarse part or the full file), and these tiles do not count toward
 *   [maxBytes]. The check after [maxAgeMs] is the same for all tiles.
 * - A file that the decoder cannot read is deleted and downloaded again one time.
 *
 * All functions block (file and network access), so call them off the main thread.
 */
class DemCache(
    private val dir: File,
    private val http: TileHttp = UrlTileHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 400L * 1024 * 1024,
    private val maxAgeMs: Long = 180L * 24 * 3600 * 1000,
    /** The overview level of the coarse parts. */
    private val coarseLevel: Int = COARSE_LEVEL,
    /**
     * The number of bytes from the start of a file that the coarse level needs, from the first
     * bytes of the file; 0 if they are too short to tell (see `elevationGeotiffPrefixSize`).
     */
    private val prefixSize: (header: ByteArray, level: Int) -> Long = ::elevationGeotiffPrefixSize,
) {
    private val indexFile = File(dir, "index.json")
    private val entries = mutableMapOf<String, DemEntry>()
    private val kept = mutableListOf<KeptPlace>()

    init {
        dir.mkdirs()
        readIndex()
        // A file without an entry (for example a part of a file after a crash during a write) is
        // deleted, and an entry without its file is removed.
        dir.listFiles()?.forEach { file ->
            val name = file.name.removeSuffix(".tif")
            val known = file.name.endsWith(".tif") && entries[name]?.sea == false
            if (file != indexFile && !known) file.delete()
        }
        entries.entries.removeAll { (name, entry) -> !entry.sea && !fileOf(name).isFile }
    }

    /** The entry of the tile [name], or null if the tile is not in the cache. */
    @Synchronized fun entry(name: String): DemEntry? = entries[name]

    /** The size of all files in the cache, in bytes, also the files of the kept places. */
    @Synchronized fun totalBytes(): Long = entries.values.sumOf { it.size }

    /** The size of the files that count toward the limit: all files but the kept tiles. */
    @Synchronized
    fun cacheBytes(): Long {
        val keptTiles = keptTiles()
        return entries.filterKeys { it !in keptTiles }.values.sumOf { it.size }
    }

    /** The kept places with the state of their tiles, in the order of their creation. */
    @Synchronized
    fun keptPlaces(): List<KeptPlaceStatus> = kept.map { place ->
        // A coarse part is also present if the full file of its tile serves it.
        val serving =
            place.tiles.mapNotNull { name ->
                val tile = tileOfEntry(name)
                listOf(name, tile).distinct().firstOrNull { present(it) }
            }
        KeptPlaceStatus(
            place,
            serving.size,
            serving.distinct().sumOf { entries.getValue(it).size },
        )
    }

    private fun present(name: String): Boolean =
        entries[name]?.let { it.sea || fileOf(name).isFile } ?: false

    /**
     * Adds [place] to the kept places, or replaces the kept place with the same id. Its tiles are
     * kept from now on, also before they are downloaded.
     */
    @Synchronized
    fun addKeptPlace(place: KeptPlace) {
        val index = kept.indexOfFirst { it.id == place.id }
        if (index >= 0) kept[index] = place else kept += place
        writeIndex()
    }

    /**
     * Removes the kept place [id]. Its tiles stay on the disk as normal tiles of the cache, so the
     * next eviction can delete them. A tile of another kept place stays kept.
     */
    @Synchronized
    fun removeKeptPlace(id: String) {
        if (kept.removeAll { it.id == id }) writeIndex()
    }

    /**
     * Makes sure that the full file of the tile with the south-west corner [latitudeDeg],
     * [longitudeDeg] is on the disk, without a read of the file. It downloads a tile that is not in
     * the cache or whose file is gone, and checks a tile with the server if it is older than the
     * maximum age. It does not change the time of the last use.
     */
    @Synchronized
    fun fetch(latitudeDeg: Int, longitudeDeg: Int, keep: Set<String> = emptySet()): TileFetch {
        val name = tileName(latitudeDeg, longitudeDeg)
        val url = tileUrl(latitudeDeg, longitudeDeg)
        var entry = entries[name]
        if (entry != null && !entry.sea && !fileOf(name).isFile) {
            // The file is gone, for example by a manual delete.
            remove(name)
            entry = null
        }
        if (entry != null && clock() - entry.checkedMs > maxAgeMs) {
            entry = revalidate(name, url, coarse = false, entry, keep)
        }
        val current = entry ?: download(name, url, coarse = false, keep) ?: return TileFetch.MISSING
        return if (current.sea) TileFetch.SEA else TileFetch.READY
    }

    /**
     * Gives the tile with the south-west corner [latitudeDeg], [longitudeDeg], read with [decode]
     * from the full file. It downloads the file if it is not in the cache, and checks it with the
     * server if it is older than the maximum age. After a download, it deletes old entries that are
     * not in [keep] (names of entries) until the cache is not larger than its limit.
     */
    @Synchronized
    fun <T : Any> load(
        latitudeDeg: Int,
        longitudeDeg: Int,
        keep: Set<String> = emptySet(),
        decode: (ByteArray) -> T,
    ): TileLoad<T> =
        loadEntry(
            tileName(latitudeDeg, longitudeDeg),
            tileUrl(latitudeDeg, longitudeDeg),
            coarse = false,
            keep,
            decode,
        )

    /**
     * Gives the tile with the south-west corner [latitudeDeg], [longitudeDeg] for the coarse level,
     * read with [decode]: [decode] must read the overview at [coarseLevel]. It uses the full file
     * if it is in the cache, else the coarse part (downloaded and checked as in [load]).
     */
    @Synchronized
    fun <T : Any> loadCoarse(
        latitudeDeg: Int,
        longitudeDeg: Int,
        keep: Set<String> = emptySet(),
        decode: (ByteArray) -> T,
    ): TileLoad<T> {
        val name = tileName(latitudeDeg, longitudeDeg)
        val url = tileUrl(latitudeDeg, longitudeDeg)
        if (entries.containsKey(name)) {
            val full = loadEntry(name, url, coarse = false, keep, decode)
            // A full file that is gone or bad, offline: the coarse part can still serve.
            if (full !is TileLoad.Missing && full !is TileLoad.Unreadable) return full
        }
        return loadEntry(coarseName(name), url, coarse = true, keep, decode)
    }

    /**
     * The name of the entry that serves the coarse level of the tile [latitudeDeg], [longitudeDeg]
     * now: the full file if it is in the cache, else the coarse part.
     */
    @Synchronized
    fun coarseEntryName(latitudeDeg: Int, longitudeDeg: Int): String {
        val name = tileName(latitudeDeg, longitudeDeg)
        val full = entries[name]
        return if (full != null && (full.sea || fileOf(name).isFile)) name else coarseName(name)
    }

    /** The name of the entry of the coarse part of the tile [name] (a name of [tileName]). */
    fun coarseName(name: String): String = "$name.l$coarseLevel"

    private fun <T : Any> loadEntry(
        name: String,
        url: String,
        coarse: Boolean,
        keep: Set<String>,
        decode: (ByteArray) -> T,
    ): TileLoad<T> {
        var entry = entries[name]
        if (entry != null && clock() - entry.checkedMs > maxAgeMs) {
            entry = revalidate(name, url, coarse, entry, keep)
        }
        var current: DemEntry =
            entry ?: download(name, url, coarse, keep) ?: return TileLoad.Missing
        for (attempt in 0 until 2) {
            if (current.sea) {
                touch(name)
                return TileLoad.Sea
            }
            val value =
                try {
                    decode(fileOf(name).readBytes())
                } catch (_: Exception) {
                    // Also a file that is gone (IOException) is read again from the server.
                    null
                }
            if (value != null) {
                touch(name)
                return TileLoad.Data(value)
            }
            remove(name)
            if (attempt == 0) {
                current = download(name, url, coarse, keep) ?: return TileLoad.Missing
            }
        }
        return TileLoad.Unreadable
    }

    /**
     * Deletes the tiles that were used least recently and are not in [keep], down to the limit. It
     * never deletes a tile of a kept place, and these tiles do not count toward the limit.
     */
    @Synchronized
    fun evict(keep: Set<String>) {
        var total = cacheBytes()
        if (total <= maxBytes) return
        val keptTiles = keptTiles()
        val candidates =
            entries
                .filter { (name, entry) ->
                    name !in keep && name !in keptTiles && entry.size > 0
                }
                .toList()
                .sortedBy {
                    it.second.usedMs
                }
        for ((name, entry) in candidates) {
            if (total <= maxBytes) break
            fileOf(name).delete()
            entries.remove(name)
            total -= entry.size
        }
        writeIndex()
    }

    /** A conditional request for an old entry. Returns the entry after the check. */
    private fun revalidate(
        name: String,
        url: String,
        coarse: Boolean,
        entry: DemEntry,
        keep: Set<String>,
    ): DemEntry {
        val response =
            try {
                if (coarse) {
                    downloadPrefix(url, entry.etag, entry.lastModified) ?: return entry
                } else {
                    http.get(url, entry.etag, entry.lastModified, null)
                }
            } catch (_: IOException) {
                return entry
            }
        return when (response.code) {
            HttpURLConnection.HTTP_NOT_MODIFIED -> update(name, entry.copy(checkedMs = clock()))
            HttpURLConnection.HTTP_OK,
            HttpURLConnection.HTTP_NOT_FOUND -> store(name, response, keep)
            // A server error is not an answer about the tile, so the old tile stays.
            else -> entry
        }
    }

    /** Downloads an entry that is not in the cache. Returns null if the download fails. */
    private fun download(name: String, url: String, coarse: Boolean, keep: Set<String>): DemEntry? {
        val response =
            try {
                if (coarse) downloadPrefix(url, null, null) ?: return null
                else http.get(url, null, null, null)
            } catch (_: IOException) {
                return null
            }
        return when (response.code) {
            HttpURLConnection.HTTP_OK,
            HttpURLConnection.HTTP_NOT_FOUND -> store(name, response, keep)
            else -> null
        }
    }

    /**
     * Downloads the coarse part of a file: first [HEADER_BYTES] bytes, then more if [prefixSize]
     * cannot tell the size from them, then the rest of the part. Returns a response with the code
     * 200 and the part (or the whole file, if the server ignores the Range), or the answer of the
     * server to the first request if it is not 206 (for example 304 or 404). Returns null if a
     * later request fails, or if the file changes between two requests. Throws [IOException] if the
     * network fails.
     */
    private fun downloadPrefix(url: String, etag: String?, lastModified: String?): HttpResponse? {
        val first = http.get(url, etag, lastModified, 0L until HEADER_BYTES)
        // A server without Range sends the whole file (200). The cache keeps only the part.
        val whole = first.code == HttpURLConnection.HTTP_OK
        if (first.code != HTTP_PARTIAL && !whole) return first
        var data = first.body ?: return null
        val total = (if (whole) data.size.toLong() else first.totalSize) ?: return null
        fun part(bytes: ByteArray) = HttpResponse(200, bytes, first.etag, first.lastModified, total)
        // Asks for the bytes after data, up to end (exclusive). Returns false if this fails or if
        // the ETag is not the ETag of the first answer (the parts are then from two different
        // files). Replaces data with the whole file if the server sends it.
        fun more(end: Long): Boolean {
            val response = http.get(url, null, null, data.size.toLong() until end)
            val body = response.body ?: return false
            if (response.code == HttpURLConnection.HTTP_OK) {
                data = body
                return true
            }
            val sameFile = first.etag == null || response.etag == first.etag
            if (response.code != HTTP_PARTIAL || !sameFile) return false
            if (body.size.toLong() != end - data.size) return false
            data += body
            return true
        }
        while (true) {
            val needed =
                try {
                    prefixSize(data, coarseLevel)
                } catch (_: IllegalArgumentException) {
                    // Not a file that the core can read: keep it, so the read fails and the cache
                    // tries one new download.
                    return part(data)
                }
            when {
                needed in 1..data.size -> return part(data.copyOf(needed.toInt()))
                data.size.toLong() >= total -> return part(data)
                needed == 0L -> if (!more(minOf(data.size * 4L, total))) return null
                needed > total -> return part(data)
                else -> return if (more(needed)) part(data) else null
            }
        }
    }

    /** Stores a response with the code 200 (a file or a coarse part) or 404 (the sea). */
    private fun store(name: String, response: HttpResponse, keep: Set<String>): DemEntry {
        val now = clock()
        val body = response.body
        if (response.code != HttpURLConnection.HTTP_OK || body == null) {
            fileOf(name).delete()
            if (tileOfEntry(name) == name) dropCoarse(name)
            return update(name, DemEntry(null, null, now, now, 0, sea = true))
        }
        // Write a new file and then rename it, so a crash does not leave a part of a file.
        val temporary = File(dir, "$name.part")
        temporary.writeBytes(body)
        if (!temporary.renameTo(fileOf(name))) {
            temporary.delete()
            throw IOException("cannot write the tile $name")
        }
        val entry =
            update(
                name,
                DemEntry(
                    response.etag,
                    response.lastModified,
                    now,
                    now,
                    body.size.toLong(),
                    sea = false,
                ),
            )
        if (tileOfEntry(name) == name) {
            // The full file serves the coarse level too, so a tile has at most one of the two
            // files.
            dropCoarse(name)
        }
        evict(keep + name)
        return entry
    }

    private fun touch(name: String) {
        entries[name]?.let { update(name, it.copy(usedMs = clock())) }
    }

    private fun remove(name: String) {
        fileOf(name).delete()
        entries.remove(name)
        writeIndex()
    }

    private fun update(name: String, entry: DemEntry): DemEntry {
        entries[name] = entry
        writeIndex()
        return entry
    }

    private fun fileOf(name: String) = File(dir, "$name.tif")

    /** Deletes the coarse part of the tile [name], if it is in the cache. */
    private fun dropCoarse(name: String) {
        val coarse = coarseName(name)
        if (entries.containsKey(coarse)) remove(coarse)
    }

    /** The entries of the kept places: both entries of each kept tile. */
    private fun keptTiles(): Set<String> =
        kept.flatMapTo(mutableSetOf()) { place ->
            place.tiles.flatMap { listOf(tileOfEntry(it), coarseName(tileOfEntry(it))) }
        }

    private fun readIndex() {
        if (!indexFile.isFile) return
        try {
            val root = JSONObject(indexFile.readText())
            val tiles = root.getJSONObject("tiles")
            for (name in tiles.keys()) {
                val t = tiles.getJSONObject(name)
                entries[name] =
                    DemEntry(
                        etag = t.optString("etag").ifEmpty { null },
                        lastModified = t.optString("lastModified").ifEmpty { null },
                        checkedMs = t.getLong("checked"),
                        usedMs = t.getLong("used"),
                        size = t.getLong("size"),
                        sea = t.getBoolean("sea"),
                    )
            }
            // An index of version 1 has no kept places.
            val places = root.optJSONArray("kept") ?: JSONArray()
            for (i in 0 until places.length()) {
                val k = places.getJSONObject(i)
                val names = k.getJSONArray("tiles")
                kept +=
                    KeptPlace(
                        id = k.getString("id"),
                        name = k.getString("name"),
                        latitudeDeg = k.getDouble("latitude"),
                        longitudeDeg = k.getDouble("longitude"),
                        tiles = List(names.length()) { names.getString(it) },
                    )
            }
        } catch (_: JSONException) {
            // A damaged index starts an empty cache; init deletes the files that it does not know.
            entries.clear()
            kept.clear()
        } catch (_: IOException) {
            entries.clear()
            kept.clear()
        }
    }

    private fun writeIndex() {
        val tiles = JSONObject()
        for ((name, e) in entries) {
            tiles.put(
                name,
                JSONObject()
                    .put("etag", e.etag ?: "")
                    .put("lastModified", e.lastModified ?: "")
                    .put("checked", e.checkedMs)
                    .put("used", e.usedMs)
                    .put("size", e.size)
                    .put("sea", e.sea),
            )
        }
        val places = JSONArray()
        for (k in kept) {
            places.put(
                JSONObject()
                    .put("id", k.id)
                    .put("name", k.name)
                    .put("latitude", k.latitudeDeg)
                    .put("longitude", k.longitudeDeg)
                    .put("tiles", JSONArray(k.tiles))
            )
        }
        val temporary = File(dir, "index.json.part")
        temporary.writeText(
            JSONObject().put("version", 2).put("tiles", tiles).put("kept", places).toString()
        )
        if (!temporary.renameTo(indexFile)) temporary.delete()
    }

    companion object {
        /** The first request for a coarse part asks for this number of bytes. */
        const val HEADER_BYTES = 16L * 1024

        private const val HTTP_PARTIAL = 206

        /** The name of the tile of an entry: `N47_00_E011_00` for `N47_00_E011_00.l2`. */
        fun tileOfEntry(name: String): String = name.substringBefore('.')

        /** The name of a tile in Copernicus, for example `N47_00_E011_00`. */
        fun tileName(latitudeDeg: Int, longitudeDeg: Int): String =
            String.format(
                Locale.ROOT,
                "%s%02d_00_%s%03d_00",
                if (latitudeDeg < 0) "S" else "N",
                abs(latitudeDeg),
                if (longitudeDeg < 0) "W" else "E",
                abs(longitudeDeg),
            )

        /** The URL of a tile of Copernicus GLO-90 on AWS Open Data. */
        fun tileUrl(latitudeDeg: Int, longitudeDeg: Int): String {
            val id = "Copernicus_DSM_COG_30_${tileName(latitudeDeg, longitudeDeg)}_DEM"
            return "https://copernicus-dem-90m.s3.amazonaws.com/$id/$id.tif"
        }
    }
}

/** The HTTP layer of the app: [HttpURLConnection]. */
object UrlTileHttp : TileHttp {
    /** A tile has 2 to 5 MB. A larger response is not a tile. */
    private const val MAX_BYTES = 64 * 1024 * 1024

    private const val HTTP_PARTIAL = 206

    override fun get(
        url: String,
        etag: String?,
        lastModified: String?,
        range: LongRange?,
    ): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", "Sunradar (Android; at.stefl.sunradar)")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            etag?.let { connection.setRequestProperty("If-None-Match", it) }
            lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            range?.let { connection.setRequestProperty("Range", "bytes=${it.first}-${it.last}") }
            val code = connection.responseCode
            val body =
                if (code == HttpURLConnection.HTTP_OK || code == HTTP_PARTIAL) {
                    if (connection.contentLengthLong > MAX_BYTES) throw IOException("too large")
                    connection.inputStream.use { readAtMost(it, MAX_BYTES) }
                } else {
                    null
                }
            val total =
                when (code) {
                    HTTP_PARTIAL ->
                        connection
                            .getHeaderField("Content-Range")
                            ?.substringAfterLast('/')
                            ?.trim()
                            ?.toLongOrNull()
                    HttpURLConnection.HTTP_OK -> body?.size?.toLong()
                    else -> null
                }
            return HttpResponse(
                code,
                body,
                connection.getHeaderField("ETag"),
                connection.getHeaderField("Last-Modified"),
                total,
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Reads the stream. Throws [IOException] if it has more than [limit] bytes. */
    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > limit) throw IOException("the response is too large")
        }
    }
}
