package at.stefl.sunradar

import at.stefl.sunradar.core.Observer
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geodesy on a sphere. For the distances of a landmark (up to some kilometres) it is sufficient.
 */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** The initial true bearing from [from] to [to], in degrees from north, 0 to 360. */
    fun bearingDeg(from: Observer, to: Observer): Double {
        val p1 = Math.toRadians(from.latitudeDeg)
        val p2 = Math.toRadians(to.latitudeDeg)
        val dl = Math.toRadians(to.longitudeDeg - from.longitudeDeg)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** The great-circle distance in metres (haversine). */
    fun distanceM(from: Observer, to: Observer): Double {
        val p1 = Math.toRadians(from.latitudeDeg)
        val p2 = Math.toRadians(to.latitudeDeg)
        val dp = p2 - p1
        val dl = Math.toRadians(to.longitudeDeg - from.longitudeDeg)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }
}
