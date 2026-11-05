package at.stefl.shotradar

import android.content.Intent
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import at.stefl.almucantar.core.bodyPosition
import at.stefl.almucantar.core.moonIlluminatedFraction
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// The shot: the photo from the spot in first person, with a time slider, the lenses, the data of
// the shot, and the way to the spot. A drag turns the view, a pinch zooms it, and a double tap aims
// it again.

@Composable
fun ShotScreen(model: Model, shot: Shot) {
    val context = LocalContext.current
    val zone = model.zoneAt(shot.spot)
    val s = subjectOf(shot.subject)
    var focal by remember {
        mutableIntStateOf(if (shot.eclipse != null) 600 else if (shot.target != null) 300 else 24)
    }
    val minutes = ((shot.pathEndUtc - shot.pathStartUtc) / 60).roundToInt().coerceAtLeast(1)
    var offset by remember {
        mutableFloatStateOf(((shot.unixUtc - shot.pathStartUtc) / 60).toFloat())
    }
    val t = shot.pathStartUtc + offset.roundToInt() * 60.0
    var pan by remember { mutableStateOf(Pan()) }
    val horizon = rememberHorizon(model, shot.spot)
    val title =
        when {
            shot.eclipse != null -> eclipseName(shot.eclipse.first, shot.eclipse.second.kind)
            shot.target != null ->
                "${s.name} behind ${if (shot.target.name.startsWith("Peak")) shot.target.name else "the landmark"}"
            else -> "${s.name} over the horizon"
        }
    Page {
        Box(Modifier.fillMaxWidth().aspectRatio(0.9f).background(Shade.night)) {
            if (horizon == null) {
                Box(
                    Modifier.align(Alignment.Center)
                        .background(Shade.print)
                        .padding(horizontal = 12.dp)
                ) {
                    HorizonStatus(model, shot.spot, "Developing the shot…")
                }
            } else {
                Photo(shot, horizon, t, focal, zone, model.sky, pan) { pan = it }
            }
        }
        Muted("Drag to look around · pinch to zoom · double tap to aim again", small = true)
        Step(
            "◉",
            if (shot.eclipse != null) "The eclipse"
            else if (shot.target != null) "The shot" else "The sky",
        )
        Title(title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TIME", fontFamily = Mono, fontSize = 11.sp, color = Shade.muted)
            Slider(
                offset,
                { offset = it },
                Modifier.weight(1f).padding(horizontal = 10.dp),
                valueRange = 0f..minutes.toFloat(),
                colors = sliderColors(),
            )
            Text(
                clock(t, zone),
                Modifier.width(52.dp),
                fontFamily = Mono,
                fontSize = 15.sp,
                color = Shade.ink,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in LENSES) Chip("$l mm", l == focal, Modifier.weight(1f)) {
                // A new lens aims the view again: the pan of another lens can turn the subject
                // out of a narrow view.
                focal = l
                pan = Pan()
            }
        }
        val body = bodyPosition(s.body, t, shot.spot)
        val fraction = moonIlluminatedFraction(t)
        val rows = buildList {
            add("When" to dateTime(t, zone))
            add(
                "Light" to
                    if (totality(shot, t)) "Totality: the sky is dark"
                    else light(t, shot.spot).label
            )
            add(
                s.name to
                    "${deg(body.azimuthDeg)} · ${deg(body.altitudeDeg)} up" +
                        if (s.id == SubjectId.MOON) " · ${percent(fraction)} lit" else ""
            )
            add(
                "From" to
                    "%.5f°, %.5f°"
                        .format(Locale.ROOT, shot.spot.latitudeDeg, shot.spot.longitudeDeg) +
                        (horizon?.let { " · ${it.groundM.roundToInt()} m" } ?: "")
            )
            shot.target?.let {
                add(
                    "Landmark" to
                        "${km(it.distanceM)} · ${deg(it.direction.azimuthDeg)} · ${deg(it.direction.altitudeDeg, 2)} up"
                )
            }
            shot.eclipse?.let { (e, local) ->
                add(
                    (if (e.solar) "Covered now" else "In the umbra now") to
                        percent(coveredNow(shot, t))
                )
                for (p in local.phases) add(
                    p.label to "${clock(p.unixUtc, zone)} · ${deg(p.altitudeDeg)} up"
                )
            }
            shot.riseUtc?.let { add("Rises" to "${clock(it, zone)} over the terrain") }
            shot.setUtc?.let { add("Sets" to "${clock(it, zone)} behind the terrain") }
            add("Lens" to "$focal mm · ${deg(lensSpanDeg(focal))} wide")
        }
        Exif(rows)
        ButtonRow {
            ShotButton("Navigate", Modifier.weight(1f), primary = true) {
                val lat = "%.5f".format(Locale.ROOT, shot.spot.latitudeDeg)
                val lon = "%.5f".format(Locale.ROOT, shot.spot.longitudeDeg)
                // A geo URI opens the map app of the phone; it has the place, not more.
                val intent = Intent(Intent.ACTION_VIEW, "geo:$lat,$lon?q=$lat,$lon".toUri())
                try {
                    context.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon"
                                .toUri(),
                        )
                    )
                }
            }
            ShotButton("On the map", Modifier.weight(1f)) { model.go(Screen.ShotMapView(shot)) }
        }
    }
}

/** The photo of a shot, with the gestures of the pan. */
@Composable
private fun Photo(
    shot: Shot,
    horizon: Horizon,
    t: Double,
    focal: Int,
    zone: java.time.ZoneId,
    sky: Sky?,
    pan: Pan,
    onPan: (Pan) -> Unit,
) {
    val density = LocalDensity.current.density
    val painter = remember { PhotoPainter(density) }
    // The sky changes with the time; a pan changes only the view, so a drag calculates no sky.
    val track = remember(shot, zone) { trackOf(shot, zone) }
    val base =
        remember(shot, horizon, t, focal, zone, sky, track) {
            frameOf(shot, horizon, t, focal, false, zone, sky, track = track)
        }
    val frame = remember(base, pan) { base.withView(viewOf(shot, focal, false, pan)) }
    // The bright stars twinkle: a slow clock for the drawing, only at night.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(frame.stars != null) {
        while (isActive && frame.stars != null) {
            now = System.currentTimeMillis()
            delay(120)
        }
    }
    var width by remember { mutableFloatStateOf(1f) }
    val current by androidx.compose.runtime.rememberUpdatedState(pan)
    val currentFocal by androidx.compose.runtime.rememberUpdatedState(focal)
    Canvas(
        Modifier.fillMaxWidth()
            .aspectRatio(0.9f)
            .pointerInput(Unit) {
                detectTransformGestures { _, move, zoom, _ ->
                    val p = current
                    val k = viewSpanDeg(currentFocal, false, p) / width
                    onPan(
                        p.copy(
                            azimuthDeg = p.azimuthDeg - move.x * k,
                            altitudeDeg = p.altitudeDeg + move.y * k,
                            zoom = (p.zoom * zoom).coerceIn(0.25, 4.0),
                        )
                    )
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onPan(Pan()) }) }
    ) {
        width = size.width
        drawIntoCanvas { c ->
            val r = RectF(0f, 0f, size.width, size.height)
            c.nativeCanvas.save()
            c.nativeCanvas.clipRect(r)
            painter.draw(c.nativeCanvas, r, frame, now)
            c.nativeCanvas.restore()
        }
    }
}
