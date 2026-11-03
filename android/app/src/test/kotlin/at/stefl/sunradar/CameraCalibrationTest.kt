package at.stefl.sunradar

import at.stefl.almucantar.core.Camera
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraCalibrationTest {
    private val calibration =
        SensorCalibration(
            intrinsics = doubleArrayOf(3000.0, 2990.0, 2010.0, 1505.0),
            distortion = doubleArrayOf(-0.08, 0.02, -0.004, 0.0008, -0.0005),
            distorted = true,
            preCorrectionLeft = 8.0,
            preCorrectionTop = 6.0,
            activeLeft = 16.0,
            activeTop = 12.0,
            activeWidth = 4000.0,
            activeHeight = 3000.0,
            focalLengthMm = 6.9,
            physicalWidthMm = 9.6,
            physicalHeightMm = 7.2,
            pixelArrayWidth = 4032.0,
            pixelArrayHeight = 3024.0,
        )

    /** The pixel of an ideal normalized point in a camera model (Brown-Conrady, OpenCV order). */
    private fun pixel(
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        k: DoubleArray,
        x: Double,
        y: Double,
    ): DoubleArray {
        val r2 = x * x + y * y
        val radial = 1 + k[0] * r2 + k[1] * r2 * r2 + k[2] * r2 * r2 * r2
        val xd = x * radial + 2 * k[3] * x * y + k[4] * (r2 + 2 * x * x)
        val yd = y * radial + k[3] * (r2 + 2 * y * y) + 2 * k[4] * x * y
        return doubleArrayOf(fx * xd + cx, fy * yd + cy)
    }

    private fun pixel(c: Camera, x: Double, y: Double) =
        pixel(c.fx, c.fy, c.cx, c.cy, doubleArrayOf(c.k1, c.k2, c.k3, c.p1, c.p2), x, y)

    /**
     * A matrix that rotates the active array by [degrees] clockwise, scales by [scale], and moves.
     */
    private fun matrix(degrees: Int, scale: Double, tx: Double, ty: Double): FloatArray {
        val (r00, r01, r10, r11) =
            when (degrees) {
                0 -> listOf(1, 0, 0, 1)
                90 -> listOf(0, -1, 1, 0)
                180 -> listOf(-1, 0, 0, -1)
                else -> listOf(0, 1, -1, 0)
            }
        return floatArrayOf(
            (scale * r00).toFloat(),
            (scale * r01).toFloat(),
            tx.toFloat(),
            (scale * r10).toFloat(),
            (scale * r11).toFloat(),
            ty.toFloat(),
            0f,
            0f,
            1f,
        )
    }

    @Test
    fun theViewModelAgreesWithTheSensorModelAndTheMatrix() {
        val random = Random(1)
        for (degrees in listOf(0, 90, 180, 270)) {
            val m = matrix(degrees, 0.37, 1234.0, -321.0)
            val view = viewCamera(calibration, m, 1080.0, 2400.0)!!
            assertEquals(CalibrationSource.INTRINSICS, view.source)
            val i = calibration.intrinsics!!
            val cx = i[2] - 8.0
            val cy = i[3] - 6.0
            repeat(50) {
                // A point in the sensor frame, and the same point in the view frame (rotated).
                val xs = random.nextDouble(-0.6, 0.6)
                val ys = random.nextDouble(-0.45, 0.45)
                val sensor = pixel(i[0], i[1], cx, cy, calibration.distortion!!, xs, ys)
                val expectedX = m[0] * sensor[0] + m[1] * sensor[1] + m[2]
                val expectedY = m[3] * sensor[0] + m[4] * sensor[1] + m[5]
                val r00 = Math.round(m[0] / 0.37f)
                val r01 = Math.round(m[1] / 0.37f)
                val r10 = Math.round(m[3] / 0.37f)
                val r11 = Math.round(m[4] / 0.37f)
                val actual = pixel(view.camera, r00 * xs + r01 * ys, r10 * xs + r11 * ys)
                assertEquals("x at $degrees°", expectedX, actual[0], 1e-2)
                assertEquals("y at $degrees°", expectedY, actual[1], 1e-2)
            }
        }
    }

    @Test
    fun theFocalLengthsSwapAt90Degrees() {
        val view = viewCamera(calibration, matrix(90, 0.5, 1500.0, 0.0), 1500.0, 2000.0)!!.camera
        assertEquals(0.5 * 2990.0, view.fx, 1e-3)
        assertEquals(0.5 * 3000.0, view.fy, 1e-3)
    }

    @Test
    fun withoutIntrinsicsTheFocalLengthIsTheFallback() {
        val view =
            viewCamera(
                calibration.copy(intrinsics = null),
                matrix(0, 1.0, 0.0, 0.0),
                4000.0,
                3000.0,
            )!!
        assertEquals(CalibrationSource.FOCAL_LENGTH, view.source)
        assertEquals(6.9 / 9.6 * 4032.0, view.camera.fx, 1e-9)
        assertEquals(2000.0, view.camera.cx, 1e-9)
        // Without a calibration, the distortion is unknown, so it is 0.
        assertEquals(0.0, view.camera.k1, 0.0)
    }

    @Test
    fun aCorrectedPreviewHasNoDistortion() {
        val view =
            viewCamera(
                calibration.copy(distorted = false),
                matrix(0, 1.0, 0.0, 0.0),
                4000.0,
                3000.0,
            )!!
        assertEquals(0.0, view.camera.k1, 0.0)
        assertEquals(0.0, view.camera.p2, 0.0)
    }

    @Test
    fun aMatrixThatIsNotAMultipleOf90DegreesIsRejected() {
        val c = kotlin.math.cos(0.3).toFloat()
        val s = kotlin.math.sin(0.3).toFloat()
        assertNull(
            viewCamera(calibration, floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f), 100.0, 100.0)
        )
    }
}
