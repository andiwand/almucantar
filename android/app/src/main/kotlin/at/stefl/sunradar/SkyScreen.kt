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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import at.stefl.sunradar.core.Horizontal
import at.stefl.sunradar.core.ScreenPoint
import at.stefl.sunradar.core.ScreenRotation
import at.stefl.sunradar.core.TimeMatch
import at.stefl.sunradar.core.View
import at.stefl.sunradar.core.cameraFromFov
import at.stefl.sunradar.core.findTime
import at.stefl.sunradar.core.headingFromDirection
import at.stefl.sunradar.core.horizonLine
import at.stefl.sunradar.core.projectLine
import at.stefl.sunradar.core.sunPosition
import at.stefl.sunradar.core.unproject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/** The horizontal field of view of the virtual camera, in portrait and in landscape. */
private const val FOV_PORTRAIT_DEG = 60.0
private const val FOV_LANDSCAPE_DEG = 90.0

/** Within this angle of the path, the crosshair counts as "on the path". */
private const val ON_PATH_DEG = 1.5

/** The angular error of a tap on the sun (the sun has a diameter of 0.5°). */
private const val SUN_TAP_SIGMA_DEG = 0.5

/** A tap more than this far from the altitude of the sun is not the sun. */
private const val SUN_TAP_MAX_ALTITUDE_ERROR_DEG = 5.0

/**
 * The sky view: the horizon with the cardinal points, the path of the sun today with the hours, the
 * sun now, and the point of the path nearest to the crosshair with its time. With the camera, the
 * same overlay lies on the camera image, and the user can align the heading with the sun.
 */
@Composable
fun SkyScreen(locationGranted: Boolean) {
    val tracker = rememberHeadingTracker()
    val place by rememberPlace(locationGranted)
    val zone = remember { ZoneId.systemDefault() }
    tracker.observer = place.observer

    var now by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000.0
        }
    }
    val date = Instant.ofEpochSecond(now.toLong()).atZone(zone).toLocalDate()
    val day =
        remember(place.observer, date) {
            SkyDay(
                place.observer,
                date.atStartOfDay(zone).toEpochSecond().toDouble(),
                date.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble(),
                zone,
            )
        }

    // The activity handles rotations itself, so reading the configuration recomposes after one.
    LocalConfiguration.current
    val displayRotation = LocalView.current.display?.rotation ?: 0

    val context = LocalContext.current
    var cameraOn by remember { mutableStateOf(false) }
    var viewCamera by remember { mutableStateOf<ViewCamera?>(null) }
    var aligning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraOn = it
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
    val sun = sunPosition(now, day.observer)
    val center = view?.let { unproject(it, ScreenPoint(it.camera.width / 2, it.camera.height / 2)) }
    val nearest = center?.let { findTime(day.observer, it, day.startUtc, day.endUtc) }

    val onTap = { at: Offset ->
        if (aligning && view != null) {
            message = alignWithSun(tracker, view, at, sun)
            aligning = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(SkyColors.sky).onSizeChanged { size = it }) {
            if (cameraOn) {
                CameraPreview(Modifier.fillMaxSize(), displayRotation) { viewCamera = it }
            }
            if (view != null && center != null) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(aligning) {
                        detectTapGestures { onTap(it) }
                    }
                ) {
                    drawSky(view, day, sun, center, nearest)
                }
            }

            Column(
                Modifier.align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusCard(place, tracker, day, cameraOn, viewCamera)
                message?.let { Panel { Text(it, color = Color.White) } }
                if (aligning)
                    Panel { Text("Tap the sun in the camera image.", color = Color.White) }
            }

            Column(
                Modifier.align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimeReadout(nearest, zone)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = toggleCamera) {
                        Text(if (cameraOn) "Sky view" else "Camera")
                    }
                    if (cameraOn) {
                        Button(
                            onClick = {
                                message = null
                                aligning = !aligning
                            },
                            enabled = sun.altitudeDeg > 0,
                        ) {
                            Text(if (aligning) "Cancel" else "Align with the sun")
                        }
                    }
                }
            }
        }
    }
}

/** Calibrates the heading with a tap on the sun. Returns the message for the user. */
private fun alignWithSun(tracker: HeadingTracker, view: View, at: Offset, sun: Horizontal): String {
    val point = ScreenPoint(at.x.toDouble(), at.y.toDouble())
    val tapped = unproject(view, point)
    val altitudeError = abs(tapped.altitudeDeg - sun.altitudeDeg)
    if (altitudeError > SUN_TAP_MAX_ALTITUDE_ERROR_DEG) {
        return "That point is %.0f° from the height of the sun. Tap the sun itself."
            .format(altitudeError)
    }
    val measurement = headingFromDirection(view, point, sun.azimuthDeg, SUN_TAP_SIGMA_DEG)
    val change = ((measurement.offsetDeg - tracker.estimate.offsetDeg + 540) % 360) - 180
    tracker.apply(measurement)
    return "Heading aligned with the sun (corrected by %.1f°).".format(change)
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
            sigma > 10 ->
                "Heading ±%.0f° (poor, align with the sun)".format(sigma) to Color(0xFFFFB74D)
            sigma > 3 -> "Heading ±%.0f°".format(sigma) to Color(0xFFFFE082)
            else -> "Heading ±%.1f°".format(sigma) to Color(0xFFA5D6A7)
        }
    Text(text, color = color)
}

@Composable
private fun TimeReadout(nearest: TimeMatch?, zone: ZoneId) {
    if (nearest == null) return
    val time = SkyDay.formatTime(nearest.unixUtc, zone)
    val detail =
        when {
            nearest.sun.altitudeDeg < -0.8 -> "Nearest point of today's path · below the horizon"
            nearest.separationDeg <= ON_PATH_DEG -> "The sun is in the crosshair today"
            else -> "Nearest point of today's path · %.0f° away".format(nearest.separationDeg)
        }
    Panel {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                time,
                color = SkyColors.nearest,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(detail, color = Color.White)
        }
    }
}

private fun DrawScope.drawSky(
    view: View,
    day: SkyDay,
    sun: Horizontal,
    center: Horizontal,
    nearest: TimeMatch?,
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

    // The point of the path nearest to the crosshair, with a line from the crosshair.
    val c = Offset(size.width / 2, size.height / 2)
    if (nearest != null) {
        screenPoint(view, nearest.sun)?.let { at ->
            if (nearest.separationDeg > ON_PATH_DEG) {
                drawLine(
                    SkyColors.nearest,
                    c,
                    at,
                    strokeWidth = 1.5f * px,
                    pathEffect = dashed(1f, 6 * px).pathEffect,
                )
            }
            drawCircle(SkyColors.nearest, radius = 9 * px, center = at, style = Stroke(2.5f * px))
        }
    }

    // The sun now, or an arrow at the border of the screen that points to it.
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
    val sky = if (skyDeg < 0.01) "< 0.01°" else "%.2f°".format(java.util.Locale.ROOT, skyDeg)
    return "$label (sky $sky)"
}

private fun cameraText(camera: ViewCamera?): String =
    when (camera?.source) {
        null -> "Camera: waiting for the calibration"
        CalibrationSource.INTRINSICS -> "Camera: lens calibration"
        CalibrationSource.FOCAL_LENGTH -> "Camera: focal length only (less accurate)"
    }
