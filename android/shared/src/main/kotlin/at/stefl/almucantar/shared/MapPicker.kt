package at.stefl.almucantar.shared

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.stefl.almucantar.core.Observer
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TILE_SIZE = 256
private const val MIN_ZOOM = 3
private const val MAX_ZOOM = 19

// The OSM tile usage policy asks for a User-Agent that identifies the app, and for the
// attribution on the map. https://operations.osmfoundation.org/policies/tiles/
private const val TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png"

/** Web Mercator: the world position in pixels at [zoom] (256 px tiles). */
internal fun worldX(longitudeDeg: Double, zoom: Int) =
    mercatorX(longitudeDeg) * TILE_SIZE * 2.0.pow(zoom)

internal fun worldY(latitudeDeg: Double, zoom: Int) =
    mercatorY(latitudeDeg) * TILE_SIZE * 2.0.pow(zoom)

internal fun longitude(worldX: Double, zoom: Int) = worldX / (TILE_SIZE * 2.0.pow(zoom)) * 360 - 180

internal fun latitude(worldY: Double, zoom: Int) =
    mercatorLatitude(worldY / (TILE_SIZE * 2.0.pow(zoom)))

/** The tiles in memory, shared by all maps of the process. */
private object TileCache {
    /** About 32 MB of tiles (a tile of 256 × 256 pixels has 256 KB). */
    val bitmaps =
        object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    val loading = mutableSetOf<String>()
    /** The time of the last failed load of each tile, so an offline map does not retry at once. */
    val failed = mutableMapOf<String, Long>()
    const val RETRY_MS = 30_000L

    fun mayLoad(key: String) =
        (System.currentTimeMillis() - (failed[key] ?: 0L)) > RETRY_MS && loading.add(key)

    suspend fun load(z: Int, x: Int, y: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(TILE_URL.format(z, x, y)).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", UserAgent.value)
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
        }
}

/**
 * A map of OpenStreetMap tiles to pick a landmark. Drag to move, pinch to zoom, and tap to put the
 * landmark. [onPick] gets the landmark, or null if the user cancels.
 */
@Composable
fun MapPicker(center: Observer, onPick: (Observer?) -> Unit) {
    var zoom by remember { mutableIntStateOf(16) }
    // The world pixel at the center of the screen.
    var cx by remember { mutableDoubleStateOf(worldX(center.longitudeDeg, 16)) }
    var cy by remember { mutableDoubleStateOf(worldY(center.latitudeDeg, 16)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var landmark by remember { mutableStateOf<Observer?>(null) }
    var version by remember { mutableIntStateOf(0) } // redraw when a tile arrives
    val scope = rememberCoroutineScope()
    // The zoom of the pinch since the last zoom step: each event gives only a small factor.
    var pinch by remember { mutableFloatStateOf(1f) }

    fun setZoom(newZoom: Int) {
        val z = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val factor = 2.0.pow(z - zoom)
        cx *= factor
        cy *= factor
        zoom = z
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFE5E3DF))) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        cx -= pan.x
                        cy -= pan.y
                        pinch *= gestureZoom
                        if (pinch > 1.5f || pinch < 1 / 1.5f) {
                            setZoom(if (pinch > 1) zoom + 1 else zoom - 1)
                            pinch = 1f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        val wx = cx + at.x - size.width / 2.0
                        val wy = cy + at.y - size.height / 2.0
                        landmark = Observer(latitude(wy, zoom), longitude(wx, zoom))
                    }
                }
        ) {
            size = IntSize(this.size.width.toInt(), this.size.height.toInt())
            version.let {} // read the state, so a loaded tile redraws the map
            val left = cx - this.size.width / 2
            val top = cy - this.size.height / 2
            val n = 1 shl zoom
            for (ty in
                floor(top / TILE_SIZE).toInt()..floor((top + this.size.height) / TILE_SIZE)
                        .toInt()) {
                if (ty < 0 || ty >= n) continue
                for (tx in
                    floor(left / TILE_SIZE).toInt()..floor((left + this.size.width) / TILE_SIZE)
                            .toInt()) {
                    val x = ((tx % n) + n) % n
                    val key = "$zoom/$x/$ty"
                    val bitmap = TileCache.bitmaps.get(key)
                    if (bitmap != null) {
                        drawImage(
                            bitmap.asImageBitmap(),
                            dstOffset =
                                IntOffset(
                                    (tx * TILE_SIZE - left).toInt(),
                                    (ty * TILE_SIZE - top).toInt(),
                                ),
                            dstSize = IntSize(TILE_SIZE, TILE_SIZE),
                        )
                    } else if (TileCache.mayLoad(key)) {
                        val z = zoom
                        scope.launch {
                            try {
                                val tile = TileCache.load(z, x, ty)
                                if (tile != null) TileCache.bitmaps.put(key, tile)
                                else TileCache.failed[key] = System.currentTimeMillis()
                            } finally {
                                // Also if the user leaves the map during the load.
                                TileCache.loading.remove(key)
                            }
                            version++
                        }
                    }
                }
            }
            fun screen(o: Observer) =
                Offset(
                    (worldX(o.longitudeDeg, zoom) - left).toFloat(),
                    (worldY(o.latitudeDeg, zoom) - top).toFloat(),
                )
            val me = screen(center)
            landmark?.let {
                val target = screen(it)
                drawLine(Color(0xFF1565C0), me, target, strokeWidth = 3.dp.toPx())
                drawCircle(Color(0xFFD32F2F), radius = 9.dp.toPx(), center = target)
                drawCircle(
                    Color.White,
                    radius = 9.dp.toPx(),
                    center = target,
                    style = Stroke(2.dp.toPx()),
                )
            }
            drawCircle(Color(0xFF1565C0), radius = 8.dp.toPx(), center = me)
            drawCircle(Color.White, radius = 8.dp.toPx(), center = me, style = Stroke(2.dp.toPx()))
        }

        Column(
            Modifier.align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(12.dp)
                .background(Color(0xE6FFFFFF), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                "Tap a landmark that you can see from here, for example a tower.",
                color = Color.Black,
            )
            landmark?.let {
                Text(
                    "Bearing %.1f° · %.0f m away"
                        .format(Geo.bearingDeg(center, it), Geo.distanceM(center, it)),
                    color = Color.Black,
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { setZoom(zoom - 1) }) { Text("−") }
                FilledTonalButton(onClick = { setZoom(zoom + 1) }) { Text("+") }
                FilledTonalButton(onClick = { onPick(null) }) { Text("Cancel") }
                Button(onClick = { onPick(landmark) }, enabled = landmark != null) {
                    Text("Use landmark")
                }
            }
            Text(
                "© OpenStreetMap contributors",
                color = Color.Black,
                fontSize = 11.sp,
                modifier = Modifier.background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp),
            )
        }
    }
}
