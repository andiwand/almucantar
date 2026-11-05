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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
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

fun frameOf(
    shot: Shot,
    horizon: Horizon,
    t: Double,
    focalMm: Int,
    thumb: Boolean,
    zone: ZoneId,
    sky: Sky?,
    pan: Pan = Pan(),
): PhotoFrame {
    val at = shot.spot
    val sun = sunPosition(t, at)
    val moon = moonPosition(t, at)
    val s = subjectOf(shot.subject)
    val body = bodyPosition(s.body, t, at)
    // The view: on the landmark, or on the subject alone at the time of the shot.
    val center = shot.target?.direction ?: bodyPosition(s.body, shot.unixUtc, at)
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
    val view =
        PhotoView(
            center.azimuthDeg + pan.azimuthDeg,
            (altitude + pan.altitudeDeg).coerceIn(-30.0, 89.0),
            span,
        )
    val sunAlt = sunPosition(t, at, SunOptions(refraction = false)).altitudeDeg
    val limit = limitingMagnitude(sunAlt)
    val night = sunAlt < -8
    // The path of the subject, with the full hours.
    val points = mutableListOf<Horizontal>()
    val ticks = mutableListOf<Pair<Horizontal, String>>()
    var u = shot.pathStartUtc
    while (u <= shot.pathEndUtc) {
        val d = bodyPosition(s.body, u, at)
        points += d
        if (u.roundToInt().mod(3600) < 300) ticks += d to clock(floor(u / 3600) * 3600, zone)
        u += 300
    }
    val fraction = moonIlluminatedFraction(t)
    val eclipse = shot.eclipse
    val solar =
        eclipse
            ?.takeIf { it.first.solar }
            ?.let { (_, local) ->
                SolarDiscs(
                    local.sunRadiusDeg,
                    local.moonRadiusDeg,
                    discCoverage(separationDeg(sun, moon), local.sunRadiusDeg, local.moonRadiusDeg),
                )
            }
    val stars = sky?.stars
    return PhotoFrame(
        view = view,
        sun = sun,
        moon = moon,
        moonFraction = fraction,
        terrain = horizon.terrain(),
        track = PhotoTrack(points, ticks),
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
        separationDeg(sunPosition(t, shot.spot), moonPosition(t, shot.spot)),
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

fun km(m: Double) = if (m < 10_000) "%.2f km".format(m / 1000) else "%.1f km".format(m / 1000)

fun deg(d: Double, digits: Int = 1) = "%.${digits}f°".format(d)

fun percent(f: Double) = "${(min(1.0, max(0.0, f)) * 100).roundToInt()} %"
