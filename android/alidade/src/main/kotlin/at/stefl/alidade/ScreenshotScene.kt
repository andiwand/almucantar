package at.stefl.alidade

import at.stefl.almucantar.core.Body
import at.stefl.almucantar.core.Site
import at.stefl.almucantar.core.bodyPosition
import at.stefl.almucantar.core.sight

/**
 * A fixed state of the app for a store screenshot (see `screenshots/README.md`). Only the demo mode
 * of a debug build makes one (`ScreenshotMode` in `src/debug/`). The scene sets the time, the
 * choices, and the stack of screens, as if the user had made them. The example is the one of the
 * website: the full moon of 24 November 2026 behind the Großglockner, seen from a ridge above Kals
 * (`web/alidade/site/src/shot.json`). The terrain comes from the network, as in the normal app.
 */
class ScreenshotScene(
    val name: String,
    /** The time of the app ("now"), in seconds since 1970. */
    private val nowUtc: Double,
    private val flow: Flow,
    /** The screens on top of [Screen.Home], the last one is shown. */
    private val screens: () -> List<Screen>,
) {
    fun apply(model: Model) {
        model.clockUtc = nowUtc
        model.subject = SubjectId.MOON
        model.landmark = GLOCKNER
        model.spot = RIDGE
        model.map.center(GLOCKNER.observer, 12.0)
        model.flow = flow
        model.stack += screens()
    }

    companion object {
        /** The summit of the Großglockner, 3798 m. */
        val GLOCKNER = Point(47.07457, 12.69434, "Großglockner", 3798.0)

        /** The spot of the website: a ridge above Kals, 7.35 km from the summit. */
        val RIDGE = Point(47.045689, 12.607105, "Kals am Großglockner", 2482.1)

        /** The moment of the website: the center of the full moon on the summit. */
        const val MOONRISE_UTC = 1795536480.1

        /** The shot of the website, as "See the shot" of the line of spots makes it. */
        fun moonShot(): Shot {
            val eye = Site(RIDGE.latitudeDeg, RIDGE.longitudeDeg, RIDGE.groundM!! + EYE_HEIGHT_M)
            val top = Site(GLOCKNER.latitudeDeg, GLOCKNER.longitudeDeg, GLOCKNER.groundM!!)
            val view = sight(eye, top)
            val target =
                Target(
                    view.direction,
                    "Landmark near ${GLOCKNER.name}",
                    view.distanceM,
                    GLOCKNER.groundM,
                    GLOCKNER.observer,
                )
            val body = bodyPosition(Body.MOON, MOONRISE_UTC, RIDGE.observer)
            return Shot(
                SubjectId.MOON,
                MOONRISE_UTC,
                RIDGE.observer,
                target,
                0.0,
                body,
                MOONRISE_UTC - 3600,
                MOONRISE_UTC + 3600,
            )
        }
    }
}
