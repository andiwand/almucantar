package at.stefl.sonnseite

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.ScreenPoint
import at.stefl.almucantar.core.View
import at.stefl.almucantar.core.unproject
import at.stefl.almucantar.shared.screenPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The drawn sky of the demo mode: it replaces the camera image in the store screenshots. It is the
 * sky of the website (`web/sonnseite/site/src/sky.ts`) with the projection of the app: the same
 * colors, the same stars, and the same skyline, from the same random generator.
 */
@Composable
fun DemoSky(modifier: Modifier, view: View, sun: Horizontal) {
    Canvas(modifier) { drawDemoSky(view, sun) }
}

private class Rgb(val r: Float, val g: Float, val b: Float) {
    fun mix(other: Rgb, t: Float) =
        Rgb(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t)

    fun color(alpha: Float = 1f) = Color(r / 255f, g / 255f, b / 255f, alpha)
}

/** The colors of the sky at the top and at the horizon, for a sun altitude (`SKY` of sky.ts). */
private val SKY =
    listOf(
        Triple(-18f, Rgb(3f, 6f, 15f), Rgb(10f, 18f, 38f)),
        Triple(-8f, Rgb(10f, 20f, 46f), Rgb(52f, 40f, 86f)),
        Triple(-2f, Rgb(24f, 42f, 86f), Rgb(196f, 104f, 92f)),
        Triple(3f, Rgb(34f, 70f, 132f), Rgb(242f, 160f, 96f)),
        Triple(12f, Rgb(44f, 96f, 168f), Rgb(236f, 200f, 150f)),
        Triple(30f, Rgb(40f, 106f, 190f), Rgb(150f, 202f, 238f)),
    )

private fun skyColors(altitudeDeg: Float): Pair<Rgb, Rgb> {
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

/**
 * The random generator of sky.ts (`random(seed)`), bit for bit: Kotlin Int arithmetic wraps at 32
 * bits, as `Math.imul` and `>>> 0` do in JavaScript.
 */
private fun random(seed: Int): () -> Double {
    var s = seed
    return {
        s += 0x6d2b79f5
        var t = s
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
    }
}

private class Star(val azimuth: Double, val altitude: Double, val size: Double)

private val STARS: List<Star> =
    random(7).let { next ->
        List(420) {
            val azimuth = next() * 360
            // More stars high in the sky: the area of a band of altitude goes with its cosine.
            val altitude = Math.toDegrees(asin(next()))
            val size = if (next() < 0.92) 0.6 + next() * 0.6 else 1.3 + next() * 0.7
            next() // the phase of the twinkle, which a still picture does not use
            Star(azimuth, altitude, size)
        }
    }

private class Light(val x: Double, val y: Double)

private class Building(
    val azimuth: Double,
    val width: Double,
    val height: Double,
    val roof: Double,
    val lights: List<Light>,
)

private val BUILDINGS: List<Building> =
    random(42).let { next ->
        val result = mutableListOf<Building>()
        var azimuth = 0.0
        while (azimuth < 360) {
            val width = 2 + next() * 7
            // Lower buildings in the south, so the low winter sun stays visible.
            val south = cos(Math.toRadians(azimuth - 180))
            val height = (0.6 + next() * 4.2) * (1 - 0.45 * max(0.0, south))
            val lights = mutableListOf<Light>()
            var i = 0
            while (i < width * height * 0.5) {
                if (next() < 0.35) {
                    val x = next()
                    lights += Light(x, 0.15 + next() * 0.75)
                }
                i++
            }
            result += Building(azimuth, width, height, next(), lights)
            azimuth += width + if (next() < 0.25) next() * 3 else 0.0
        }
        result
    }

private fun DrawScope.drawDemoSky(view: View, sun: Horizontal) {
    val center = unproject(view, ScreenPoint(view.camera.width / 2, view.camera.height / 2))
    val horizonY =
        screenPoint(view, Horizontal(center.azimuthDeg, 0.0))?.y?.coerceIn(0f, size.height)
            ?: if (center.altitudeDeg > 0) size.height else 0f
    val sunAltitude = sun.altitudeDeg.toFloat()
    val day = ((sunAltitude + 6) / 12).coerceIn(0f, 1f)

    // The sky: a gradient from the top of the screen to the horizon.
    val (top, low) = skyColors(sunAltitude)
    drawRect(
        Brush.verticalGradient(listOf(top.color(), low.color()), 0f, max(1f, horizonY)),
        size = Size(size.width, max(1f, horizonY)),
    )

    // The glow around the sun, also when it is a little below the horizon.
    if (sunAltitude > -12) {
        screenPoint(view, sun)?.let {
            val strength = min(1f, (sunAltitude + 12) / 14)
            drawRect(
                Brush.radialGradient(
                    0f to Color(255, 214, 140).copy(alpha = 0.42f * strength),
                    0.35f to Color(255, 170, 90).copy(alpha = 0.12f * strength),
                    1f to Color(255, 170, 90, 0),
                    center = it,
                    radius = size.height * 0.7f,
                ),
                size = Size(size.width, max(1f, horizonY)),
            )
        }
    }

    // The stars.
    val starAlpha = ((-sunAltitude - 4) / 8).coerceIn(0f, 1f)
    if (starAlpha > 0) {
        for (star in STARS) {
            val at = screenPoint(view, Horizontal(star.azimuth, star.altitude)) ?: continue
            if (at.x !in 0f..size.width || at.y !in 0f..horizonY) continue
            val alpha = starAlpha * 0.85f * if (star.size > 1.2) 0.95f else 0.6f
            drawCircle(Color.White.copy(alpha = alpha), radius = star.size.toFloat() * density, at)
        }
    }

    // The ground and the skyline, in the same color.
    val ground = Rgb(5f, 7f, 13f).mix(Rgb(22f, 30f, 44f), day).color()
    drawRect(ground, Offset(0f, horizonY), Size(size.width, size.height - horizonY))
    val lightAlpha = if (day < 0.7f) 0.75f * (1 - day / 0.7f) else 0f
    for (b in BUILDINGS) drawBuilding(view, b, ground, lightAlpha)
}

private fun DrawScope.drawBuilding(view: View, b: Building, ground: Color, lightAlpha: Float) {
    // Skip a building far behind the camera: its corners cannot be projected.
    val outline = mutableListOf(Horizontal(b.azimuth, -0.5))
    if (b.roof < 0.2) {
        outline += Horizontal(b.azimuth, b.height * 0.85)
        outline += Horizontal(b.azimuth + b.width / 2, b.height * 1.12)
        outline += Horizontal(b.azimuth + b.width, b.height * 0.85)
    } else {
        // Points along the roof, because a line of equal altitude is curved in a perspective view.
        val steps = b.width.toInt() + 1
        for (i in 0..steps) outline += Horizontal(b.azimuth + b.width * i / steps, b.height)
    }
    outline += Horizontal(b.azimuth + b.width, -0.5)
    val points = outline.map { screenPoint(view, it) ?: return }
    if (points.all { it.x < 0 } || points.all { it.x > size.width }) return
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    path.close()
    drawPath(path, ground)

    if (lightAlpha <= 0) return
    val window = Size(2.5f * density, 3f * density)
    for (light in b.lights) {
        val direction = Horizontal(b.azimuth + light.x * b.width * 0.9, b.height * (1 - light.y))
        val at = screenPoint(view, direction) ?: continue
        drawRect(Color(255, 206, 120).copy(alpha = lightAlpha), at, window)
    }
}
