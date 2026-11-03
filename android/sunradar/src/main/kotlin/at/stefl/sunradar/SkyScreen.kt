package at.stefl.sunradar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import at.stefl.almucantar.core.AzimuthCrossing
import at.stefl.almucantar.core.DirectionLine
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.ScreenPoint
import at.stefl.almucantar.core.ScreenRotation
import at.stefl.almucantar.core.TimeMatch
import at.stefl.almucantar.core.View
import at.stefl.almucantar.core.cameraFromFov
import at.stefl.almucantar.core.findAzimuthTimes
import at.stefl.almucantar.core.findDates
import at.stefl.almucantar.core.headingFromDirection
import at.stefl.almucantar.core.horizonLine
import at.stefl.almucantar.core.moonIlluminatedFraction
import at.stefl.almucantar.core.moonPosition
import at.stefl.almucantar.core.projectLine
import at.stefl.almucantar.core.sunPosition
import at.stefl.almucantar.core.unproject
import at.stefl.almucantar.shared.CalibrationSource
import at.stefl.almucantar.shared.CameraPreview
import at.stefl.almucantar.shared.FixedHeading
import at.stefl.almucantar.shared.Geo
import at.stefl.almucantar.shared.Glyph
import at.stefl.almucantar.shared.GlyphKind
import at.stefl.almucantar.shared.HeadingTracker
import at.stefl.almucantar.shared.MapPicker
import at.stefl.almucantar.shared.MoonAt
import at.stefl.almucantar.shared.MoonPhase
import at.stefl.almucantar.shared.OrientationFrame
import at.stefl.almucantar.shared.Place
import at.stefl.almucantar.shared.PlacePanel
import at.stefl.almucantar.shared.PlaceSource
import at.stefl.almucantar.shared.SkyColors
import at.stefl.almucantar.shared.TerrainLines
import at.stefl.almucantar.shared.TimePanel
import at.stefl.almucantar.shared.ViewBlob
import at.stefl.almucantar.shared.ViewCamera
import at.stefl.almucantar.shared.angleBetweenDeg
import at.stefl.almucantar.shared.dashed
import at.stefl.almucantar.shared.dayBounds
import at.stefl.almucantar.shared.drawArrow
import at.stefl.almucantar.shared.drawPolylines
import at.stefl.almucantar.shared.drawUprightText
import at.stefl.almucantar.shared.edgeIndicator
import at.stefl.almucantar.shared.errorScale
import at.stefl.almucantar.shared.fillRings
import at.stefl.almucantar.shared.labelPaint
import at.stefl.almucantar.shared.rememberDisplayRotation
import at.stefl.almucantar.shared.rememberHeadingTracker
import at.stefl.almucantar.shared.rememberPlace
import at.stefl.almucantar.shared.rememberTerrain
import at.stefl.almucantar.shared.rememberTimeZone
import at.stefl.almucantar.shared.screenPoint
import at.stefl.almucantar.shared.upAt
import at.stefl.almucantar.shared.viewCamera
import at.stefl.almucantar.shared.wallClockTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The horizontal field of view of the virtual camera, in portrait and in landscape. */
private const val FOV_PORTRAIT_DEG = 60.0
private const val FOV_LANDSCAPE_DEG = 90.0

/** Above this altitude of the crosshair, the azimuth is badly defined (the phone is flat). */
private const val MAX_CROSSHAIR_ALTITUDE_DEG = 75.0

/** The angular error of a tap on the sun or the moon (both have a diameter of 0.5°). */
private const val BODY_TAP_SIGMA_DEG = 0.5

/** A tap more than this far from the altitude of the body is not the body. */
private const val BODY_TAP_MAX_ALTITUDE_ERROR_DEG = 5.0

/** The moon must be at least this bright to be found in the camera image. */
private const val MIN_MOON_FRACTION = 0.1

/** A region of the camera image that is probably the sun. */
data class DetectedSun(val blob: ViewBlob, val separationDeg: Double)

/** A saturated region with a larger angular radius than this is not the sun (0.27°). */
private const val MAX_SUN_BLOB_RADIUS_DEG = 3.0

/** Within this angle of the prediction, the detected sun counts as aligned. */
private const val ALIGNED_DEG = 1.0

/** The padding of the buttons of the bottom row, so five buttons fit on a narrow phone. */
private val BUTTON_PADDING = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/** The things that the user can align the heading with. */
enum class AlignTarget {
    SUN,
    MOON,
    LANDMARK,
}

/**
 * The sky view: the horizon with the cardinal points, the path of the sun on the selected day with
 * the hours, the sun and the moon at the selected time, and a vertical line through the crosshair
 * with the time when the sun is in that direction. With the camera, the same overlay lies on the
 * camera image, and the user can align the heading with the sun, the moon, or a landmark.
 */
@Composable
fun SkyScreen(locationGranted: Boolean, scene: ScreenshotScene? = null) {
    // The demo mode of the store screenshots fixes the view, the heading, the place, and the time.
    val tracker = rememberHeadingTracker(scene?.let { FixedHeading(it.deviceToEnu, it.estimate) })
    val deviceZone = rememberTimeZone()
    var message by remember { mutableStateOf<String?>(null) }
    // The demo mode does not read or write the last place.
    val placeState =
        rememberPlace(locationGranted && scene == null, deviceZone, scene == null) {
            message = it
        }
    val place = scene?.place ?: placeState.place
    // All days and times are in the time zone of the place: a dropped place can be in another
    // time zone than the device.
    val zone = scene?.zone ?: place.zone ?: deviceZone
    var showPlace by remember { mutableStateOf(false) }
    SideEffect { tracker.observer = place.observer }

    var now by remember {
        mutableDoubleStateOf(scene?.unixUtc ?: (System.currentTimeMillis() / 1000.0))
    }
    LaunchedEffect(scene) {
        while (scene == null) {
            delay(1000)
            now = System.currentTimeMillis() / 1000.0
        }
    }

    // The selected date and time. minuteOfDay null means the live time.
    var dayOffset by remember { mutableIntStateOf(0) }
    var minuteOfDay by remember { mutableStateOf(scene?.minuteOfDay) }
    var showTime by remember { mutableStateOf(scene?.showTime ?: false) }
    val nowLocal = Instant.ofEpochSecond(now.toLong()).atZone(zone)
    val today = nowLocal.toLocalDate()
    val date = today.plusDays(dayOffset.toLong())
    val liveMinute = nowLocal.hour * 60 + nowLocal.minute
    val time =
        if (minuteOfDay == null && dayOffset == 0) {
            now
        } else {
            wallClockTime(date, minuteOfDay ?: liveMinute, zone)
        }

    val day =
        remember(place.observer, date, zone) {
            val (start, end) = dayBounds(date, zone)
            SkyDay(place.observer, start, end, zone)
        }
    // The terrain horizon needs a place that the user is at or chose: the city of the time zone
    // can be far away. The demo mode downloads nothing and shows no terrain.
    val terrainSetting = rememberTerrainSetting()
    val terrain by
        rememberTerrain(
            place.observer.takeIf { place.isKnown && scene == null },
            terrainSetting.value && scene == null,
        )
    val terrainProfile = terrain.profile
    val terrainLines = remember(terrainProfile) { terrainProfile?.let { TerrainLines(it) } }
    val terrainDayState =
        produceState<TerrainDay?>(null, day, terrainProfile, zone) {
            value = terrainProfile?.let {
                withContext(Dispatchers.Default) { TerrainDay(day, it, zone) }
            }
        }
    // A terrain day of the day before stays until the new one is ready; it is not used.
    val terrainDay = terrainDayState.value?.takeIf { it.day === day && terrainProfile != null }
    var showSettings by remember { mutableStateOf(false) }
    val guides =
        remember(place.observer, date.year, zone) {
            solsticeGuides(place.observer, date.year, zone)
        }

    val displayRotation = rememberDisplayRotation()

    val context = LocalContext.current
    var cameraOn by remember { mutableStateOf(false) }
    var viewCamera by remember { mutableStateOf<ViewCamera?>(null) }
    var aligning by remember { mutableStateOf<AlignTarget?>(null) }
    var alignMenu by remember { mutableStateOf(scene?.alignMenu ?: false) }
    var pickingLandmark by remember { mutableStateOf(false) }
    var landmark by remember { mutableStateOf<Observer?>(null) }
    var pendingAlign by remember { mutableStateOf<AlignTarget?>(null) }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraOn = it
            if (!it) {
                message =
                    if (pendingAlign != null)
                        "The alignment with the sun or the moon needs the camera."
                    else "The camera is not allowed."
                pendingAlign = null
            }
        }
    // Starts an alignment with the sun or the moon. These need the camera image, so the camera
    // switches on first (with the permission prompt, if needed).
    val startBodyAlign = { target: AlignTarget ->
        message = null
        if (cameraOn) {
            aligning = target
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            cameraOn = true
            aligning = target
        } else {
            pendingAlign = target
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(cameraOn) {
        if (cameraOn && pendingAlign != null) {
            aligning = pendingAlign
            pendingAlign = null
        }
    }
    // "Use my location" in the place panel asks for the permission, if needed.
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
    val toggleCamera = {
        aligning = null
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

    var blobs by remember { mutableStateOf<List<ViewBlob>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val q = tracker.deviceToFrame
    val view =
        if (q == null || size.width == 0) {
            null
        } else {
            val fov =
                scene?.fovDeg
                    ?: if (size.width < size.height) FOV_PORTRAIT_DEG else FOV_LANDSCAPE_DEG
            View(
                deviceToEnu = q,
                // The scene of the demo mode is a device held upright in portrait, also on a tablet
                // whose natural orientation is landscape.
                screenRotation =
                    if (scene != null) ScreenRotation.ROTATION_0
                    else ScreenRotation.entries[displayRotation],
                headingOffsetDeg = tracker.estimate.offsetDeg,
                camera =
                    viewCamera?.camera?.takeIf { cameraOn }
                        ?: cameraFromFov(size.width.toDouble(), size.height.toDouble(), fov),
            )
        }
    // The sun and the moon now (for the alignment) and at the selected time (for the overlay).
    // They change once per second at most, so they are not calculated for each sensor event.
    val sunNow = remember(now, day.observer) { sunPosition(now, day.observer) }
    val moonNow = remember(now, day.observer) { moonPosition(now, day.observer) }
    val moonFraction = remember(now) { moonIlluminatedFraction(now) }
    val sun = remember(time, day.observer) { sunPosition(time, day.observer) }
    val moon =
        remember(time, day.observer) {
            val fraction = moonIlluminatedFraction(time)
            if (fraction >= 0.02) MoonAt(moonPosition(time, day.observer), fraction) else null
        }
    val center = view?.let { unproject(it, ScreenPoint(it.camera.width / 2, it.camera.height / 2)) }
    val crosshairUsable = center != null && abs(center.altitudeDeg) <= MAX_CROSSHAIR_ALTITUDE_DEG
    // The search runs again only if the azimuth changes by 0.05° (about 12 s on the path).
    val azimuthKey = center?.takeIf { crosshairUsable }?.let { (it.azimuthDeg * 20).roundToInt() }
    val crossing =
        remember(azimuthKey, day) {
            azimuthKey?.let {
                findAzimuthTimes(day.observer, it / 20.0, day.startUtc, day.endUtc).firstOrNull()
            }
        }

    // The dates in the next 12 months when the sun goes through the crosshair. The search takes
    // some milliseconds, so it runs in the background when the crosshair has been still for a
    // moment. The old dates stay until the new ones are ready, so sensor noise does not make them
    // flicker.
    var crosshairDates by remember { mutableStateOf<List<TimeMatch>>(emptyList()) }
    val crosshairKey =
        center
            ?.takeIf { crosshairUsable }
            ?.let { (it.azimuthDeg * 2).roundToInt() to (it.altitudeDeg * 2).roundToInt() }
    LaunchedEffect(crosshairKey, place.observer, today, zone) {
        val direction = center
        if (direction == null || crosshairKey == null || direction.altitudeDeg < 0) {
            crosshairDates = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        val start = today.atStartOfDay(zone).toEpochSecond().toDouble()
        crosshairDates =
            withContext(Dispatchers.Default) {
                findDates(place.observer, direction, start, start + 365 * 86400.0)
            }
    }

    // The detected sun: the saturated region nearest to the predicted sun, within the error of the
    // heading. A region larger than a few degrees is an overexposed sky, not the sun.
    val detected =
        view
            ?.takeIf { cameraOn && sunNow.altitudeDeg > 0 }
            ?.let { v ->
                val maxSeparation = maxOf(10.0, 3 * sqrt(tracker.estimate.varianceDeg2))
                blobs
                    .mapNotNull { blob ->
                        val direction =
                            unproject(
                                v,
                                ScreenPoint(blob.center.x.toDouble(), blob.center.y.toDouble()),
                            )
                        val edge =
                            unproject(
                                v,
                                ScreenPoint(
                                    (blob.center.x + blob.radius).toDouble(),
                                    blob.center.y.toDouble(),
                                ),
                            )
                        val separation = angleBetweenDeg(direction, sunNow)
                        if (
                            angleBetweenDeg(direction, edge) > MAX_SUN_BLOB_RADIUS_DEG ||
                                separation > maxSeparation
                        ) {
                            null
                        } else {
                            DetectedSun(blob, separation)
                        }
                    }
                    .minByOrNull { it.separationDeg }
            }

    val screenDensity = LocalDensity.current.density
    val onTap = { at: Offset ->
        val target = aligning
        val sunBox = detected?.takeIf {
            (at - it.blob.center).getDistance() < it.blob.radius + 48 * screenDensity
        }
        if (view != null && sunBox != null) {
            message = alignWithBody(tracker, view, sunBox.blob.center, sunNow, "sun")
            aligning = null
        } else if (view != null && (target == AlignTarget.SUN || target == AlignTarget.MOON)) {
            message =
                if (target == AlignTarget.SUN) {
                    alignWithBody(tracker, view, at, sunNow, "sun")
                } else {
                    alignWithBody(tracker, view, at, moonNow, "moon")
                }
            aligning = null
        }
    }

    // The tap handler of the newest composition, with the current view and detected sun.
    val currentOnTap by rememberUpdatedState(onTap)

    MaterialTheme(colorScheme = darkColorScheme()) {
        if (showSettings) SettingsDialog(terrainSetting) { showSettings = false }
        if (pickingLandmark) {
            MapPicker(place.observer) {
                pickingLandmark = false
                landmark = it
                aligning = if (it != null) AlignTarget.LANDMARK else null
            }
            return@MaterialTheme
        }
        if (showPlace) {
            PlacePanel(
                place,
                deviceZone,
                // The demo mode downloads nothing.
                offline = scene == null,
                onDrop = { at, name, placeZone -> placeState.drop(at, name, placeZone) },
                onUseDevice = useDeviceLocation,
                onClose = { showPlace = false },
            )
            return@MaterialTheme
        }

        Box(Modifier.fillMaxSize().background(SkyColors.sky).onSizeChanged { size = it }) {
            if (scene != null && view != null) {
                // The drawn sky of the demo mode, in place of the camera image.
                DemoSky(Modifier.fillMaxSize(), view, sun)
            }
            if (cameraOn) {
                CameraPreview(
                    Modifier.fillMaxSize(),
                    displayRotation,
                    detectSun = sunNow.altitudeDeg > 0,
                    onBlobs = { blobs = it },
                ) {
                    viewCamera = it
                }
            }
            if (view != null && center != null) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { currentOnTap(it) }
                    }
                ) {
                    detected?.let { drawDetectedSun(it) }
                    drawSky(
                        view,
                        day,
                        Terrain(terrainLines, terrainDay, fill = !cameraOn),
                        guides,
                        sun,
                        moon,
                        center,
                        crossing,
                        landmark
                            ?.takeIf { aligning == AlignTarget.LANDMARK }
                            ?.let {
                                Horizontal(Geo.bearingDeg(place.observer, it), 0.0)
                            },
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
                StatusCard(
                    place,
                    zone.takeIf { scene == null && it != deviceZone },
                    tracker,
                    day,
                    terrainDay,
                    terrain.status,
                    cameraOn,
                    viewCamera,
                ) {
                    message = null
                    showPlace = true
                }
                message?.let { Panel { Text(it, color = Color.White) } }
                aligning?.let { Panel { Text(alignHint(it), color = Color.White) } }
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
                Readout(center, crosshairUsable, crossing, crosshairDates, zone)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = toggleCamera, contentPadding = BUTTON_PADDING) {
                        Text(if (cameraOn || scene != null) "Sky" else "Camera")
                    }
                    FilledTonalButton(
                        onClick = { showTime = !showTime },
                        contentPadding = BUTTON_PADDING,
                    ) {
                        Text(if (dayOffset == 0 && minuteOfDay == null) "Time" else "Time ●")
                    }
                    if (aligning == AlignTarget.LANDMARK) {
                        Button(
                            onClick = {
                                message = alignWithLandmark(tracker, view, place, landmark)
                                aligning = null
                            }
                        ) {
                            Text("Aligned")
                        }
                    } else if (aligning != null) {
                        Button(onClick = { aligning = null }) { Text("Cancel") }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                message = null
                                showPlace = true
                            },
                            contentPadding = BUTTON_PADDING,
                        ) {
                            Text("Place")
                        }
                        Box {
                            Button(
                                onClick = { alignMenu = true },
                                contentPadding = BUTTON_PADDING,
                            ) {
                                Text("Align")
                            }
                            DropdownMenu(
                                expanded = alignMenu,
                                onDismissRequest = { alignMenu = false },
                            ) {
                                val sunUp = sunNow.altitudeDeg > 0
                                val moonUsable =
                                    moonNow.altitudeDeg > 0 && moonFraction >= MIN_MOON_FRACTION
                                // A dropped place also works, for example offline without GPS,
                                // if the user stands at it. The error of the drop is larger.
                                val located = place.isKnown
                                val landmarkDetail =
                                    when {
                                        place.source == PlaceSource.MANUAL ->
                                            "Pick it on a map, then aim at it (you must be at the place)"
                                        located -> "Pick it on a map, then aim at it"
                                        else -> "Needs your location or a place (Place)"
                                    }
                                AlignItem(
                                    "With the sun",
                                    if (sunUp) "Tap the sun in the camera image"
                                    else "The sun is below the horizon",
                                    enabled = sunUp,
                                ) {
                                    alignMenu = false
                                    startBodyAlign(AlignTarget.SUN)
                                }
                                AlignItem(
                                    "With the moon",
                                    when {
                                        moonNow.altitudeDeg <= 0 -> "The moon is below the horizon"
                                        moonFraction < MIN_MOON_FRACTION ->
                                            "The moon is too dim (new moon)"
                                        else -> "Tap the moon in the camera image"
                                    },
                                    enabled = moonUsable,
                                ) {
                                    alignMenu = false
                                    startBodyAlign(AlignTarget.MOON)
                                }
                                AlignItem(
                                    "With a landmark",
                                    landmarkDetail,
                                    enabled = located,
                                ) {
                                    alignMenu = false
                                    message = null
                                    pickingLandmark = true
                                }
                            }
                        }
                    }
                    // The settings are the last button, and they are hidden during an alignment.
                    if (aligning == null) {
                        FilledTonalButton(
                            onClick = { showSettings = true },
                            // Narrower than the default minimum width of a button (58 dp).
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
}

/** A menu item with a second line that says what happens, or why it is not possible now. */
@Composable
private fun AlignItem(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title)
                Text(
                    detail,
                    fontSize = 12.sp,
                    color = if (enabled) Color(0xFFBBBBBB) else Color(0xFFFFB74D),
                )
            }
        },
        enabled = enabled,
        onClick = onClick,
    )
}

private fun alignHint(target: AlignTarget) =
    when (target) {
        AlignTarget.SUN -> "Tap the sun in the camera image."
        AlignTarget.MOON -> "Tap the moon in the camera image."
        AlignTarget.LANDMARK -> "Point the crosshair at the landmark, then press \"Aligned\"."
    }

/** Calibrates the heading with a tap on the sun or the moon. Returns the message for the user. */
private fun alignWithBody(
    tracker: HeadingTracker,
    view: View,
    at: Offset,
    body: Horizontal,
    name: String,
): String {
    val point = ScreenPoint(at.x.toDouble(), at.y.toDouble())
    val tapped = unproject(view, point)
    val altitudeError = abs(tapped.altitudeDeg - body.altitudeDeg)
    if (altitudeError > BODY_TAP_MAX_ALTITUDE_ERROR_DEG) {
        return "That point is %.0f° from the height of the %s. Tap the %s itself."
            .format(altitudeError, name, name)
    }
    return applyMeasurement(tracker, name) {
        headingFromDirection(view, point, body.azimuthDeg, BODY_TAP_SIGMA_DEG)
    }
}

/** The assumed error of a place that the user dropped on the map, in meters. */
private const val DROPPED_PLACE_ERROR_M = 500.0

/**
 * Calibrates the heading with a landmark in the crosshair. The error of the bearing comes from the
 * location error and from the distance: an error e at a distance d is atan(e / d).
 */
private fun alignWithLandmark(
    tracker: HeadingTracker,
    view: View?,
    place: Place,
    landmark: Observer?,
): String {
    if (view == null || landmark == null) return "No landmark."
    val distance = Geo.distanceM(place.observer, landmark)
    if (distance < 50) return "The landmark is too near. Choose one that is farther away."
    // A dropped place has no accuracy: assume the error of a drop on the map, as on iOS.
    val positionError = (place.accuracyM ?: DROPPED_PLACE_ERROR_M) + 5.0
    val sigma = Math.toDegrees(atan(positionError / distance)) + 0.3
    val center = ScreenPoint(view.camera.width / 2, view.camera.height / 2)
    return applyMeasurement(tracker, "landmark") {
        headingFromDirection(view, center, Geo.bearingDeg(place.observer, landmark), sigma)
    }
}

/**
 * Applies a measurement of the heading. The core rejects a direction that gives no heading (for
 * example near the zenith) with an IllegalArgumentException, and its message goes to the user.
 */
private fun applyMeasurement(
    tracker: HeadingTracker,
    name: String,
    measure: () -> at.stefl.almucantar.core.HeadingMeasurement,
): String {
    val measurement =
        try {
            measure()
        } catch (e: IllegalArgumentException) {
            return "No alignment: ${e.message ?: "the direction gives no heading"}."
        }
    val change = ((measurement.offsetDeg - tracker.estimate.offsetDeg + 540) % 360) - 180
    tracker.apply(measurement)
    return "Heading aligned with the %s (corrected by %.1f°, now ±%.1f°)."
        .format(
            name,
            change,
            sqrt(tracker.estimate.varianceDeg2),
        )
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
    /** The time zone of the place, if it is not the zone of the device. */
    otherZone: ZoneId?,
    tracker: HeadingTracker,
    day: SkyDay,
    terrainDay: TerrainDay?,
    terrainStatus: String?,
    cameraOn: Boolean,
    camera: ViewCamera?,
    onPlace: () -> Unit,
) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // A tap on the place opens the place panel, as the button "Place" does.
            Text(
                placeText(place),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onPlace),
            )
            otherZone?.let { Text("Times in ${it.id}", color = Color(0xFFDDDDDD)) }
            HeadingText(tracker)
            Text(day.summary, color = Color(0xFFDDDDDD))
            terrainDay?.let { Text(it.summary, color = SkyColors.terrain) }
            terrainStatus?.let { Text(it, color = Color(0xFFFFB74D), fontSize = 12.sp) }
            if (cameraOn) Text(cameraText(camera), color = Color(0xFFDDDDDD))
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
            sigma > 30 -> "Heading unknown: move the phone in a figure 8" to Color(0xFFFF8A80)
            sigma > 10 -> "Heading ±%.0f° (poor, use Align)".format(sigma) to Color(0xFFFFB74D)
            sigma > 3 -> "Heading ±%.0f°".format(sigma) to Color(0xFFFFE082)
            else -> "Heading ±%.1f°".format(sigma) to Color(0xFFA5D6A7)
        }
    Text(text, color = color)
}

/** A short date in the current locale. The locale can change while the app runs. */
private fun shortDate() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

@Composable
private fun Readout(
    center: Horizontal?,
    usable: Boolean,
    crossing: AzimuthCrossing?,
    dates: List<TimeMatch>,
    zone: ZoneId,
) {
    if (center == null) return
    Panel {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                !usable ->
                    Text(
                        "Hold the phone up to see when the sun is in that direction.",
                        color = Color.White,
                    )
                crossing == null ->
                    Text("The sun does not reach this direction on this day.", color = Color.White)
                else -> {
                    Text(
                        SkyDay.formatTime(crossing.unixUtc, zone),
                        color = SkyColors.nearest,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val altitude = crossing.sun.altitudeDeg
                    Text(
                        if (altitude >= 0)
                            "The sun is in this direction, %.0f° high".format(altitude)
                        else "The sun is in this direction below the horizon",
                        color = Color.White,
                    )
                }
            }
            if (dates.isNotEmpty()) {
                Text(
                    "In the crosshair: " +
                        dates.joinToString(", ") {
                            shortDate()
                                .format(Instant.ofEpochSecond(it.unixUtc.toLong()).atZone(zone)) +
                                " " +
                                SkyDay.formatTime(it.unixUtc, zone)
                        },
                    color = SkyColors.sun,
                )
            }
        }
    }
}

/** A box around the detected sun, with a hint to tap it. */
private fun DrawScope.drawDetectedSun(sun: DetectedSun) {
    val px = density
    val r = sun.blob.radius + 14 * px
    val color = if (sun.separationDeg <= ALIGNED_DEG) Color(0xFFA5D6A7) else Color(0xFFFFE082)
    drawRoundRect(
        color,
        topLeft = sun.blob.center - Offset(r, r),
        size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8 * px),
        style = Stroke(2.5f * px),
    )
    val label = if (sun.separationDeg <= ALIGNED_DEG) "Sun ✓" else "Sun · tap to align"
    drawIntoCanvas {
        it.nativeCanvas.drawText(
            label,
            sun.blob.center.x,
            sun.blob.center.y - r - 8 * px,
            labelPaint(13 * px),
        )
    }
}

/** The terrain of the drawing. [fill] fills the area below the terrain line (sky view only). */
private class Terrain(val lines: TerrainLines?, val day: TerrainDay?, val fill: Boolean)

private fun DrawScope.drawSky(
    view: View,
    day: SkyDay,
    terrain: Terrain,
    guides: List<DirectionLine>,
    sun: Horizontal,
    moon: MoonAt?,
    center: Horizontal,
    crossing: AzimuthCrossing?,
    landmark: Horizontal?,
) {
    val px = density
    val labels = labelPaint(14 * px)
    val small = labelPaint(12 * px, bold = false)

    // The terrain: a light fill below the terrain line in the sky view, and the line.
    val terrainLines = terrain.lines
    if (terrainLines != null) {
        if (terrain.fill) fillRings(view, terrainLines.fill, SkyColors.terrainFill)
        for (line in terrainLines.lines) {
            drawPolylines(projectLine(view, line), SkyColors.terrain, Stroke(2 * px))
        }
    }

    // The horizon with a tick every 15°, and the cardinal points upright above it. It is fainter
    // when the terrain line exists.
    val horizonColor = if (terrainLines != null) SkyColors.horizonFaint else SkyColors.horizon
    drawPolylines(horizonLine(view), horizonColor, Stroke(1.5f * px))
    for (azimuth in 0 until 360 step 15) {
        val direction = Horizontal(azimuth.toDouble(), 0.0)
        val at = screenPoint(view, direction) ?: continue
        val up = upAt(view, direction, at)
        val length = if (azimuth % 90 == 0) 10 * px else 5 * px
        drawLine(horizonColor, at, at + up * length, strokeWidth = 1.5f * px)
        val label =
            when (azimuth) {
                0 -> "N"
                90 -> "E"
                180 -> "S"
                270 -> "W"
                else -> null
            }
        if (label != null) drawUprightText(label, at, up, 16 * px, labels)
    }

    // The paths of the solstices: the band that the sun can reach in a year.
    for (guide in guides) drawPolylines(
        projectLine(view, guide),
        SkyColors.guide,
        dashed(1.5f * px, 4 * px),
    )

    // The path: dashed below the horizon, bright with a glow above.
    drawPolylines(projectLine(view, day.fullPath), SkyColors.nightPath, dashed(2 * px, 8 * px))
    // With the terrain, the parts behind the terrain are dim and dashed.
    val terrainDay = terrain.day
    for (segment in terrainDay?.visibleSegments ?: day.daySegments) {
        val lines = projectLine(view, segment)
        drawPolylines(lines, SkyColors.glow, Stroke(9 * px))
        drawPolylines(lines, SkyColors.dayPath, Stroke(3 * px))
    }
    terrainDay?.hiddenSegments?.forEach {
        drawPolylines(projectLine(view, it), SkyColors.hiddenPath, dashed(3 * px, 6 * px))
    }
    for (mark in day.hourMarks) {
        val at = screenPoint(view, mark.direction) ?: continue
        drawCircle(SkyColors.dayPath, radius = 4 * px, center = at)
        drawUprightText(mark.label, at, upAt(view, mark.direction, at), 10 * px, labels)
    }
    for (mark in day.horizonMarks) {
        val at = screenPoint(view, mark.direction) ?: continue
        drawCircle(Color.White, radius = 3 * px, center = at)
        drawUprightText(mark.label, at, upAt(view, mark.direction, at), -22 * px, small)
    }
    // The visible sunrise and sunset over the terrain.
    terrainDay?.marks?.forEach { mark ->
        val at = screenPoint(view, mark.direction) ?: return@forEach
        drawCircle(SkyColors.terrain, radius = 3 * px, center = at)
        drawUprightText(mark.label, at, upAt(view, mark.direction, at), -22 * px, small)
    }

    // The vertical line through the crosshair (constant azimuth), and where it crosses the path.
    if (crossing != null) {
        // From the horizon to the crossing with the path (up, or down if it is below the horizon).
        val end = crossing.sun.altitudeDeg
        val steps = maxOf(2, abs(end).toInt() + 1)
        val vertical = (0..steps).map { Horizontal(center.azimuthDeg, end * it / steps) }
        drawPolylines(
            projectLine(view, DirectionLine.of(vertical)),
            SkyColors.nearest,
            dashed(1.5f * px, 6 * px),
        )
        screenPoint(view, crossing.sun)?.let {
            drawCircle(SkyColors.nearest, radius = 9 * px, center = it, style = Stroke(2.5f * px))
        }
    }

    // A landmark of the calibration: a marker on the horizon at its bearing.
    landmark?.let { direction ->
        screenPoint(view, direction)?.let {
            drawCircle(Color(0xFFFF5252), radius = 8 * px, center = it, style = Stroke(2.5f * px))
            drawUprightText("landmark", it, upAt(view, direction, it), 14 * px, small)
        }
    }

    // The moon with its phase. The faint disc shows the dark part.
    moon?.let { m ->
        screenPoint(view, m.direction)?.let {
            drawMoon(view, m, sun, it, 11 * px)
            drawUprightText("moon", it, upAt(view, m.direction, it), 16 * px, small)
        }
    }

    // The sun, or an arrow at the border of the screen that points to it.
    val bounds = Rect(0f, 0f, size.width, size.height).deflate(28 * px)
    val indicator = edgeIndicator(view, center, sun, bounds)
    if (indicator == null) {
        screenPoint(view, sun)?.let {
            drawCircle(SkyColors.glow, radius = 26 * px, center = it)
            drawCircle(SkyColors.sun, radius = 14 * px, center = it)
        }
    } else {
        val (at, direction) = indicator
        drawArrow(at, direction, 18 * px, SkyColors.sun)
        drawCircle(SkyColors.sun, radius = 6 * px, center = at - direction * 26f * px)
    }

    // The crosshair.
    val c = Offset(size.width / 2, size.height / 2)
    val arm = 16 * px
    drawLine(Color.White, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = 1.5f * px)
    drawLine(Color.White, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = 1.5f * px)
}

private fun placeText(place: Place): String {
    val name =
        place.name ?: "%.2f°, %.2f°".format(place.observer.latitudeDeg, place.observer.longitudeDeg)
    return when (place.source) {
        PlaceSource.DEVICE -> name + (place.accuracyM?.let { " · ${errorScale(it)}" } ?: "")
        PlaceSource.MANUAL -> name
        PlaceSource.LAST -> "$name · last place"
        PlaceSource.TIME_ZONE -> "$name · approximate (time zone)"
        PlaceSource.DEFAULT -> "$name · no location"
    }
}

private fun cameraText(camera: ViewCamera?): String =
    when (camera?.source) {
        null -> "Camera: waiting for the calibration"
        CalibrationSource.INTRINSICS -> "Camera: lens calibration"
        CalibrationSource.FOCAL_LENGTH -> "Camera: focal length only (less accurate)"
    }

/**
 * Draws the moon at [at] with the lit part toward the sun. The direction of the sun on the screen
 * comes from a point 1° from the moon toward the sun, see [MoonPhase.towardSun].
 */
private fun DrawScope.drawMoon(
    view: View,
    moon: MoonAt,
    sun: Horizontal,
    at: Offset,
    radius: Float,
) {
    val toSun = screenPoint(view, MoonPhase.towardSun(moon.direction, sun))?.minus(at)
    val length = toSun?.getDistance() ?: 0f
    val u = if (toSun != null && length > 1e-3f) toSun / length else Offset(0f, -1f)
    val v = Offset(-u.y, u.x)
    drawCircle(SkyColors.moon.copy(alpha = 0.22f), radius = radius, center = at)
    val path = Path()
    MoonPhase.litOutline(moon.fraction).forEachIndexed { i, (x, y) ->
        val p = at + (u * x.toFloat() + v * y.toFloat()) * radius
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, SkyColors.moon)
}
