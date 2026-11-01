package at.stefl.sunradar

import android.content.Context
import at.stefl.sunradar.core.Observer

/** The main city of a time zone, from `assets/tzdata/zone.tab` (IANA, public domain). */
data class TimeZonePlace(val observer: Observer, val city: String)

object TimeZonePlaces {
    /**
     * Returns the main city of the time zone [zoneId] (for example "Europe/Vienna"), or null if the
     * table does not have the zone (for example "Etc/UTC").
     */
    fun find(context: Context, zoneId: String): TimeZonePlace? =
        context.assets.open("tzdata/zone.tab").bufferedReader().useLines { lines ->
            lines
                .filterNot { it.startsWith("#") }
                .map { it.split('\t') }
                .firstOrNull { it.size >= 3 && it[2] == zoneId }
                ?.let { columns ->
                    parseIso6709(columns[1])?.let {
                        TimeZonePlace(it, zoneId.substringAfterLast('/').replace('_', ' '))
                    }
                }
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
