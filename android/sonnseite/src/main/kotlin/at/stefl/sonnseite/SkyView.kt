package at.stefl.sonnseite

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.ScreenPoint
import at.stefl.almucantar.core.View
import at.stefl.almucantar.core.horizonLine
import at.stefl.almucantar.core.unproject
import at.stefl.almucantar.shared.screenPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A color with components from 0 to 255, so the tables are the same as in the web code. */
class Rgb(val r: Float, val g: Float, val b: Float) {
    fun mix(other: Rgb, t: Float) =
        Rgb(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t)

    fun color(alpha: Float = 1f) = Color(r / 255f, g / 255f, b / 255f, alpha)
}

/**
 * The colors of the sky at the top and at the horizon, for a sun altitude (`SKY` of
 * `web/shared/src/panorama.ts`).
 */
private val SKY =
    listOf(
        Triple(-18f, Rgb(3f, 6f, 15f), Rgb(10f, 18f, 38f)),
        Triple(-8f, Rgb(10f, 20f, 46f), Rgb(52f, 40f, 86f)),
        Triple(-2f, Rgb(24f, 42f, 86f), Rgb(196f, 104f, 92f)),
        Triple(3f, Rgb(34f, 70f, 132f), Rgb(242f, 160f, 96f)),
        Triple(12f, Rgb(44f, 96f, 168f), Rgb(236f, 200f, 150f)),
        Triple(30f, Rgb(40f, 106f, 190f), Rgb(150f, 202f, 238f)),
    )

/** The color at the top and the color at the horizon of the sky, for a sun altitude. */
fun skyColors(altitudeDeg: Float): Pair<Rgb, Rgb> {
    if (altitudeDeg <= SKY.first().first) return SKY.first().second to SKY.first().third
    for (i in 1 until SKY.size) {
        val (a1, top1, low1) = SKY[i]
        val (a0, top0, low0) = SKY[i - 1]
        if (altitudeDeg <= a1) {
            val t = (altitudeDeg - a0) / (a1 - a0)
            return top0.mix(top1, t) to low0.mix(low1, t)
        }
    }
    return SKY.last().second to SKY.last().third
}

/** The ground of the sky view at night and by day. */
private val GROUND_NIGHT = Rgb(10f, 14f, 22f)
private val GROUND_DAY = Rgb(214f, 204f, 186f)

/** Above this sun altitude, the sky view is bright, so the labels and the helpers are dark. */
const val BRIGHT_SUN_ALTITUDE_DEG = -4.0

/**
 * The sky view without the camera, as `drawSkyView` of `web/sonnseite/app/src/main.ts`: a sky in
 * the colors of the altitude of the sun, a glow around the sun, and the ground below the horizon,
 * light by day.
 */
fun DrawScope.drawSkyView(view: View, sun: Horizontal) {
    val w = size.width
    val h = size.height
    val sunAltitude = sun.altitudeDeg.toFloat()
    val (top, low) = skyColors(sunAltitude)
    val day = ((sunAltitude + 6) / 12).coerceIn(0f, 1f)

    // The horizon below the middle of the screen gives the end of the gradient.
    val middle = unproject(view, ScreenPoint(w / 2.0, h / 2.0))
    val azimuth = middle.azimuthDeg.takeIf { it.isFinite() } ?: 180.0
    val horizon = screenPoint(view, Horizontal(azimuth, 0.0))
    val zenith = screenPoint(view, Horizontal(azimuth, 60.0))
    val y1 = horizon?.y ?: h
    val y0 = min(zenith?.y ?: 0f, y1 - 1)
    drawRect(Brush.verticalGradient(listOf(top.color(), low.color()), y0, y1))

    // The glow around the sun, also when it is a little below the horizon.
    if (sunAltitude > -10) {
        screenPoint(view, sun)?.let {
            val strength = min(1f, (sunAltitude + 10) / 14)
            drawRect(
                Brush.radialGradient(
                    0f to Color(255, 220, 150).copy(alpha = 0.45f * strength),
                    0.35f to Color(255, 180, 90).copy(alpha = 0.12f * strength),
                    1f to Color(255, 180, 90, 0),
                    center = it,
                    radius = max(w, h) * 0.6f,
                )
            )
        }
    }

    // The ground: each piece of the horizon line, moved far toward the nadir, closes the area
    // below it.
    val below = screenPoint(view, Horizontal(azimuth, -30.0))
    var down = Offset(0f, 1f)
    if (horizon != null && below != null) {
        val d = below - horizon
        val length = hypot(d.x, d.y)
        if (length > 1) down = d / length
    }
    val far = down * ((w + h) * 2)
    val ground = GROUND_NIGHT.mix(GROUND_DAY, day).color()
    val lines = horizonLine(view)
    for (line in 0 until lines.count) {
        val n = lines.size(line)
        if (n < 2) continue
        val path = Path()
        path.moveTo(lines.x(line, 0).toFloat(), lines.y(line, 0).toFloat())
        for (i in 1 until n) path.lineTo(lines.x(line, i).toFloat(), lines.y(line, i).toFloat())
        for (i in n - 1 downTo 0) {
            path.lineTo(lines.x(line, i).toFloat() + far.x, lines.y(line, i).toFloat() + far.y)
        }
        path.close()
        drawPath(path, ground)
    }
}
