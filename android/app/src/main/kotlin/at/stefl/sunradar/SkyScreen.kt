package at.stefl.sunradar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import at.stefl.sunradar.core.Horizontal
import at.stefl.sunradar.core.Polylines
import at.stefl.sunradar.core.ScreenPoint
import at.stefl.sunradar.core.ScreenRotation
import at.stefl.sunradar.core.View
import at.stefl.sunradar.core.cameraFromFov
import at.stefl.sunradar.core.findTime
import at.stefl.sunradar.core.horizonLine
import at.stefl.sunradar.core.magneticDeclinationDeg
import at.stefl.sunradar.core.project
import at.stefl.sunradar.core.projectLine
import at.stefl.sunradar.core.sunPosition
import at.stefl.sunradar.core.unproject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

private val SKY = Color(0xFF0B1A30)
private val HORIZON = Color(0xB3FFFFFF)
private val NIGHT_PATH = Color(0x40FFC83D)
private val DAY_PATH = Color(0xFFFFC83D)
private val SUN = Color(0xFFFFE08A)

/** The horizontal field of view of the virtual camera, in portrait and in landscape. */
private const val FOV_PORTRAIT_DEG = 60.0
private const val FOV_LANDSCAPE_DEG = 90.0

/**
 * The time in the crosshair is shown only if the path is nearer than this angle. The plan proposes
 * 3 degrees (PLAN.md, section 5.6).
 */
private const val AUTO_MODE_MAX_SEPARATION_DEG = 3.0

/**
 * The sky view without camera: the horizon, the cardinal points, the path of today with the hours,
 * the sun now, and in the crosshair the time when the sun is there (auto mode).
 */
@Composable
fun SkyScreen(locationGranted: Boolean) {
    val orientation by rememberOrientation()
    val place by rememberPlace(locationGranted)
    val zone = remember { ZoneId.systemDefault() }

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
    val declination = remember(place.observer, date) { magneticDeclinationDeg(place.observer, now) }
    val rotation = ScreenRotation.entries[LocalView.current.display?.rotation ?: 0]
    var crosshair by remember { mutableDoubleStateOf(Double.NaN) }

    // The camera: on if the user switches it on and allows it. Without a camera model (not yet
    // known, or no camera), the view uses the virtual camera of the sky view.
    val context = LocalContext.current
    var cameraOn by remember { mutableStateOf(false) }
    var viewCamera by remember { mutableStateOf<ViewCamera?>(null) }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraOn = it
        }
    val toggleCamera = {
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

    Box(Modifier.fillMaxSize().background(SKY)) {
        if (cameraOn) {
            CameraPreview(Modifier.fillMaxSize()) { viewCamera = it }
        }
        val q = orientation.deviceToEnu
        if (q != null) {
            Canvas(Modifier.fillMaxSize()) {
                val fov = if (size.width < size.height) FOV_PORTRAIT_DEG else FOV_LANDSCAPE_DEG
                val camera =
                    viewCamera?.camera?.takeIf { cameraOn }
                        ?: cameraFromFov(size.width.toDouble(), size.height.toDouble(), fov)
                val view =
                    View(
                        deviceToEnu = q,
                        screenRotation = rotation,
                        headingOffsetDeg = declination,
                        camera = camera,
                    )
                drawSky(view, day, now)
                crosshair = autoModeTime(view, day)
            }
        }

        // The end padding keeps the text away from the camera button.
        Column(Modifier.safeDrawingPadding().padding(start = 16.dp, top = 16.dp, end = 120.dp)) {
            Text(placeText(place), color = Color.White)
            Text(
                "Declination %.1f° %s"
                    .format(Locale.ROOT, abs(declination), if (declination >= 0) "E" else "W"),
                color = Color.White,
            )
            Text(compassText(orientation), color = Color.White)
            Text(day.summary, color = Color.White)
            if (cameraOn) Text(cameraText(viewCamera), color = Color.White)
        }

        Button(
            onClick = toggleCamera,
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp),
        ) {
            Text(if (cameraOn) "Sky" else "Camera")
        }

        Text(
            text =
                if (crosshair.isNaN()) "The sun does not pass here today"
                else "The sun is here at ${SkyDay.formatTime(crosshair, zone)}",
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp),
        )
    }
}

/** The time when the sun is in the crosshair today, or NaN if the path is not near. */
private fun autoModeTime(view: View, day: SkyDay): Double {
    val center = unproject(view, ScreenPoint(view.camera.width / 2, view.camera.height / 2))
    val match = findTime(day.observer, center, day.startUtc, day.endUtc)
    val visible = match.sun.altitudeDeg > -1
    return if (match.separationDeg <= AUTO_MODE_MAX_SEPARATION_DEG && visible) match.unixUtc
    else Double.NaN
}

private fun DrawScope.drawSky(view: View, day: SkyDay, now: Double) {
    val text =
        Paint().apply {
            isAntiAlias = true
            color = Color.White.toArgb()
            textSize = 14.dp.toPx()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

    drawPolylines(horizonLine(view), HORIZON, 1.5.dp.toPx())
    for ((azimuth, label) in listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W")) {
        val p = project(view, Horizontal(azimuth, 0.0))
        if (p.valid) {
            drawIntoCanvas {
                it.nativeCanvas.drawText(
                    label,
                    p.point.x.toFloat(),
                    p.point.y.toFloat() - 8.dp.toPx(),
                    text,
                )
            }
        }
    }

    drawPolylines(projectLine(view, day.fullPath), NIGHT_PATH, 2.dp.toPx())
    for (segment in day.daySegments) {
        drawPolylines(projectLine(view, segment), DAY_PATH, 3.dp.toPx())
    }

    text.textSize = 12.dp.toPx()
    for (mark in day.hourMarks) {
        val p = project(view, mark.direction)
        if (p.valid) {
            val at = Offset(p.point.x.toFloat(), p.point.y.toFloat())
            drawCircle(DAY_PATH, radius = 4.dp.toPx(), center = at)
            drawIntoCanvas { it.nativeCanvas.drawText(mark.label, at.x, at.y - 10.dp.toPx(), text) }
        }
    }

    val sun = project(view, sunPosition(now, day.observer))
    if (sun.valid) {
        drawCircle(
            SUN,
            radius = 14.dp.toPx(),
            center = Offset(sun.point.x.toFloat(), sun.point.y.toFloat()),
        )
    }

    val c = center
    val arm = 16.dp.toPx()
    drawLine(Color.White, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = 1.dp.toPx())
    drawLine(Color.White, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = 1.dp.toPx())
}

private fun DrawScope.drawPolylines(lines: Polylines, color: Color, width: Float) {
    for (line in 0 until lines.count) {
        if (lines.size(line) < 2) continue
        val path = Path()
        path.moveTo(lines.x(line, 0).toFloat(), lines.y(line, 0).toFloat())
        for (i in 1 until lines.size(line)) {
            path.lineTo(lines.x(line, i).toFloat(), lines.y(line, i).toFloat())
        }
        drawPath(path, color, style = Stroke(width))
    }
}

private fun placeText(place: Place): String {
    val o = place.observer
    val coordinates =
        "%.2f° %s, %.2f° %s"
            .format(
                Locale.ROOT,
                abs(o.latitudeDeg),
                if (o.latitudeDeg >= 0) "N" else "S",
                abs(o.longitudeDeg),
                if (o.longitudeDeg >= 0) "E" else "W",
            )
    val name = place.name?.let { "$it, " } ?: ""
    val accuracy =
        when (place.source) {
            PlaceSource.DEVICE -> place.accuracyM?.let { " · ${errorScale(it)}" } ?: ""
            PlaceSource.TIME_ZONE -> " · from the time zone, error up to hundreds of km"
            PlaceSource.DEFAULT -> " · no location, error unknown"
        }
    return "$name$coordinates$accuracy"
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
        CalibrationSource.INTRINSICS -> {
            val distorted = camera.camera.k1 != 0.0 || camera.camera.k2 != 0.0
            "Camera: lens calibration" + if (distorted) " with distortion" else ""
        }
        CalibrationSource.FOCAL_LENGTH -> "Camera: focal length only (less accurate)"
    }

private fun compassText(orientation: Orientation): String {
    if (!orientation.available) return "This device has no rotation vector sensor"
    val accuracy =
        when (orientation.accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
            else -> "unreliable"
        }
    val error = orientation.headingErrorDeg?.let { " (±%.0f°)".format(Locale.ROOT, it) } ?: ""
    return "Compass accuracy: $accuracy$error"
}
