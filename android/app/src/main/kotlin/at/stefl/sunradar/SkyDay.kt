package at.stefl.sunradar

import at.stefl.sunradar.core.DayKind
import at.stefl.sunradar.core.DirectionLine
import at.stefl.sunradar.core.Horizontal
import at.stefl.sunradar.core.Observer
import at.stefl.sunradar.core.PathOptions
import at.stefl.sunradar.core.sunEvents
import at.stefl.sunradar.core.sunPath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A mark on the path at a full hour of local time. */
data class HourMark(val direction: Horizontal, val label: String)

/**
 * The data of one local day, calculated once per day and place, so the frames only project. The
 * path has a point every 5 minutes, so it is dense enough for the clipping.
 */
class SkyDay(val observer: Observer, val startUtc: Double, val endUtc: Double, zone: ZoneId) {
    private val path = sunPath(observer, startUtc, endUtc, PathOptions(stepS = 300.0))

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

    /** For example "Sunrise 04:53, sunset 21:00", or "Polar day". */
    val summary: String =
        sunEvents(observer, startUtc, endUtc).let { events ->
            when (events.kind) {
                DayKind.POLAR_DAY -> "Polar day"
                DayKind.POLAR_NIGHT -> "Polar night"
                DayKind.NORMAL ->
                    events.crossings.joinToString(", ") {
                        (if (it.rising) "Sunrise " else "Sunset ") + formatTime(it.unixUtc, zone)
                    }
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
