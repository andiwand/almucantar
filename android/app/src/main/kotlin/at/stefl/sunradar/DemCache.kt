package at.stefl.sunradar

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import org.json.JSONException
import org.json.JSONObject

/** A response of the tile server. [body] is set only for the code 200. */
class HttpResponse(
    val code: Int,
    val body: ByteArray? = null,
    val etag: String? = null,
    val lastModified: String? = null,
)

/** The HTTP layer of [DemCache], so the JVM tests can replace it. */
fun interface TileHttp {
    /**
     * Sends a GET request. If [etag] or [lastModified] is not null, the request is conditional
     * (If-None-Match, If-Modified-Since). Throws [IOException] if the network fails.
     */
    @Throws(IOException::class)
    fun get(url: String, etag: String?, lastModified: String?): HttpResponse
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

/** The record of one tile in the index of the cache. */
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
 * A persistent cache of the elevation tiles of Copernicus GLO-90: one file per tile, and an index
 * (`index.json`) with the ETag, the Last-Modified value, the time of the last check, the time of
 * the last use, the size, and a flag for a sea tile.
 * - A tile or a sea flag that is older than [maxAgeMs] gets a conditional request. 304 updates the
 *   time of the check, 200 replaces the file. A network error keeps the old tile, so the cache
 *   works offline.
 * - When the files are larger than [maxBytes] in total, [load] deletes the tiles that were used
 *   least recently, but not the tiles in its argument `keep`.
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
) {
    private val indexFile = File(dir, "index.json")
    private val entries = mutableMapOf<String, DemEntry>()

    init {
        dir.mkdirs()
        readIndex()
        // A file without an entry (for example after a crash during a write) is deleted, and an
        // entry without its file is removed.
        dir.listFiles()?.forEach { file ->
            if (file != indexFile && entries[file.nameWithoutExtension]?.sea != false) file.delete()
        }
        entries.entries.removeAll { (name, entry) -> !entry.sea && !fileOf(name).isFile }
    }

    /** The entry of the tile [name], or null if the tile is not in the cache. */
    @Synchronized fun entry(name: String): DemEntry? = entries[name]

    /** The size of all files in the cache, in bytes. */
    @Synchronized fun totalBytes(): Long = entries.values.sumOf { it.size }

    /**
     * Gives the tile with the south-west corner [latitudeDeg], [longitudeDeg], read with [decode].
     * It downloads the tile if it is not in the cache, and checks it with the server if it is older
     * than the maximum age. After a download, it deletes old tiles that are not in [keep] (names of
     * [tileName]) until the cache is not larger than its limit.
     */
    @Synchronized
    fun <T : Any> load(
        latitudeDeg: Int,
        longitudeDeg: Int,
        keep: Set<String> = emptySet(),
        decode: (ByteArray) -> T,
    ): TileLoad<T> {
        val name = tileName(latitudeDeg, longitudeDeg)
        val url = tileUrl(latitudeDeg, longitudeDeg)
        var entry = entries[name]
        if (entry != null && clock() - entry.checkedMs > maxAgeMs) {
            entry = revalidate(name, url, entry, keep)
        }
        var current: DemEntry = entry ?: download(name, url, keep) ?: return TileLoad.Missing
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
            if (attempt == 0) current = download(name, url, keep) ?: return TileLoad.Missing
        }
        return TileLoad.Unreadable
    }

    /** Deletes the tiles that were used least recently and are not in [keep], down to the limit. */
    @Synchronized
    fun evict(keep: Set<String>) {
        var total = totalBytes()
        if (total <= maxBytes) return
        val candidates =
            entries
                .filter { (name, entry) -> name !in keep && entry.size > 0 }
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
        entry: DemEntry,
        keep: Set<String>,
    ): DemEntry {
        val response =
            try {
                http.get(url, entry.etag, entry.lastModified)
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

    /** Downloads a tile that is not in the cache. Returns null if the download fails. */
    private fun download(name: String, url: String, keep: Set<String>): DemEntry? {
        val response =
            try {
                http.get(url, null, null)
            } catch (_: IOException) {
                return null
            }
        return when (response.code) {
            HttpURLConnection.HTTP_OK,
            HttpURLConnection.HTTP_NOT_FOUND -> store(name, response, keep)
            else -> null
        }
    }

    /** Stores a response with the code 200 (a file) or 404 (the sea). */
    private fun store(name: String, response: HttpResponse, keep: Set<String>): DemEntry {
        val now = clock()
        val body = response.body
        if (response.code != HttpURLConnection.HTTP_OK || body == null) {
            fileOf(name).delete()
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

    private fun readIndex() {
        if (!indexFile.isFile) return
        try {
            val tiles = JSONObject(indexFile.readText()).getJSONObject("tiles")
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
        } catch (_: JSONException) {
            // A damaged index starts an empty cache; init deletes the files that it does not know.
            entries.clear()
        } catch (_: IOException) {
            entries.clear()
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
        val temporary = File(dir, "index.json.part")
        temporary.writeText(JSONObject().put("version", 1).put("tiles", tiles).toString())
        if (!temporary.renameTo(indexFile)) temporary.delete()
    }

    companion object {
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

    override fun get(url: String, etag: String?, lastModified: String?): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", "Sunradar (Android; at.stefl.sunradar)")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            etag?.let { connection.setRequestProperty("If-None-Match", it) }
            lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            val code = connection.responseCode
            val body =
                if (code == HttpURLConnection.HTTP_OK) {
                    if (connection.contentLengthLong > MAX_BYTES) throw IOException("too large")
                    connection.inputStream.use { readAtMost(it, MAX_BYTES) }
                } else {
                    null
                }
            return HttpResponse(
                code,
                body,
                connection.getHeaderField("ETag"),
                connection.getHeaderField("Last-Modified"),
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
