package at.stefl.sunradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import java.time.ZoneId

/**
 * The rotation of the display (`Surface.ROTATION_*`). A listener of the display gives each change,
 * also a turn of 180° from one landscape to the other, which does not change the configuration.
 */
@Composable
fun rememberDisplayRotation(): Int {
    val view = LocalView.current
    var rotation by remember { mutableIntStateOf(view.display?.rotation ?: 0) }
    DisposableEffect(view) {
        val manager = view.context.getSystemService(DisplayManager::class.java)
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) {
                    val display = view.display ?: return
                    if (display.displayId == displayId) rotation = display.rotation
                }

                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit
            }
        manager.registerDisplayListener(listener, null)
        rotation = view.display?.rotation ?: 0
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    return rotation
}

/** The time zone of the device. It changes when the user travels or changes the setting. */
@Composable
fun rememberTimeZone(): ZoneId {
    val context = LocalContext.current
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    zone = ZoneId.systemDefault()
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        zone = ZoneId.systemDefault()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return zone
}
