package at.stefl.sunradar

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.stefl.sunradar.core.Observer
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MIN_MAP_ZOOM = 1.0
private const val MAX_MAP_ZOOM = 12.0

/** The base map of the app, decoded once for the process (about 0.5 s on a phone). */
object BaseMapStore {
    private val mutex = Mutex()
    private var map: BaseMap? = null

    /** The base map, or null if the asset cannot be read. */
    suspend fun load(context: Context): BaseMap? = mutex.withLock {
        map
            ?: withContext(Dispatchers.Default) {
                    try {
                        BaseMap.decode(context.assets.open("basemap.json").use { it.readBytes() })
                    } catch (e: java.io.IOException) {
                        null
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                .also { map = it }
    }
}

/**
 * The name of a dropped place: [name] (a place of the search), else "near" the nearest place of the
 * map, with the coordinates.
 */
internal fun droppedPlaceName(map: BaseMap, at: Observer, name: String?): String {
    val coordinates = "%.3f°, %.3f°".format(Locale.ROOT, at.latitudeDeg, at.longitudeDeg)
    val label = name ?: map.nearest(at)?.let { "near ${it.name}" } ?: return coordinates
    return "$label · $coordinates"
}

/**
 * The place panel: a search of the places of the base map and a map of Natural Earth that works
 * offline. Drag to move, pinch or the buttons to zoom, and tap to drop the place. [onDrop] gets the
 * place, its name, and its time zone.
 */
@Composable
fun PlacePanel(
    place: Place,
    deviceZone: ZoneId,
    onDrop: (Observer, String, ZoneId) -> Unit,
    onUseDevice: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    // The back key closes the panel, not the app.
    BackHandler(onBack = onClose)
    val map by produceState<BaseMap?>(null) { value = BaseMapStore.load(context) }
    var query by remember { mutableStateOf("") }
    // The center of the view in Web Mercator coordinates of a world of the size 1, and the zoom.
    var cx by remember { mutableDoubleStateOf(mercatorX(place.observer.longitudeDeg)) }
    var cy by remember { mutableDoubleStateOf(mercatorY(place.observer.latitudeDeg)) }
    var zoom by remember { mutableDoubleStateOf(5.0) }
    var pin by remember { mutableStateOf(place.observer) }

    fun drop(at: Observer, name: String?) {
        val m = map ?: return
        pin = at
        onDrop(at, droppedPlaceName(m, at, name), m.timeZoneAt(at, deviceZone))
    }

    Box(Modifier.fillMaxSize().background(MapColors.sea)) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val world = worldSize(zoom, density)
                        // Keep the point under the fingers at the same place on the screen.
                        val fx = (centroid.x - size.width / 2.0) / world
                        val fy = (centroid.y - size.height / 2.0) / world
                        val newZoom =
                            (zoom + ln(gestureZoom.toDouble()) / ln(2.0)).coerceIn(
                                MIN_MAP_ZOOM,
                                MAX_MAP_ZOOM,
                            )
                        val factor = 2.0.pow(zoom - newZoom)
                        cx += fx - fx * factor - pan.x / worldSize(newZoom, density)
                        cy += fy - fy * factor - pan.y / worldSize(newZoom, density)
                        cx -= floor(cx)
                        cy = cy.coerceIn(0.0, 1.0)
                        zoom = newZoom
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        val world = worldSize(zoom, density)
                        val x = cx + (at.x - size.width / 2.0) / world
                        val y = (cy + (at.y - size.height / 2.0) / world).coerceIn(0.0, 1.0)
                        drop(Observer(mercatorLatitude(y), mercatorLongitude(x)), null)
                    }
                }
        ) {
            map?.let { drawBaseMap(it, cx, cy, zoom, pin) }
        }

        Column(
            Modifier.align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .background(Color(0xE6000000), RoundedCornerShape(12.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.weight(1f),
                    placeholder = { Text("Search a place") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                FilledTonalButton(onClick = onClose) { Text("Close") }
            }
            val results = remember(map, query) { map?.search(query).orEmpty() }
            for (p in results) {
                Text(
                    "${p.name}, ${p.country}",
                    color = Color.White,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable {
                                cx = mercatorX(p.longitudeDeg)
                                cy = mercatorY(p.latitudeDeg)
                                zoom = maxOf(zoom, 8.0)
                                query = ""
                                focus.clearFocus()
                                drop(p.observer, p.name)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
            if (map == null) Text("Loading the map …", color = Color.White)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { zoom = (zoom.roundToInt() - 1.0).coerceAtLeast(MIN_MAP_ZOOM) }
                ) {
                    Text("−")
                }
                FilledTonalButton(
                    onClick = { zoom = (zoom.roundToInt() + 1.0).coerceAtMost(MAX_MAP_ZOOM) }
                ) {
                    Text("+")
                }
                Button(onClick = onUseDevice) { Text("Use my location") }
            }
            Text(
                "Tap the map to drop the place. ${map?.attribution ?: "Map: Natural Earth"}",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.background(Color(0x99000000)).padding(horizontal = 4.dp),
            )
        }
    }
}

private object MapColors {
    val sea = Color(0xFF0E2440)
    val rivers = Color(0x806EAAE6)
    val lakes = Color(0xCC6EAAE6)
    val coastline = Color(0xE6DCE6F0)
    val borders = Color(0x99FFC83D)
    val pin = Color(0xFFFFC83D)
}

/** The width of the world in pixels at [zoom]: 256 dp at zoom 0, as in the PWA. */
private fun worldSize(zoom: Double, density: Float) = 256.0 * density * 2.0.pow(zoom)

private val placePaints = mutableMapOf<Float, Paint>()

private fun placePaint(sizePx: Float) =
    placePaints.getOrPut(sizePx) {
        Paint().apply {
            isAntiAlias = true
            color = Color.White.copy(alpha = 0.85f).toArgb()
            textSize = sizePx
            typeface = Typeface.DEFAULT
        }
    }

/**
 * Draws the base map with the center ([cx], [cy]) and [zoom], and the [pin]. Near the date line,
 * the screen shows two copies of the world, so the map draws each copy that is on the screen.
 */
private fun DrawScope.drawBaseMap(
    map: BaseMap,
    cx: Double,
    cy: Double,
    zoom: Double,
    pin: Observer,
) {
    val world = worldSize(zoom, density)
    val left = cx - size.width / 2 / world
    val top = cy - size.height / 2 / world
    val right = cx + size.width / 2 / world
    val bottom = cy + size.height / 2 / world
    val px = density
    for (copy in floor(left).toInt()..floor(right).toInt()) {
        // The screen box in the coordinates of this copy of the world.
        val box = Box(left - copy, top, right - copy, bottom)
        translate(left = ((copy - left) * world).toFloat(), top = (-top * world).toFloat()) {
            strokeLayer(map.rivers, box, world, MapColors.rivers, Stroke(1 * px))
            strokeLayer(map.lakes, box, world, MapColors.lakes, Stroke(1 * px))
            strokeLayer(map.coastline, box, world, MapColors.coastline, Stroke(1.2f * px))
            strokeLayer(
                map.borders,
                box,
                world,
                MapColors.borders,
                Stroke(
                    1 * px,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * px, 3 * px)),
                ),
            )
        }
    }

    // The place names: more of them at a larger zoom. The places are the largest first, so a large
    // place gets its label before a small one near it.
    val maxRank = minOf(10, (zoom * 1.6).roundToInt())
    val paint = placePaint(12 * px)
    val used = mutableListOf<Offset>()
    for (place in map.places) {
        if (place.rank > maxRank) continue
        var x = (mercatorX(place.longitudeDeg) - left) * world
        // The copy of the world that is nearest to the screen.
        x -= floor(x / world) * world
        if (x > size.width) continue
        val y = (mercatorY(place.latitudeDeg) - top) * world
        if (y < 0 || y > size.height) continue
        val at = Offset(x.toFloat(), y.toFloat())
        if (used.any { abs(it.x - at.x) < 70 * px && abs(it.y - at.y) < 16 * px }) continue
        used += at
        drawCircle(Color.White, radius = 1.5f * px, center = at)
        drawContext.canvas.nativeCanvas.drawText(place.name, at.x + 4 * px, at.y - 3 * px, paint)
        if (used.size > 200) break
    }

    var pinX = (mercatorX(pin.longitudeDeg) - left) * world
    pinX -= floor(pinX / world) * world
    val pinAt = Offset(pinX.toFloat(), ((mercatorY(pin.latitudeDeg) - top) * world).toFloat())
    drawCircle(MapColors.pin, radius = 7 * px, center = pinAt)
    drawCircle(Color.Black, radius = 7 * px, center = pinAt, style = Stroke(1.5f * px))
}

/** A box in Web Mercator coordinates. */
private class Box(val left: Double, val top: Double, val right: Double, val bottom: Double)

/**
 * Strokes the lines of a layer that are in [box]. The coordinates are relative to the world (the
 * caller translates). A point nearer than one pixel to the point before it is skipped, so the world
 * view stays fast.
 */
private fun DrawScope.strokeLayer(
    lines: List<MapLine>,
    box: Box,
    world: Double,
    color: Color,
    stroke: Stroke,
) {
    val path = Path()
    val scale = world.toFloat()
    for (line in lines) {
        if (line.right < box.left || line.left > box.right) continue
        if (line.bottom < box.top || line.top > box.bottom) continue
        val p = line.points
        var lastX = p[0] * scale
        var lastY = p[1] * scale
        path.moveTo(lastX, lastY)
        var k = 2
        while (k < p.size) {
            val x = p[k] * scale
            val y = p[k + 1] * scale
            if (abs(x - lastX) >= 1f || abs(y - lastY) >= 1f || k == p.size - 2) {
                path.lineTo(x, y)
                lastX = x
                lastY = y
            }
            k += 2
        }
    }
    drawPath(path, color, style = stroke)
}
