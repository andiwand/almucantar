package at.stefl.alidade

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.ScreenPoint
import at.stefl.almucantar.core.ScreenRotation
import at.stefl.almucantar.core.View
import at.stefl.almucantar.core.bodyPosition
import at.stefl.almucantar.core.cameraFromFov
import at.stefl.almucantar.core.horizonLine
import at.stefl.almucantar.core.projectLine
import at.stefl.almucantar.core.unproject
import at.stefl.almucantar.shared.CameraPreview
import at.stefl.almucantar.shared.Geo
import at.stefl.almucantar.shared.OrientationFrame
import at.stefl.almucantar.shared.TerrainLines
import at.stefl.almucantar.shared.ViewCamera
import at.stefl.almucantar.shared.deviceLocation
import at.stefl.almucantar.shared.drawArrow
import at.stefl.almucantar.shared.drawPolylines
import at.stefl.almucantar.shared.drawUprightText
import at.stefl.almucantar.shared.edgeIndicator
import at.stefl.almucantar.shared.fillRings
import at.stefl.almucantar.shared.labelPaint
import at.stefl.almucantar.shared.rememberDisplayRotation
import at.stefl.almucantar.shared.rememberHeadingTracker
import at.stefl.almucantar.shared.screenPoint
import at.stefl.almucantar.shared.upAt
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The live view of a shot: the shot in first person through the camera, as in sonnseite and
// sternstunde. The phone shows the terrain of the spot, the path of the subject with the full
// hours, the subject at the time of the slider, and the landmark, in the direction that the phone
// points to. An arrow at the border leads to the subject. Without the camera, the same overlay lies
// on a dark sky. The view starts without the camera; the button "Camera" switches it on. The view
// is true at the spot; the panel shows how far away the phone is.

/** The horizontal field of view without the camera, in portrait and landscape. */
private const val FOV_PORTRAIT_DEG = 60.0
private const val FOV_LANDSCAPE_DEG = 90.0

/** The radius of the disc of the sun and the moon, in degrees. */
private const val DISC_RADIUS_DEG = 0.265

private val PATH_COLOR = Color(0xFFE8955E)
private val TERRAIN_COLOR = Color(0xD9AAD2A0)
private val SUN_COLOR = Color(0xFFFFE08A)
private val MOON_COLOR = Color(0xFFE0E6F0)

@Composable
fun LiveScreen(model: Model, shot: Shot, startUtc: Double, locationGranted: Boolean) {
    val context = LocalContext.current
    val tracker = rememberHeadingTracker()
    SideEffect { tracker.observer = shot.spot }
    val displayRotation = rememberDisplayRotation()
    val zone = model.zoneAt(shot.spot)
    val s = subjectOf(shot.subject)
    val horizon = rememberHorizon(model, shot.spot)
    val terrain = remember(horizon) { horizon?.let { TerrainLines(it.skyline) } }
    val track = remember(shot, zone) { trackOf(shot, zone) }
    val minutes = ((shot.pathEndUtc - shot.pathStartUtc) / 60).roundToInt().coerceAtLeast(1)
    var minute by rememberSaveable {
        mutableFloatStateOf(
            ((startUtc - shot.pathStartUtc) / 60).toFloat().coerceIn(0f, minutes.toFloat())
        )
    }
    val t = shot.pathStartUtc + minute.roundToInt() * 60.0
    val body = remember(t) { bodyPosition(s.body, t, shot.spot) }

    // The view starts with the drawn sky. The button "Camera" puts the overlay on the camera
    // image, and the app asks for the permission at the first tap.
    var cameraOn by rememberSaveable { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraOn = it
            cameraDenied = !it
        }
    val toggleCamera = {
        if (cameraOn) cameraOn = false
        else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
            cameraOn = true
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    var viewCamera by remember { mutableStateOf<ViewCamera?>(null) }

    // The distance from the phone to the spot: the view is true only at the spot.
    val here by
        produceState<Observer?>(null, locationGranted) {
            if (locationGranted) value = deviceLocation(context)
        }

    var size by remember { mutableStateOf(IntSize.Zero) }
    val q = tracker.deviceToFrame
    val view =
        if (q == null || size.width == 0) null
        else
            View(
                deviceToEnu = q,
                screenRotation = ScreenRotation.entries[displayRotation],
                headingOffsetDeg = tracker.shownOffsetDeg,
                camera =
                    viewCamera?.camera?.takeIf { cameraOn }
                        ?: cameraFromFov(
                            size.width.toDouble(),
                            size.height.toDouble(),
                            if (size.width < size.height) FOV_PORTRAIT_DEG else FOV_LANDSCAPE_DEG,
                        ),
            )

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0F1E)).onSizeChanged { size = it }) {
        if (cameraOn) {
            CameraPreview(
                Modifier.fillMaxSize(),
                displayRotation,
                detectSun = false,
                onBlobs = {},
            ) {
                viewCamera = it
            }
        }
        if (view != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawLive(view, shot, s, body, track, terrain, cameraOn)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(12.dp)
        ) {
            Print(Modifier.fillMaxWidth(), padding = 14.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Title(shotTitle(shot), Modifier.weight(1f), size = 20)
                        FilterChip(
                            selected = cameraOn,
                            onClick = toggleCamera,
                            label = { Text("Camera") },
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.ic_camera),
                                    contentDescription = null,
                                    Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Shade.ink,
                                    selectedLabelColor = Shade.cream,
                                    selectedLeadingIconColor = Shade.cream,
                                ),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            clock(t, zone),
                            Modifier.width(56.dp),
                            fontFamily = Mono,
                            fontSize = 17.sp,
                            color = Shade.ink,
                        )
                        Slider(
                            minute,
                            { minute = it },
                            Modifier.weight(1f),
                            valueRange = 0f..minutes.toFloat(),
                            colors = sliderColors(),
                        )
                    }
                    Text(
                        "${s.name} ${deg(body.azimuthDeg, 0)} · ${deg(body.altitudeDeg)} up · " +
                            date(t, zone),
                        fontFamily = Mono,
                        fontSize = 12.sp,
                        color = Shade.inkSoft,
                    )
                    Muted(
                        status(
                            tracker.frame,
                            tracker.estimate.varianceDeg2,
                            here,
                            shot,
                            cameraDenied,
                        ),
                        small = true,
                    )
                }
            }
        }
    }
}

/** The line of the state: where the phone is, and how good the heading is. */
private fun status(
    frame: OrientationFrame,
    varianceDeg2: Double,
    here: Observer?,
    shot: Shot,
    cameraDenied: Boolean,
): String {
    val where =
        here?.let {
            val d = Geo.distanceM(it, shot.spot)
            when {
                d < 300 -> "You are at the spot."
                // Far away, a decimal of a kilometre tells nothing.
                d >= 100_000 -> "You are ${(d / 1000).roundToInt()} km from the spot."
                else -> "You are ${km(d)} from the spot."
            }
        } ?: "The view is true at the spot."
    val heading =
        if (frame == OrientationFrame.NONE) "No compass: north is not known."
        else "Heading ±${sqrt(varianceDeg2).roundToInt()}°."
    val camera = if (cameraDenied) " The camera is not allowed." else ""
    return "$where $heading$camera"
}

private fun DrawScope.drawLive(
    view: View,
    shot: Shot,
    s: Subject,
    body: Horizontal,
    track: PhotoTrack,
    terrain: TerrainLines?,
    cameraOn: Boolean,
) {
    val dp = density
    // The horizon, the terrain of the spot, and the cardinal points.
    drawPolylines(
        horizonLine(view),
        Color.White.copy(alpha = if (terrain != null) 0.3f else 0.7f),
        Stroke(width = 1 * dp),
    )
    terrain?.let { t ->
        if (!cameraOn) fillRings(view, t.fill, Color(0xB80E1614))
        for (line in t.lines) {
            drawPolylines(projectLine(view, line), TERRAIN_COLOR, Stroke(width = 1.5f * dp))
        }
    }
    val cardinal = labelPaint(14 * dp)
    for ((az, label) in listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W")) {
        val d = Horizontal(az, 0.0)
        val at = screenPoint(view, d) ?: continue
        drawUprightText(label, at, upAt(view, d, at), 12 * dp, cardinal)
    }

    // The path of the subject, with a mark and the time at each full hour.
    for (part in projectLine(view, track.points)) {
        if (part.size < 2) continue
        drawPath(part.toPath(), PATH_COLOR, style = Stroke(width = 2 * dp))
    }
    val small = labelPaint(11 * dp, bold = false)
    for ((h, label) in track.ticks) {
        val at = screenPoint(view, h) ?: continue
        drawCircle(PATH_COLOR, radius = 3 * dp, center = at)
        drawUprightText(label, at, upAt(view, h, at), 8 * dp, small)
    }

    // The landmark: a pin with its name.
    shot.target?.let { target ->
        screenPoint(view, target.direction)?.let { at ->
            val top = at + upAt(view, target.direction, at) * (36 * dp)
            drawLine(Color.White, at, top, strokeWidth = 1.5f * dp)
            drawCircle(Color.White, radius = 3 * dp, center = at)
            drawUprightText(
                target.name,
                top,
                upAt(view, target.direction, at),
                6 * dp,
                labelPaint(12 * dp),
            )
        }
    }

    // The subject at the time of the slider: the disc at its true size, at least a dot.
    val color =
        when (s.id) {
            SubjectId.SUN -> SUN_COLOR
            SubjectId.MOON -> MOON_COLOR
            else -> Color.White
        }
    val radius = max(6 * dp, (view.camera.fx * Math.toRadians(DISC_RADIUS_DEG)).toFloat())
    val at = screenPoint(view, body)
    if (at != null) {
        drawCircle(color.copy(alpha = 0.25f), radius = radius * 2.2f, center = at)
        drawCircle(color, radius = radius, center = at)
        drawUprightText(s.name, at, upAt(view, body, at), radius + 10 * dp, labelPaint(13 * dp))
    }

    // An arrow at the border of the screen leads to the subject.
    val bounds = Rect(28 * dp, 96 * dp, size.width - 28 * dp, size.height - 200 * dp)
    val center = unproject(view, ScreenPoint(view.camera.width / 2, view.camera.height / 2))
    edgeIndicator(view, center, body, bounds)?.let { (edge, direction) ->
        drawArrow(edge, direction, 18 * dp, PATH_COLOR)
    }
}

private fun List<ScreenPoint>.toPath(): Path =
    Path().apply {
        forEachIndexed { i, p ->
            val o = Offset(p.x.toFloat(), p.y.toFloat())
            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
        }
    }
