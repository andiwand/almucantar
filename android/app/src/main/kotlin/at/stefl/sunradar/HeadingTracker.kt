package at.stefl.sunradar

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import at.stefl.sunradar.core.HeadingEstimate
import at.stefl.sunradar.core.HeadingFilter
import at.stefl.sunradar.core.HeadingMeasurement
import at.stefl.sunradar.core.Observer
import at.stefl.sunradar.core.Quaternion
import at.stefl.sunradar.core.Vector3
import at.stefl.sunradar.core.headingFilterPredict
import at.stefl.sunradar.core.headingFilterStart
import at.stefl.sunradar.core.headingFilterUpdate
import at.stefl.sunradar.core.headingFilterUpdateMagnetometer

/** The orientation frame of the tracker. */
enum class OrientationFrame {
    /** `TYPE_GAME_ROTATION_VECTOR`: no magnetometer, so steel does not disturb it, but no north. */
    GAME,
    /** `TYPE_ROTATION_VECTOR`: magnetic north, for devices without a game rotation vector. */
    MAGNETIC,
    /** The device has no rotation vector sensor. */
    NONE,
}

/** The magnetometer of the tracker. */
enum class MagnetometerKind {
    /** `TYPE_MAGNETIC_FIELD_UNCALIBRATED`: the raw field. The filter estimates the bias itself. */
    RAW,
    /** `TYPE_MAGNETIC_FIELD`: the field that the OS calibrated. The filter corrects the rest. */
    CALIBRATED,
    /** The device has no magnetometer. */
    NONE,
}

/**
 * The orientation and the heading filter of the core (PLAN.md, section 5.4a). The orientation comes
 * from the game rotation vector, which has no north. The filter estimates the offset from its frame
 * to true north, the hard-iron bias of the magnetometer, and a local disturbance of the field. It
 * takes one magnetometer sample per second, and calibrations such as a sun tap with [apply]. A sun
 * tap also corrects the bias, so the magnetometer does not pull the offset back.
 */
class HeadingTracker {
    var deviceToFrame by mutableStateOf<Quaternion?>(null)
        private set

    /** The offset and its variance, for the view. */
    var estimate by mutableStateOf(HeadingEstimate())
        private set

    var frame by mutableStateOf(OrientationFrame.GAME)
        internal set

    var magnetometer by mutableStateOf(MagnetometerKind.RAW)
        internal set

    /** The place, for the declination and the expected field. The screen sets it. */
    var observer: Observer = VIENNA.observer

    private var filter: HeadingFilter? = null
    private var lastOrientationNs = 0L
    private var pendingDtS = 0.0
    private var lastMagnetometerUpdateNs = 0L

    internal fun onOrientation(values: FloatArray, timestampNs: Long) {
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, values)
        if (lastOrientationNs != 0L) {
            val dt = (timestampNs - lastOrientationNs) / 1e9
            if (dt > 0) pendingDtS += dt
        }
        lastOrientationNs = timestampNs
        // The prediction needs the core, so it runs at most ten times per second.
        if (pendingDtS >= 0.1) filter?.let { set(predicted(it)) }
        deviceToFrame =
            Quaternion(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble())
    }

    /**
     * One magnetometer event. For [MagnetometerKind.RAW], `values[3..5]` is the bias that the OS
     * estimates: it is only the first guess of the filter.
     */
    internal fun onMagnetometer(values: FloatArray, accuracy: Int, timestampNs: Long) {
        val q = deviceToFrame ?: return
        // The disturbance model of the filter assumes about one sample per second.
        if (timestampNs - lastMagnetometerUpdateNs < 1_000_000_000L) return
        lastMagnetometerUpdateNs = timestampNs
        val raw = magnetometer == MagnetometerKind.RAW
        // A calibrated field without an OS calibration has the full bias in it: skip it.
        if (!raw && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val start =
            filter
                ?: if (raw) {
                    val osBias =
                        Vector3(values[3].toDouble(), values[4].toDouble(), values[5].toDouble())
                    val sigma =
                        if (accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
                            RAW_BIAS_SIGMA_UT
                        else UNKNOWN_BIAS_SIGMA_UT
                    headingFilterStart(osBias, sigma)
                } else {
                    headingFilterStart(Vector3(), CALIBRATED_BIAS_SIGMA_UT)
                }
        val field = Vector3(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
        try {
            set(
                headingFilterUpdateMagnetometer(
                    predicted(start),
                    q,
                    field,
                    observer,
                    System.currentTimeMillis() / 1000.0,
                )
            )
        } catch (_: IllegalArgumentException) {
            // An invalid quaternion or a singular update: skip this sample.
        }
    }

    /** Combines a measurement with the filter, for example from a sun tap. */
    fun apply(measurement: HeadingMeasurement) {
        val start = filter ?: headingFilterStart(Vector3(), UNKNOWN_BIAS_SIGMA_UT)
        set(headingFilterUpdate(predicted(start), measurement))
    }

    private fun predicted(f: HeadingFilter): HeadingFilter {
        val dt = pendingDtS
        pendingDtS = 0.0
        return if (dt > 0) headingFilterPredict(f, dt) else f
    }

    private fun set(f: HeadingFilter) {
        filter = f
        estimate = f.estimate
    }

    private companion object {
        /** The error of the bias of the OS when the OS says that it is calibrated. */
        const val RAW_BIAS_SIGMA_UT = 10.0
        /** The error of a bias that nobody estimated: a hard-iron bias can be this large. */
        const val UNKNOWN_BIAS_SIGMA_UT = 50.0
        /** The error that is left in a field that the OS calibrated. */
        const val CALIBRATED_BIAS_SIGMA_UT = 5.0
    }
}

/**
 * Creates a [HeadingTracker] and connects it to the sensors while the activity is started, so the
 * sensors stop in the background.
 */
@Composable
fun rememberHeadingTracker(): HeadingTracker {
    val context = LocalContext.current
    val tracker = remember { HeadingTracker() }
    LifecycleStartEffect(context) {
        val manager = context.getSystemService(SensorManager::class.java)
        val game = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val rotation = game ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val raw = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
        val magnetometer = raw ?: manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        tracker.frame =
            when {
                game != null -> OrientationFrame.GAME
                rotation != null -> OrientationFrame.MAGNETIC
                else -> OrientationFrame.NONE
            }
        tracker.magnetometer =
            when {
                raw != null -> MagnetometerKind.RAW
                magnetometer != null -> MagnetometerKind.CALIBRATED
                else -> MagnetometerKind.NONE
            }
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_MAGNETIC_FIELD,
                        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED ->
                            // Not every device calls onAccuracyChanged, but each event has it.
                            tracker.onMagnetometer(event.values, event.accuracy, event.timestamp)
                        else -> tracker.onOrientation(event.values, event.timestamp)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
        if (manager != null && rotation != null) {
            manager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME)
            magnetometer?.let {
                manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
        onStopOrDispose { manager?.unregisterListener(listener) }
    }
    return tracker
}
