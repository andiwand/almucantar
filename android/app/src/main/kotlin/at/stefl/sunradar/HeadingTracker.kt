package at.stefl.sunradar

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import at.stefl.sunradar.core.HeadingEstimate
import at.stefl.sunradar.core.HeadingMeasurement
import at.stefl.sunradar.core.Observer
import at.stefl.sunradar.core.Quaternion
import at.stefl.sunradar.core.Vector3
import at.stefl.sunradar.core.headingFromMagnetometer
import at.stefl.sunradar.core.headingPredict
import at.stefl.sunradar.core.headingUpdate

/** The orientation frame of the tracker. */
enum class OrientationFrame {
    /** `TYPE_GAME_ROTATION_VECTOR`: no magnetometer, so steel does not disturb it, but no north. */
    GAME,
    /** `TYPE_ROTATION_VECTOR`: magnetic north, for devices without a game rotation vector. */
    MAGNETIC,
    /** The device has no rotation vector sensor. */
    NONE,
}

/**
 * The orientation and the heading filter of the core (PLAN.md, section 5.4a). The orientation comes
 * from the game rotation vector, which has no north. The filter estimates the offset from its frame
 * to true north: it predicts on each orientation event (the yaw drifts), takes one magnetometer
 * measurement per second (the variance grows near steel), and takes calibrations such as a sun tap
 * with [apply].
 */
class HeadingTracker {
    var deviceToFrame by mutableStateOf<Quaternion?>(null)
        private set

    var estimate by mutableStateOf(HeadingEstimate())
        private set

    var frame by mutableStateOf(OrientationFrame.GAME)
        internal set

    /** One of `SensorManager.SENSOR_STATUS_*`, for the magnetometer. */
    var magnetometerAccuracy by mutableIntStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE)
        private set

    /** The place, for the declination and the expected field. The screen sets it. */
    var observer: Observer = VIENNA.observer

    private var lastOrientationNs = 0L
    private var lastMagnetometerUpdateNs = 0L

    internal fun onOrientation(values: FloatArray, timestampNs: Long) {
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, values)
        if (lastOrientationNs != 0L) {
            val dt = (timestampNs - lastOrientationNs) / 1e9
            if (dt > 0) estimate = headingPredict(estimate, dt)
        }
        lastOrientationNs = timestampNs
        deviceToFrame =
            Quaternion(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble())
    }

    internal fun onMagnetometer(values: FloatArray, timestampNs: Long) {
        val q = deviceToFrame ?: return
        // The errors of a magnetometer are correlated in time, so at most one update per second.
        if (timestampNs - lastMagnetometerUpdateNs < 1_000_000_000L) return
        lastMagnetometerUpdateNs = timestampNs
        if (magnetometerAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val field = Vector3(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
        try {
            apply(headingFromMagnetometer(q, field, observer, System.currentTimeMillis() / 1000.0))
        } catch (_: IllegalArgumentException) {
            // A field of 0 or an invalid quaternion: skip this measurement.
        }
    }

    internal fun onMagnetometerAccuracy(accuracy: Int) {
        magnetometerAccuracy = accuracy
    }

    /** Combines a measurement with the estimate, for example from a sun tap. */
    fun apply(measurement: HeadingMeasurement) {
        estimate = headingUpdate(estimate, measurement)
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
        val magnetometer = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        tracker.frame =
            when {
                game != null -> OrientationFrame.GAME
                rotation != null -> OrientationFrame.MAGNETIC
                else -> OrientationFrame.NONE
            }
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            // Not every device calls onAccuracyChanged, but each event has it.
                            tracker.onMagnetometerAccuracy(event.accuracy)
                            tracker.onMagnetometer(event.values, event.timestamp)
                        }
                        else -> tracker.onOrientation(event.values, event.timestamp)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                    if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD)
                        tracker.onMagnetometerAccuracy(accuracy)
                }
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
