package at.stefl.almucantar.shared

import android.content.Context

/**
 * The User-Agent of the HTTP requests (map tiles and elevation tiles). The tile policy of
 * OpenStreetMap asks that it names the app, so each app sets it at the start with [init].
 */
object UserAgent {
    @Volatile
    var value: String = "Almucantar (Android)"
        private set

    /** Sets the User-Agent from the name, the version, and the package of the app. */
    fun init(context: Context) {
        val manager = context.packageManager
        val version = manager.getPackageInfo(context.packageName, 0).versionName
        val label = context.applicationInfo.loadLabel(manager)
        value = "$label/$version (Android; ${context.packageName})"
    }
}
