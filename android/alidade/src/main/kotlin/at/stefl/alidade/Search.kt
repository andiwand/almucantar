package at.stefl.alidade

import at.stefl.almucantar.core.AlignmentOptions
import at.stefl.almucantar.core.Body
import at.stefl.almucantar.core.BodyOptions
import at.stefl.almucantar.core.DarkOptions
import at.stefl.almucantar.core.DayKind
import at.stefl.almucantar.core.EclipseKind
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.LunarEclipse
import at.stefl.almucantar.core.MoonOptions
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.Site
import at.stefl.almucantar.core.SunOptions
import at.stefl.almucantar.core.TerrainEventOptions
import at.stefl.almucantar.core.bodyPosition
import at.stefl.almucantar.core.darkWindows
import at.stefl.almucantar.core.findAlignments
import at.stefl.almucantar.core.localSolarEclipses
import at.stefl.almucantar.core.lunarEclipses
import at.stefl.almucantar.core.moonIlluminatedFraction
import at.stefl.almucantar.core.moonPosition
import at.stefl.almucantar.core.sight
import at.stefl.almucantar.core.solarEclipses
import at.stefl.almucantar.core.sunPosition
import at.stefl.almucantar.core.terrainEvents
import at.stefl.almucantar.shared.angleBetweenDeg
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// The searches of alidade, without the screens: the subjects, the dates for a spot and a
// landmark ("I know my spot"), the moments for the line of spots ("Find me a spot"), the shots from
// a place ("I am here"), and the eclipses. The core finds the alignments and the eclipses; this
// file chooses and sorts. The PWA has the same rules (web/alidade/app/src/search.ts).

enum class SubjectId {
    MOON,
    MILKY_WAY,
    SUN,
    VENUS,
    JUPITER,
    SATURN,
    MARS,
}

class Subject(
    val id: SubjectId,
    val name: String,
    val note: String,
    val body: Body,
    /** A match puts the center of the subject this near to the landmark. */
    val maxSeparationDeg: Double,
    /**
     * The sky must be at least this dark (the airless altitude of the sun), or null for any sky.
     */
    val maxSunDeg: Double?,
)

val SUBJECTS =
    listOf(
        Subject(SubjectId.MOON, "Moon", "rising, setting, the full moon", Body.MOON, 0.3, null),
        Subject(
            SubjectId.MILKY_WAY,
            "Milky Way",
            "its bright center, in a dark sky",
            Body.GALACTIC_CENTER,
            2.0,
            -15.0,
        ),
        Subject(SubjectId.SUN, "Sun", "sunrise and sunset", Body.SUN, 0.3, null),
        Subject(
            SubjectId.VENUS,
            "Venus",
            "the evening and the morning star",
            Body.VENUS,
            0.4,
            -4.0,
        ),
        Subject(
            SubjectId.JUPITER,
            "Jupiter",
            "the bright planet of the night",
            Body.JUPITER,
            0.4,
            -6.0,
        ),
        Subject(SubjectId.SATURN, "Saturn", "the planet with the rings", Body.SATURN, 0.4, -8.0),
        Subject(SubjectId.MARS, "Mars", "the red planet", Body.MARS, 0.4, -6.0),
    )

fun subjectOf(id: SubjectId): Subject = SUBJECTS.first { it.id == id }

/** The landmark of a shot: its direction from the spot, its name, and its distance. */
data class Target(
    val direction: Horizontal,
    val name: String,
    val distanceM: Double,
    val elevationM: Double?,
    val place: Observer,
)

/** An eclipse, for the whole Earth. */
data class Eclipse(
    val solar: Boolean,
    val kind: EclipseKind,
    val peakUtc: Double,
    /** A total or annular solar eclipse: the center of the shadow at the peak. */
    val peakPlace: Observer?,
    val lunar: LunarEclipse?,
)

/** A phase of an eclipse at a place: the time and the altitude of the sun or the moon. */
data class Phase(val label: String, val unixUtc: Double, val altitudeDeg: Double)

/** An eclipse seen from one place. */
data class LocalEclipse(
    /** The kind at this place: a total eclipse can be partial here. */
    val kind: EclipseKind,
    /** The part of the sun (or of the moon in the umbra) that is covered at the peak. */
    val coverage: Double,
    val phases: List<Phase>,
    val peak: Phase,
    /** True if the sun or the moon is over the horizon of 0° in a phase. */
    val visible: Boolean,
    /** The radii of the sun and the moon in degrees, from the contacts, for the photo. */
    val sunRadiusDeg: Double,
    val moonRadiusDeg: Double,
)

/**
 * A possible photo: a subject from a spot at a time, behind a landmark or alone over the horizon.
 * The photo shows the path of the subject from [pathStartUtc] to [pathEndUtc].
 */
data class Shot(
    val subject: SubjectId,
    val unixUtc: Double,
    val spot: Observer,
    val target: Target?,
    val separationDeg: Double,
    val body: Horizontal,
    val pathStartUtc: Double,
    val pathEndUtc: Double,
    val riseUtc: Double? = null,
    val setUtc: Double? = null,
    val eclipse: Pair<Eclipse, LocalEclipse>? = null,
)

/** The light of the sky from the airless altitude of the sun. */
data class Light(val sunDeg: Double, val label: String, val dark: Boolean)

fun light(unixUtc: Double, at: Observer): Light {
    val sun = sunPosition(unixUtc, at, SunOptions(refraction = false))
    val a = sun.altitudeDeg
    val label =
        when {
            a >= 0 -> "Daylight"
            a >= -6 -> "Blue hour"
            a >= -12 -> "Nautical twilight"
            a >= -18 -> "Astronomical twilight"
            else -> "Night"
        }
    return Light(a, label, a < -12)
}

/** A moon with less light than this shows only as a crescent in the twilight. */
private const val MIN_MOON_FRACTION = 0.1

/** The thinnest crescent that a photo shows. It is still near the sun. */
private const val MIN_CRESCENT_FRACTION = 0.03

/** The sun must be this low for a thin crescent: then the twilight and the earthshine show. */
private const val CRESCENT_MAX_SUN_DEG = -3.0

/** The path of an alignment: an hour before and after it. */
private const val AROUND_S = 3600.0

/** True if the subject is visible at the shot: a dark enough sky, and a moon that is lit. */
fun darkEnough(s: Shot): Boolean {
    val sunDeg = light(s.unixUtc, s.spot).sunDeg
    if (s.subject == SubjectId.MOON) {
        val fraction = moonIlluminatedFraction(s.unixUtc)
        if (fraction < MIN_CRESCENT_FRACTION) return false
        if (fraction < MIN_MOON_FRACTION && sunDeg > CRESCENT_MAX_SUN_DEG) return false
    }
    val limit = subjectOf(s.subject).maxSunDeg
    return limit == null || sunDeg <= limit
}

private val COMPASS =
    listOf(
        "N",
        "NNE",
        "NE",
        "ENE",
        "E",
        "ESE",
        "SE",
        "SSE",
        "S",
        "SSW",
        "SW",
        "WSW",
        "W",
        "WNW",
        "NW",
        "NNW",
    )

/** The point of the compass of an azimuth, for example "ENE". */
fun compass(azimuthDeg: Double): String =
    COMPASS[round(((azimuthDeg % 360) + 360) % 360 / 22.5).toInt() % 16]

/** "I know my spot": the dates of the next [days] days when the subject is behind the landmark. */
fun datesFor(
    subject: SubjectId,
    eye: Site,
    landmark: Site,
    name: String,
    startUtc: Double,
    days: Int = 365,
): List<Shot> {
    val s = subjectOf(subject)
    val view = sight(eye, landmark)
    val spot = Observer(eye.latitudeDeg, eye.longitudeDeg)
    val target =
        Target(
            view.direction,
            name,
            view.distanceM,
            landmark.elevationM,
            Observer(landmark.latitudeDeg, landmark.longitudeDeg),
        )
    return findAlignments(
            s.body,
            spot,
            view.direction,
            startUtc,
            startUtc + days * 86_400.0,
            AlignmentOptions(maxSeparationDeg = s.maxSeparationDeg),
        )
        .map {
            Shot(
                subject,
                it.unixUtc,
                spot,
                target,
                it.separationDeg,
                it.body,
                it.unixUtc - AROUND_S,
                it.unixUtc + AROUND_S,
            )
        }
        .filter { darkEnough(it) }
}

/**
 * A moment for the line of spots: the subject low over the horizon after its rise or before its
 * set.
 */
data class Moment(val unixUtc: Double, val rising: Boolean, val fraction: Double?)

/**
 * "Find me a spot": the next moments when the subject is a few degrees over the horizon, so a
 * landmark can stand in front of it. For the moon only near the full moon; for the Milky Way and
 * the planets only in a dark sky.
 */
fun momentsFor(subject: SubjectId, at: Observer, startUtc: Double, count: Int = 8): List<Moment> {
    val s = subjectOf(subject)
    val moments = mutableListOf<Moment>()
    val step = 600.0
    fun up(t: Double) = bodyPosition(s.body, t, at).altitudeDeg > 0
    var before = up(startUtc)
    var t = startUtc + step
    val end = startUtc + 200 * 86_400.0
    while (t < end && moments.size < count) {
        val now = up(t)
        if (now != before) {
            // About 3° over the horizon: 20 to 30 minutes after the rise or before the set.
            val time = if (now) t + 1500 else t - 1500
            // A set just after the start gives a moment in the past.
            if (time < startUtc) {
                before = now
                t += step
                continue
            }
            val fraction = if (s.body == Body.MOON) moonIlluminatedFraction(time) else null
            val dark = s.maxSunDeg == null || light(time, at).sunDeg <= s.maxSunDeg
            if ((fraction == null || fraction > 0.8) && dark) moments += Moment(time, now, fraction)
            // The next crossing is hours away; skip the rest of the night or the day.
            t += 4 * 3600
            before = up(t)
        } else {
            before = now
        }
        t += step
    }
    return moments
}

/** The landmarks of a place: the peaks of its skyline, with their place and height. */
fun peakTargets(horizon: Horizon, refractionCoefficient: Double = 0.13): List<Target> {
    val eye = horizon.groundM + 1.6
    return findPeaks(horizon.skyline).map { p ->
        val d = p.distanceM
        // The height from the altitude: the inverse of the model of sight().
        val drop = d * d * (1 - refractionCoefficient) / (2 * 6_371_008.8)
        Target(
            Horizontal(p.azimuthDeg, p.altitudeDeg),
            "Peak to the ${compass(p.azimuthDeg)}",
            d,
            eye + d * tan(Math.toRadians(p.altitudeDeg)) + drop,
            destination(horizon.observer, p.azimuthDeg, d),
        )
    }
}

/** "I am here": all subjects behind all peaks between the times, in a dark enough sky. */
fun shotsFromPlace(
    horizon: Horizon,
    targets: List<Target>,
    startUtc: Double,
    endUtc: Double,
): List<Shot> {
    val shots = mutableListOf<Shot>()
    for (target in targets) {
        for (s in SUBJECTS) {
            for (a in
                findAlignments(
                    s.body,
                    horizon.observer,
                    target.direction,
                    startUtc,
                    endUtc,
                    AlignmentOptions(maxSeparationDeg = s.maxSeparationDeg),
                )) {
                val shot =
                    Shot(
                        s.id,
                        a.unixUtc,
                        horizon.observer,
                        target,
                        a.separationDeg,
                        a.body,
                        a.unixUtc - AROUND_S,
                        a.unixUtc + AROUND_S,
                    )
                if (darkEnough(shot)) shots += shot
            }
        }
    }
    return shots.sortedBy { it.unixUtc }
}

/**
 * "I am here", without a landmark: each subject while it is over the terrain of the place and the
 * sky is dark enough for it. A shot is one such interval, at least 15 minutes long.
 */
fun skyFromPlace(horizon: Horizon, startUtc: Double, endUtc: Double): List<Shot> {
    val at = horizon.observer
    val shots = mutableListOf<Shot>()
    for (s in SUBJECTS) {
        // The intervals over the terrain. The limb of the sun and the moon counts; a point does
        // not.
        val limb = if (s.body == Body.SUN || s.body == Body.MOON) 0.27 else 0.0
        val events =
            terrainEvents(
                at,
                horizon.skyline,
                startUtc,
                endUtc,
                TerrainEventOptions(body = s.body, limbDeg = limb),
            )
        val visible = mutableListOf<Triple<Double, Double, Pair<Boolean, Boolean>>>()
        var open: Double? =
            if (events.crossings.isEmpty()) {
                if (events.kind == DayKind.POLAR_DAY) startUtc else null
            } else if (!events.crossings.first().rising) startUtc else null
        var rose = false
        for (c in events.crossings) {
            if (c.rising) {
                open = c.unixUtc
                rose = true
            } else if (open != null) {
                visible += Triple(open, c.unixUtc, rose to true)
                open = null
                rose = false
            }
        }
        open?.let { visible += Triple(it, endUtc, rose to false) }
        val dark =
            if (s.maxSunDeg == null) listOf(startUtc to endUtc)
            else
                darkWindows(
                        at,
                        startUtc,
                        endUtc,
                        DarkOptions(maxSunAltitudeDeg = s.maxSunDeg, maxMoonFraction = 1.0),
                    )
                    .map { it.startUtc to it.endUtc }
        for ((a, b, flags) in visible) {
            for ((ws, we) in dark) {
                val from = max(a, ws)
                val to = min(b, we)
                if (to - from < 900) continue
                val rise = flags.first && from == a
                val set = flags.second && to == b
                val lead = min(1200.0, (to - from) / 2)
                val best = if (rise) from + lead else if (set) to - lead else (from + to) / 2
                if (s.body == Body.MOON && moonIlluminatedFraction(best) < MIN_MOON_FRACTION)
                    continue
                shots +=
                    Shot(
                        s.id,
                        best,
                        at,
                        null,
                        0.0,
                        bodyPosition(s.body, best, at),
                        from,
                        to,
                        riseUtc = if (rise) a else null,
                        setUtc = if (set) b else null,
                    )
            }
        }
    }
    return shots.sortedBy { it.unixUtc }
}

/** The declination of a direction, seen from the latitude [latitudeDeg]. */
fun declinationDeg(direction: Horizontal, latitudeDeg: Double): Double {
    val phi = Math.toRadians(latitudeDeg)
    val a = Math.toRadians(direction.altitudeDeg)
    val z = Math.toRadians(direction.azimuthDeg)
    return Math.toDegrees(asin(sin(phi) * sin(a) + cos(phi) * cos(a) * cos(z)))
}

/** Why "I know my spot" found nothing, if the direction is out of the path of the subject. */
fun noMatchReason(
    subject: SubjectId,
    direction: Horizontal,
    at: Observer,
    startUtc: Double,
): String {
    val s = subjectOf(subject)
    val dec = declinationDeg(direction, at.latitudeDeg)
    var low = Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    // Every 3 hours over a year: the moon changes its declination by at most 1.5° in that time.
    var t = startUtc
    while (t <= startUtc + 365 * 86_400.0) {
        val d =
            declinationDeg(
                bodyPosition(s.body, t, at, BodyOptions(refraction = false)),
                at.latitudeDeg,
            )
        low = min(low, d)
        high = max(high, d)
        t += 3 * 3600
    }
    val name = s.name.lowercase()
    val range = "%.1f° to %.1f°".format(Locale.ROOT, low, high)
    if (dec < low - s.maxSeparationDeg || dec > high + s.maxSeparationDeg) {
        return "The $name never comes to this direction in the next 12 months: the landmark is at " +
            "the declination %.1f°, and the $name stays between $range. ".format(Locale.ROOT, dec) +
            "Look from another side of the landmark, or from higher up."
    }
    if (subject == SubjectId.MILKY_WAY) {
        return "No dark night with the center of the Milky Way behind the landmark in the next 12 months."
    }
    return "No match in the next 12 months, although the landmark is in the path of the $name ($range). " +
        "Another spot, a little to the side, can work."
}

// --- The eclipses -------------------------------------------------------------------------------

val ECLIPSE_NAMES =
    mapOf(
        EclipseKind.PENUMBRAL to "Penumbral",
        EclipseKind.PARTIAL to "Partial",
        EclipseKind.ANNULAR to "Annular",
        EclipseKind.TOTAL to "Total",
    )

/** The name of an eclipse, for example "Total solar eclipse". */
fun eclipseName(e: Eclipse, kind: EclipseKind = e.kind) =
    "${ECLIPSE_NAMES[kind]} ${if (e.solar) "solar" else "lunar"} eclipse"

/** The next solar eclipses (10 years) and lunar eclipses (5 years), in time order. */
fun upcomingEclipses(startUtc: Double): List<Eclipse> {
    val year = 365.25 * 86_400
    val solar =
        solarEclipses(startUtc, startUtc + 10 * year).map {
            Eclipse(
                true,
                it.kind,
                it.peakUtc,
                it.peakPlace.takeIf { p -> !p.latitudeDeg.isNaN() },
                null,
            )
        }
    val lunar =
        lunarEclipses(startUtc, startUtc + 5 * year).map {
            Eclipse(false, it.kind, it.peakUtc, null, it)
        }
    return (solar + lunar).sortedBy { it.peakUtc }
}

/** What a place sees of an eclipse, or null if it sees no part of a solar eclipse. */
fun localEclipse(e: Eclipse, at: Observer): LocalEclipse? {
    if (e.solar) {
        val local =
            localSolarEclipses(at, e.peakUtc - 86_400, e.peakUtc + 86_400).firstOrNull {
                abs(it.peakUtc - e.peakUtc) < 6 * 3600
            } ?: return null
        val airless = SunOptions(refraction = false)
        val airlessMoon = MoonOptions(refraction = false)
        fun phase(label: String, t: Double) = Phase(label, t, sunPosition(t, at).altitudeDeg)
        val central = !local.totalBeginUtc.isNaN()
        val name = if (local.kind == EclipseKind.ANNULAR) "annular" else "total"
        val phases = buildList {
            add(phase("First contact", local.partialBeginUtc))
            if (central) add(phase("The $name phase begins", local.totalBeginUtc))
            add(phase("The peak", local.peakUtc))
            if (central) add(phase("The $name phase ends", local.totalEndUtc))
            add(phase("Last contact", local.partialEndUtc))
        }
        // The radii from the contacts: at the first contact the discs touch outside (the sum of
        // the radii), at the second inside (the difference).
        fun gap(t: Double) =
            angleBetweenDeg(sunPosition(t, at, airless), moonPosition(t, at, airlessMoon))
        val outside = gap(local.partialBeginUtc)
        var sunRadius = 0.2666
        var moonRadius = outside - sunRadius
        if (central) {
            val inside = gap(local.totalBeginUtc)
            val total = local.kind == EclipseKind.TOTAL
            sunRadius = if (total) (outside - inside) / 2 else (outside + inside) / 2
            moonRadius = if (total) (outside + inside) / 2 else (outside - inside) / 2
        }
        return LocalEclipse(
            local.kind,
            local.obscuration,
            phases,
            phases.first { it.label == "The peak" },
            phases.any { it.altitudeDeg > 0 },
            sunRadius,
            moonRadius,
        )
    }
    val l = e.lunar!!
    fun phase(label: String, t: Double) = Phase(label, t, moonPosition(t, at).altitudeDeg)
    val phases = buildList {
        add(phase("Into the penumbra", l.penumbralBeginUtc))
        if (!l.partialBeginUtc.isNaN()) add(phase("Into the umbra", l.partialBeginUtc))
        if (!l.totalBeginUtc.isNaN()) add(phase("The total phase begins", l.totalBeginUtc))
        add(phase("The peak", l.peakUtc))
        if (!l.totalEndUtc.isNaN()) add(phase("The total phase ends", l.totalEndUtc))
        if (!l.partialEndUtc.isNaN()) add(phase("Out of the umbra", l.partialEndUtc))
        add(phase("Out of the penumbra", l.penumbralEndUtc))
    }
    return LocalEclipse(
        l.kind,
        l.obscuration,
        phases,
        phases.first { it.label == "The peak" },
        phases.any { it.altitudeDeg > 0 },
        0.2666,
        0.26,
    )
}

/**
 * The part of the moon in the umbra at a time, for the photo: up from the entry into the umbra to
 * the total phase (or to the peak of a partial eclipse), and down again. The penumbra dims a
 * little.
 */
fun lunarUmbra(l: LunarEclipse, t: Double): Double {
    fun ramp(a: Double, b: Double) = ((t - a) / (b - a)).coerceIn(0.0, 1.0)
    if (l.partialBeginUtc.isNaN()) {
        return 0.12 *
            min(ramp(l.penumbralBeginUtc, l.peakUtc), 1 - ramp(l.peakUtc, l.penumbralEndUtc))
    }
    val top = if (!l.totalBeginUtc.isNaN()) 1.0 else l.obscuration
    val into = if (!l.totalBeginUtc.isNaN()) l.totalBeginUtc else l.peakUtc
    val out = if (!l.totalEndUtc.isNaN()) l.totalEndUtc else l.peakUtc
    if (t < into) return top * ramp(l.partialBeginUtc, into)
    if (t > out) return top * (1 - ramp(out, l.partialEndUtc))
    return top
}

/**
 * The part of the area of the sun that the moon covers, from the angle between their centers and
 * their radii: the area of the overlap of two discs, divided by the area of the sun.
 */
fun discCoverage(separationDeg: Double, sunRadiusDeg: Double, moonRadiusDeg: Double): Double {
    val d = separationDeg
    val r = sunRadiusDeg
    val m = moonRadiusDeg
    if (d >= r + m) return 0.0
    if (d <= abs(m - r)) return if (m >= r) 1.0 else m * m / (r * r)
    // Near the touching distances, the rounding can put the arguments a little out of [-1, 1].
    val a = r * r * kotlin.math.acos(((d * d + r * r - m * m) / (2 * d * r)).coerceIn(-1.0, 1.0))
    val b = m * m * kotlin.math.acos(((d * d + m * m - r * r) / (2 * d * m)).coerceIn(-1.0, 1.0))
    val c = 0.5 * sqrt(max(0.0, (-d + r + m) * (d + r - m) * (d - r + m) * (d + r + m)))
    return ((a + b - c) / (PI * r * r)).coerceIn(0.0, 1.0)
}

/** The point at [distanceM] from [from] in the direction [azimuthDeg], on the sphere. */
fun destination(from: Observer, azimuthDeg: Double, distanceM: Double): Observer {
    val d = distanceM / 6_371_008.8
    val phi = Math.toRadians(from.latitudeDeg)
    val theta = Math.toRadians(azimuthDeg)
    val phi2 = asin(sin(phi) * cos(d) + cos(phi) * sin(d) * cos(theta))
    val lambda2 =
        Math.toRadians(from.longitudeDeg) +
            kotlin.math.atan2(sin(theta) * sin(d) * cos(phi), cos(d) - sin(phi) * sin(phi2))
    val lon = (Math.toDegrees(lambda2) + 540) % 360 - 180
    return Observer(Math.toDegrees(phi2), lon)
}
