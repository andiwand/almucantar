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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.stefl.sunradar.core.Observer
import java.time.ZoneId
import java.time.format.TextStyle
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
 * place, its name, and its time zone. A card at the bottom shows the place and its time zone. If
 * [offline] is true, the card also shows the kept places and the action "Keep offline" for the
 * terrain tiles of [place].
 */
@Composable
fun PlacePanel(
    place: Place,
    deviceZone: ZoneId,
    offline: Boolean,
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
    var zoom by remember { mutableDoubleStateOf(7.0) }

    fun drop(at: Observer, name: String?) {
        val m = map ?: return
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
                        focus.clearFocus()
                        val world = worldSize(zoom, density)
                        val x = cx + (at.x - size.width / 2.0) / world
                        val y = (cy + (at.y - size.height / 2.0) / world).coerceIn(0.0, 1.0)
                        drop(Observer(mercatorLatitude(y), mercatorLongitude(x)), null)
                    }
                }
        ) {
            map?.let { drawBaseMap(it, cx, cy, zoom, place.observer) }
        }

        // The search: a rounded bar with the back arrow, and the results below it.
        Column(
            Modifier.align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = PanelColors.surface,
                shadowElevation = 6.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Glyph(GlyphKind.BACK, "Close") }
                    TextField(
                        query,
                        { query = it },
                        Modifier.weight(1f),
                        placeholder = { Text("Search a place") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Glyph(GlyphKind.CLEAR, "Clear") }
                    }
                }
            }
            val results = remember(map, query) { map?.search(query).orEmpty() }
            if (results.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PanelColors.surface,
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        for (p in results) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        cx = mercatorX(p.longitudeDeg)
                                        cy = mercatorY(p.latitudeDeg)
                                        zoom = maxOf(zoom, 8.0)
                                        query = ""
                                        focus.clearFocus()
                                        drop(p.observer, p.name)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Glyph(GlyphKind.PIN, null, tint = PanelColors.muted)
                                Column {
                                    Text(p.name, color = PanelColors.text)
                                    if (p.country.isNotEmpty()) {
                                        Text(p.country, color = PanelColors.muted, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (map == null) Text("Loading the map …", color = PanelColors.text)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            // The map buttons, above the card on the right, as in other map apps.
            Column(
                Modifier.padding(end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapButton(GlyphKind.PLUS, "Zoom in") {
                    zoom = (zoom.roundToInt() + 1.0).coerceAtMost(MAX_MAP_ZOOM)
                }
                MapButton(GlyphKind.MINUS, "Zoom out") {
                    zoom = (zoom.roundToInt() - 1.0).coerceAtLeast(MIN_MAP_ZOOM)
                }
                MapButton(GlyphKind.LOCATE, "Use my location") {
                    cx = mercatorX(place.observer.longitudeDeg)
                    cy = mercatorY(place.observer.latitudeDeg)
                    onUseDevice()
                }
            }
            PlaceCard(place, deviceZone, offline, map?.attribution)
        }
    }
}

/** A round button of the map with a [glyph]. */
@Composable
private fun MapButton(glyph: GlyphKind, description: String, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = PanelColors.surface,
        contentColor = PanelColors.text,
    ) {
        Glyph(glyph, description)
    }
}

/**
 * The card at the bottom: the name of [place], its coordinates and time zone, the hint, and (if
 * [offline]) the kept places and "Keep offline".
 */
@Composable
private fun PlaceCard(place: Place, deviceZone: ZoneId, offline: Boolean, attribution: String?) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = PanelColors.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // A dropped place has the name "near X · coordinates": the card shows the coordinates
            // in the second line, with the time zone.
            Text(
                place.name?.substringBefore(" · ") ?: "Your location",
                color = PanelColors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The readable name of the zone ("Central European Time"), not the IANA ID.
            val zone =
                (place.zone ?: deviceZone).getDisplayName(
                    TextStyle.FULL,
                    LocalConfiguration.current.locales[0],
                )
            Text(
                "%.3f°, %.3f° · %s"
                    .format(
                        Locale.ROOT,
                        place.observer.latitudeDeg,
                        place.observer.longitudeDeg,
                        zone,
                    ),
                color = PanelColors.muted,
                fontSize = 14.sp,
            )
            Text(
                "Tap the map to move the place, or search for it.",
                color = PanelColors.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (offline) OfflineSection(place)
            Text(
                attribution ?: "Map: Natural Earth",
                color = PanelColors.muted.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.End).padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * The kept places (name, number of tiles, size, and "Remove") and the action "Keep offline" for
 * [place]. The default place and the city of the time zone cannot be kept, because the user is not
 * there and did not choose them.
 */
@Composable
private fun OfflineSection(place: Place) {
    val context = LocalContext.current.applicationContext
    val job by OfflinePlaces.job.collectAsState()
    val version by OfflinePlaces.version.collectAsState()
    val kept by
        produceState(emptyList<KeptPlaceStatus>(), version) {
            value = withContext(Dispatchers.IO) { OfflinePlaces.list(context) }
        }
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = PanelColors.divider)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val running = job?.finished == false
        if (place.isKnown) {
            FilledTonalButton(
                onClick = { OfflinePlaces.keep(context, place.observer, keptName(place)) },
                enabled = !running,
            ) {
                Text("Keep offline")
            }
        }
        Text(
            job?.text ?: "Keep the terrain of this place for a trip without a network.",
            color = PanelColors.muted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    if (kept.isNotEmpty()) {
        Text(
            "Kept offline",
            color = PanelColors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
            for (k in kept) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            k.place.name,
                            color = PanelColors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val missing = if (k.missing > 0) " · ${k.missing} missing" else ""
                        Text(
                            "${tileCount(k.place.tileCount)}, ${megabytes(k.bytes)}$missing",
                            color = PanelColors.muted,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = { OfflinePlaces.remove(context, k.place.id) }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

/** The colors of the surfaces of the panel. */
private object PanelColors {
    val surface = Color(0xFF1B2433)
    val text = Color(0xFFE8ECF2)
    val muted = Color(0xFFA9B3C2)
    val divider = Color(0x33FFFFFF)
}

private enum class GlyphKind {
    BACK,
    CLEAR,
    PLUS,
    MINUS,
    LOCATE,
    PIN,
}

/** A small line icon of 24 dp. The app draws its few icons itself, so it needs no icon library. */
@Composable
private fun Glyph(kind: GlyphKind, description: String?, tint: Color = PanelColors.text) {
    Canvas(
        Modifier.size(24.dp).semantics {
            if (description != null) contentDescription = description
        }
    ) {
        val u = size.minDimension / 24f
        val stroke = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 2f * u, StrokeCap.Round)
        when (kind) {
            GlyphKind.BACK -> {
                line(5f, 12f, 19f, 12f)
                line(5f, 12f, 11f, 6f)
                line(5f, 12f, 11f, 18f)
            }
            GlyphKind.CLEAR -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            GlyphKind.PLUS -> {
                line(5f, 12f, 19f, 12f)
                line(12f, 5f, 12f, 19f)
            }
            GlyphKind.MINUS -> line(5f, 12f, 19f, 12f)
            GlyphKind.LOCATE -> {
                drawCircle(tint, 6f * u, Offset(12f * u, 12f * u), style = stroke)
                drawCircle(tint, 2.5f * u, Offset(12f * u, 12f * u))
                line(12f, 2f, 12f, 5f)
                line(12f, 19f, 12f, 22f)
                line(2f, 12f, 5f, 12f)
                line(19f, 12f, 22f, 12f)
            }
            GlyphKind.PIN -> {
                drawPath(pinPath(Offset(12f * u, 21f * u), 6.5f * u), tint, style = stroke)
                drawCircle(tint, 2f * u, Offset(12f * u, 9.5f * u))
            }
        }
    }
}

/** The outline of a map pin with its tip at [tip] and a round head of the radius [r]. */
private fun pinPath(tip: Offset, r: Float): Path {
    val head = Offset(tip.x, tip.y - 1.75f * r)
    return Path().apply {
        moveTo(tip.x, tip.y)
        // The two sides touch the head; the arc closes the top.
        lineTo(head.x - r * 0.87f, head.y + r * 0.5f)
        arcTo(
            Rect(head.x - r, head.y - r, head.x + r, head.y + r),
            startAngleDegrees = 150f,
            sweepAngleDegrees = 240f,
            forceMoveTo = false,
        )
        close()
    }
}

/** The name of a kept place: the name of [place], else its coordinates. */
private fun keptName(place: Place): String =
    place.name
        ?: "%.3f°, %.3f°"
            .format(Locale.ROOT, place.observer.latitudeDeg, place.observer.longitudeDeg)

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

private val haloPaints = mutableMapOf<Float, Paint>()

private fun haloPaint(sizePx: Float) =
    haloPaints.getOrPut(sizePx) {
        Paint(placePaint(sizePx)).apply {
            color = MapColors.sea.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = sizePx / 4
            strokeJoin = Paint.Join.ROUND
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
    val halo = haloPaint(12 * px)
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
        if (used.any { abs(it.x - at.x) < 90 * px && abs(it.y - at.y) < 22 * px }) continue
        used += at
        drawCircle(Color.White, radius = 2f * px, center = at)
        // A dark halo, so the name stays readable over the lines of the map.
        val canvas = drawContext.canvas.nativeCanvas
        canvas.drawText(place.name, at.x + 5 * px, at.y - 4 * px, halo)
        canvas.drawText(place.name, at.x + 5 * px, at.y - 4 * px, paint)
        if (used.size > 200) break
    }

    var pinX = (mercatorX(pin.longitudeDeg) - left) * world
    pinX -= floor(pinX / world) * world
    val pinAt = Offset(pinX.toFloat(), ((mercatorY(pin.latitudeDeg) - top) * world).toFloat())
    // A map pin with its tip at the place, and a shadow on the ground.
    drawOval(
        Color(0x66000000),
        topLeft = Offset(pinAt.x - 5 * px, pinAt.y - 2 * px),
        size = Size(10 * px, 4 * px),
    )
    val outline = pinPath(pinAt, 11 * px)
    drawPath(outline, MapColors.pin)
    drawPath(outline, Color(0xFF3A2A00), style = Stroke(1.5f * px))
    drawCircle(Color(0xFF3A2A00), radius = 4 * px, center = Offset(pinAt.x, pinAt.y - 19.25f * px))
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
