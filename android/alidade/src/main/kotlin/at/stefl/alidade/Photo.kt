package at.stefl.alidade

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.StarCatalog
import at.stefl.almucantar.shared.MilkyWay
import at.stefl.almucantar.shared.MoonPhase
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.json.JSONObject

// The first-person photo of alidade: the sky in the light of the sun, the stars and the Milky
// Way, the sun or a planet, the moon in its phase, the terrain in layers from far to near, the
// label of the landmark, the frame of a lens, and the marks of a viewfinder. It is a port of the
// photo of the PWA and the website (web/shared/src/photo.ts) to the Android canvas, with the same
// colors. The core calculates all directions; this file only draws.

/** The direction in the center of the photo, and the azimuth range across its width. */
data class PhotoView(val azimuthDeg: Double, val altitudeDeg: Double, val spanDeg: Double)

/** The terrain in layers: layer 0 is the nearest ground, the last layer the farthest. */
interface PhotoTerrain {
    val layers: Int

    /** The altitude of the terrain of a layer at an azimuth, or NaN where the layer has none. */
    fun altitude(layer: Int, azimuthDeg: Double): Double
}

/** The terrain of a [Horizon]: one layer for each band. */
fun Horizon.terrain(): PhotoTerrain =
    object : PhotoTerrain {
        override val layers = bands.size

        override fun altitude(layer: Int, azimuthDeg: Double) =
            profileAltitude(bands[layer], azimuthDeg)
    }

/** The path of a body over some hours, with a label at each full hour. */
class PhotoTrack(val points: List<Horizontal>, val ticks: List<Pair<Horizontal, String>>)

/** A body in addition to the moon: the sun or a planet. */
class PhotoBody(val sun: Boolean, val direction: Horizontal, val label: String)

class PhotoPin(val direction: Horizontal, val title: String, val detail: String, val alpha: Float)

class PhotoFinder(val center: Horizontal, val focalMm: Int, val alpha: Float)

class PhotoHud(val topLeft: String, val topRight: String, val bottom: String)

class SolarDiscs(val sunRadiusDeg: Double, val moonRadiusDeg: Double, val coverage: Double)

/** The stars of the photo: the magnitudes and colors, and the directions (azimuth, altitude). */
class PhotoStars(val stars: Stars, val directions: DoubleArray, val limitMag: Double)

/** The Milky Way of the photo: the points, their directions, and its strength from 0 to 1. */
class PhotoDust(val milkyWay: MilkyWay, val directions: DoubleArray, val strength: Double)

data class PhotoFrame(
    val view: PhotoView,
    val sun: Horizontal,
    val moon: Horizontal,
    val moonFraction: Double,
    val terrain: PhotoTerrain,
    val track: PhotoTrack? = null,
    val body: PhotoBody? = null,
    val stars: PhotoStars? = null,
    val dust: PhotoDust? = null,
    val pin: PhotoPin? = null,
    val finder: PhotoFinder? = null,
    val hud: PhotoHud? = null,
    val solarEclipse: SolarDiscs? = null,
    /** A lunar eclipse: the part of the moon in the umbra of the Earth, 0 to 1. */
    val lunarUmbra: Double = 0.0,
)

/** The stars of `assets/stars/stars.json`, the brightest first: only what the photo needs. */
class Stars(val catalog: StarCatalog, val mag: FloatArray, val bv: FloatArray) {
    companion object {
        fun decode(text: String): Stars {
            val stars = JSONObject(text).getJSONObject("stars")
            fun column(name: String): DoubleArray {
                val a = stars.getJSONArray(name)
                return DoubleArray(a.length()) { a.getDouble(it) }
            }
            return Stars(
                StarCatalog(column("ra"), column("dec"), column("pm_ra"), column("pm_dec")),
                column("mag").let { c -> FloatArray(c.size) { c[it].toFloat() } },
                column("bv").let { c -> FloatArray(c.size) { c[it].toFloat() } },
            )
        }
    }
}

/** A color as three channels from 0 to 255. */
private typealias Rgb = DoubleArray

private fun rgb(r: Int, g: Int, b: Int): Rgb =
    doubleArrayOf(r.toDouble(), g.toDouble(), b.toDouble())

private fun mix(a: Rgb, b: Rgb, t: Double): Rgb = DoubleArray(3) { a[it] + (b[it] - a[it]) * t }

private fun argb(c: Rgb, alpha: Double = 1.0): Int =
    android.graphics.Color.argb(
        (alpha.coerceIn(0.0, 1.0) * 255).roundToInt(),
        c[0].roundToInt().coerceIn(0, 255),
        c[1].roundToInt().coerceIn(0, 255),
        c[2].roundToInt().coerceIn(0, 255),
    )

/** Sets a shader. The alpha of the color multiplies the shader, so the color must be opaque. */
private fun Paint.useShader(value: Shader) {
    color = android.graphics.Color.BLACK
    shader = value
}

private fun clamp(v: Double, a: Double = 0.0, b: Double = 1.0) = min(b, max(a, v))

private fun bell(x: Double, center: Double, width: Double) =
    exp(-((x - center) * (x - center)) / (2 * width * width))

private fun wrap180(deg: Double) = ((deg % 360) + 540) % 360 - 180

// The colors of the logo of alidade: dusk violet, rose, and cream.
private val INK = rgb(42, 31, 61)
private val ROSE = rgb(138, 74, 85)
private val CREAM = rgb(255, 243, 214)

/** The angle of view across the long side of a full-frame camera (36 × 24 mm). */
fun lensSpanDeg(focalMm: Int): Double = Math.toDegrees(2 * atan(18.0 / focalMm))

/** The sky at the top and at the horizon, for an altitude of the sun: warmer than a real sky. */
private val SKY =
    listOf(
        Triple(-18.0, rgb(12, 10, 26), rgb(26, 21, 44)),
        Triple(-12.0, rgb(22, 20, 48), rgb(56, 44, 80)),
        Triple(-8.0, rgb(36, 36, 78), rgb(104, 78, 118)),
        Triple(-5.0, rgb(56, 58, 108), rgb(176, 124, 146)),
        Triple(-2.0, rgb(82, 94, 140), rgb(226, 164, 148)),
        Triple(2.0, rgb(112, 138, 182), rgb(242, 196, 156)),
        Triple(10.0, rgb(128, 168, 214), rgb(244, 222, 196)),
        Triple(30.0, rgb(112, 160, 214), rgb(214, 230, 240)),
    )

private fun skyPair(sunAltitudeDeg: Double): Pair<Rgb, Rgb> {
    if (sunAltitudeDeg <= SKY[0].first) return SKY[0].second to SKY[0].third
    for (i in 1 until SKY.size) {
        if (sunAltitudeDeg <= SKY[i].first) {
            val t = (sunAltitudeDeg - SKY[i - 1].first) / (SKY[i].first - SKY[i - 1].first)
            return mix(SKY[i - 1].second, SKY[i].second, t) to
                mix(SKY[i - 1].third, SKY[i].third, t)
        }
    }
    return SKY.last().second to SKY.last().third
}

private val BELT = rgb(236, 168, 172)
private val SHADOW = rgb(84, 82, 130)

/**
 * The color of the sky at an altitude. Opposite to the sun, in the twilight, the sky has the pink
 * belt of Venus over the blue shadow of the Earth: the light of a moonrise at dusk.
 */
private fun skyColor(altitudeDeg: Double, sunAltitudeDeg: Double, opposite: Double): Rgb {
    val (top, low) = skyPair(sunAltitudeDeg)
    var c = mix(low, top, clamp(altitudeDeg / 42).pow(0.55))
    val strength = bell(sunAltitudeDeg, -3.5, 2.6) * opposite
    if (strength > 0.01) {
        val center = max(2.0, -sunAltitudeDeg) + 4.5
        c = mix(c, BELT, 0.5 * strength * bell(altitudeDeg, center, 3.6))
        c = mix(c, SHADOW, 0.45 * strength * clamp((center - 3 - altitudeDeg) / 4))
    }
    return c
}

/** The colors of the terrain layers: the far ones in the haze, the near ones dark. */
private fun layerColors(depth: Double, sunAltitudeDeg: Double, haze: Rgb): Pair<Rgb, Rgb> {
    fun pick(far: Rgb, near: Rgb) = mix(far, near, depth)
    val night = pick(rgb(58, 50, 84), rgb(16, 12, 26))
    val dusk = pick(rgb(138, 114, 160), rgb(34, 24, 50))
    val day = pick(rgb(168, 176, 198), rgb(58, 64, 74))
    val base =
        if (sunAltitudeDeg < -4) mix(night, dusk, clamp((sunAltitudeDeg + 13) / 9))
        else mix(dusk, day, clamp((sunAltitudeDeg + 4) / 14))
    val hazed = mix(base, haze, 0.35 * (1 - depth))
    // Snow on the high and far terrain: the crest is lighter than the foot.
    val snow =
        mix(
            hazed,
            if (sunAltitudeDeg > -8) rgb(236, 228, 240) else rgb(120, 116, 150),
            0.3 * (1 - depth),
        )
    return snow to mix(hazed, rgb(10, 8, 18), 0.25 * depth)
}

/** The color of a star from its B-V color index, pale as the eye sees it. */
private val STAR_COLORS =
    listOf(
        -0.4 to rgb(170, 191, 255),
        0.0 to rgb(202, 215, 255),
        0.3 to rgb(248, 247, 255),
        0.65 to rgb(255, 244, 234),
        1.0 to rgb(255, 222, 180),
        1.5 to rgb(255, 196, 140),
        2.0 to rgb(255, 170, 110),
    )

private fun starColor(bv: Double): Rgb {
    val x = bv.coerceIn(STAR_COLORS.first().first, STAR_COLORS.last().first)
    for (i in 1 until STAR_COLORS.size) {
        if (x <= STAR_COLORS[i].first) {
            val (x0, c0) = STAR_COLORS[i - 1]
            val (x1, c1) = STAR_COLORS[i]
            return mix(c0, c1, (x - x0) / (x1 - x0))
        }
    }
    return STAR_COLORS.last().second
}

private fun starRadius(mag: Double) = max(0.6, min(4.5, 3.2 - 0.45 * mag))

/** The projection of the photo: a direction to a point in the rectangle, and back. */
class PhotoProjection(val r: RectF, val v: PhotoView) {
    val k = r.width() / v.spanDeg
    private val cx = r.centerX()
    private val cy = r.centerY()

    fun x(az: Double) = (cx + wrap180(az - v.azimuthDeg) * k).toFloat()

    fun y(alt: Double) = (cy - (alt - v.altitudeDeg) * k).toFloat()

    fun azimuthAt(px: Float) = v.azimuthDeg + (px - cx) / k

    fun altitudeAt(py: Float) = v.altitudeDeg + (cy - py) / k
}

/**
 * Draws the photo of [f] into the rectangle [r] of the canvas, with [density] pixels for each dp.
 * The caller clips to [r].
 */
class PhotoPainter(private val density: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11 * density
        }
    private val path = Path()
    private val cloud: Bitmap by lazy {
        val size = 64
        createBitmap(size, size).also { b ->
            val p =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader =
                        RadialGradient(
                            32f,
                            32f,
                            32f,
                            0xFFFFFFFF.toInt(),
                            0x00FFFFFF,
                            Shader.TileMode.CLAMP,
                        )
                }
            Canvas(b).drawCircle(32f, 32f, 32f, p)
        }
    }

    // The Milky Way adds its light to the sky.
    private val cloudPaint =
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        }
    private val grainPaint =
        Paint().apply {
            color = argb(rgb(236, 226, 246))
            xfermode = cloudPaint.xfermode
        }

    private fun dp(v: Number) = v.toFloat() * density

    private fun radial(
        x: Float,
        y: Float,
        r0: Float,
        r1: Float,
        vararg stops: Pair<Float, Int>,
    ): Shader {
        // A gradient that starts at r0 > 0: the stops move outward by r0 / r1.
        val start = if (r1 > 0) r0 / r1 else 0f
        return RadialGradient(
            x,
            y,
            max(r1, 0.01f),
            stops.map { it.second }.toIntArray(),
            stops.map { start + (1 - start) * it.first }.toFloatArray(),
            Shader.TileMode.CLAMP,
        )
    }

    fun draw(canvas: Canvas, r: RectF, f: PhotoFrame, nowMs: Long = 0) {
        val p = PhotoProjection(r, f.view)
        val v = f.view
        val k = p.k
        val sunAlt = f.sun.altitudeDeg
        val opposite = (1 - cos(Math.toRadians(wrap180(v.azimuthDeg - f.sun.azimuthDeg)))) / 2

        // The sky.
        val colors =
            IntArray(13) {
                argb(skyColor(p.altitudeAt(r.top + r.height() * it / 12), sunAlt, opposite))
            }
        fill.useShader(
            LinearGradient(
                0f,
                r.top,
                0f,
                r.bottom,
                colors,
                FloatArray(13) { it / 12f },
                Shader.TileMode.CLAMP,
            )
        )
        canvas.drawRect(r, fill)
        fill.shader = null
        // In a solar eclipse, the sky gets dark only when the moon covers most of the sun.
        val coverage = f.solarEclipse?.coverage ?: 0.0
        val dim = if (coverage > 0.8) ((coverage - 0.8) / 0.2).pow(2) * 0.82 else 0.0
        if (dim > 0) {
            fill.color = argb(rgb(14, 12, 30), dim)
            canvas.drawRect(r, fill)
        }
        val haze = skyColor(1.0, sunAlt, opposite)

        // The glow of the sun, also when it is just below the horizon.
        val sx = p.x(f.sun.azimuthDeg)
        val sy = p.y(f.sun.altitudeDeg)
        if (sunAlt > -8 && abs(wrap180(f.sun.azimuthDeg - v.azimuthDeg)) < v.spanDeg) {
            val radius = max(r.width(), r.height()) * 0.6f
            val a = 0.55 * clamp((sunAlt + 8) / 8)
            fill.useShader(
                radial(
                    sx,
                    sy,
                    0f,
                    radius,
                    0f to argb(rgb(255, 226, 180), a),
                    0.3f to argb(rgb(250, 190, 140), a * 0.35),
                    1f to argb(rgb(250, 190, 140), 0.0),
                )
            )
            canvas.drawRect(r, fill)
            fill.shader = null
        }

        starsAndMilkyWay(canvas, r, f, p, nowMs)

        // The path of a body, dotted, with the hours.
        f.track?.let { track ->
            stroke.color = argb(CREAM, 0.85)
            stroke.strokeWidth = dp(1.6)
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.pathEffect = DashPathEffect(floatArrayOf(dp(1.5), dp(5)), 0f)
            path.reset()
            var started = false
            var lastX = 0f
            for (point in track.points) {
                val px = p.x(point.azimuthDeg)
                val py = p.y(point.altitudeDeg)
                if (started && abs(px - lastX) < r.width()) path.lineTo(px, py)
                else path.moveTo(px, py)
                started = true
                lastX = px
            }
            canvas.drawPath(path, stroke)
            stroke.pathEffect = null
            text.textAlign = Paint.Align.CENTER
            text.color = argb(CREAM, 0.9)
            fill.color = argb(CREAM, 0.9)
            for ((direction, label) in track.ticks) {
                val px = p.x(direction.azimuthDeg)
                val py = p.y(direction.altitudeDeg)
                canvas.drawCircle(px, py, dp(2.6), fill)
                canvas.drawText(label, px, py - dp(9), text)
            }
        }

        val eclipse = f.solarEclipse
        if (eclipse != null) drawSolarEclipse(canvas, f, eclipse, p, dim)
        else
            f.body?.let {
                drawBody(canvas, it, p.x(it.direction.azimuthDeg), p.y(it.direction.altitudeDeg))
            }
        if (f.moonFraction > 0.01 && f.moon.altitudeDeg > -2 && eclipse == null)
            moonDisc(canvas, f, p)

        // The terrain, from the far layer to the near one.
        val layers = f.terrain.layers
        for (layer in layers - 1 downTo 0) {
            val depth = if (layers > 1) 1 - layer.toDouble() / (layers - 1) else 1.0
            val (crest, foot) = layerColors(depth, sunAlt, haze)
            var top = Float.POSITIVE_INFINITY
            path.reset()
            path.moveTo(r.left - 2, r.bottom + 2)
            var px = r.left - 2
            val step = dp(2)
            while (px <= r.right + 2) {
                val alt = f.terrain.altitude(layer, p.azimuthAt(px))
                // A part without terrain in this layer lies below the photo.
                val py = if (alt.isFinite()) p.y(alt) else r.bottom + 2
                top = min(top, py)
                path.lineTo(px, py)
                px += step
            }
            path.lineTo(r.right + 2, r.bottom + 2)
            path.close()
            val y0 = max(r.top, min(top, r.bottom - 1))
            fill.useShader(
                LinearGradient(
                    0f,
                    y0,
                    0f,
                    r.bottom,
                    intArrayOf(argb(crest), argb(foot), argb(mix(foot, rgb(8, 6, 14), 0.3))),
                    floatArrayOf(0f, min(1.0, 0.25 + 0.4 * (1 - depth)).toFloat(), 1f),
                    Shader.TileMode.CLAMP,
                )
            )
            canvas.drawPath(path, fill)
            fill.shader = null
            // A thin light on the crest: the glow of the sky behind the ridge.
            stroke.color = argb(mix(crest, CREAM, 0.5), 0.35 * (1 - depth * 0.6))
            stroke.strokeWidth = dp(1)
            canvas.drawPath(path, stroke)
        }

        // The vignette of a long lens: the corners a little darker.
        val outer = hypot(r.width(), r.height()) * 0.62f
        fill.useShader(
            radial(
                r.centerX(),
                r.centerY(),
                min(r.width(), r.height()) * 0.35f,
                outer,
                0f to argb(rgb(20, 12, 30), 0.0),
                1f to argb(rgb(20, 12, 30), 0.38),
            )
        )
        canvas.drawRect(r, fill)
        fill.shader = null

        f.pin?.let {
            if (it.alpha > 0.01)
                drawPin(canvas, it, p.x(it.direction.azimuthDeg), p.y(it.direction.altitudeDeg))
        }
        f.finder?.let { if (it.alpha > 0.01) drawFinder(canvas, r, it, p) }
        f.hud?.let { drawHud(canvas, r, it) }
    }

    private fun starsAndMilkyWay(
        canvas: Canvas,
        r: RectF,
        f: PhotoFrame,
        p: PhotoProjection,
        nowMs: Long,
    ) {
        val dust = f.dust
        if (dust != null && dust.strength > 0.01) {
            val weight = dust.milkyWay.weight
            val cloudFlags = dust.milkyWay.cloud
            val size = (2.6 * p.k).toFloat()
            val paint = cloudPaint
            val grain = grainPaint
            val dst = RectF()
            for (i in weight.indices) {
                val alt = dust.directions[2 * i + 1]
                if (alt < 0) continue
                val px = p.x(dust.directions[2 * i])
                val py = p.y(alt)
                if (px < r.left - size || px > r.right + size || py < r.top - size || py > r.bottom)
                    continue
                val a = dust.strength * weight[i] * clamp(alt / 12)
                if (cloudFlags[i]) {
                    paint.alpha = (0.07 * a * 255 * 0.92).roundToInt().coerceIn(0, 255)
                    val s = size * (0.6f + 0.8f * ((i * 7919) % 100) / 100f)
                    dst.set(px - s / 2, py - s / 2, px + s / 2, py + s / 2)
                    canvas.drawBitmap(cloud, null, dst, paint)
                } else {
                    grain.alpha = (0.5 * a * 255).roundToInt().coerceIn(0, 255)
                    canvas.drawRect(px, py, px + density, py + density, grain)
                }
            }
        }
        val s = f.stars ?: return
        if (s.limitMag < 0.5) return
        val mag = s.stars.mag
        var count = 0
        while (count < mag.size && mag[count] <= s.limitMag) count++
        for (i in count - 1 downTo 0) {
            val alt = s.directions[2 * i + 1]
            if (alt < 0) continue
            val px = p.x(s.directions[2 * i])
            val py = p.y(alt)
            if (px < r.left || px > r.right || py < r.top || py > r.bottom) continue
            val m = mag[i].toDouble()
            val fade = clamp(0.3 + (s.limitMag - m) / 1.5) * clamp(0.3 + alt / 8)
            val twinkle = if (m < 2) 0.85 + 0.15 * sin(nowMs / 650.0 + i) else 1.0
            fill.color = argb(starColor(s.stars.bv[i].toDouble()), fade * twinkle)
            canvas.drawCircle(px, py, dp(starRadius(m) * 0.8), fill)
        }
    }

    private fun drawBody(canvas: Canvas, b: PhotoBody, px: Float, py: Float) {
        if (b.sun) {
            val radius = dp(90)
            fill.useShader(
                radial(
                    px,
                    py,
                    0f,
                    radius,
                    0f to argb(rgb(255, 246, 220), 0.95),
                    0.12f to argb(rgb(255, 220, 160), 0.6),
                    1f to argb(rgb(255, 200, 140), 0.0),
                )
            )
            canvas.drawCircle(px, py, radius, fill)
            fill.shader = null
            fill.color = argb(rgb(255, 250, 240))
            canvas.drawCircle(px, py, dp(7), fill)
        } else {
            fill.useShader(
                radial(
                    px,
                    py,
                    0f,
                    dp(12),
                    0f to argb(rgb(255, 248, 230), 0.9),
                    1f to argb(rgb(255, 248, 230), 0.0),
                )
            )
            canvas.drawCircle(px, py, dp(12), fill)
            fill.shader = null
            fill.color = argb(rgb(255, 253, 246))
            canvas.drawCircle(px, py, dp(2.6), fill)
        }
        text.textAlign = Paint.Align.LEFT
        text.color = argb(CREAM, 0.95)
        canvas.drawText(b.label.uppercase(), px + dp(12), py - dp(10), text)
    }

    private fun moonDisc(canvas: Canvas, f: PhotoFrame, p: PhotoProjection) {
        val mx = p.x(f.moon.azimuthDeg)
        val my = p.y(f.moon.altitudeDeg)
        // The apparent radius of the moon is about 0.26°. In a wide view it stays visible.
        val radius = max(dp(3.5), (0.259 * p.k).toFloat())
        val warm = clamp((9 - f.moon.altitudeDeg) / 9)
        // The umbra of the Earth makes the moon dark and copper red: the light of all the sunsets.
        val umbra = clamp(f.lunarUmbra)
        val color = mix(mix(CREAM, rgb(255, 200, 150), warm * 0.8), rgb(150, 52, 34), 0.88 * umbra)
        val light = f.moonFraction * (1 - 0.8 * umbra)
        fill.useShader(
            radial(
                mx,
                my,
                radius * 0.8f,
                radius * 7,
                0f to argb(color, 0.38 * light),
                0.35f to argb(color, 0.1 * light),
                1f to argb(color, 0.0),
            )
        )
        canvas.drawRect(mx - radius * 7, my - radius * 7, mx + radius * 7, my + radius * 7, fill)
        fill.shader = null

        // The dark part: a little earthshine.
        fill.color = argb(rgb(80, 70, 100), 0.35)
        canvas.drawCircle(mx, my, radius, fill)

        // The lit part points to the sun.
        val toward = MoonPhase.towardSun(f.moon, f.sun, 1.0)
        val angle = atan2(p.y(toward.altitudeDeg) - my, p.x(toward.azimuthDeg) - mx)
        canvas.withTranslation(mx, my) {
            canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
            path.reset()
            MoonPhase.litOutline(f.moonFraction, 40).forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo((x * radius).toFloat(), (y * radius).toFloat())
                else path.lineTo((x * radius).toFloat(), (y * radius).toFloat())
            }
            path.close()
            fill.useShader(
                radial(
                    0f,
                    0f,
                    0f,
                    radius,
                    0f to argb(mix(color, rgb(255, 255, 250), 0.4)),
                    1f to argb(color),
                )
            )
            canvas.drawPath(path, fill)
            fill.shader = null
            // A few soft seas, so the full moon looks like the moon and not like a disc.
            if (radius > dp(14)) {
                fill.color = argb(mix(color, rgb(170, 150, 150), 0.5), 0.13)
                val c = cos(-angle)
                val s = sin(-angle)
                for ((sx, sy, sr) in SEAS) {
                    // The seas turn with the disc, so undo the rotation for them.
                    canvas.drawCircle(
                        (sx * c - sy * s) * radius,
                        (sx * s + sy * c) * radius,
                        sr * radius,
                        fill,
                    )
                }
            }
        }
    }

    /**
     * A solar eclipse: the sun at its true size, its corona at totality, and the dark moon on it.
     */
    private fun drawSolarEclipse(
        canvas: Canvas,
        f: PhotoFrame,
        e: SolarDiscs,
        p: PhotoProjection,
        dim: Double,
    ) {
        val sx = p.x(f.sun.azimuthDeg)
        val sy = p.y(f.sun.altitudeDeg)
        val mx = p.x(f.moon.azimuthDeg)
        val my = p.y(f.moon.altitudeDeg)
        // In a wide view the discs stay visible: both grow by the same factor, and the moon moves
        // away from the sun by that factor, so the overlap looks as it is.
        val scale = max(1.0, dp(6) / (e.sunRadiusDeg * p.k)).toFloat()
        val rs = (e.sunRadiusDeg * p.k).toFloat() * scale
        val rm = (e.moonRadiusDeg * p.k).toFloat() * scale
        val ox = sx + (mx - sx) * scale
        val oy = sy + (my - sy) * scale
        // The glow of the sun fades as the moon covers it; the corona appears at totality.
        val light = 1 - e.coverage
        fill.useShader(
            radial(
                sx,
                sy,
                rs,
                rs * 9,
                0f to argb(rgb(255, 236, 200), 0.55 * max(light, 0.05)),
                1f to argb(rgb(255, 220, 170), 0.0),
            )
        )
        canvas.drawRect(sx - rs * 9, sy - rs * 9, sx + rs * 9, sy + rs * 9, fill)
        if (dim > 0.4) {
            fill.useShader(
                radial(
                    sx,
                    sy,
                    rs * 0.95f,
                    rs * 3.2f,
                    0f to argb(rgb(240, 244, 255), 0.85 * dim),
                    0.35f to argb(rgb(220, 228, 255), 0.3 * dim),
                    1f to argb(rgb(220, 228, 255), 0.0),
                )
            )
            canvas.drawCircle(sx, sy, rs * 3.2f, fill)
        }
        fill.shader = null
        fill.color = argb(rgb(255, 250, 240))
        canvas.drawCircle(sx, sy, rs, fill)
        fill.color = argb(rgb(18, 13, 28))
        canvas.drawCircle(ox, oy, rm, fill)
    }

    /** The label of the landmark: a ring on the summit and a tag above it. */
    private fun drawPin(canvas: Canvas, pin: PhotoPin, px: Float, py: Float) {
        val a = pin.alpha.toDouble()
        val ring = dp(9 + 10 * (1 - pin.alpha))
        stroke.color = argb(CREAM, 0.95 * a)
        stroke.strokeWidth = dp(1.4)
        canvas.drawCircle(px, py, ring, stroke)
        for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
            canvas.drawLine(
                px + dx * (ring + dp(3)),
                py + dy * (ring + dp(3)),
                px + dx * (ring + dp(9)),
                py + dy * (ring + dp(9)),
                stroke,
            )
        }
        val top = py - ring - dp(52)
        canvas.drawLine(px, py - ring - dp(9), px, top + dp(30), stroke)
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        text.textSize = dp(12)
        val w1 = text.measureText(pin.title)
        text.typeface = Typeface.MONOSPACE
        text.textSize = dp(11)
        val w = max(w1, text.measureText(pin.detail)) + dp(20)
        fill.color = argb(rgb(255, 249, 238), 0.94 * a)
        canvas.drawRect(px - w / 2, top - dp(8), px + w / 2, top + dp(30), fill)
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        text.textSize = dp(12)
        text.color = argb(INK, a)
        canvas.drawText(pin.title, px, top + dp(8), text)
        text.typeface = Typeface.MONOSPACE
        text.textSize = dp(11)
        text.color = argb(ROSE, a)
        canvas.drawText(pin.detail, px, top + dp(23), text)
    }

    /** The frame of a lens on a full-frame camera (36 × 24 mm), with the parts outside darker. */
    private fun drawFinder(canvas: Canvas, r: RectF, finder: PhotoFinder, p: PhotoProjection) {
        val a = finder.alpha.toDouble()
        val hw = (Math.toDegrees(atan(18.0 / finder.focalMm)) * p.k).toFloat()
        val hh = (Math.toDegrees(atan(12.0 / finder.focalMm)) * p.k).toFloat()
        val cx = p.x(finder.center.azimuthDeg)
        val cy = p.y(finder.center.altitudeDeg)
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(r, Path.Direction.CW)
        path.addRect(cx - hw, cy - hh, cx + hw, cy + hh, Path.Direction.CW)
        fill.color = argb(rgb(14, 10, 22), 0.5 * a)
        canvas.drawPath(path, fill)
        path.fillType = Path.FillType.WINDING
        stroke.color = argb(CREAM, 0.9 * a)
        stroke.strokeWidth = dp(1.2)
        canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, stroke)
        stroke.color = argb(CREAM, 0.22 * a)
        stroke.strokeWidth = dp(1)
        for (t in listOf(1f / 3, 2f / 3)) {
            canvas.drawLine(cx - hw + 2 * hw * t, cy - hh, cx - hw + 2 * hw * t, cy + hh, stroke)
            canvas.drawLine(cx - hw, cy - hh + 2 * hh * t, cx + hw, cy - hh + 2 * hh * t, stroke)
        }
        text.textAlign = Paint.Align.LEFT
        text.color = argb(CREAM, 0.9 * a)
        canvas.drawText("${finder.focalMm} MM · 36 × 24", cx - hw, cy + hh + dp(16), text)
    }

    /** The marks and the texts of a viewfinder. */
    private fun drawHud(canvas: Canvas, r: RectF, hud: PhotoHud) {
        val inset = dp(14)
        val arm = dp(18)
        stroke.color = argb(CREAM, 0.75)
        stroke.strokeWidth = dp(1.5)
        val corners =
            listOf(
                floatArrayOf(r.left + inset, r.top + inset, 1f, 1f),
                floatArrayOf(r.right - inset, r.top + inset, -1f, 1f),
                floatArrayOf(r.left + inset, r.bottom - inset, 1f, -1f),
                floatArrayOf(r.right - inset, r.bottom - inset, -1f, -1f),
            )
        for ((px, py, dx, dy) in corners) {
            path.reset()
            path.moveTo(px + dx * arm, py)
            path.lineTo(px, py)
            path.lineTo(px, py + dy * arm)
            canvas.drawPath(path, stroke)
        }
        // The small crosshair in the center.
        val cx = r.centerX()
        val cy = r.centerY()
        stroke.color = argb(CREAM, 0.35)
        stroke.strokeWidth = dp(1)
        canvas.drawLine(cx - dp(7), cy, cx + dp(7), cy, stroke)
        canvas.drawLine(cx, cy - dp(7), cx, cy + dp(7), stroke)

        text.color = argb(CREAM, 0.88)
        val middle = -(text.ascent() + text.descent()) / 2
        val small = r.width() < dp(420)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(hud.topLeft, r.left + inset + dp(10), r.top + inset + dp(10) + middle, text)
        if (!small) {
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                hud.topRight,
                r.right - inset - dp(10),
                r.top + inset + dp(10) + middle,
                text,
            )
        }
        // The data strip at the bottom, like the display of a camera.
        if (hud.bottom.isNotEmpty()) {
            fill.color = argb(rgb(14, 10, 22), 0.42)
            canvas.drawRect(r.left, r.bottom - dp(30), r.right, r.bottom, fill)
            text.color = argb(CREAM, 0.92)
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(hud.bottom, r.centerX(), r.bottom - dp(15) + middle, text)
        }
    }

    private companion object {
        val SEAS =
            listOf(
                Triple(-0.25f, -0.3f, 0.28f),
                Triple(0.15f, -0.35f, 0.2f),
                Triple(0.25f, 0.05f, 0.22f),
                Triple(-0.1f, 0.25f, 0.16f),
                Triple(-0.45f, 0.05f, 0.14f),
            )
    }
}
