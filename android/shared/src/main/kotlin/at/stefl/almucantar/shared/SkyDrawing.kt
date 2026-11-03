package at.stefl.almucantar.shared

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withRotation
import at.stefl.almucantar.core.DirectionLine
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.Polylines
import at.stefl.almucantar.core.View
import at.stefl.almucantar.core.project
import at.stefl.almucantar.core.projectLine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// Drawing helpers of the sky view. Labels stand upright relative to the horizon, not to the
// screen: the "up" direction at a label is the screen direction to a point a little higher in
// the sky.

object SkyColors {
    val sky = Color(0xFF0B1A30)
    val horizon = Color(0xCCFFFFFF)
    /** The 0° horizon when the terrain line exists. */
    val horizonFaint = Color(0x4DFFFFFF)
    val terrain = Color(0xFFC5E1A5)
    val terrainFill = Color(0x33557A46)
    /** The path behind the terrain. */
    val hiddenPath = Color(0x66FFC83D)
    val nightPath = Color(0x80FFC83D)
    val dayPath = Color(0xFFFFC83D)
    val glow = Color(0x40FFC83D)
    val sun = Color(0xFFFFE08A)
    val nearest = Color(0xFF7FD8FF)
    val guide = Color(0x59FFFFFF)
    val moon = Color(0xFFE0E6F0)
}

/** A text paint with a dark shadow, so the text is readable on a camera image. */
private val labelPaints = mutableMapOf<Pair<Float, Boolean>, Paint>()

/** A paint for labels. The paints are kept, because each frame needs them; do not change one. */
fun labelPaint(sizePx: Float, bold: Boolean = true) =
    labelPaints.getOrPut(sizePx to bold) { newLabelPaint(sizePx, bold) }

private fun newLabelPaint(sizePx: Float, bold: Boolean) =
    Paint().apply {
        isAntiAlias = true
        color = Color.White.toArgb()
        textSize = sizePx
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
        setShadowLayer(sizePx / 4, 0f, 0f, Color.Black.toArgb())
    }

fun Offset.normalized(): Offset {
    val d = hypot(x, y)
    return if (d > 0) Offset(x / d, y / d) else Offset(0f, -1f)
}

/** The screen point of a direction, or null if it is not in the valid area. */
fun screenPoint(view: View, direction: Horizontal): Offset? =
    project(view, direction)
        .takeIf { it.valid }
        ?.let { Offset(it.point.x.toFloat(), it.point.y.toFloat()) }

/** The unit vector on the screen that points up in the sky at [direction]. */
fun upAt(view: View, direction: Horizontal, at: Offset): Offset {
    val higher = Horizontal(direction.azimuthDeg, (direction.altitudeDeg + 0.5).coerceAtMost(89.9))
    val p = screenPoint(view, higher) ?: return Offset(0f, -1f)
    return (p - at).normalized()
}

/** Draws [text] at a distance [gap] above [at], rotated so that its top points to [up]. */
fun DrawScope.drawUprightText(text: String, at: Offset, up: Offset, gap: Float, paint: Paint) {
    val angle = Math.toDegrees(atan2(up.x.toDouble(), -up.y.toDouble())).toFloat()
    drawIntoCanvas {
        it.nativeCanvas.withRotation(angle, at.x, at.y) { drawText(text, at.x, at.y - gap, paint) }
    }
}

fun DrawScope.drawPolylines(lines: Polylines, color: Color, stroke: Stroke) {
    for (line in 0 until lines.count) {
        if (lines.size(line) < 2) continue
        val path = Path()
        path.moveTo(lines.x(line, 0).toFloat(), lines.y(line, 0).toFloat())
        for (i in 1 until lines.size(line)) {
            path.lineTo(lines.x(line, i).toFloat(), lines.y(line, i).toFloat())
        }
        drawPath(path, color, style = stroke)
    }
}

/**
 * Fills closed rings of directions. The projection clips a ring to the valid area; the parts of a
 * clipped ring are joined with straight lines, which lie outside the screen or near its border. All
 * rings are one path, so a shared edge of two rings shows no seam.
 */
fun DrawScope.fillRings(view: View, rings: List<DirectionLine>, color: Color) {
    val path = Path()
    for (ring in rings) {
        val lines = projectLine(view, ring)
        var started = false
        for (line in 0 until lines.count) {
            for (i in 0 until lines.size(line)) {
                val x = lines.x(line, i).toFloat()
                val y = lines.y(line, i).toFloat()
                if (started) path.lineTo(x, y) else path.moveTo(x, y)
                started = true
            }
        }
        if (started) path.close()
    }
    drawPath(path, color)
}

fun dashed(width: Float, dash: Float) =
    Stroke(
        width = width,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.8f)),
        cap = StrokeCap.Round,
    )

/** Unit vectors in East-North-Up, for the arrow to a direction outside the screen. */
private fun enu(h: Horizontal): DoubleArray {
    val a = Math.toRadians(h.azimuthDeg)
    val e = Math.toRadians(h.altitudeDeg)
    return doubleArrayOf(cos(e) * sin(a), cos(e) * cos(a), sin(e))
}

private fun horizontal(v: DoubleArray): Horizontal {
    val az = Math.toDegrees(atan2(v[0], v[1]))
    return Horizontal(if (az < 0) az + 360 else az, Math.toDegrees(atan2(v[2], hypot(v[0], v[1]))))
}

private fun lerp(a: DoubleArray, b: DoubleArray, t: Double): Horizontal {
    val v = DoubleArray(3) { a[it] + t * (b[it] - a[it]) }
    val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    return horizontal(DoubleArray(3) { v[it] / n })
}

/**
 * If [target] is outside [bounds], returns the point on the border of the screen in the direction
 * of [target], seen from [from] (the center of the view), and the direction to go on. Null if the
 * target is visible.
 */
fun edgeIndicator(
    view: View,
    from: Horizontal,
    target: Horizontal,
    bounds: Rect,
): Pair<Offset, Offset>? {
    val inside = { h: Horizontal -> screenPoint(view, h)?.takeIf { bounds.contains(it) } }
    if (inside(target) != null) return null
    val a = enu(from)
    var b = enu(target)
    // Directly behind the user, the way is not defined; take a way to the right.
    if (a.indices.sumOf { (a[it] + b[it]) * (a[it] + b[it]) } < 1e-6)
        b = enu(Horizontal(from.azimuthDeg + 179.0, from.altitudeDeg))
    var lo = 0.0
    var hi = 1.0
    repeat(24) {
        val mid = (lo + hi) / 2
        if (inside(lerp(a, b, mid)) != null) lo = mid else hi = mid
    }
    val edge = screenPoint(view, lerp(a, b, lo)) ?: return null
    val before = screenPoint(view, lerp(a, b, lo * 0.9)) ?: return null
    return edge to (edge - before).normalized()
}

/** An arrow head at [at], pointing to [direction]. */
fun DrawScope.drawArrow(at: Offset, direction: Offset, size: Float, color: Color) {
    val back = at - direction * size
    val side = Offset(-direction.y, direction.x) * (size * 0.6f)
    val path =
        Path().apply {
            moveTo(at.x, at.y)
            lineTo(back.x + side.x, back.y + side.y)
            lineTo(back.x - side.x, back.y - side.y)
            close()
        }
    drawPath(path, color)
}

/** The angle between two directions, in degrees. */
fun angleBetweenDeg(a: Horizontal, b: Horizontal): Double {
    val u = enu(a)
    val v = enu(b)
    val dot = (u[0] * v[0] + u[1] * v[1] + u[2] * v[2]).coerceIn(-1.0, 1.0)
    val cross =
        hypot(
            hypot(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2]),
            u[0] * v[1] - u[1] * v[0],
        )
    return Math.toDegrees(atan2(cross, dot))
}
