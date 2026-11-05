package at.stefl.sonnseite

import android.content.Intent
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The demo mode for the store screenshots. The test `ScreenshotTests` starts [MainActivity] with
 * the intent extra [EXTRA] and the name of a screen. The scenes are the table of
 * `screenshots/README.md`. Only the debug build has this file; the release build has a version that
 * never gives a scene.
 */
object ScreenshotMode {
    const val EXTRA = "sonnseite.screenshot"

    private val VIENNA_ZONE: ZoneId = ZoneId.of("Europe/Vienna")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Double =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, VIENNA_ZONE)
            .toEpochSecond()
            .toDouble()

    val SCENES: Map<String, ScreenshotScene> =
        listOf(
                ScreenshotScene("01-path", at(2026, 8, 20, 19, 0), VIENNA_ZONE, 255.0, 20.0),
                ScreenshotScene(
                    "02-date",
                    at(2026, 12, 21, 12, 0),
                    VIENNA_ZONE,
                    180.0,
                    30.0,
                    showTime = true,
                ),
                ScreenshotScene(
                    "03-align",
                    at(2026, 5, 12, 10, 0),
                    VIENNA_ZONE,
                    125.0,
                    35.0,
                    headingSigmaDeg = 12.0,
                    alignMenu = true,
                ),
                ScreenshotScene("04-moon", at(2026, 11, 19, 21, 0), VIENNA_ZONE, 195.0, 25.0),
            )
            .associateBy { it.name }

    /** The scene that [intent] asks for, or null for the normal app. */
    fun scene(intent: Intent?): ScreenshotScene? =
        intent?.getStringExtra(EXTRA)?.let {
            requireNotNull(SCENES[it]) { "no screenshot scene $it: one of ${SCENES.keys}" }
        }
}
