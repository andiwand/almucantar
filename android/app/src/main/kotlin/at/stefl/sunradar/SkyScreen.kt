package at.stefl.sunradar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import at.stefl.sunradar.core.AzimuthCrossing
import at.stefl.sunradar.core.DirectionLine
import at.stefl.sunradar.core.Horizontal
import at.stefl.sunradar.core.Observer
import at.stefl.sunradar.core.ScreenPoint
import at.stefl.sunradar.core.ScreenRotation
import at.stefl.sunradar.core.TimeMatch
import at.stefl.sunradar.core.View
import at.stefl.sunradar.core.cameraFromFov
import at.stefl.sunradar.core.findAzimuthTimes
import at.stefl.sunradar.core.findDates
import at.stefl.sunradar.core.headingFromDirection
import at.stefl.sunradar.core.horizonLine
import at.stefl.sunradar.core.moonIlluminatedFraction
import at.stefl.sunradar.core.moonPosition
import at.stefl.sunradar.core.projectLine
import at.stefl.sunradar.core.sunPosition
import at.stefl.sunradar.core.unproject
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
fun SkyScreen(locationGranted: Boolean) {
    val tracker = rememberHeadingTracker()
    val zone = rememberTimeZone()
    val place by rememberPlace(locationGranted, zone)
    SideEffect { tracker.observer = place.observer }

    var now by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000.0
        }
    }

    // The selected date and time. minuteOfDay null means the live time.
    var dayOffset by remember { mutableIntStateOf(0) }
    var minuteOfDay by remember { mutableStateOf<Int?>(null) }
    var showTime by remember { mutableStateOf(false) }
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
            SkyDay(
                place.observer,
                date.atStartOfDay(zone).toEpochSecond().toDouble(),
                date.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble(),
                zone,
            )
        }
    val guides =
        remember(place.observer, date.year, zone) {
            solsticeGuides(place.observer, date.year, zone)
        }

    val displayRotation = rememberDisplayRotation()

    val context = LocalContext.current
    var cameraOn by remember { mutableStateOf(false) }
    var viewCamera by remember { mutableStateOf<ViewCamera?>(null) }
    var aligning by remember { mutableStateOf<AlignTarget?>(null) }
    var alignMenu by remember { mutableStateOf(false) }
    var pickingLandmark by remember { mutableStateOf(false) }
    var landmark by remember { mutableStateOf<Observer?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
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
            val fov = if (size.width < size.height) FOV_PORTRAIT_DEG else FOV_LANDSCAPE_DEG
            View(
                deviceToEnu = q,
                screenRotation = ScreenRotation.entries[displayRotation],
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
            moonPosition(time, day.observer).takeIf { moonIlluminatedFraction(time) >= 0.02 }
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
        if (pickingLandmark) {
            MapPicker(place.observer) {
                pickingLandmark = false
                landmark = it
                aligning = if (it != null) AlignTarget.LANDMARK else null
            }
            return@MaterialTheme
        }

        Box(Modifier.fillMaxSize().background(SkyColors.sky).onSizeChanged { size = it }) {
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
                StatusCard(place, tracker, day, cameraOn, viewCamera)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = toggleCamera) {
                        Text(if (cameraOn) "Sky" else "Camera")
                    }
                    FilledTonalButton(onClick = { showTime = !showTime }) {
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
                        Box {
                            Button(onClick = { alignMenu = true }) { Text("Align") }
                            DropdownMenu(
                                expanded = alignMenu,
                                onDismissRequest = { alignMenu = false },
                            ) {
                                val sunUp = sunNow.altitudeDeg > 0
                                val moonUsable =
                                    moonNow.altitudeDeg > 0 && moonFraction >= MIN_MOON_FRACTION
                                val located = place.source == PlaceSource.DEVICE
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
                                    if (located) "Pick it on a map, then aim at it"
                                    else "Needs your location (allow location access)",
                                    enabled = located,
                                ) {
                                    alignMenu = false
                                    message = null
                                    pickingLandmark = true
                                }
                            }
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
    val positionError = (place.accuracyM ?: 50.0) + 5.0
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
    measure: () -> at.stefl.sunradar.core.HeadingMeasurement,
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
    tracker: HeadingTracker,
    day: SkyDay,
    cameraOn: Boolean,
    camera: ViewCamera?,
) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(placeText(place), color = Color.White, fontWeight = FontWeight.SemiBold)
            HeadingText(tracker)
            Text(day.summary, color = Color(0xFFDDDDDD))
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

private fun DrawScope.drawSky(
    view: View,
    day: SkyDay,
    guides: List<DirectionLine>,
    sun: Horizontal,
    moon: Horizontal?,
    center: Horizontal,
    crossing: AzimuthCrossing?,
    landmark: Horizontal?,
) {
    val px = density
    val labels = labelPaint(14 * px)
    val small = labelPaint(12 * px, bold = false)

    // The horizon with a tick every 15°, and the cardinal points upright above it.
    drawPolylines(horizonLine(view), SkyColors.horizon, Stroke(1.5f * px))
    for (azimuth in 0 until 360 step 15) {
        val direction = Horizontal(azimuth.toDouble(), 0.0)
        val at = screenPoint(view, direction) ?: continue
        val up = upAt(view, direction, at)
        val length = if (azimuth % 90 == 0) 10 * px else 5 * px
        drawLine(SkyColors.horizon, at, at + up * length, strokeWidth = 1.5f * px)
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
    for (segment in day.daySegments) {
        val lines = projectLine(view, segment)
        drawPolylines(lines, SkyColors.glow, Stroke(9 * px))
        drawPolylines(lines, SkyColors.dayPath, Stroke(3 * px))
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

    // The moon.
    moon?.let { direction ->
        screenPoint(view, direction)?.let {
            drawCircle(SkyColors.moon, radius = 11 * px, center = it)
            drawUprightText("moon", it, upAt(view, direction, it), 16 * px, small)
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
        PlaceSource.TIME_ZONE -> "$name · approximate (time zone)"
        PlaceSource.DEFAULT -> "$name · no location"
    }
}

/**
 * The location error as a power of ten (±1 m, ±10 m, ... ±100 km), and the error that it causes on
 * the sky: moving by d on the Earth tilts the local vertical by d / 6371 km, which is about 1° per
 * 111 km.
 */
internal fun errorScale(accuracyM: Double): String {
    val exponent = kotlin.math.ceil(kotlin.math.log10(accuracyM.coerceIn(1.0, 1e5))).toInt()
    val label =
        when (exponent) {
            0 -> "±1 m"
            1 -> "±10 m"
            2 -> "±100 m"
            3 -> "±1 km"
            4 -> "±10 km"
            else -> "±100 km"
        }
    val skyDeg = Math.pow(10.0, exponent.toDouble()) / 111_195.0
    val sky = if (skyDeg < 0.01) "< 0.01°" else "%.2f°".format(Locale.ROOT, skyDeg)
    return "$label (sky $sky)"
}

private fun cameraText(camera: ViewCamera?): String =
    when (camera?.source) {
        null -> "Camera: waiting for the calibration"
        CalibrationSource.INTRINSICS -> "Camera: lens calibration"
        CalibrationSource.FOCAL_LENGTH -> "Camera: focal length only (less accurate)"
    }
