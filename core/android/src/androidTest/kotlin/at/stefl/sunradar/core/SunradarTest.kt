package at.stefl.sunradar.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Kotlin API on a device or an emulator, so it tests the JNI layer, the packing, and the
 * native library of the ABI. The C++ tests check the precision; these tests check the binding.
 */
@RunWith(AndroidJUnit4::class)
class SunradarTest {
    private val vienna = Observer(latitudeDeg = 48.2082, longitudeDeg = 16.3738)

    // 2026-06-20T22:00Z, the local midnight of 2026-06-21 in Vienna (CEST).
    private val dayStart = 1781992800.0
    private val day = 86400.0

    // A device held upright in portrait, with the back camera to the north.
    private val northUpright = Quaternion(w = cos(PI / 4), x = sin(PI / 4))

    @Test
    fun versionHasThreeParts() {
        assertEquals(2, version().count { it == '.' })
    }

    @Test
    fun defaultsAreTheSameAsInCpp() {
        val expected =
            SunOptions().pack() +
                EventOptions().pack() +
                PathOptions().pack() +
                SearchOptions().pack() +
                Camera().pack() +
                View().pack().copyOfRange(0, 6) +
                MagnetometerOptions().pack() +
                HeadingEstimate().pack() +
                MoonOptions().pack() +
                doubleArrayOf(DEFAULT_HEADING_DRIFT_DEG2_PER_S) +
                HeadingFilterOptions().pack()
        assertEquals(expected.toList(), SunradarNative.defaults().toList())
    }

    @Test
    fun sunPositionAtNoon() {
        val h = sunPosition(1782039420.0, vienna)
        assertTrue(h.azimuthDeg in 179.0..181.0)
        assertTrue(h.altitudeDeg in 65.0..65.5)
        val airless = sunPosition(1782039420.0, vienna, SunOptions(refraction = false))
        assertTrue(h.altitudeDeg > airless.altitudeDeg)
    }

    @Test
    fun sunEventsAndPolarDay() {
        val events = sunEvents(vienna, dayStart, dayStart + day)
        assertEquals(DayKind.NORMAL, events.kind)
        assertEquals(listOf(true, false), events.crossings.map { it.rising })
        assertTrue(events.crossings[0].unixUtc < events.culminationUtc)

        val tromso = Observer(latitudeDeg = 69.6492, longitudeDeg = 18.9553)
        val polar = sunEvents(tromso, dayStart, dayStart + day)
        assertEquals(DayKind.POLAR_DAY, polar.kind)
        assertTrue(polar.crossings.isEmpty())
    }

    @Test
    fun sunPath() {
        val path = sunPath(vienna, dayStart, dayStart + day, PathOptions(stepS = 3600.0))
        assertEquals(25, path.size)
        assertEquals(dayStart + 3600.0, path[1].unixUtc, 0.0)
        assertEquals(sunPosition(path[13].unixUtc, vienna), path[13].position)
    }

    @Test
    fun magneticDeclination() {
        // The WMM2025 test value at height 0 km: 2027.5, 13 S, 59 W: -17.49 degrees.
        val t = 1798761600.0 + 0.5 * 365 * day
        val d = magneticDeclinationDeg(Observer(-13.0, -59.0), t)
        assertEquals(-17.49, d, 0.006)
    }

    @Test
    fun projectAndUnproject() {
        val view = View(deviceToEnu = northUpright)
        val center = project(view, Horizontal(0.0, 0.0))
        assertTrue(center.valid)
        assertEquals(500.0, center.point.x, 1e-9)
        assertEquals(500.0, center.point.y, 1e-9)

        val right = project(view, Horizontal(10.0, 0.0))
        assertEquals(500.0 + view.camera.fx * tan(10 * PI / 180), right.point.x, 1e-9)
        val back = unproject(view, right.point)
        assertEquals(10.0, back.azimuthDeg, 1e-9)
        assertEquals(0.0, back.altitudeDeg, 1e-9)

        assertFalse(project(view, Horizontal(180.0, 0.0)).valid)
    }

    @Test
    fun screenRotationAndCamera() {
        // The same scene in landscape, with the device turned counterclockwise (ROTATION_90):
        // device +x points up, device +y points west.
        val landscape =
            quaternionFromAxes(
                x = doubleArrayOf(0.0, 0.0, 1.0),
                y = doubleArrayOf(-1.0, 0.0, 0.0),
                z = doubleArrayOf(0.0, -1.0, 0.0),
            )
        val camera = cameraFromFov(1920.0, 1080.0, 70.0)
        assertEquals(960.0 / tan(35 * PI / 180), camera.fx, 1e-9)
        val view = View(landscape, ScreenRotation.ROTATION_90, camera = camera)
        val p = project(view, Horizontal(10.0, 5.0))
        assertTrue(p.valid)
        assertTrue(p.point.x > 960.0)
        assertTrue(p.point.y < 540.0)
    }

    @Test
    fun linesAndHorizon() {
        val view = View(deviceToEnu = northUpright)
        val horizon = horizonLine(view)
        assertEquals(1, horizon.count)
        for (i in 0 until horizon.size(0)) {
            assertEquals(500.0, horizon.y(0, i), 1e-6)
        }

        val path = sunPath(vienna, dayStart, dayStart + day)
        val packed = DirectionLine.fromPath(path)
        assertEquals(path.size, packed.size)
        assertEquals(path[100].position, packed[100])
        val flat = projectLine(View(deviceToEnu = northUpright, headingOffsetDeg = 90.0), packed)
        val list = projectPath(View(deviceToEnu = northUpright, headingOffsetDeg = 90.0), path)
        assertEquals(list, flat.toList())
    }

    @Test
    fun search() {
        val t = dayStart + 10 * 3600.0
        val direction = sunPosition(t, vienna)
        val match = findTime(vienna, direction, dayStart, dayStart + day)
        assertEquals(t, match.unixUtc, 1.0)
        assertTrue(match.separationDeg < 1e-3)

        val year = 1767222000.0 // 2026-01-01T00:00 CET
        val may1 = year + 120 * day + 10 * 3600
        val dates = findDates(vienna, sunPosition(may1, vienna), year, year + 365 * day)
        assertEquals(listOf(120, 222), dates.map { ((it.unixUtc - year) / day).toInt() })
    }

    @Test
    fun headingFilter() {
        val e = headingUpdate(HeadingEstimate(10.0, 4.0), HeadingMeasurement(20.0, 4.0))
        assertEquals(15.0, e.offsetDeg, 1e-12)
        assertEquals(2.0, e.varianceDeg2, 1e-12)
        assertEquals(5.0, headingPredict(HeadingEstimate(0.0, 4.0), 60.0).varianceDeg2, 1e-12)

        // The center of the screen shows the azimuth 0 of the frame; truly it is 70.
        val view = View(deviceToEnu = northUpright)
        val m = headingFromDirection(view, ScreenPoint(500.0, 500.0), 70.0, 0.5)
        assertEquals(70.0, m.offsetDeg, 1e-9)

        // A field to the north of the frame (device -z) and down (device -y): the offset is the
        // declination.
        val t = 1767225600.0
        val mag = headingFromMagnetometer(northUpright, Vector3(0.0, -40.0, -20.0), vienna, t)
        assertEquals(magneticDeclinationDeg(vienna, t), mag.offsetDeg, 1e-9)
    }

    @Test
    fun headingFilterWithBias() {
        var f = headingFilterStart(Vector3(1.0, 2.0, 3.0), 0.5)
        assertEquals(UNKNOWN_HEADING_VARIANCE_DEG2, f.varianceDeg2, 0.0)
        assertEquals(HEADING_FILTER_SIZE * HEADING_FILTER_SIZE, f.covariance.size)
        assertEquals(2.0, f.biasUt.y, 0.0)
        // The field of headingFilter, plus the bias: the offset is near the declination.
        val t = 1767225600.0
        f = headingFilterPredict(f, 1.0)
        f = headingFilterUpdateMagnetometer(f, northUpright, Vector3(1.0, -38.0, -17.0), vienna, t)
        assertEquals(magneticDeclinationDeg(vienna, t), f.offsetDeg, 5.0)
        f = headingFilterUpdate(f, HeadingMeasurement(10.0, 0.01))
        assertEquals(10.0, f.offsetDeg, 0.1)
        assertEquals(f.offsetDeg, f.estimate.offsetDeg, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            headingFilterStart(Vector3(), 0.0)
        }
    }

    @Test
    fun moonAndAzimuthTimes() {
        val t = 1767225600.0
        val airless = moonPosition(t, vienna, MoonOptions(refraction = false))
        assertTrue(moonPosition(t, vienna).altitudeDeg >= airless.altitudeDeg)
        assertTrue(moonIlluminatedFraction(1767441600.0) > 0.99)

        val ae = sunPosition(1782039420.0, vienna, SunOptions(model = SolarModel.ASTRONOMY_ENGINE))
        assertEquals(sunPosition(1782039420.0, vienna).altitudeDeg, ae.altitudeDeg, 0.03)

        val at = dayStart + 9.5 * 3600
        val crossings =
            findAzimuthTimes(vienna, sunPosition(at, vienna).azimuthDeg, dayStart, dayStart + day)
        assertEquals(1, crossings.size)
        assertEquals(at, crossings[0].unixUtc, 1.0)
    }

    @Test
    fun invalidArgumentsThrowIllegalArgumentException() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                sunPosition(0.0, Observer(latitudeDeg = 91.0))
            }
        assertTrue(e.message!!.contains("latitude_deg"))
        assertThrows(IllegalArgumentException::class.java) { sunEvents(vienna, 10.0, 10.0) }
        assertThrows(IllegalArgumentException::class.java) {
            project(View(deviceToEnu = Quaternion(0.0, 0.0, 0.0, 0.0)), Horizontal())
        }
        assertThrows(IllegalArgumentException::class.java) {
            cameraFromFov(100.0, 100.0, 180.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            findDates(vienna, Horizontal(), dayStart, dayStart + 3600.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            headingUpdate(HeadingEstimate(), HeadingMeasurement(0.0, 0.0))
        }
    }

    private fun quaternionFromAxes(x: DoubleArray, y: DoubleArray, z: DoubleArray): Quaternion {
        // The rotation matrix has the columns x, y, z. This form needs a positive trace or a
        // large m00, which is true for the poses of these tests.
        val m00 = x[0]
        val m11 = y[1]
        val m22 = z[2]
        val trace = m00 + m11 + m22
        if (trace > 0) {
            val s = 2 * kotlin.math.sqrt(trace + 1)
            return Quaternion(s / 4, (y[2] - z[1]) / s, (z[0] - x[2]) / s, (x[1] - y[0]) / s)
        }
        val m = maxOf(m00, m11, m22)
        return when {
            m == m00 -> {
                val s = 2 * kotlin.math.sqrt(1 + m00 - m11 - m22)
                Quaternion((y[2] - z[1]) / s, s / 4, (y[0] + x[1]) / s, (z[0] + x[2]) / s)
            }
            m == m11 -> {
                val s = 2 * kotlin.math.sqrt(1 + m11 - m00 - m22)
                Quaternion((z[0] - x[2]) / s, (y[0] + x[1]) / s, s / 4, (z[1] + y[2]) / s)
            }
            else -> {
                val s = 2 * kotlin.math.sqrt(1 + m22 - m00 - m11)
                Quaternion((x[1] - y[0]) / s, (z[0] + x[2]) / s, (z[1] + y[2]) / s, s / 4)
            }
        }.also { check(abs(it.w * it.w + it.x * it.x + it.y * it.y + it.z * it.z - 1) < 1e-9) }
    }
}
