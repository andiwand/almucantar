package at.stefl.almucantar.shared

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The local time of a place. All days and times use the time zone of the place (Place.zone), not
// the time zone of the device.

/**
 * The Unix time of the wall clock time [minuteOfDay] on [date]. It is not midnight + minutes,
 * because a day with a DST change has 23 or 25 hours.
 */
fun wallClockTime(date: LocalDate, minuteOfDay: Int, zone: ZoneId): Double =
    date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toEpochSecond().toDouble()

/** The local date of [unixUtc] in [zone]: the day of the place, not of the device. */
fun localDate(unixUtc: Double, zone: ZoneId): LocalDate =
    Instant.ofEpochSecond(unixUtc.toLong()).atZone(zone).toLocalDate()

/**
 * The start and the end of [date] in [zone], as Unix times. A day with a DST change has 23 or 25
 * hours.
 */
fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Double, Double> =
    date.atStartOfDay(zone).toEpochSecond().toDouble() to
        date.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble()
