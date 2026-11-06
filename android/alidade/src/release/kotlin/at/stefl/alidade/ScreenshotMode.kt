package at.stefl.alidade

import android.content.Intent

/** The release build has no demo mode: see `src/debug/` for the version of the debug build. */
object ScreenshotMode {
    const val EXTRA = "alidade.screenshot"

    @Suppress("UNUSED_PARAMETER") fun scene(intent: Intent?): ScreenshotScene? = null
}
