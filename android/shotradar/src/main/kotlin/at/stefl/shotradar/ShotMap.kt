package at.stefl.shotradar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.shared.OsmTiles
import at.stefl.almucantar.shared.TILE_SIZE
import at.stefl.almucantar.shared.latitude
import at.stefl.almucantar.shared.longitude
import at.stefl.almucantar.shared.worldX
import at.stefl.almucantar.shared.worldY
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// The map of shotradar: OpenStreetMap tiles (they show the names and the heights of the peaks),
// with
// a continuous zoom, and the marks of the app: the landmark, the spot, lines (the line of the
// spots,
// the direction of a subject, the path of the shadow of an eclipse), and dots (the spots). A drag
// moves the map, a pinch zooms it, and a tap gives the place.

/** A line of the map. [widthM] gives a band of that width on the ground instead of [widthDp]. */
class MapLine(
    val points: List<Observer>,
    val color: Color,
    val widthDp: Float = 3f,
    val dash: Boolean = false,
    val widthM: Double? = null,
)

/** A spot of the line of spots: full if the view to the landmark is clear. */
class MapDot(val at: Observer, val full: Boolean, val selected: Boolean = false)

class MapMarks(
    val landmark: Observer? = null,
    val spot: Observer? = null,
    val lines: List<MapLine> = emptyList(),
    val dots: List<MapDot> = emptyList(),
)

private const val MIN_ZOOM = 0.5
private const val MAX_ZOOM = 18.0

/** The view of the map: the center and the zoom (2 is the whole Earth on a phone, 12 a valley). */
@Stable
class MapState(latitudeDeg: Double, longitudeDeg: Double, zoom: Double) {
    var latitudeDeg by mutableDoubleStateOf(latitudeDeg)
    var longitudeDeg by mutableDoubleStateOf(longitudeDeg)
    var zoom by mutableDoubleStateOf(zoom)
    var size by mutableStateOf(IntSize.Zero)
        internal set

    /** Changes after a tile arrives, so the map draws again. */
    internal var version by mutableIntStateOf(0)

    /** The pixels for each dp: a tile is 256 dp wide at an integer zoom, as on the web. */
    var density = 1f
        internal set

    private val scale
        get() = 2.0.pow(zoom) * density

    /** The world pixel of a place at the zoom of the map. */
    fun world(at: Observer): Offset =
        Offset(
            (worldX(at.longitudeDeg, 0) * scale).toFloat(),
            (worldY(at.latitudeDeg, 0) * scale).toFloat(),
        )

    /** The point of a place on the screen. */
    fun screen(at: Observer): Offset {
        val c = world(Observer(latitudeDeg, longitudeDeg))
        var p = world(at) - c
        // The nearer copy of the world, across the date line.
        val w = (TILE_SIZE * scale).toFloat()
        if (p.x > w / 2) p = p.copy(x = p.x - w)
        if (p.x < -w / 2) p = p.copy(x = p.x + w)
        return p + Offset(size.width / 2f, size.height / 2f)
    }

    /** The place at a point of the screen. */
    fun place(point: Offset): Observer {
        val c = world(Observer(latitudeDeg, longitudeDeg))
        val wx = c.x + point.x - size.width / 2.0
        val wy = c.y + point.y - size.height / 2.0
        val lon = longitude(wx / scale, 0)
        return Observer(latitude(wy / scale, 0).coerceIn(-85.0, 85.0), (lon + 540) % 360 - 180)
    }

    fun center(at: Observer, newZoom: Double = zoom) {
        latitudeDeg = at.latitudeDeg
        longitudeDeg = at.longitudeDeg
        zoom = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /** Shows both places, with a margin. */
    fun fit(a: Observer, b: Observer) {
        latitudeDeg = (a.latitudeDeg + b.latitudeDeg) / 2
        longitudeDeg = (a.longitudeDeg + b.longitudeDeg) / 2
        val dx = abs(worldX(a.longitudeDeg, 0) - worldX(b.longitudeDeg, 0))
        val dy = abs(worldY(a.latitudeDeg, 0) - worldY(b.latitudeDeg, 0))
        val room = max(1, min(size.width, size.height)) * 0.6
        val z = ln(room / density / max(max(dx, dy), 1e-6)) / ln(2.0)
        zoom = z.coerceIn(MIN_ZOOM, 15.0)
    }

    /** The meters for each pixel of the screen at the center. */
    fun metersPerPixel(): Double =
        40_075_016.686 * cos(Math.toRadians(latitudeDeg)) / (TILE_SIZE * scale)

    internal fun pan(by: Offset) {
        val c = world(Observer(latitudeDeg, longitudeDeg)) - by
        val lon = longitude(c.x / scale, 0)
        longitudeDeg = (lon + 540) % 360 - 180
        latitudeDeg = latitude(c.y / scale, 0).coerceIn(-85.0, 85.0)
    }

    internal fun zoomBy(factor: Float) {
        zoom = (zoom + ln(factor.toDouble()) / ln(2.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}

private val LANDMARK = Color(0xFF8A4A55)
private val SPOT = Color(0xFF2A1F3D)

/** The map with the marks. [onTap] gets the place and the point on the screen. */
@Composable
fun ShotMap(
    state: MapState,
    marks: MapMarks,
    modifier: Modifier = Modifier,
    onTap: (Observer, Offset) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxSize().background(Color(0xFFE9E4DA))) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(state) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        state.pan(pan)
                        if (zoom != 1f) state.zoomBy(zoom)
                    }
                }
                .pointerInput(state, onTap) {
                    detectTapGestures(
                        onDoubleTap = { state.zoomBy(2f) },
                        onTap = { onTap(state.place(it), it) },
                    )
                }
        ) {
            state.size = IntSize(size.width.toInt(), size.height.toInt())
            state.density = density
            state.version.let {} // read the state, so a loaded tile draws the map again
            drawTiles(state) { z, x, y, key ->
                scope.launch {
                    try {
                        val tile = OsmTiles.load(z, x, y)
                        if (tile != null) OsmTiles.bitmaps.put(key, tile)
                        else OsmTiles.failed[key] = System.currentTimeMillis()
                    } finally {
                        OsmTiles.loading.remove(key)
                    }
                    state.version++
                }
            }
            for (line in marks.lines) drawMapLine(state, line)
            for (dot in marks.dots) {
                val p = state.screen(dot.at)
                val r = (if (dot.selected) 8 else 6).dp.toPx()
                if (dot.full) drawCircle(LANDMARK, r, p) else drawCircle(Color(0xFFFFFAF2), r, p)
                drawCircle(
                    if (dot.selected) SPOT else LANDMARK,
                    r,
                    p,
                    style = Stroke((if (dot.selected) 3 else 2).dp.toPx()),
                )
            }
            marks.landmark?.let { drawPin(state.screen(it), LANDMARK) }
            marks.spot?.let {
                val p = state.screen(it)
                drawCircle(Color.White, 10.dp.toPx(), p)
                drawCircle(SPOT, 7.dp.toPx(), p)
            }
        }
    }
}

/** The tiles of the view, scaled to the zoom of the map. [load] starts the load of a tile. */
private fun DrawScope.drawTiles(state: MapState, load: (Int, Int, Int, String) -> Unit) {
    val level = floor(state.zoom).toInt().coerceIn(0, 19)
    val scale = 2.0.pow(state.zoom - level) * density
    val tile = TILE_SIZE * scale
    val n = 1 shl level
    val cx = worldX(state.longitudeDeg, level)
    val cy = worldY(state.latitudeDeg, level)
    val left = cx - size.width / 2 / scale
    val top = cy - size.height / 2 / scale
    for (ty in
        floor(top / TILE_SIZE).toInt()..floor((top + size.height / scale) / TILE_SIZE).toInt()) {
        if (ty < 0 || ty >= n) continue
        for (tx in
            floor(left / TILE_SIZE).toInt()..floor((left + size.width / scale) / TILE_SIZE)
                    .toInt()) {
            val x = ((tx % n) + n) % n
            val key = "$level/$x/$ty"
            val bitmap = OsmTiles.bitmaps.get(key)
            val ox = ((tx * TILE_SIZE - left) * scale).roundToInt()
            val oy = ((ty * TILE_SIZE - top) * scale).roundToInt()
            val side = tile.roundToInt() + 1
            if (bitmap != null) {
                drawImage(
                    bitmap.asImageBitmap(),
                    dstOffset = IntOffset(ox, oy),
                    dstSize = IntSize(side, side),
                )
            } else if (OsmTiles.mayLoad(key)) {
                load(level, x, ty, key)
            }
        }
    }
}

private fun DrawScope.drawMapLine(state: MapState, line: MapLine) {
    if (line.points.size < 2) return
    val width =
        line.widthM?.let {
            (it / state.metersPerPixel()).toFloat().coerceAtLeast(line.widthDp.dp.toPx())
        } ?: line.widthDp.dp.toPx()
    val world = (TILE_SIZE * 2.0.pow(state.zoom) * state.density).toFloat()
    // The points in a row without a jump at the date line: each point goes to the copy of the
    // world that is nearest to the point before it.
    val points = ArrayList<Offset>(line.points.size)
    for (o in line.points) {
        var p = state.screen(o)
        points.lastOrNull()?.let { last ->
            while (p.x - last.x > world / 2) p = p.copy(x = p.x - world)
            while (last.x - p.x > world / 2) p = p.copy(x = p.x + world)
        }
        points += p
    }
    val path = androidx.compose.ui.graphics.Path()
    for (copy in listOf(-world, 0f, world)) {
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x + copy, p.y) else path.lineTo(p.x + copy, p.y)
        }
    }
    drawPath(
        path,
        line.color,
        style =
            Stroke(
                width,
                cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                pathEffect =
                    if (line.dash) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
                    else null,
            ),
    )
}

/** The pin of the landmark: a drop with a white ring. */
private fun DrawScope.drawPin(p: Offset, color: Color) {
    val r = 9.dp.toPx()
    val tip = Offset(p.x, p.y)
    val head = Offset(p.x, p.y - r * 2.2f)
    val path =
        androidx.compose.ui.graphics.Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(head.x - r * 0.85f, head.y + r * 0.5f)
            lineTo(head.x + r * 0.85f, head.y + r * 0.5f)
            close()
        }
    drawPath(path, color)
    drawCircle(color, r, head)
    drawCircle(Color.White, r * 0.42f, head)
}
