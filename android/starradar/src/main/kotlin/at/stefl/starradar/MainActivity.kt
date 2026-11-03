package at.stefl.starradar

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
import at.stefl.almucantar.shared.UserAgent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserAgent.init(this)
        // The app is always dark, so the icons of the system bars are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // The user holds the phone up and watches the sky, so the screen stays on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The star data is a few hundred kilobytes; the app reads it once.
        val stars =
            StarData.decode(assets.open("stars.json").bufferedReader().use { it.readText() })

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
                if (!granted) launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            StarScreen(stars, locationGranted = granted)
        }
    }
}
