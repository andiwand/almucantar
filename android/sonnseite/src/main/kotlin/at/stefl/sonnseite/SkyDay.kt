package at.stefl.sonnseite

import at.stefl.almucantar.core.DayKind
import at.stefl.almucantar.core.DirectionLine
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.PathOptions
import at.stefl.almucantar.core.PathPoint
import at.stefl.almucantar.core.sunEvents
import at.stefl.almucantar.core.sunPath
import at.stefl.almucantar.core.sunPosition
import at.stefl.almucantar.shared.dayBounds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A mark on the path at a full hour of local time, or at a sunrise or a sunset. */
data class HourMark(val direction: Horizontal, val label: String)

/**
 * The data of one local day, calculated once per day and place, so the frames only project. The
 * path has a point every 5 minutes, so it is dense enough for the clipping.
 */
class SkyDay(val observer: Observer, val startUtc: Double, val endUtc: Double, zone: ZoneId) {
    /** The path with a point every 5 minutes. */
    val path: List<PathPoint> = sunPath(observer, startUtc, endUtc, PathOptions(stepS = 300.0))

    /** The full path, also below the horizon. */
    val fullPath: DirectionLine = DirectionLine.fromPath(path)

    /** The parts of the path above the horizon. */
    val daySegments: List<DirectionLine> = buildList {
        var current = mutableListOf<Horizontal>()
        for (point in path) {
            if (point.position.altitudeDeg >= 0) {
                current.add(point.position)
            } else if (current.isNotEmpty()) {
                add(current)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) add(current)
    }
        .filter { it.size >= 2 }
        .map { DirectionLine.of(it) }

    /** The full hours above -6 degrees (the civil twilight). */
    val hourMarks: List<HourMark> =
        path
            .filter { ((it.unixUtc - startUtc) % 3600.0) == 0.0 && it.position.altitudeDeg > -6 }
            .map { HourMark(it.position, formatHour(it.unixUtc, zone)) }

    private val events = sunEvents(observer, startUtc, endUtc)

    /** The sunrises and sunsets, as marks on the horizon ("↑ 07:37", "↓ 17:30"). */
    val horizonMarks: List<HourMark> =
        events.crossings.map {
            HourMark(
                sunPosition(it.unixUtc, observer),
                (if (it.rising) "↑ " else "↓ ") + formatTime(it.unixUtc, zone),
            )
        }

    /** For example "↑ 04:53 · ↓ 21:00", or "Polar day". */
    val summary: String =
        when (events.kind) {
            DayKind.POLAR_DAY -> "Polar day: the sun does not set"
            DayKind.POLAR_NIGHT -> "Polar night: the sun does not rise"
            DayKind.NORMAL ->
                events.crossings.joinToString(" · ") {
                    (if (it.rising) "Sunrise " else "Sunset ") + formatTime(it.unixUtc, zone)
                }
        }

    companion object {
        private val HOUR = DateTimeFormatter.ofPattern("HH")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        fun formatHour(unixUtc: Double, zone: ZoneId): String =
            HOUR.format(Instant.ofEpochSecond(unixUtc.toLong()).atZone(zone))

        fun formatTime(unixUtc: Double, zone: ZoneId): String =
            TIME.format(Instant.ofEpochSecond(Math.round(unixUtc)).atZone(zone))
    }
}

/** The parts above the horizon of the paths of the two solstices of [year], as guide lines. */
fun solsticeGuides(observer: Observer, year: Int, zone: ZoneId): List<DirectionLine> =
    listOf(6, 12).flatMap { month ->
        val (start, end) = dayBounds(LocalDate.of(year, month, 21), zone)
        SkyDay(observer, start, end, zone).daySegments
    }
