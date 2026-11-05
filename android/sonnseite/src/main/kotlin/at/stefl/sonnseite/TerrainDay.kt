package at.stefl.sonnseite

import at.stefl.almucantar.core.DayKind
import at.stefl.almucantar.core.DirectionLine
import at.stefl.almucantar.core.HorizonProfile
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.sunPosition
import at.stefl.almucantar.core.terrainAltitudeDeg
import at.stefl.almucantar.core.terrainEvents
import java.time.ZoneId

/**
 * The terrain on one day: the parts of the path above the horizon that the terrain hides or not,
 * and the visible sunrise and sunset. It uses the native library, so make it off the main thread.
 */
class TerrainDay(val day: SkyDay, profile: HorizonProfile, zone: ZoneId) {
    /** The parts of the path above the horizon and above the terrain. */
    val visibleSegments: List<DirectionLine>

    /** The parts of the path above the horizon but behind the terrain. */
    val hiddenSegments: List<DirectionLine>

    /** The times when the sun appears and goes away behind the terrain, on the terrain line. */
    val marks: List<HourMark>

    /** For example "Visible sunrise 07:52 · visible sunset 16:10". */
    val summary: String

    init {
        val visible = mutableListOf<DirectionLine>()
        val hidden = mutableListOf<DirectionLine>()
        var run = mutableListOf<Horizontal>()
        var runHidden = false
        fun flush() {
            if (run.size >= 2) (if (runHidden) hidden else visible) += DirectionLine.of(run)
            run = mutableListOf()
        }
        for (point in day.path) {
            val p = point.position
            if (p.altitudeDeg < 0) {
                flush()
                continue
            }
            val terrain = terrainAltitudeDeg(profile, p.azimuthDeg)
            val behind = !terrain.isNaN() && p.altitudeDeg < terrain
            if (run.isNotEmpty() && behind != runHidden) {
                // The point of the change belongs to both parts, so the line has no gap.
                val last = run.last()
                flush()
                run += last
            }
            runHidden = behind
            run += p
        }
        flush()
        visibleSegments = visible
        hiddenSegments = hidden

        val events = terrainEvents(day.observer, profile, day.startUtc, day.endUtc)
        marks =
            events.crossings.map {
                HourMark(
                    sunPosition(it.unixUtc, day.observer),
                    (if (it.rising) "↑ " else "↓ ") + SkyDay.formatTime(it.unixUtc, zone),
                )
            }
        val rise = events.crossings.firstOrNull { it.rising }
        val set = events.crossings.lastOrNull { !it.rising }
        // The sun goes away behind a peak and appears again: each setting but the last.
        val hides = events.crossings.count { !it.rising } - 1
        summary =
            when (events.kind) {
                DayKind.POLAR_DAY -> "The terrain does not hide the sun on this day"
                DayKind.POLAR_NIGHT -> "The terrain hides the sun all day"
                DayKind.NORMAL ->
                    listOfNotNull(
                            rise?.let { "Visible sunrise " + SkyDay.formatTime(it.unixUtc, zone) },
                            set?.let { "visible sunset " + SkyDay.formatTime(it.unixUtc, zone) },
                            if (hides > 0) "hidden ${hides}× during the day" else null,
                        )
                        .joinToString(" · ")
                        .replaceFirstChar { it.uppercase() }
            }
    }
}
