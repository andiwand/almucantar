package at.stefl.sternstunde

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
    const val EXTRA = "sternstunde.screenshot"

    private val VIENNA_ZONE: ZoneId = ZoneId.of("Europe/Vienna")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Double =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, VIENNA_ZONE)
            .toEpochSecond()
            .toDouble()

    val SCENES: Map<String, ScreenshotScene> =
        listOf(
                ScreenshotScene("01-night", at(2026, 12, 18, 21, 30), VIENNA_ZONE, 140.0, 28.0),
                ScreenshotScene(
                    "02-tap",
                    at(2026, 12, 18, 21, 30),
                    VIENNA_ZONE,
                    140.0,
                    28.0,
                    selected = "Betelgeuse",
                ),
                ScreenshotScene(
                    "03-milkyway",
                    at(2026, 8, 12, 22, 30),
                    VIENNA_ZONE,
                    190.0,
                    18.0,
                    selected = "Milky Way",
                ),
                ScreenshotScene(
                    "04-red",
                    at(2026, 10, 10, 21, 0),
                    VIENNA_ZONE,
                    0.0,
                    35.0,
                    red = true,
                ),
            )
            .associateBy { it.name }

    /** The scene that [intent] asks for, or null for the normal app. */
    fun scene(intent: Intent?): ScreenshotScene? =
        intent?.getStringExtra(EXTRA)?.let {
            requireNotNull(SCENES[it]) { "no screenshot scene $it: one of ${SCENES.keys}" }
        }
}
