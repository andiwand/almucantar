package at.stefl.sunradar

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import at.stefl.sunradar.core.Quaternion

/** The orientation from the rotation vector sensor. */
data class Orientation(
    /** Device frame to East-North-Up, with magnetic north. Null before the first event. */
    val deviceToEnu: Quaternion? = null,
    /** One of `SensorManager.SENSOR_STATUS_*`. */
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    /** The estimated heading accuracy of the sensor, if the device gives it. */
    val headingErrorDeg: Double? = null,
    /** False if the device has no rotation vector sensor. */
    val available: Boolean = true,
)

/**
 * Reads `TYPE_ROTATION_VECTOR`. The OS fuses the accelerometer, the magnetometer, and the
 * gyroscope. M7 replaces this with the game rotation vector and the heading filter of the core
 * (PLAN.md, section 5.4a).
 */
@Composable
fun rememberOrientation(): State<Orientation> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Orientation()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val q = FloatArray(4)
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // The quaternion is [w, x, y, z], and it rotates from the device frame to
                    // the world frame (x east, y magnetic north, z up).
                    SensorManager.getQuaternionFromVector(q, event.values)
                    // values[4] is the estimated heading accuracy in radians, or -1. Some
                    // devices and the emulator give 0, which means "unknown" too.
                    val error =
                        if (event.values.size > 4 && event.values[4] > 0) {
                            Math.toDegrees(event.values[4].toDouble())
                        } else {
                            null
                        }
                    state.value =
                        state.value.copy(
                            deviceToEnu =
                                Quaternion(
                                    q[0].toDouble(),
                                    q[1].toDouble(),
                                    q[2].toDouble(),
                                    q[3].toDouble(),
                                ),
                            headingErrorDeg = error,
                        )
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                    state.value = state.value.copy(accuracy = accuracy)
                }
            }
        if (manager == null || sensor == null) {
            state.value = Orientation(available = false)
        } else {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { manager?.unregisterListener(listener) }
    }
    return state
}
