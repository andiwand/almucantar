package at.stefl.almucantar.shared

import at.stefl.almucantar.core.Observer
import java.text.Normalizer
import java.time.DateTimeException
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

// The offline base map (`assets/basemap/basemap.json`, Natural Earth, public domain): the decode,
// the search of the places, and the time zone of a dropped place. The format is in the docstring
// of `scripts/basemap/build_basemap.py`. The PWA has the same functions in `web/shared/src/basemap.ts`.

private const val MAX_MAP_LATITUDE_DEG = 85.0511287798066

/** Web Mercator: the x of [longitudeDeg] on a world of the width 1. */
internal fun mercatorX(longitudeDeg: Double) = (longitudeDeg + 180) / 360

/** Web Mercator: the y of [latitudeDeg] on a world of the height 1 (0 is the north). */
internal fun mercatorY(latitudeDeg: Double): Double {
    val lat = Math.toRadians(latitudeDeg.coerceIn(-MAX_MAP_LATITUDE_DEG, MAX_MAP_LATITUDE_DEG))
    return (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2
}

/** The longitude of the Web Mercator [x], from -180 to 180 also if x is outside 0 to 1. */
internal fun mercatorLongitude(x: Double): Double {
    val lon = x * 360 - 180
    return ((lon + 180) % 360 + 360) % 360 - 180
}

internal fun mercatorLatitude(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))

/**
 * A line of the map in Web Mercator coordinates of a world of the size 1: x0, y0, x1, y1, ... The
 * box (in the same coordinates) makes it possible to skip a line outside the screen.
 */
class MapLine(
    val points: FloatArray,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** A place of the base map. A smaller [rank] is a larger place (0 to 10). */
data class MapPlace(
    val name: String,
    val longitudeDeg: Double,
    val latitudeDeg: Double,
    val rank: Int,
    val country: String,
    /** An IANA time zone, or "" if Natural Earth has none. */
    val timeZone: String,
) {
    val observer: Observer
        get() = Observer(latitudeDeg, longitudeDeg)
}

class BaseMap(
    val coastline: List<MapLine>,
    val borders: List<MapLine>,
    val lakes: List<MapLine>,
    val rivers: List<MapLine>,
    /** The places, the largest first (the order of the file). */
    val places: List<MapPlace>,
    val attribution: String,
) {
    /** The names of the places without accents in lower case, for the search. */
    private val folded: List<String> = places.map { fold(it.name) }

    /**
     * The places whose name starts with [text], then the places whose name contains it, the largest
     * first. The search ignores the case and the accents, so "zurich" finds "Zürich".
     */
    fun search(text: String, limit: Int = 8): List<MapPlace> {
        val query = fold(text.trim())
        if (query.isEmpty()) return emptyList()
        val starts = places.indices.filter { folded[it].startsWith(query) }
        val contains =
            places.indices.filter { !folded[it].startsWith(query) && folded[it].contains(query) }
        return (starts + contains).take(limit).map { places[it] }
    }

    /** The nearest place to [at]. If [withTimeZone], only a place that has a time zone. */
    fun nearest(at: Observer, withTimeZone: Boolean = false): MapPlace? =
        places
            .asSequence()
            .filter { !withTimeZone || it.timeZone.isNotEmpty() }
            .minByOrNull { Geo.distanceM(at, it.observer) }

    /**
     * The time zone of a dropped place: the zone of the nearest place that has one. If java.time
     * does not know that zone, the result is [fallback].
     */
    fun timeZoneAt(at: Observer, fallback: ZoneId): ZoneId =
        nearest(at, withTimeZone = true)?.timeZone?.let { zoneOrNull(it) } ?: fallback

    companion object {
        /** Decodes the JSON text of the base map (UTF-8). */
        fun decode(json: ByteArray): BaseMap = BaseMapReader(json).read()
    }
}

/** The zone with the IANA name [id], or null if java.time does not know it. */
internal fun zoneOrNull(id: String?): ZoneId? =
    try {
        if (id.isNullOrEmpty()) null else ZoneId.of(id)
    } catch (_: DateTimeException) {
        null
    }

/** Lower case without accents, so "wien" finds "Wien" and "zurich" finds "Zürich". */
internal fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * A small JSON reader for the base map. A general JSON library makes an object for each of the 900
 * 000 numbers, so this reader reads the bytes directly. It knows objects, arrays, strings,
 * integers, and numbers, which is all that the format uses.
 */
private class BaseMapReader(private val bytes: ByteArray) {
    private var i = 0

    fun read(): BaseMap {
        var version = -1L
        var scale = 0.0
        var attribution = ""
        var rawLayers = mapOf<String, List<LongArray>>()
        var rawPlaces = listOf<RawPlace>()
        objectFields { name ->
            when (name) {
                "version" -> version = long()
                "scale" -> scale = long().toDouble()
                "attribution" -> attribution = string()
                "layers" -> rawLayers = layers()
                "places" -> rawPlaces = places()
                else -> skip()
            }
        }
        require(version == 1L) { "base map version $version is not supported" }
        require(scale > 0) { "the base map has no scale" }
        fun layer(name: String) = rawLayers[name].orEmpty().map { line(it, scale) }
        return BaseMap(
            layer("coastline"),
            layer("borders"),
            layer("lakes"),
            layer("rivers"),
            rawPlaces.map {
                MapPlace(it.name, it.lon / scale, it.lat / scale, it.rank, it.country, it.zone)
            },
            attribution,
        )
    }

    private class RawPlace(
        val name: String,
        val lon: Long,
        val lat: Long,
        val rank: Int,
        val country: String,
        val zone: String,
    )

    /** A line from the delta-encoded integers, in Web Mercator coordinates. */
    private fun line(flat: LongArray, scale: Double): MapLine {
        val points = FloatArray(flat.size)
        var lon = 0L
        var lat = 0L
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (k in 0 until flat.size - 1 step 2) {
            lon += flat[k]
            lat += flat[k + 1]
            val x = mercatorX(lon / scale).toFloat()
            val y = mercatorY(lat / scale).toFloat()
            points[k] = x
            points[k + 1] = y
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
        }
        return MapLine(points, left, top, right, bottom)
    }

    private fun layers(): Map<String, List<LongArray>> {
        val result = mutableMapOf<String, List<LongArray>>()
        objectFields { name ->
            val lines = mutableListOf<LongArray>()
            arrayItems { lines += longArray() }
            result[name] = lines
        }
        return result
    }

    private fun places(): List<RawPlace> {
        val result = mutableListOf<RawPlace>()
        arrayItems {
            expect('[')
            val name = string()
            expect(',')
            val lon = long()
            expect(',')
            val lat = long()
            expect(',')
            val rank = long().toInt()
            expect(',')
            val country = string()
            expect(',')
            val zone = string()
            expect(']')
            result += RawPlace(name, lon, lat, rank, country, zone)
        }
        return result
    }

    private fun longArray(): LongArray {
        var values = LongArray(64)
        var n = 0
        arrayItems {
            if (n == values.size) values = values.copyOf(n * 2)
            values[n++] = long()
        }
        return values.copyOf(n)
    }

    private inline fun objectFields(field: (String) -> Unit) {
        expect('{')
        if (peek() == '}'.code) {
            i++
            return
        }
        while (true) {
            val name = string()
            expect(':')
            field(name)
            when (next()) {
                ','.code -> continue
                '}'.code -> return
                else -> fail("',' or '}'")
            }
        }
    }

    private inline fun arrayItems(item: () -> Unit) {
        expect('[')
        if (peek() == ']'.code) {
            i++
            return
        }
        while (true) {
            item()
            when (next()) {
                ','.code -> continue
                ']'.code -> return
                else -> fail("',' or ']'")
            }
        }
    }

    private fun long(): Long {
        skipSpace()
        var negative = false
        if (bytes[i] == '-'.code.toByte()) {
            negative = true
            i++
        }
        val start = i
        var value = 0L
        while (i < bytes.size && bytes[i] in DIGIT_0..DIGIT_9) {
            value = value * 10 + (bytes[i] - DIGIT_0)
            i++
        }
        if (i == start) fail("a number")
        return if (negative) -value else value
    }

    private fun string(): String {
        expect('"')
        val start = i
        var escaped = false
        while (bytes[i] != '"'.code.toByte()) {
            if (bytes[i] == '\\'.code.toByte()) {
                escaped = true
                i++
            }
            i++
        }
        val raw = String(bytes, start, i - start, Charsets.UTF_8)
        i++
        return if (escaped) unescape(raw) else raw
    }

    private fun unescape(raw: String): String = buildString {
        var k = 0
        while (k < raw.length) {
            val c = raw[k++]
            if (c != '\\') {
                append(c)
                continue
            }
            when (val e = raw[k++]) {
                'n' -> append('\n')
                't' -> append('\t')
                'r' -> append('\r')
                'b' -> append('\b')
                'f' -> append('\u000C')
                'u' -> {
                    append(raw.substring(k, k + 4).toInt(16).toChar())
                    k += 4
                }
                else -> append(e)
            }
        }
    }

    /** Skips one value of any type. */
    private fun skip() {
        when (peek()) {
            '{'.code -> objectFields { skip() }
            '['.code -> arrayItems { skip() }
            '"'.code -> string()
            else -> {
                skipSpace()
                while (i < bytes.size && bytes[i].toInt().toChar() !in ",}] \n\r\t") i++
            }
        }
    }

    private fun skipSpace() {
        while (i < bytes.size && bytes[i].toInt().toChar() in " \n\r\t") i++
    }

    private fun peek(): Int {
        skipSpace()
        if (i >= bytes.size) fail("more data")
        return bytes[i].toInt()
    }

    private fun next(): Int = peek().also { i++ }

    private fun expect(c: Char) {
        if (next() != c.code) {
            i--
            fail("'$c'")
        }
    }

    private fun fail(what: String): Nothing =
        throw IllegalArgumentException("base map: expected $what at byte $i")

    companion object {
        const val DIGIT_0 = '0'.code.toByte()
        const val DIGIT_9 = '9'.code.toByte()
    }
}
