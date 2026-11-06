package at.stefl.sonnseite

import at.stefl.almucantar.core.HeadingEstimate
import at.stefl.almucantar.core.Quaternion
import at.stefl.almucantar.shared.Place
import at.stefl.almucantar.shared.PlaceSource
import at.stefl.almucantar.shared.VIENNA
import at.stefl.almucantar.shared.upright
import java.time.Instant
import java.time.ZoneId

/**
 * A fixed state of the app for a store screenshot (see `screenshots/README.md`). Only the demo mode
 * of a debug build makes one (`ScreenshotMode` in `src/debug/`). The app then uses no sensors, no
 * location, and no camera: the view, the place, the time, and the heading come from here, and a
 * drawn sky ([DemoSky]) replaces the camera image.
 */
data class ScreenshotScene(
    val name: String,
    /** The shown time. The app also uses it as "now", so the time panel opens on its date. */
    val unixUtc: Double,
    val zone: ZoneId,
    /** The azimuth of the back camera, in degrees from true north. */
    val azimuthDeg: Double,
    /** How far the back camera points up, in degrees. */
    val pitchDeg: Double,
    /** The error of the heading that the status card shows. */
    val headingSigmaDeg: Double = 0.5,
    val showTime: Boolean = false,
    val alignMenu: Boolean = false,
    /** The horizontal field of view in portrait, in degrees. */
    val fovDeg: Double = 65.0,
) {
    val place = Place(VIENNA.observer, PlaceSource.DEVICE, name = "Vienna")

    val deviceToEnu: Quaternion
        get() = upright(azimuthDeg, pitchDeg)

    val estimate: HeadingEstimate
        get() = HeadingEstimate(0.0, headingSigmaDeg * headingSigmaDeg)

    /** The minute of the local day of [unixUtc], for the time panel. */
    val minuteOfDay: Int
        get() =
            Instant.ofEpochSecond(unixUtc.toLong()).atZone(zone).let { it.hour * 60 + it.minute }
}
