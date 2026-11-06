package at.stefl.alidade

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
    const val EXTRA = "alidade.screenshot"

    /** "Now" of all scenes: a few weeks before the full moon behind the Großglockner. */
    private val NOW =
        ZonedDateTime.of(2026, 11, 6, 12, 0, 0, 0, ZoneId.of("Europe/Vienna"))
            .toEpochSecond()
            .toDouble()

    /** The screens of "I have a landmark" up to the dates of the spot. */
    private fun toDates() =
        listOf(
            Screen.Subject,
            Screen.Landmark,
            Screen.From,
            Screen.Spot(Screen.Purpose.DATES),
            Screen.Dates,
        )

    val SCENES: Map<String, ScreenshotScene> =
        listOf(
                ScreenshotScene("01-home", NOW, Flow.LANDMARK) { emptyList() },
                ScreenshotScene("02-dates", NOW, Flow.LANDMARK) { toDates() },
                ScreenshotScene("03-shot", NOW, Flow.LANDMARK) {
                    toDates() + Screen.ShotView(ScreenshotScene.moonShot())
                },
                ScreenshotScene("04-eclipses", NOW, Flow.ECLIPSE) { listOf(Screen.Eclipses) },
            )
            .associateBy { it.name }

    /** The scene that [intent] asks for, or null for the normal app. */
    fun scene(intent: Intent?): ScreenshotScene? =
        intent?.getStringExtra(EXTRA)?.let {
            requireNotNull(SCENES[it]) { "no screenshot scene $it: one of ${SCENES.keys}" }
        }
}
