package at.stefl.sonnseite

import android.content.Intent

/** The release build has no demo mode: see `src/debug/` for the version of the debug build. */
object ScreenshotMode {
    const val EXTRA = "sonnseite.screenshot"

    @Suppress("UNUSED_PARAMETER") fun scene(intent: Intent?): ScreenshotScene? = null
}
