package at.stefl.sunradar

import at.stefl.sunradar.core.Horizontal
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The moon at the selected time: its direction and the illuminated fraction, from 0 to 1. */
data class MoonAt(val direction: Horizontal, val fraction: Double)

/**
 * The shape of the lit part of the moon. The bright side of the moon faces the sun, along the great
 * circle from the moon to the sun. The iOS app and the PWA use the same calculation.
 */
object MoonPhase {
    /**
     * The direction at [angleDeg] from [moon] toward [sun], on the great circle through both. The
     * app projects the moon and this direction to get the direction of the sun on the screen. The
     * two points are near each other, so the projection works also when the sun is behind the
     * camera. If the sun is exactly opposite the moon, the direction goes up.
     */
    fun towardSun(moon: Horizontal, sun: Horizontal, angleDeg: Double = 1.0): Horizontal {
        val m = enu(moon)
        val s = enu(sun)
        val dot = m[0] * s[0] + m[1] * s[1] + m[2] * s[2]
        var t = DoubleArray(3) { s[it] - dot * m[it] }
        var norm = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
        if (norm < 1e-9) {
            // Up, without the part along the moon.
            t = doubleArrayOf(-m[2] * m[0], -m[2] * m[1], 1 - m[2] * m[2])
            norm = sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2])
            if (norm < 1e-9) return moon
        }
        val a = Math.toRadians(angleDeg)
        val p = DoubleArray(3) { m[it] * cos(a) + t[it] / norm * sin(a) }
        return Horizontal(
            (Math.toDegrees(atan2(p[0], p[1])) + 360) % 360,
            Math.toDegrees(asin(p[2].coerceIn(-1.0, 1.0))),
        )
    }

    /**
     * The outline of the lit part on a unit disc, as points (x, y). The x axis points to the sun.
     * The outline is the half of the limb that faces the sun, then the terminator back: a half
     * ellipse with the semi-axis 1 - 2 * [fraction] along x. So the area is pi * [fraction].
     */
    fun litOutline(fraction: Double, steps: Int = 24): List<Pair<Double, Double>> {
        val k = 1 - 2 * fraction.coerceIn(0.0, 1.0)
        val limb =
            (0..steps).map {
                val angle = -PI / 2 + PI * it / steps
                cos(angle) to sin(angle)
            }
        val terminator =
            (0..steps).map {
                val angle = PI / 2 - PI * it / steps
                k * cos(angle) to sin(angle)
            }
        return limb + terminator
    }

    private fun enu(h: Horizontal): DoubleArray {
        val az = Math.toRadians(h.azimuthDeg)
        val alt = Math.toRadians(h.altitudeDeg)
        return doubleArrayOf(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
    }
}
