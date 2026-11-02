package at.stefl.sunradar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the icons of the system bars are always light, also when the
        // system uses its light theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // The user holds the phone up and watches the sky, so the screen stays on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The demo mode of the store screenshots, only in a debug build.
        val scene = ScreenshotMode.scene(intent)

        setContent {
            var granted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            val launcher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    granted = it
                }
            LaunchedEffect(Unit) {
                if (!granted && scene == null) {
                    launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
            SkyScreen(locationGranted = granted, scene = scene)
        }
    }
}
