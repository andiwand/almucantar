package at.stefl.almucantar.shared

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Draws the app behind the system bars, with icons of the system bars that are dark if [lightBars]
 * is true, else light.
 *
 * This replaces `enableEdgeToEdge()` of androidx.activity, because that function calls
 * `Window.setStatusBarColor`, `Window.setNavigationBarColor`, and the cutout mode `SHORT_EDGES`,
 * which Android 15 deprecates, and Google Play warns about them. From Android 15 on, the system
 * draws each app edge to edge. For the older versions, the theme of each app (`res/values/
 * themes.xml`) makes the bars transparent and lets the app use the cutout, and this function lets
 * the content go behind the bars. The screens handle the insets themselves.
 */
fun Activity.drawBehindSystemBars(lightBars: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightBars
        isAppearanceLightNavigationBars = lightBars
    }
}
