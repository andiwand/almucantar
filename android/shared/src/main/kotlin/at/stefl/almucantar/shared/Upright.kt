package at.stefl.almucantar.shared

import at.stefl.almucantar.core.Quaternion
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// The view of the demo modes of the store screenshots: a device held upright, without sensors.

/**
 * A device held upright in its natural orientation, with the back camera to [azimuthDeg] and
 * pitched up by [pitchDeg], as a quaternion from the device frame to ENU. The PWA has the same
 * function (`upright` in `web/shared/src/orientation.ts`).
 */
fun upright(azimuthDeg: Double, pitchDeg: Double): Quaternion {
    val a = Math.toRadians(azimuthDeg)
    val p = Math.toRadians(pitchDeg)
    val forward = doubleArrayOf(sin(a) * cos(p), cos(a) * cos(p), sin(p))
    val right = doubleArrayOf(cos(a), -sin(a), 0.0)
    val up = doubleArrayOf(-sin(a) * sin(p), -cos(a) * sin(p), cos(p))
    return fromAxes(right, up, DoubleArray(3) { -forward[it] })
}

/** The quaternion of a rotation matrix with the columns [x], [y], and [z]. */
internal fun fromAxes(x: DoubleArray, y: DoubleArray, z: DoubleArray): Quaternion {
    val (m00, m10, m20) = x
    val (m01, m11, m21) = y
    val (m02, m12, m22) = z
    val trace = m00 + m11 + m22
    return when {
        trace > 0 -> {
            val s = 2 * sqrt(trace + 1)
            Quaternion(s / 4, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
        }
        m00 > m11 && m00 > m22 -> {
            val s = 2 * sqrt(1 + m00 - m11 - m22)
            Quaternion((m21 - m12) / s, s / 4, (m01 + m10) / s, (m02 + m20) / s)
        }
        m11 > m22 -> {
            val s = 2 * sqrt(1 + m11 - m00 - m22)
            Quaternion((m02 - m20) / s, (m01 + m10) / s, s / 4, (m12 + m21) / s)
        }
        else -> {
            val s = 2 * sqrt(1 + m22 - m00 - m11)
            Quaternion((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, s / 4)
        }
    }
}
