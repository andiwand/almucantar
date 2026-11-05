package at.stefl.starradar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.ScreenPoint
import at.stefl.almucantar.core.ScreenRotation
import at.stefl.almucantar.core.View
import at.stefl.almucantar.core.cameraFromFov
import at.stefl.almucantar.core.headingFromDirection
import at.stefl.almucantar.core.horizonLine
import at.stefl.almucantar.core.projectLine
import at.stefl.almucantar.core.projectPoints
import at.stefl.almucantar.core.unproject
import at.stefl.almucantar.shared.CameraPreview
import at.stefl.almucantar.shared.Glyph
import at.stefl.almucantar.shared.GlyphKind
import at.stefl.almucantar.shared.HeadingTracker
import at.stefl.almucantar.shared.MilkyWay
import at.stefl.almucantar.shared.MoonAt
import at.stefl.almucantar.shared.MoonPhase
import at.stefl.almucantar.shared.OrientationFrame
import at.stefl.almucantar.shared.Place
import at.stefl.almucantar.shared.PlacePanel
import at.stefl.almucantar.shared.PlaceSource
import at.stefl.almucantar.shared.TerrainLines
import at.stefl.almucantar.shared.TimePanel
import at.stefl.almucantar.shared.ViewCamera
import at.stefl.almucantar.shared.drawArrow
import at.stefl.almucantar.shared.drawPolylines
import at.stefl.almucantar.shared.drawUprightText
import at.stefl.almucantar.shared.edgeIndicator
import at.stefl.almucantar.shared.fillRings
import at.stefl.almucantar.shared.labelPaint
import at.stefl.almucantar.shared.rememberDisplayRotation
import at.stefl.almucantar.shared.rememberHeadingTracker
import at.stefl.almucantar.shared.rememberPlace
import at.stefl.almucantar.shared.rememberTerrain
import at.stefl.almucantar.shared.rememberTimeZone
import at.stefl.almucantar.shared.screenPoint
import at.stefl.almucantar.shared.upAt
import at.stefl.almucantar.shared.wallClockTime
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The horizontal field of view of the sky view without the camera, in portrait and landscape. */
private const val FOV_PORTRAIT_DEG = 70.0
private const val FOV_LANDSCAPE_DEG = 100.0

/** The field of view of a pinch in the sky view, in degrees. */
private const val MIN_FOV_DEG = 10.0
private const val MAX_FOV_DEG = 120.0

/** The alignment takes only objects to this magnitude, because the user must see them. */
private const val ALIGN_MAX_MAG = 2.5

/** The error of a tap on a star in the camera image, in degrees. */
private const val STAR_TAP_SIGMA_DEG = 0.3

/** The padding of the buttons of the bottom row, so six buttons fit on a narrow phone. */
private val BUTTON_PADDING = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** An object on the screen, for a tap. A brighter object wins a tap between two. */
private class Hit(val target: Target, val at: Offset, val mag: Double)

/**
 * The sky view of starradar: the stars, the constellations, the planets, the moon, and the Milky
 * Way in the direction of the phone, over the terrain of the place. With the camera, the same
 * overlay lies on the camera image. A tap on an object shows its name and its rise and set; Find
 * leads to an object; Align corrects the heading with a bright star.
 */
@Composable
fun StarScreen(stars: StarData, locationGranted: Boolean) {
    val settings = rememberSettings()
    val tracker = rememberHeadingTracker()
    val deviceZone = rememberTimeZone()
    var message by remember { mutableStateOf<String?>(null) }
    val placeState = rememberPlace(locationGranted, deviceZone, persistent = true) { message = it }
    val place = placeState.place
    val zone = place.zone ?: deviceZone
    SideEffect { tracker.observer = place.observer }

    var now by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000.0
        }
    }
    var dayOffset by remember { mutableIntStateOf(0) }
    var minuteOfDay by remember { mutableStateOf<Int?>(null) }
    val nowLocal = Instant.ofEpochSecond(now.toLong()).atZone(zone)
    val today = nowLocal.toLocalDate()
    val liveMinute = nowLocal.hour * 60 + nowLocal.minute
    val time =
        if (minuteOfDay == null && dayOffset == 0) now
        else wallClockTime(today.plusDays(dayOffset.toLong()), minuteOfDay ?: liveMinute, zone)

    // The sky changes slowly: the stars move 0.004° in a second, so it is calculated once per
    // second (a few milliseconds), and each frame only projects it.
    val milkyWay = remember { MilkyWay.create() }
    val labels = remember(stars) { SkyAt.labelCatalog(stars) }
    val sky =
        remember(time.toLong(), place.observer) {
            SkyAt.compute(stars, milkyWay, labels, time, place.observer)
        }

    val terrain by rememberTerrain(place.observer.takeIf { place.isKnown }, settings.terrain.value)
    val profile = terrain.profile?.takeIf { settings.terrain.value }
    val terrainLines = remember(profile) { profile?.let { TerrainLines(it) } }

    val context = LocalContext.current
    val displayRotation = rememberDisplayRotation()
    var cameraOn by remember { mutableStateOf(false) }
    var viewCamera by remember { mutableStateOf<ViewCamera?>(null) }
    var showTime by remember { mutableStateOf(false) }
    var showPlace by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var aligning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Target?>(null) }
    // The zoom of the sky view: a pinch changes it, a double tap resets it. The camera view has the
    // field of view of the lens, so the zoom does not change it.
    var zoom by remember { mutableDoubleStateOf(1.0) }

    // True while the permission prompt of the camera is open for an alignment.
    var pendingAlign by remember { mutableStateOf(false) }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraOn = it
            aligning = it && pendingAlign
            if (!it) {
                message =
                    if (pendingAlign) "The alignment needs the camera."
                    else "The camera is not allowed."
            }
            pendingAlign = false
        }
    // The alignment compares the drawn object with the object in the camera image. Without the
    // camera, a tap gives the heading that the app has already, so the camera switches on first.
    // The alignment compares with the sky now, so it sets the time back to now.
    val toggleAlign = {
        message = null
        if (!aligning) {
            dayOffset = 0
            minuteOfDay = null
        }
        if (aligning || cameraOn) {
            aligning = !aligning
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            cameraOn = true
            aligning = true
        } else {
            pendingAlign = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    val toggleCamera = {
        aligning = false
        if (cameraOn) {
            cameraOn = false
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            cameraOn = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    val locationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) placeState.useDevice() else message = "No location access: the place stays."
        }
    val useDeviceLocation = {
        showPlace = false
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            placeState.useDevice()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    val q = tracker.deviceToFrame
    val view =
        if (q == null || size.width == 0) {
            null
        } else {
            val base = if (size.width < size.height) FOV_PORTRAIT_DEG else FOV_LANDSCAPE_DEG
            val fov = (base / zoom).coerceIn(MIN_FOV_DEG, MAX_FOV_DEG)
            View(
                deviceToEnu = q,
                screenRotation = ScreenRotation.entries[displayRotation],
                headingOffsetDeg = tracker.estimate.offsetDeg,
                camera =
                    viewCamera?.camera?.takeIf { cameraOn }
                        ?: cameraFromFov(size.width.toDouble(), size.height.toDouble(), fov),
            )
        }

    // True if the last gesture had more than one finger, so its end is not a tap.
    var pinched by remember { mutableStateOf(false) }
    // The objects of the last frame, for a tap.
    val hits = remember { mutableListOf<Hit>() }
    val density = LocalDensity.current.density
    val onTap = { at: Offset ->
        val v = view
        when {
            // The end of a pinch is not a tap.
            pinched -> {}
            // The camera image shows the sky now, so the alignment needs the time now.
            aligning && time != now -> {
                aligning = false
                message = "The alignment needs the time now."
            }
            // The view must have the camera model of the lens, not the one of the sky view.
            aligning ->
                if (v != null && viewCamera != null) {
                    aligning = false
                    message = alignWith(tracker, v, sky, stars, hits, at, density)
                }
            else -> selected = nearest(hits, at, 32 * density)?.target
        }
    }
    val currentOnTap by rememberUpdatedState(onTap)

    MaterialTheme(colorScheme = darkColorScheme()) {
        if (showSettings) SettingsDialog(settings, stars.attribution) { showSettings = false }
        // The red light: everything turns red, so the eyes stay adapted to the dark. It also
        // covers the place panel and Find.
        val red = settings.red.value
        val redLight = Modifier.drawWithContent {
            drawContent()
            if (red) drawRect(Color(0xFFFF2010), blendMode = BlendMode.Multiply)
        }
        if (showPlace) {
            Box(redLight) {
                PlacePanel(
                    place,
                    deviceZone,
                    offline = true,
                    onDrop = { at, name, placeZone -> placeState.drop(at, name, placeZone) },
                    onUseDevice = useDeviceLocation,
                    onClose = { showPlace = false },
                )
            }
            return@MaterialTheme
        }
        if (showFind) {
            Box(redLight) {
                FindPanel(stars, onClose = { showFind = false }) {
                    selected = it
                    showFind = false
                }
            }
            return@MaterialTheme
        }

        Box(
            Modifier.fillMaxSize()
                .background(Color(0xFF050916))
                .onSizeChanged { size = it }
                .then(redLight)
        ) {
            if (cameraOn) {
                CameraPreview(
                    Modifier.fillMaxSize(),
                    displayRotation,
                    detectSun = false,
                    onBlobs = {},
                    // The red light must also tint the camera image.
                    tintable = true,
                ) {
                    viewCamera = it
                }
            }
            if (view != null) {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, PointerEventPass.Initial)
                                pinched = false
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } > 1) pinched = true
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { if (!pinched) zoom = 1.0 },
                                onTap = { currentOnTap(it) },
                            )
                        }
                        .pointerInput(cameraOn) {
                            if (!cameraOn) {
                                detectTransformGestures { _, _, scale, _ ->
                                    val base =
                                        if (size.width < size.height) FOV_PORTRAIT_DEG
                                        else FOV_LANDSCAPE_DEG
                                    zoom =
                                        (zoom * scale).coerceIn(
                                            base / MAX_FOV_DEG,
                                            base / MIN_FOV_DEG,
                                        )
                                }
                            }
                        }
                ) {
                    hits.clear()
                    drawStarSky(
                        view,
                        sky,
                        stars,
                        milkyWay,
                        terrainLines,
                        profile,
                        cameraOn,
                        settings.lines.value,
                        selected,
                        hits,
                    )
                }
            }

            Column(
                Modifier.align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .widthIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusCard(place, tracker, sky, terrain.status) {
                    message = null
                    showPlace = true
                }
                message?.let { Panel { Text(it, color = Color.White) } }
                if (aligning) {
                    Panel {
                        Text(
                            "Point at a bright star, a planet, or the moon, and tap it.",
                            color = Color.White,
                        )
                    }
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showTime) {
                    Panel {
                        TimePanel(
                            today,
                            dayOffset,
                            { dayOffset = it },
                            minuteOfDay,
                            liveMinute,
                            { minuteOfDay = it },
                        )
                    }
                }
                selected?.let { InfoCard(it, stars, sky, profile, zone) { selected = null } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = toggleCamera, contentPadding = BUTTON_PADDING) {
                        Text(if (cameraOn) "Sky" else "Camera")
                    }
                    FilledTonalButton(
                        onClick = { showTime = !showTime },
                        contentPadding = BUTTON_PADDING,
                    ) {
                        Text(if (dayOffset == 0 && minuteOfDay == null) "Time" else "Time ●")
                    }
                    FilledTonalButton(
                        onClick = { showFind = true },
                        contentPadding = BUTTON_PADDING,
                    ) {
                        Text("Find")
                    }
                    FilledTonalButton(
                        onClick = {
                            message = null
                            showPlace = true
                        },
                        contentPadding = BUTTON_PADDING,
                    ) {
                        Text("Place")
                    }
                    Button(onClick = toggleAlign, contentPadding = BUTTON_PADDING) {
                        Text(if (aligning) "Cancel" else "Align")
                    }
                    FilledTonalButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.widthIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Glyph(GlyphKind.MORE, "Menu", tint = LocalContentColor.current)
                    }
                }
            }
        }
    }
}

/** The object nearest to [at] within [radius] pixels; a brighter object wins a near tie. */
private fun nearest(
    hits: List<Hit>,
    at: Offset,
    radius: Float,
    accept: (Hit) -> Boolean = { true },
): Hit? =
    hits
        .filter { accept(it) && (it.at - at).getDistance() <= radius }
        .minByOrNull { (it.at - at).getDistance() + 3 * it.mag.toFloat() }

/** Aligns the heading with a tapped bright object. Returns the message for the user. */
private fun alignWith(
    tracker: HeadingTracker,
    view: View,
    sky: SkyAt,
    stars: StarData,
    hits: List<Hit>,
    at: Offset,
    density: Float,
): String {
    val hit =
        nearest(hits, at, 40 * density) {
            it.mag <= ALIGN_MAX_MAG && it.target !is Target.ConstellationTarget
        } ?: return "Tap a bright star, a planet, or the moon."
    val truth = hit.target.direction(sky)
    val measurement =
        try {
            headingFromDirection(
                view,
                ScreenPoint(at.x.toDouble(), at.y.toDouble()),
                truth.azimuthDeg,
                STAR_TAP_SIGMA_DEG,
            )
        } catch (e: IllegalArgumentException) {
            return "No alignment: ${e.message ?: "the direction gives no heading"}."
        }
    tracker.apply(measurement)
    return "Heading aligned with %s (now ±%.1f°)."
        .format(hit.target.name(stars), sqrt(tracker.estimate.varianceDeg2))
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Box(
        Modifier.background(Color(0xB3000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        content()
    }
}

@Composable
private fun StatusCard(
    place: Place,
    tracker: HeadingTracker,
    sky: SkyAt,
    terrainStatus: String?,
    onPlace: () -> Unit,
) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                place.name
                    ?: "%.3f°, %.3f°"
                        .format(place.observer.latitudeDeg, place.observer.longitudeDeg),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onPlace),
            )
            if (place.source == PlaceSource.DEFAULT) {
                Text("No location: tap Place", color = Color(0xFFFFB74D))
            }
            HeadingText(tracker)
            val moon = sky.moon
            val moonText =
                if (moon.direction.altitudeDeg > 0)
                    "moon up, %d%% lit".format((moon.fraction * 100).toInt())
                else "moon down"
            Text("${StarLook.darkness(sky.sun.altitudeDeg)} · $moonText", color = Color(0xFFDDDDDD))
            terrainStatus?.let { Text(it, color = Color(0xFFFFB74D), fontSize = 12.sp) }
        }
    }
}

@Composable
private fun HeadingText(tracker: HeadingTracker) {
    if (tracker.frame == OrientationFrame.NONE) {
        Text("This device has no orientation sensor", color = Color(0xFFFF8A80))
        return
    }
    val sigma = sqrt(tracker.estimate.varianceDeg2)
    val (text, color) =
        when {
            sigma > 10 ->
                "Heading ±%.0f° (align with a bright star)".format(sigma) to Color(0xFFFFB74D)
            sigma > 3 -> "Heading ±%.0f°".format(sigma) to Color(0xFFFFE082)
            else -> "Heading ±%.1f°".format(sigma) to Color(0xFFA5D6A7)
        }
    Text(text, color = color)
}

private fun timeFormat() = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

/** The card of a tapped or found object: its name, kind, height, and rise and set. */
@Composable
private fun InfoCard(
    target: Target,
    stars: StarData,
    sky: SkyAt,
    profile: at.stefl.almucantar.core.HorizonProfile?,
    zone: ZoneId,
    onClose: () -> Unit,
) {
    // The rise and the set take some milliseconds: calculate them in the background, again for a
    // new target, a new place, a new terrain, or after 10 minutes. The result keeps its target, so
    // the card does not show the times of the last target while it calculates.
    val step = (sky.unixUtc / 600).toLong()
    val result by
        produceState<Pair<Target, RiseSet>?>(null, target, sky.observer, step, profile) {
            value =
                withContext(Dispatchers.Default) {
                    target to target.riseAndSet(stars, sky.unixUtc, sky.observer, profile)
                }
        }
    val riseSet = result?.takeIf { it.first == target }?.second
    val d = target.direction(sky)
    val kind =
        when (target) {
            is Target.Star -> "Star · magnitude %.1f".format(stars.mag[target.index])
            is Target.PlanetTarget ->
                "Planet · magnitude %.1f"
                    .format(sky.planets.first { it.planet == target.planet }.mag)
            Target.Moon -> "Moon · %d%% lit".format((sky.moon.fraction * 100).toInt())
            Target.Sun -> "Sun"
            is Target.ConstellationTarget -> "Constellation"
            Target.GalacticCenter -> "Milky Way · Sagittarius A*"
        }
    val format = timeFormat()
    fun local(t: Double) = format.format(Instant.ofEpochSecond(t.toLong()).atZone(zone))
    val over = if (profile != null) " over the terrain" else ""
    val times =
        riseSet?.let { r ->
            when (r.always) {
                true -> "Up all day$over"
                false -> "Below the horizon all day$over"
                null ->
                    listOfNotNull(
                            r.rise?.let { it to "rises ${local(it)}" },
                            r.set?.let { it to "sets ${local(it)}" },
                        )
                        .sortedBy { it.first }
                        .joinToString(" · ") { it.second }
                        .replaceFirstChar { it.uppercase() } + over
            }
        } ?: "…"
    Panel {
        Column(Modifier.clickable(onClick = onClose).widthIn(max = 440.dp)) {
            Text(
                target.name(stars),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
            Text(kind, color = Color(0xFFB4C0D2))
            Text(times, color = Color.White)
            Text(
                "%.0f° high, azimuth %.0f°".format(d.altitudeDeg, d.azimuthDeg),
                color = Color(0xFFB4C0D2),
            )
        }
    }
}

private val MARKS = Regex("\\p{M}")

/** Without accents and in lower case, so "alnilam" finds "Alnilam". */
private fun fold(text: String) =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

/** An entry of Find: the name, the kind, the target, and the folded name for the search. */
private class FindEntry(val name: String, val kind: String, val target: Target) {
    val key = fold(name)
}

/** Find: the stars with a name, the planets, the moon, the constellations, and the Milky Way. */
@Composable
private fun FindPanel(stars: StarData, onClose: () -> Unit, onPick: (Target) -> Unit) {
    val entries =
        remember(stars) {
            PLANETS.map { FindEntry(it.second.first, "Planet", Target.PlanetTarget(it.first)) } +
                listOf(
                    FindEntry("Moon", "Moon", Target.Moon),
                    FindEntry("Sun", "Star", Target.Sun),
                    FindEntry("Milky Way center", "Milky Way", Target.GalacticCenter),
                ) +
                stars.constellations.mapIndexed { i, c ->
                    FindEntry(c.name, "Constellation", Target.ConstellationTarget(i))
                } +
                stars.names.map { (i, name) ->
                    FindEntry(name, "Star · %.1f".format(stars.mag[i]), Target.Star(i))
                }
        }
    // The back key closes the search, not the app.
    BackHandler(onBack = onClose)
    var query by remember { mutableStateOf("") }
    val q = fold(query.trim())
    val found =
        if (q.isEmpty()) emptyList()
        else entries.filter { q in it.key }.sortedBy { if (it.key.startsWith(q)) 0 else 1 }.take(20)
    Box(Modifier.fillMaxSize().background(Color(0xFF050916)).safeDrawingPadding().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("A star, a planet, or a constellation") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") }
            }
            LazyColumn(Modifier.heightIn(max = 600.dp)) {
                items(found) { entry ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onPick(entry.target) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(entry.name, color = Color.White, modifier = Modifier.weight(1f))
                        Text(entry.kind, color = Color(0xFFB4C0D2), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** The color of the sky without the camera, from the altitude of the sun. */
private fun skyColor(sunAltitudeDeg: Double): Color {
    val stops =
        listOf(
            -18.0 to Color(5, 9, 22),
            -12.0 to Color(10, 18, 42),
            -6.0 to Color(24, 40, 82),
            0.0 to Color(44, 74, 130),
            10.0 to Color(52, 104, 170),
        )
    val a = sunAltitudeDeg.coerceIn(-18.0, 10.0)
    for (i in 1 until stops.size) {
        val (a1, c1) = stops[i]
        if (a <= a1) {
            val (a0, c0) = stops[i - 1]
            val t = ((a - a0) / (a1 - a0)).toFloat()
            return Color(
                c0.red + (c1.red - c0.red) * t,
                c0.green + (c1.green - c0.green) * t,
                c0.blue + (c1.blue - c0.blue) * t,
            )
        }
    }
    return stops.last().second
}

private val constellationColor = Color(0x617FAAFF)
private val selectionColor = Color(0xFF7FD8FF)

/** A copy of the shared label paint with another color. */
private fun tintedPaint(sizePx: Float, bold: Boolean, color: Color): Paint =
    Paint(labelPaint(sizePx, bold)).apply { this.color = color.toArgb() }

/**
 * Draws the sky: the Milky Way, the constellation lines, the stars, the names, the planets, the
 * moon, the sun, the horizon, the terrain, and the selection. Objects below the horizon or behind
 * the terrain are dim, so the user can still find them. Each drawn object goes into [hits].
 */
private fun DrawScope.drawStarSky(
    view: View,
    sky: SkyAt,
    stars: StarData,
    milkyWay: MilkyWay,
    terrainLines: TerrainLines?,
    profile: at.stefl.almucantar.core.HorizonProfile?,
    cameraOn: Boolean,
    linesOn: Boolean,
    selected: Target?,
    hits: MutableList<Hit>,
) {
    val dp = density
    if (!cameraOn) drawRect(skyColor(sky.sun.altitudeDeg))
    val limit = StarLook.limitingMagnitude(sky.sun.altitudeDeg)
    val hidden = { d: Horizontal -> d.altitudeDeg < horizonAt(profile, d.azimuthDeg) }

    // The Milky Way: soft clouds and fine grains, added on top of each other, only in a dark sky.
    // The air near the horizon dims it.
    val milky = ((limit - 3.5) / 2.5).coerceIn(0.0, 1.0).toFloat()
    if (milky > 0.01f) {
        val points = projectPoints(view, sky.dust)
        // A cloud is about 2.8° across, so its size follows the zoom of the view.
        val radius = (view.camera.fx * Math.toRadians(1.4)).toFloat()
        val grains = ArrayList<Offset>()
        for (i in milkyWay.weight.indices) {
            val x = points[2 * i]
            if (x.isNaN()) continue
            val alt = sky.dust[2 * i + 1]
            if (alt < 0) continue
            val fade = (alt / 14).coerceIn(0.0, 1.0).toFloat()
            val alpha = milky * milkyWay.weight[i] * fade
            val at = Offset(x.toFloat(), points[2 * i + 1].toFloat())
            if (milkyWay.cloud[i]) {
                drawCircle(
                    Color(0xFFD6DCFF).copy(alpha = 0.05f * alpha),
                    radius = radius,
                    center = at,
                    blendMode = BlendMode.Plus,
                )
            } else if (alpha > 0.15f) {
                grains += at
            }
        }
        drawPoints(grains, PointMode.Points, Color(0x66E1E6FF), strokeWidth = 1.2f * dp)
    }

    val points = projectPoints(view, sky.stars)
    // The constellation lines between projected stars. A segment needs both ends on the screen.
    if (linesOn && limit > 1) {
        val path = Path()
        for (c in stars.constellations) {
            for (line in c.lines) {
                for (k in 1 until line.size) {
                    val a = line[k - 1]
                    val b = line[k]
                    if (points[2 * a].isNaN() || points[2 * b].isNaN()) continue
                    path.moveTo(points[2 * a].toFloat(), points[2 * a + 1].toFloat())
                    path.lineTo(points[2 * b].toFloat(), points[2 * b + 1].toFloat())
                }
            }
        }
        drawPath(path, constellationColor, style = Stroke(width = 1 * dp))
    }

    // The stars, the faintest first.
    var count = 0
    while (count < stars.size && stars.mag[count] <= limit) count++
    for (i in count - 1 downTo 0) {
        val x = points[2 * i]
        if (x.isNaN()) continue
        val at = Offset(x.toFloat(), points[2 * i + 1].toFloat())
        val mag = stars.mag[i]
        val fade = (0.35 + (limit - mag) / 1.5).coerceIn(0.0, 1.0).toFloat()
        val alpha = if (hidden(sky.star(i))) 0.22f * fade else fade
        val color = StarLook.color(stars.bv[i])
        val r = StarLook.radiusDp(mag) * dp
        if (mag < 1.5f)
            drawCircle(color.copy(alpha = 0.18f * alpha), radius = r * 2.6f, center = at)
        drawCircle(color.copy(alpha = alpha), radius = r, center = at)
        hits += Hit(Target.Star(i), at, mag.toDouble())
    }

    // The names of the brightest stars, and of the constellations.
    val starPaint = tintedPaint(12 * dp, false, Color(0xD9DCE4FF))
    val starPaintDim = tintedPaint(12 * dp, false, Color(0x4DDCE4FF))
    for (i in 0 until count) {
        if (stars.mag[i] > minOf(limit - 1, 1.6)) break
        val name = stars.names[i] ?: continue
        val d = sky.star(i)
        val at = screenPoint(view, d) ?: continue
        drawUprightText(
            name,
            at,
            upAt(view, d, at),
            8 * dp,
            if (hidden(d)) starPaintDim else starPaint,
        )
    }
    if (linesOn && limit > 1) {
        val paint = tintedPaint(12 * dp, false, Color(0xBF7FAAFF)).apply { textSkewX = -0.2f }
        stars.constellations.forEachIndexed { i, c ->
            val d = sky.label(i)
            val at = screenPoint(view, d) ?: return@forEachIndexed
            drawUprightText(c.name, at, upAt(view, d, at), 0f, paint)
            hits += Hit(Target.ConstellationTarget(i), at, 7.0)
        }
    }
    if (milky > 0.3f) {
        screenPoint(view, sky.galacticCenter)?.let {
            drawUprightText(
                "Milky Way center",
                it,
                upAt(view, sky.galacticCenter, it),
                6 * dp,
                tintedPaint(12 * dp, false, Color(0x99C8D2F0)),
            )
            hits += Hit(Target.GalacticCenter, it, 6.5)
        }
    }

    // The planets that the eye sees now.
    val planetPaint = tintedPaint(13 * dp, true, Color(0xFFFFDCA0))
    for (planet in sky.planets) {
        if (planet.mag > limit + 0.5) continue
        val at = screenPoint(view, planet.direction) ?: continue
        val alpha = if (hidden(planet.direction)) 0.3f else 1f
        drawCircle(
            planet.color.copy(alpha = alpha),
            radius = (StarLook.radiusDp(planet.mag.toFloat()) + 1) * dp,
            center = at,
        )
        drawUprightText(planet.name, at, upAt(view, planet.direction, at), 9 * dp, planetPaint)
        hits += Hit(Target.PlanetTarget(planet.planet), at, planet.mag - 1)
    }

    // The moon with its phase, and the sun.
    if (sky.moon.fraction >= 0.01) {
        screenPoint(view, sky.moon.direction)?.let {
            drawMoon(view, sky.moon, sky.sun, it, 11 * dp)
            drawUprightText(
                "Moon",
                it,
                upAt(view, sky.moon.direction, it),
                16 * dp,
                labelPaint(12 * dp, false),
            )
            hits += Hit(Target.Moon, it, -12.0)
        }
    }
    screenPoint(view, sky.sun)?.let {
        drawCircle(Color(0xFFFFE08A), radius = 13 * dp, center = it)
        drawUprightText("Sun", it, upAt(view, sky.sun, it), 18 * dp, labelPaint(12 * dp, false))
        hits += Hit(Target.Sun, it, -26.0)
    }

    // The horizon, the terrain, and the cardinal points.
    drawPolylines(
        horizonLine(view),
        if (profile != null) Color(0x4DFFFFFF) else Color(0xB3FFFFFF),
        Stroke(width = 1 * dp),
    )
    terrainLines?.let { t ->
        if (!cameraOn) fillRings(view, t.fill, Color(0xB80E1614))
        for (line in t.lines) {
            drawPolylines(projectLine(view, line), Color(0xD9AAD2A0), Stroke(width = 1.5f * dp))
        }
    }
    val cardinal = labelPaint(14 * dp)
    for ((az, label) in listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W")) {
        val d = Horizontal(az, 0.0)
        val at = screenPoint(view, d) ?: continue
        drawUprightText(label, at, upAt(view, d, at), 12 * dp, cardinal)
    }

    // The selected object: a ring, or an arrow at the border of the screen that leads to it.
    selected?.let { target ->
        val d = target.direction(sky)
        val bounds = Rect(28 * dp, 28 * dp, size.width - 28 * dp, size.height - 28 * dp)
        val center = unproject(view, ScreenPoint(view.camera.width / 2, view.camera.height / 2))
        val edge = edgeIndicator(view, center, d, bounds)
        if (edge == null) {
            screenPoint(view, d)?.let {
                drawCircle(selectionColor, radius = 16 * dp, center = it, style = Stroke(2 * dp))
            }
        } else {
            drawArrow(edge.first, edge.second, 14 * dp, selectionColor)
        }
    }
}

/** The moon at [at] with the lit part toward the sun (see [MoonPhase.towardSun]). */
private fun DrawScope.drawMoon(
    view: View,
    moon: MoonAt,
    sun: Horizontal,
    at: Offset,
    radius: Float,
) {
    val toSun = screenPoint(view, MoonPhase.towardSun(moon.direction, sun))?.minus(at)
    val length = toSun?.let { hypot(it.x, it.y) } ?: 0f
    val u = if (toSun != null && length > 1e-3f) toSun / length else Offset(0f, -1f)
    val v = Offset(-u.y, u.x)
    drawCircle(Color(0x38E0E6F0), radius = radius, center = at)
    val path = Path()
    MoonPhase.litOutline(moon.fraction).forEachIndexed { i, (x, y) ->
        val p = at + (u * x.toFloat() + v * y.toFloat()) * radius
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, Color(0xFFE0E6F0))
}
