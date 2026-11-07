package at.stefl.alidade

import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.Observer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

// The favorites: the places and the shots that the user keeps. The start page shows them. A shot
// is stored with all its fields, except the eclipse: the app stores the time of its peak and finds
// the eclipse again with the core when it loads the favorites.

/** The same shot gives the same key, also after a round trip through JSON. */
fun shotKey(s: Shot): String =
    "%s|%d|%.5f|%.5f|%s"
        .format(
            Locale.ROOT,
            s.subject.name,
            s.unixUtc.roundToLong(),
            s.spot.latitudeDeg,
            s.spot.longitudeDeg,
            s.target?.name ?: "",
        )

/** The same place gives the same key: the latitude and the longitude to about 1 m. */
fun placeKey(p: Point): String = "%.5f|%.5f".format(Locale.ROOT, p.latitudeDeg, p.longitudeDeg)

private fun observerJson(o: Observer) =
    JSONObject().apply {
        put("lat", o.latitudeDeg)
        put("lon", o.longitudeDeg)
    }

private fun observerOf(j: JSONObject) = Observer(j.getDouble("lat"), j.getDouble("lon"))

private fun horizontalJson(h: Horizontal) =
    JSONObject().apply {
        put("az", h.azimuthDeg)
        put("alt", h.altitudeDeg)
    }

private fun horizontalOf(j: JSONObject) = Horizontal(j.getDouble("az"), j.getDouble("alt"))

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) getDouble(name) else null

fun shotJson(s: Shot): JSONObject =
    JSONObject().apply {
        put("subject", s.subject.name)
        put("t", s.unixUtc)
        put("spot", observerJson(s.spot))
        s.target?.let { t ->
            put(
                "target",
                JSONObject().apply {
                    put("direction", horizontalJson(t.direction))
                    put("name", t.name)
                    put("distance", t.distanceM)
                    t.elevationM?.let { put("elevation", it) }
                    put("place", observerJson(t.place))
                },
            )
        }
        put("separation", s.separationDeg)
        put("body", horizontalJson(s.body))
        put("start", s.pathStartUtc)
        put("end", s.pathEndUtc)
        s.riseUtc?.let { put("rise", it) }
        s.setUtc?.let { put("set", it) }
        s.eclipse?.let { (e, _) ->
            put(
                "eclipse",
                JSONObject().apply {
                    put("solar", e.solar)
                    put("peak", e.peakUtc)
                },
            )
        }
    }

/**
 * The shot of [j], or null if the entry is damaged or if the core does not find its eclipse again.
 * An eclipse needs the core, so call this in the background.
 */
fun shotOf(j: JSONObject): Shot? =
    try {
        val spot = observerOf(j.getJSONObject("spot"))
        val target =
            j.optJSONObject("target")?.let { t ->
                Target(
                    horizontalOf(t.getJSONObject("direction")),
                    t.getString("name"),
                    t.getDouble("distance"),
                    t.optDoubleOrNull("elevation"),
                    observerOf(t.getJSONObject("place")),
                )
            }
        val eclipse =
            j.optJSONObject("eclipse")?.let { e ->
                val solar = e.getBoolean("solar")
                val peak = e.getDouble("peak")
                val event =
                    upcomingEclipses(peak - 86_400.0).firstOrNull {
                        it.solar == solar && abs(it.peakUtc - peak) < 600
                    } ?: return null
                val local = localEclipse(event, spot) ?: return null
                event to local
            }
        Shot(
            SubjectId.valueOf(j.getString("subject")),
            j.getDouble("t"),
            spot,
            target,
            j.getDouble("separation"),
            horizontalOf(j.getJSONObject("body")),
            j.getDouble("start"),
            j.getDouble("end"),
            j.optDoubleOrNull("rise"),
            j.optDoubleOrNull("set"),
            eclipse,
        )
    } catch (_: Exception) {
        null
    }

fun pointJson(p: Point) =
    JSONObject().apply {
        put("lat", p.latitudeDeg)
        put("lon", p.longitudeDeg)
        put("name", p.name)
        p.groundM?.let { put("ground", it) }
    }

fun pointOf(j: JSONObject) =
    Point(j.getDouble("lat"), j.getDouble("lon"), j.optString("name"), j.optDoubleOrNull("ground"))

/** The favorites as stored: the places and the shots, the newest first. */
class FavoriteData(val places: List<Point>, val shots: List<Shot>)

fun favoritesJson(places: List<Point>, shots: List<Shot>): String =
    JSONObject()
        .apply {
            put("places", JSONArray().apply { places.forEach { put(pointJson(it)) } })
            put("shots", JSONArray().apply { shots.forEach { put(shotJson(it)) } })
        }
        .toString()

/** Reads the favorites. A damaged entry is left out. Call it in the background (see [shotOf]). */
fun favoritesOf(text: String): FavoriteData {
    val json =
        try {
            JSONObject(text)
        } catch (_: Exception) {
            return FavoriteData(emptyList(), emptyList())
        }
    val places = json.optJSONArray("places")
    val shots = json.optJSONArray("shots")
    return FavoriteData(
        (0 until (places?.length() ?: 0)).mapNotNull {
            try {
                pointOf(places!!.getJSONObject(it))
            } catch (_: Exception) {
                null
            }
        },
        (0 until (shots?.length() ?: 0)).mapNotNull {
            shots!!.optJSONObject(it)?.let(::shotOf)
        },
    )
}
