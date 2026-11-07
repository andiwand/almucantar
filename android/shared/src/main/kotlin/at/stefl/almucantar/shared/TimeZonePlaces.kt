package at.stefl.almucantar.shared

import android.content.Context
import at.stefl.almucantar.core.Observer

/** The main city of a time zone, from `assets/tzdata/zone.tab` (IANA, public domain). */
data class TimeZonePlace(val observer: Observer, val city: String)

/** A line of zone.tab: the country code, the coordinates, and the time zone. */
internal data class ZoneLine(val country: String, val coordinates: String, val zone: String)

object TimeZonePlaces {
    /**
     * Returns the main city of the time zone [zoneId] (for example "Europe/Vienna"), or null if the
     * table does not have the zone (for example "Etc/UTC").
     */
    fun find(context: Context, zoneId: String): TimeZonePlace? = byZone(lines(context), zoneId)

    /**
     * Returns the main city of the country [countryCode] (ISO 3166, for example "AT"), or null if
     * the table does not have the country. A country with more than one zone gives the first zone
     * with the offset [offsetSeconds] of the device, else its first zone.
     */
    fun findCountry(context: Context, countryCode: String, offsetSeconds: Int): TimeZonePlace? =
        byCountry(lines(context), countryCode, offsetSeconds)

    private fun lines(context: Context): List<ZoneLine> =
        context.assets.open("zone.tab").bufferedReader().useLines { parse(it).toList() }

    internal fun parse(lines: Sequence<String>): Sequence<ZoneLine> =
        lines
            .filterNot { it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size >= 3 }
            .map { ZoneLine(it[0], it[1], it[2]) }

    internal fun byZone(lines: List<ZoneLine>, zoneId: String): TimeZonePlace? =
        lines.firstOrNull { it.zone == zoneId }?.let(::placeOf)

    internal fun byCountry(
        lines: List<ZoneLine>,
        countryCode: String,
        offsetSeconds: Int,
    ): TimeZonePlace? {
        val country = lines.filter { it.country.equals(countryCode, ignoreCase = true) }
        val now = java.time.Instant.now()
        val sameOffset = country.firstOrNull {
            zoneOrNull(it.zone)?.rules?.getOffset(now)?.totalSeconds == offsetSeconds
        }
        return (sameOffset ?: country.firstOrNull())?.let(::placeOf)
    }

    private fun placeOf(line: ZoneLine): TimeZonePlace? =
        parseIso6709(line.coordinates)?.let {
            TimeZonePlace(it, line.zone.substringAfterLast('/').replace('_', ' '))
        }

    /** Parses "+4813+01620" or "+404251-0740023" (ISO 6709, degrees, minutes, seconds). */
    internal fun parseIso6709(text: String): Observer? {
        val split = text.indexOfAny(charArrayOf('+', '-'), startIndex = 1)
        if (split < 0) return null
        val latitude = parseDegrees(text.substring(0, split), 2) ?: return null
        val longitude = parseDegrees(text.substring(split), 3) ?: return null
        return Observer(latitude, longitude)
    }

    private fun parseDegrees(part: String, degreeDigits: Int): Double? {
        val sign = if (part[0] == '-') -1.0 else 1.0
        val digits = part.substring(1)
        if (digits.length != degreeDigits + 2 && digits.length != degreeDigits + 4) return null
        val degrees = digits.substring(0, degreeDigits).toIntOrNull() ?: return null
        val minutes = digits.substring(degreeDigits, degreeDigits + 2).toIntOrNull() ?: return null
        val seconds =
            if (digits.length == degreeDigits + 4)
                digits.substring(degreeDigits + 2).toIntOrNull() ?: return null
            else 0
        return sign * (degrees + minutes / 60.0 + seconds / 3600.0)
    }
}
