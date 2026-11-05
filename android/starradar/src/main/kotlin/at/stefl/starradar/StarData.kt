package at.stefl.starradar

import androidx.compose.ui.graphics.Color
import at.stefl.almucantar.core.Planet
import at.stefl.almucantar.core.StarCatalog
import org.json.JSONObject

/**
 * The star data of starradar (`assets/stars/stars.json`, made by `scripts/stars/build_stars.py`):
 * the stars as columns for the core, their names, and the constellations. The stars are sorted by
 * magnitude, the brightest first. The web app reads the same file (`web/shared/src/stars.ts`).
 */
class StarData(
    val catalog: StarCatalog,
    val mag: FloatArray,
    val bv: FloatArray,
    /** The number of each star in the Bright Star Catalogue (HR). */
    val hr: IntArray,
    /** The proper name of a star, by its index. */
    val names: Map<Int, String>,
    val constellations: List<Constellation>,
    val attribution: String,
) {
    val size: Int
        get() = mag.size

    /** The index of the star with this name, or -1. */
    fun indexOf(name: String): Int = names.entries.firstOrNull { it.value == name }?.key ?: -1

    companion object {
        /** Decodes stars.json, version 1. */
        fun decode(text: String): StarData {
            val json = JSONObject(text)
            val version = json.getInt("version")
            require(version == 1) { "stars.json has the unknown version $version" }
            val stars = json.getJSONObject("stars")
            fun column(name: String): DoubleArray {
                val a = stars.getJSONArray(name)
                return DoubleArray(a.length()) { a.getDouble(it) }
            }
            val names = json.getJSONArray("names")
            val constellations = json.getJSONArray("constellations")
            return StarData(
                catalog =
                    StarCatalog(column("ra"), column("dec"), column("pm_ra"), column("pm_dec")),
                mag = column("mag").let { c -> FloatArray(c.size) { c[it].toFloat() } },
                bv = column("bv").let { c -> FloatArray(c.size) { c[it].toFloat() } },
                hr = stars.getJSONArray("hr").let { a -> IntArray(a.length()) { a.getInt(it) } },
                names =
                    (0 until names.length()).associate {
                        val entry = names.getJSONArray(it)
                        entry.getInt(0) to entry.getString(1)
                    },
                constellations =
                    (0 until constellations.length()).map { k ->
                        val c = constellations.getJSONObject(k)
                        val label = c.getJSONArray("label")
                        val lines = c.getJSONArray("lines")
                        Constellation(
                            id = c.getString("id"),
                            name = c.getString("name"),
                            labelRaDeg = label.getDouble(0),
                            labelDecDeg = label.getDouble(1),
                            lines =
                                (0 until lines.length()).map { i ->
                                    val line = lines.getJSONArray(i)
                                    IntArray(line.length()) { line.getInt(it) }
                                },
                        )
                    },
                attribution = json.getString("attribution"),
            )
        }
    }
}

data class Constellation(
    /** The IAU abbreviation, for example "Ori". */
    val id: String,
    /** The IAU name in Latin, for example "Orion". */
    val name: String,
    /** Where the name goes: right ascension and declination (J2000), in degrees. */
    val labelRaDeg: Double,
    val labelDecDeg: Double,
    /** The lines: each line is a list of indices of stars. */
    val lines: List<IntArray>,
)

/** The planets with their names and colors, the same as in the web app. */
val PLANETS: List<Pair<Planet, Pair<String, Color>>> =
    listOf(
        Planet.MERCURY to ("Mercury" to Color(0xFFFFECD2)),
        Planet.VENUS to ("Venus" to Color(0xFFFFFCEC)),
        Planet.MARS to ("Mars" to Color(0xFFFFAA78)),
        Planet.JUPITER to ("Jupiter" to Color(0xFFFFF0D6)),
        Planet.SATURN to ("Saturn" to Color(0xFFFFE6AA)),
        Planet.URANUS to ("Uranus" to Color(0xFFC8F0FF)),
        Planet.NEPTUNE to ("Neptune" to Color(0xFFAAC8FF)),
    )

/** How a star looks: the same rules as `web/shared/src/stars.ts`. */
object StarLook {
    /**
     * The faintest magnitude that the eye sees, from the altitude of the sun: about 6 in the
     * astronomical night, and only the brightest stars and planets in the twilight.
     */
    fun limitingMagnitude(sunAltitudeDeg: Double): Double =
        when {
            sunAltitudeDeg >= 0 -> -2.0
            sunAltitudeDeg >= -6 -> -1 + (sunAltitudeDeg / -6) * 2
            sunAltitudeDeg >= -12 -> 1 + ((sunAltitudeDeg + 6) / -6) * 2.5
            sunAltitudeDeg >= -18 -> 3.5 + ((sunAltitudeDeg + 12) / -6) * 2.5
            else -> 6.0
        }

    /** The radius of a star on the screen in dp: a brighter star is larger. */
    fun radiusDp(mag: Float): Float = (3.2f - 0.45f * mag).coerceIn(0.6f, 4.5f)

    private val stops =
        listOf(
            -0.4f to Color(170, 191, 255),
            0.0f to Color(202, 215, 255),
            0.3f to Color(248, 247, 255),
            0.65f to Color(255, 244, 234),
            1.0f to Color(255, 222, 180),
            1.5f to Color(255, 196, 140),
            2.0f to Color(255, 170, 110),
        )

    /** The color of a star from its B-V color index, pale as the eye sees it. */
    fun color(bv: Float): Color {
        val x = bv.coerceIn(stops.first().first, stops.last().first)
        for (i in 1 until stops.size) {
            val (x1, c1) = stops[i]
            if (x <= x1) {
                val (x0, c0) = stops[i - 1]
                val t = (x - x0) / (x1 - x0)
                return Color(
                    c0.red + (c1.red - c0.red) * t,
                    c0.green + (c1.green - c0.green) * t,
                    c0.blue + (c1.blue - c0.blue) * t,
                )
            }
        }
        return stops.last().second
    }

    /** The phase of the twilight from the altitude of the sun. */
    fun darkness(sunAltitudeDeg: Double): String =
        when {
            sunAltitudeDeg >= 0 -> "Day"
            sunAltitudeDeg >= -6 -> "Civil twilight"
            sunAltitudeDeg >= -12 -> "Nautical twilight"
            sunAltitudeDeg >= -18 -> "Astronomical twilight"
            else -> "Night"
        }
}
