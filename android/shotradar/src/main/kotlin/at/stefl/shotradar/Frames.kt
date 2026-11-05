package at.stefl.shotradar

import at.stefl.almucantar.core.Body
import at.stefl.almucantar.core.EclipseKind
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.SunOptions
import at.stefl.almucantar.core.bodyPosition
import at.stefl.almucantar.core.moonIlluminatedFraction
import at.stefl.almucantar.core.moonPosition
import at.stefl.almucantar.core.starPositions
import at.stefl.almucantar.core.sunPosition
import at.stefl.almucantar.shared.MilkyWay
import at.stefl.almucantar.shared.angleBetweenDeg
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The frame of the photo of a shot at a time, with a lens and a pan, and the formats of the
// numbers. The PWA builds the same frame (frameOf in web/shotradar/app/src/main.ts).

val LENSES = listOf(24, 50, 135, 300, 600)

/** The pan of the photo: the change of the direction of the view, and a zoom of the view. */
data class Pan(val azimuthDeg: Double = 0.0, val altitudeDeg: Double = 0.0, val zoom: Double = 1.0)

/** The faintest star that the eye sees for an airless altitude of the sun. */
fun limitingMagnitude(sunAltitudeDeg: Double): Double =
    when {
        sunAltitudeDeg >= 0 -> -2.0
        sunAltitudeDeg >= -6 -> -1 + (sunAltitudeDeg / -6) * 2
        sunAltitudeDeg >= -12 -> 1 + ((sunAltitudeDeg + 6) / -6) * 2.5
        sunAltitudeDeg >= -18 -> 3.5 + ((sunAltitudeDeg + 12) / -6) * 2.5
        else -> 6.0
    }

/** The stars and the Milky Way of the photos, loaded once. */
class Sky(val stars: Stars?, val milkyWay: MilkyWay)

/** The span of the view of the full photo: a little wider than the lens, so its frame shows. */
fun viewSpanDeg(focalMm: Int, thumb: Boolean, pan: Pan) =
    lensSpanDeg(focalMm) * (if (thumb) 1.0 else 1.3) / pan.zoom

/**
 * The view of the photo: on the landmark, or on the subject alone at the time of the shot, with a
 * pan.
 */
fun viewOf(shot: Shot, focalMm: Int, thumb: Boolean, pan: Pan = Pan()): PhotoView {
    val center =
        shot.target?.direction
            ?: bodyPosition(subjectOf(shot.subject).body, shot.unixUtc, shot.spot)
    val span = viewSpanDeg(focalMm, thumb, pan)
    // A subject alone: the horizon stays in the lower part of the photo, if the subject is low
    // enough for that; a high subject stays in the photo. An eclipse aims at the sun or the moon.
    val altitude =
        when {
            shot.eclipse != null -> center.altitudeDeg
            shot.target != null -> max(center.altitudeDeg, -1.0) + span * 0.12
            // 0.15 of the width keeps a high subject inside a photo of 3:2.
            else -> max(span * 0.22, center.altitudeDeg - span * 0.15)
        }
    return PhotoView(
        center.azimuthDeg + pan.azimuthDeg,
        (altitude + pan.altitudeDeg).coerceIn(-30.0, 89.0),
        span,
    )
}

/**
 * The same frame with another view. A pan moves the view and the frame of the lens, not the sky.
 */
fun PhotoFrame.withView(view: PhotoView): PhotoFrame =
    copy(
        view = view,
        finder =
            finder?.let {
                PhotoFinder(Horizontal(view.azimuthDeg, view.altitudeDeg), it.focalMm, it.alpha)
            },
    )

/**
 * The full hours of the wall clock of [zone] from [startUtc] to [endUtc], in Unix seconds. A zone
 * can have an offset of 30 or 45 minutes, so a full hour in UTC is not always a full hour there.
 */
fun fullHours(startUtc: Double, endUtc: Double, zone: ZoneId): List<Double> {
    // The offsets of all time zones are whole quarter hours.
    val step = 900L
    val hours = mutableListOf<Double>()
    var u = ceil(startUtc / step).toLong() * step
    while (u <= endUtc) {
        val offset = zone.rules.getOffset(Instant.ofEpochSecond(u)).totalSeconds
        if ((u + offset).mod(3600L) == 0L) hours += u.toDouble()
        u += step
    }
    return hours
}

/** The path of the subject of a shot, every 5 minutes, with a mark at each full hour of [zone]. */
fun trackOf(shot: Shot, zone: ZoneId): PhotoTrack {
    val body = subjectOf(shot.subject).body
    val points = mutableListOf<Horizontal>()
    var u = shot.pathStartUtc
    while (u <= shot.pathEndUtc) {
        points += bodyPosition(body, u, shot.spot)
        u += 300
    }
    val ticks =
        fullHours(shot.pathStartUtc, shot.pathEndUtc, zone).map {
            bodyPosition(body, it, shot.spot) to clock(it, zone)
        }
    return PhotoTrack(points, ticks)
}

fun frameOf(
    shot: Shot,
    horizon: Horizon,
    t: Double,
    focalMm: Int,
    thumb: Boolean,
    zone: ZoneId,
    sky: Sky?,
    pan: Pan = Pan(),
    track: PhotoTrack? = null,
): PhotoFrame {
    val at = shot.spot
    val sun = sunPosition(t, at)
    val moon = moonPosition(t, at)
    val s = subjectOf(shot.subject)
    val body = bodyPosition(s.body, t, at)
    val view = viewOf(shot, focalMm, thumb, pan)
    val sunAlt = sunPosition(t, at, SunOptions(refraction = false)).altitudeDeg
    val limit = limitingMagnitude(sunAlt)
    val night = sunAlt < -8
    val fraction = moonIlluminatedFraction(t)
    val eclipse = shot.eclipse
    val solar =
        eclipse
            ?.takeIf { it.first.solar }
            ?.let { (_, local) ->
                SolarDiscs(
                    local.sunRadiusDeg,
                    local.moonRadiusDeg,
                    discCoverage(
                        angleBetweenDeg(sun, moon),
                        local.sunRadiusDeg,
                        local.moonRadiusDeg,
                    ),
                )
            }
    val stars = sky?.stars
    return PhotoFrame(
        view = view,
        sun = sun,
        moon = moon,
        moonFraction = fraction,
        terrain = horizon.terrain(),
        track = track ?: trackOf(shot, zone),
        body =
            if (s.body == Body.MOON || s.body == Body.GALACTIC_CENTER) null
            else PhotoBody(s.body == Body.SUN, body, s.name),
        stars =
            if (night && stars != null && !thumb)
                PhotoStars(stars, starPositions(stars.catalog, t, at), limit)
            else null,
        dust =
            if (night && s.body == Body.GALACTIC_CENTER && sky != null)
                PhotoDust(
                    sky.milkyWay,
                    starPositions(sky.milkyWay.catalog, t, at),
                    ((limit - 3.5) / 2.5).coerceIn(0.0, 1.0),
                )
            else null,
        pin =
            shot.target
                ?.takeIf { !thumb }
                ?.let {
                    val height = it.elevationM?.let { m -> "${m.roundToInt()} M · " } ?: ""
                    PhotoPin(
                        it.direction,
                        it.name.uppercase(),
                        "$height${km(it.distanceM).uppercase()}",
                        1f,
                    )
                },
        finder =
            if (thumb) null
            else PhotoFinder(Horizontal(view.azimuthDeg, view.altitudeDeg), focalMm, 0.9f),
        hud =
            if (thumb) null
            else
                PhotoHud(
                    "AZ ${deg(body.azimuthDeg)} · ALT ${deg(body.altitudeDeg)}",
                    if (s.body == Body.MOON) "MOON ${(fraction * 100).roundToInt()} %"
                    else s.name.uppercase(),
                    dateTime(t, zone).uppercase(),
                ),
        solarEclipse = solar,
        lunarUmbra = eclipse?.first?.lunar?.let { lunarUmbra(it, t) } ?: 0.0,
    )
}

/** The part of the sun that the moon covers now, or of the moon in the umbra, for a shot. */
fun coveredNow(shot: Shot, t: Double): Double {
    val (e, local) = shot.eclipse ?: return 0.0
    if (!e.solar) return e.lunar?.let { lunarUmbra(it, t) } ?: 0.0
    return discCoverage(
        angleBetweenDeg(sunPosition(t, shot.spot), moonPosition(t, shot.spot)),
        local.sunRadiusDeg,
        local.moonRadiusDeg,
    )
}

/** True in the total phase of a solar eclipse. */
fun totality(shot: Shot, t: Double): Boolean {
    val (e, local) = shot.eclipse ?: return false
    return e.solar && local.kind == EclipseKind.TOTAL && coveredNow(shot, t) >= 0.999
}

// --- The formats ---------------------------------------------------------------------------

/** A format in the locale of now, so a change of the locale applies at once. */
private fun format(pattern: String, unix: Double, zone: ZoneId): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .format(Instant.ofEpochSecond(unix.toLong()).atZone(zone))

fun date(unix: Double, zone: ZoneId): String = format("EEE d MMM yyyy", unix, zone)

fun clock(unix: Double, zone: ZoneId): String = format("HH:mm", unix, zone)

fun dateTime(unix: Double, zone: ZoneId): String = format("EEE d MMM yyyy, HH:mm", unix, zone)

fun km(m: Double) =
    if (m < 10_000) "%.2f km".format(Locale.ROOT, m / 1000)
    else "%.1f km".format(Locale.ROOT, m / 1000)

fun deg(d: Double, digits: Int = 1) = "%.${digits}f°".format(Locale.ROOT, d)

fun percent(f: Double) = "${(min(1.0, max(0.0, f)) * 100).roundToInt()} %"
