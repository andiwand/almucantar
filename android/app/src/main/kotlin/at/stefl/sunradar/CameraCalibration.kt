package at.stefl.sunradar

import at.stefl.sunradar.core.Camera
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The calibration of a camera from Camera2, in the coordinates of Camera2. Only the plain values,
 * so the conversion is testable without Android.
 */
data class SensorCalibration(
    /**
     * `LENS_INTRINSIC_CALIBRATION` [fx, fy, cx, cy] in the pre-correction active array, or null.
     */
    val intrinsics: DoubleArray?,
    /**
     * `LENS_DISTORTION` [k1, k2, k3, p1, p2] (kappa_1..5), or null. Ignored if [distorted] is
     * false.
     */
    val distortion: DoubleArray?,
    /** True if the preview shows the distorted image (`DISTORTION_CORRECTION_MODE_OFF`). */
    val distorted: Boolean,
    /** `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`: left, top (in the pixel array). */
    val preCorrectionLeft: Double,
    val preCorrectionTop: Double,
    /** `SENSOR_INFO_ACTIVE_ARRAY_SIZE`: left, top, width, height (in the pixel array). */
    val activeLeft: Double,
    val activeTop: Double,
    val activeWidth: Double,
    val activeHeight: Double,
    /** For the fallback: `LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]` in mm. */
    val focalLengthMm: Double,
    /** For the fallback: `SENSOR_INFO_PHYSICAL_SIZE` in mm and `SENSOR_INFO_PIXEL_ARRAY_SIZE`. */
    val physicalWidthMm: Double,
    val physicalHeightMm: Double,
    val pixelArrayWidth: Double,
    val pixelArrayHeight: Double,
) {
    override fun equals(other: Any?) = this === other

    override fun hashCode() = System.identityHashCode(this)
}

/** Where the focal length comes from, for the status. */
enum class CalibrationSource {
    /** `LENS_INTRINSIC_CALIBRATION`, with `LENS_DISTORTION` if the device has it. */
    INTRINSICS,
    /** The focal length and the size of the sensor, without distortion. */
    FOCAL_LENGTH,
}

data class ViewCamera(val camera: Camera, val source: CalibrationSource)

/**
 * Converts the calibration of the sensor to a [Camera] in the pixels of the preview view.
 *
 * [sensorToView] has the 9 values of `PreviewView.getSensorToViewTransform()` (the order of
 * `android.graphics.Matrix.getValues`). The matrix maps the active array to the view. Its linear
 * part is s * R, where R rotates by a multiple of 90 degrees. Then:
 * - the principal point is M * c,
 * - the focal lengths are scaled by s, and they swap for 90 and 270 degrees,
 * - the normalized coordinates rotate with R, so the radial distortion stays the same, and the
 *   tangential vector t = (p2, p1) rotates with R, because the tangential term is T(p) = r^2 t + 2
 *   (t . p) p.
 *
 * Returns null if the matrix is not a scaled rotation by a multiple of 90 degrees.
 */
fun viewCamera(
    calibration: SensorCalibration,
    sensorToView: FloatArray,
    viewWidth: Double,
    viewHeight: Double,
): ViewCamera? {
    val c = calibration
    val intrinsics = c.intrinsics?.takeIf { it.size >= 4 && it[0] > 0 && it[1] > 0 }

    // The camera in the active array.
    val fx: Double
    val fy: Double
    val cx: Double
    val cy: Double
    val source: CalibrationSource
    if (intrinsics != null) {
        fx = intrinsics[0]
        fy = intrinsics[1]
        // The intrinsics are in the pre-correction array; the matrix starts at the active array.
        cx = intrinsics[2] - (c.activeLeft - c.preCorrectionLeft)
        cy = intrinsics[3] - (c.activeTop - c.preCorrectionTop)
        source = CalibrationSource.INTRINSICS
    } else {
        if (c.physicalWidthMm <= 0 || c.physicalHeightMm <= 0 || c.focalLengthMm <= 0) return null
        fx = c.focalLengthMm / c.physicalWidthMm * c.pixelArrayWidth
        fy = c.focalLengthMm / c.physicalHeightMm * c.pixelArrayHeight
        cx = c.activeWidth / 2
        cy = c.activeHeight / 2
        source = CalibrationSource.FOCAL_LENGTH
    }
    val k =
        c.distortion?.takeIf { c.distorted && intrinsics != null && it.size >= 5 } ?: DoubleArray(5)

    // M = [a b tx; d e ty]: x' = a x + b y + tx, y' = d x + e y + ty.
    val a = sensorToView[0].toDouble()
    val b = sensorToView[1].toDouble()
    val tx = sensorToView[2].toDouble()
    val d = sensorToView[3].toDouble()
    val e = sensorToView[4].toDouble()
    val ty = sensorToView[5].toDouble()
    val s = sqrt(abs(a * e - b * d))
    if (s <= 0) return null
    // R, snapped to 0 and ±1.
    val exact = doubleArrayOf(a / s, b / s, d / s, e / s)
    val r = exact.map { it.roundToInt() }
    if (r.indices.any { abs(exact[it] - r[it]) > 1e-3 }) return null
    if (abs(r[0]) + abs(r[1]) != 1 || abs(r[2]) + abs(r[3]) != 1) return null

    // The tangential vector t = (p2, p1) rotates with R.
    val p1 = k[3]
    val p2 = k[4]
    val p2View = r[0] * p2 + r[1] * p1
    val p1View = r[2] * p2 + r[3] * p1

    return ViewCamera(
        Camera(
            width = viewWidth,
            height = viewHeight,
            fx = s * (abs(r[0]) * fx + abs(r[1]) * fy),
            fy = s * (abs(r[2]) * fx + abs(r[3]) * fy),
            cx = a * cx + b * cy + tx,
            cy = d * cx + e * cy + ty,
            k1 = k[0],
            k2 = k[1],
            k3 = k[2],
            p1 = p1View,
            p2 = p2View,
        ),
        source,
    )
}
