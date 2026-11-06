package at.stefl.sternstunde

import at.stefl.almucantar.core.HeadingEstimate
import at.stefl.almucantar.core.Quaternion
import at.stefl.almucantar.shared.Place
import at.stefl.almucantar.shared.PlaceSource
import at.stefl.almucantar.shared.VIENNA
import at.stefl.almucantar.shared.upright
import java.time.ZoneId

/**
 * A fixed state of the app for a store screenshot (see `screenshots/README.md`). Only the demo mode
 * of a debug build makes one (`ScreenshotMode` in `src/debug/`). The app then uses no sensors, no
 * location, no camera, and no network: the view, the place, the time, the heading, and the settings
 * come from here.
 */
data class ScreenshotScene(
    val name: String,
    /** The shown time. The app also uses it as "now". */
    val unixUtc: Double,
    val zone: ZoneId,
    /** The azimuth of the back camera, in degrees from true north. */
    val azimuthDeg: Double,
    /** How far the back camera points up, in degrees. */
    val pitchDeg: Double,
    /** The name of the selected object (a star name, a planet, or "Milky Way"), or null. */
    val selected: String? = null,
    /** The red light. */
    val red: Boolean = false,
    /** The error of the heading that the status card shows. */
    val headingSigmaDeg: Double = 0.8,
) {
    val place = Place(VIENNA.observer, PlaceSource.DEVICE, name = "Vienna")

    val deviceToEnu: Quaternion
        get() = upright(azimuthDeg, pitchDeg)

    val estimate: HeadingEstimate
        get() = HeadingEstimate(0.0, headingSigmaDeg * headingSigmaDeg)

    /** The selected object of [stars], or null if the scene selects none. */
    fun target(stars: StarData): Target? =
        when (val name = selected) {
            null -> null
            "Milky Way" -> Target.GalacticCenter
            "Moon" -> Target.Moon
            else ->
                PLANETS.firstOrNull { it.second.first == name }
                    ?.let { Target.PlanetTarget(it.first) }
                    ?: stars.names.entries
                        .firstOrNull { it.value == name }
                        ?.let { Target.Star(it.key) }
                    ?: error("no object $name in the star data")
        }
}
