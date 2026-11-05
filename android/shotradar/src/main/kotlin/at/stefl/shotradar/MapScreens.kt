package at.stefl.shotradar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.stefl.almucantar.core.AlignmentLine
import at.stefl.almucantar.core.AlignmentLineOptions
import at.stefl.almucantar.core.EclipseKind
import at.stefl.almucantar.core.ElevationGrid
import at.stefl.almucantar.core.LinePoint
import at.stefl.almucantar.core.MoonOptions
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.SightOptions
import at.stefl.almucantar.core.Site
import at.stefl.almucantar.core.alignmentLine
import at.stefl.almucantar.core.bodyPosition
import at.stefl.almucantar.core.eclipseCentralLine
import at.stefl.almucantar.core.lineOfSight
import at.stefl.almucantar.core.moonPosition
import at.stefl.almucantar.core.sight
import at.stefl.almucantar.shared.deviceLocation
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The screens of shotradar on the map: the landmark, the spot or the place, the line of the spots,
// the map of a shot, and the path of an eclipse on the map of the Earth.

private const val LINE_DISTANCE_M = 30_000.0
private val ORANGE = Color(0xF2E8955E)

/** The map with the search of places at the top and a card at the bottom. */
@Composable
fun MapPage(
    model: Model,
    marks: MapMarks,
    onTap: (Observer, androidx.compose.ui.geometry.Offset) -> Unit = { _, _ -> },
    card: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        ShotMap(model.map, marks, onTap = onTap)
        Column(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .padding(start = 12.dp, end = 12.dp, top = 76.dp, bottom = 12.dp)
        ) {
            PlaceSearch(model)
            Box(Modifier.weight(1f))
            Text(
                "© OpenStreetMap contributors",
                Modifier.align(Alignment.End)
                    .background(Color(0xCCFFFFFF))
                    .padding(horizontal = 4.dp),
                fontSize = 10.sp,
                color = Shade.ink,
            )
            Print(Modifier.fillMaxWidth().heightIn(max = 380.dp), padding = 16.dp) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = card,
                )
            }
        }
    }
}

/** The search of the places of the base map: a tap on a result moves the map there. */
@Composable
private fun PlaceSearch(model: Model) {
    var text by remember { mutableStateOf("") }
    val found = remember(text, model.baseMap) { model.baseMap?.search(text, 5).orEmpty() }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth()
                .background(Shade.print, RoundedCornerShape(2.dp))
                .border(1.dp, Shade.line, RoundedCornerShape(2.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            if (text.isEmpty()) Text("Search a place", color = Shade.muted, fontSize = 15.sp)
            BasicTextField(
                text,
                { text = it },
                Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(color = Shade.ink, fontSize = 15.sp),
            )
        }
        for (p in found) {
            Text(
                "${p.name}  ${p.country}",
                Modifier.fillMaxWidth()
                    .background(Shade.print)
                    .clickable {
                        model.map.center(p.observer, 11.0)
                        text = ""
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                color = Shade.ink,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
fun LandmarkScreen(model: Model) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { model.landmark?.let { model.map.center(it.observer) } }
    val l = model.landmark
    MapPage(
        model,
        MapMarks(landmark = l?.observer),
        onTap = { at, _ ->
            // At a large zoom the radius is small, so the user can tap a tower in a valley.
            val radius = min(300.0, 14 * model.map.metersPerPixel())
            scope.launch {
                val top = model.summit(at, radius) ?: at
                model.landmark = Point(top.latitudeDeg, top.longitudeDeg, model.nameNear(at), null)
                model.save()
                val ground = model.ground(top)
                if (model.landmark?.observer == top) {
                    model.landmark = model.landmark?.copy(groundM = ground)
                    model.save()
                }
            }
        },
    ) {
        Step("02", "The landmark")
        Title(
            if (l == null) "Tap the landmark"
            else if (l.name.isNotEmpty()) "Near ${l.name}" else "Your landmark",
            size = 22,
        )
        if (l == null) {
            Muted("A summit, a tower, a church. A tap snaps to the highest point nearby.")
        } else {
            Exif(
                listOf(
                    "Ground" to (l.groundM?.let { "${it.roundToInt()} m" } ?: "…"),
                    "Place" to "%.4f°, %.4f°".format(l.latitudeDeg, l.longitudeDeg),
                )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("A building on it", Modifier.weight(1f))
                Chip("−", false) {
                    model.extraM = max(0, model.extraM - 10)
                    model.save()
                }
                Text("  ${model.extraM} m  ", fontFamily = Mono, color = Shade.ink)
                Chip("+", false) {
                    model.extraM = min(900, model.extraM + 10)
                    model.save()
                }
            }
        }
        ShotButton(
            "Next: from where?",
            Modifier.fillMaxWidth(),
            primary = true,
            enabled = l?.groundM != null,
        ) {
            model.go(Screen.From)
        }
    }
}

@Composable
fun SpotScreen(model: Model, purpose: Screen.Purpose, locationGranted: Boolean) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var hint by remember { mutableStateOf("Tap the map, or use the location of your phone.") }
    LaunchedEffect(Unit) { model.spot?.let { model.map.center(it.observer) } }
    val s = model.spot
    fun setSpot(at: Observer, name: String) {
        model.spot = Point(at.latitudeDeg, at.longitudeDeg, name, null)
        model.save()
        scope.launch {
            val ground = model.ground(at)
            if (model.spot?.observer == at) {
                model.spot = model.spot?.copy(groundM = ground)
                model.save()
            }
        }
    }
    MapPage(
        model,
        MapMarks(
            landmark = if (purpose == Screen.Purpose.DATES) model.landmark?.observer else null,
            spot = s?.observer,
        ),
        onTap = { at, _ -> setSpot(at, model.nameNear(at)) },
    ) {
        val dates = purpose == Screen.Purpose.DATES
        Step(if (dates) "03" else "01", if (dates) "Your spot" else "The place")
        Title(
            when {
                s != null -> if (s.name.isNotEmpty()) "Near ${s.name}" else "Your spot"
                dates -> "Where do you stand?"
                else -> "Where are you, or where will you be?"
            },
            size = 22,
        )
        if (s == null) Muted(hint)
        else
            Exif(
                listOf(
                    "Ground" to (s.groundM?.let { "${it.roundToInt()} m" } ?: "…"),
                    "Place" to "%.4f°, %.4f°".format(s.latitudeDeg, s.longitudeDeg),
                )
            )
        ButtonRow {
            ShotButton("My location", Modifier.weight(1f)) {
                if (!locationGranted) {
                    hint = "No location access. Tap the map."
                } else {
                    scope.launch {
                        val at = deviceLocation(context)
                        if (at == null) hint = "No location. Tap the map."
                        else {
                            model.map.center(at, 12.0)
                            setSpot(at, "your location")
                        }
                    }
                }
            }
            ShotButton(
                when (purpose) {
                    Screen.Purpose.DATES -> "Find the dates"
                    Screen.Purpose.ECLIPSE -> "Use this place"
                    Screen.Purpose.PLACE -> "Next: the days"
                },
                Modifier.weight(1f),
                primary = true,
                enabled = s?.groundM != null || (purpose == Screen.Purpose.ECLIPSE && s != null),
            ) {
                when (purpose) {
                    Screen.Purpose.ECLIPSE -> model.back()
                    Screen.Purpose.DATES -> model.go(Screen.Dates)
                    Screen.Purpose.PLACE -> model.go(Screen.When)
                }
            }
        }
    }
}

/** The line of spots at a time, and which spots have a clear view of the landmark. */
private class Spots(val line: AlignmentLine?, val clear: Map<LinePoint, Boolean>)

@Composable
fun SpotsScreen(model: Model, moment: Double) {
    val landmark = model.landmark ?: return
    val at = landmark.observer
    val target = landmark.site(model.extraM.toDouble()) ?: return
    val s = subjectOf(model.subject)
    val zone = model.zoneAt(at)
    val context = LocalContext.current
    var shift by remember { mutableFloatStateOf(0f) }
    var t by remember { mutableStateOf(moment) }
    var selected by remember { mutableStateOf<LinePoint?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    LaunchedEffect(Unit) { model.map.center(at, max(model.map.zoom, 11.0)) }
    val grids by
        produceState<List<ElevationGrid>?>(null) {
            value =
                withContext(Dispatchers.IO) {
                    Grids.around(context, at, LINE_DISTANCE_M, fine = true)
                }
        }
    val spots by
        produceState<Spots?>(null, t, grids) {
            val g = grids ?: return@produceState
            value = null
            selected = null
            value =
                withContext(Dispatchers.Default) {
                    val body = bodyPosition(s.body, t, at)
                    if (body.altitudeDeg < -0.5) return@withContext Spots(null, emptyMap())
                    try {
                        val line =
                            alignmentLine(
                                s.body,
                                target,
                                t,
                                g,
                                AlignmentLineOptions(
                                    maxDistanceM = LINE_DISTANCE_M,
                                    stepM = 50.0,
                                    eyeHeightM = EYE_HEIGHT_M,
                                ),
                            )
                        val clear =
                            line.spots.associateWith { p ->
                                val eye =
                                    Site(
                                        p.place.latitudeDeg,
                                        p.place.longitudeDeg,
                                        p.groundElevationM + EYE_HEIGHT_M,
                                    )
                                g.isNotEmpty() &&
                                    lineOfSight(
                                            eye,
                                            target,
                                            g,
                                            SightOptions(
                                                targetMarginM = targetMarginM(p.distanceM)
                                            ),
                                        )
                                        .visible
                            }
                        Spots(line, clear)
                    } catch (_: IllegalArgumentException) {
                        Spots(null, emptyMap())
                    }
                }
        }
    val line = spots?.line
    val toward = line?.points?.firstOrNull()?.body?.azimuthDeg
    val marks =
        MapMarks(
            landmark = at,
            lines =
                buildList {
                    if (line != null)
                        add(
                            MapLine(
                                line.points.map { it.place },
                                Shade.rose.copy(alpha = 0.8f),
                                dash = true,
                            )
                        )
                    if (toward != null)
                        add(MapLine(listOf(at, destination(at, toward, 4000.0)), ORANGE))
                },
            dots =
                line
                    ?.spots
                    ?.map { MapDot(it.place, spots?.clear?.get(it) == true, it == selected) }
                    .orEmpty(),
        )
    MapPage(
        model,
        marks,
        onTap = { _, point ->
            val l = line ?: return@MapPage
            var best: LinePoint? = null
            var bestPx = 28 * density
            for (p in l.spots) {
                val q = model.map.screen(p.place)
                val d = hypot(q.x - point.x, q.y - point.y)
                if (d < bestPx) {
                    best = p
                    bestPx = d
                }
            }
            selected = best
        },
    ) {
        val body = bodyPosition(s.body, t, at)
        val clear = spots?.clear?.values?.count { it } ?: 0
        val summary =
            when {
                spots == null -> "…"
                line == null -> if (body.altitudeDeg < -0.5) "below the horizon" else "none"
                else -> "$clear clear · ${line.spots.size - clear} blocked"
            }
        Step("04", "The spots")
        Row(verticalAlignment = Alignment.Bottom) {
            Title(clock(t, zone), size = 26)
            Text("  ${date(t, zone)}", color = Shade.muted, fontSize = 14.sp)
        }
        Slider(
            shift,
            { shift = it },
            valueRange = -120f..120f,
            steps = 119,
            onValueChangeFinished = { t = moment + (shift.roundToInt() / 2 * 2) * 60.0 },
            colors = sliderColors(),
        )
        Exif(
            listOf(
                s.name to "${deg(body.azimuthDeg, 0)} · ${deg(body.altitudeDeg)} up",
                "Spots" to summary,
            )
        )
        val p = selected
        if (p == null) Muted("The full dots have a clear view of the landmark. Tap one.")
        else {
            Text(
                "A spot ${km(p.distanceM)} from the landmark",
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = Shade.ink,
            )
            Muted(
                "${p.groundElevationM.roundToInt()} m · " +
                    if (spots?.clear?.get(p) == true) "clear view"
                    else "the terrain blocks the view"
            )
            ShotButton("See the shot", Modifier.fillMaxWidth(), primary = true) {
                val eye =
                    Site(
                        p.place.latitudeDeg,
                        p.place.longitudeDeg,
                        p.groundElevationM + EYE_HEIGHT_M,
                    )
                val view = sight(eye, target)
                val tg =
                    Target(
                        view.direction,
                        if (landmark.name.isNotEmpty()) "Landmark near ${landmark.name}"
                        else "Landmark",
                        view.distanceM,
                        target.elevationM,
                        at,
                    )
                model.go(
                    Screen.ShotView(Shot(s.id, t, p.place, tg, 0.0, p.body, t - 3600, t + 3600))
                )
            }
        }
    }
}

@Composable
fun ShotMapScreen(model: Model, shot: Shot) {
    val s = subjectOf(shot.subject)
    val body = remember(shot) { bodyPosition(s.body, shot.unixUtc, shot.spot) }
    LaunchedEffect(Unit) {
        val target = shot.target
        if (target != null) model.map.fit(shot.spot, target.place)
        else model.map.center(shot.spot, 12.0)
    }
    val zone = model.zoneAt(shot.spot)
    MapPage(
        model,
        MapMarks(
            spot = shot.spot,
            landmark = shot.target?.place,
            lines =
                listOf(
                    MapLine(
                        listOf(
                            shot.spot,
                            destination(
                                shot.spot,
                                body.azimuthDeg,
                                (shot.target?.distanceM ?: 5000.0) + 3000,
                            ),
                        ),
                        ORANGE,
                    )
                ),
        ),
    ) {
        Step("◉", "On the map")
        Title("${s.name} at ${clock(shot.unixUtc, zone)}", size = 22)
        Muted(
            "The line goes from your spot${if (shot.target != null) " over the landmark" else ""} to the " +
                "${s.name.lowercase()}, at the azimuth ${deg(body.azimuthDeg)}."
        )
        ShotButton("Back to the shot", Modifier.fillMaxWidth()) { model.back() }
    }
}

/** The point under the moon at a time: the moon is up within 90° of it. */
private fun subLunarPoint(t: Double): Observer {
    var best = Observer(0.0, 0.0)
    var high = Double.NEGATIVE_INFINITY
    val airless = MoonOptions(refraction = false)
    fun search(lat0: Double, lon0: Double, span: Double, step: Double) {
        var lat = max(-89.0, lat0 - span)
        while (lat <= min(89.0, lat0 + span)) {
            var lon = lon0 - span
            while (lon <= lon0 + span) {
                val at = Observer(lat, ((lon + 180) % 360 + 360) % 360 - 180)
                val a = moonPosition(t, at, airless).altitudeDeg
                if (a > high) {
                    high = a
                    best = at
                }
                lon += step
            }
            lat += step
        }
    }
    search(0.0, 0.0, 180.0, 10.0)
    search(best.latitudeDeg, best.longitudeDeg, 10.0, 1.0)
    return best
}

/** The marks of an eclipse on the map of the Earth, and the center of the view. */
private class EclipseMarks(val lines: List<MapLine>, val dots: List<MapDot>, val center: Observer)

@Composable
fun EclipseScreen(model: Model, e: Eclipse) {
    val marks by
        produceState<EclipseMarks?>(null, e) {
            value =
                withContext(Dispatchers.Default) {
                    val lines = mutableListOf<MapLine>()
                    val dots = mutableListOf<MapDot>()
                    var center = model.spot?.observer ?: Observer(30.0, 0.0)
                    if (e.solar && e.peakPlace != null) {
                        val path = eclipseCentralLine(e.peakUtc, 60.0)
                        if (path.size > 1) {
                            val radius = path.sumOf { abs(it.umbraRadiusKm) } / path.size
                            val places = path.map { it.place }
                            val total = e.kind == EclipseKind.TOTAL
                            lines +=
                                MapLine(
                                    places,
                                    if (total) Color(0x521D1426) else Color(0x4D8A4A55),
                                    widthDp = 4f,
                                    widthM = 2 * radius * 1000,
                                )
                            lines += MapLine(places, Shade.ink, widthDp = 1.5f)
                        }
                        dots += MapDot(e.peakPlace, true)
                        center = e.peakPlace
                    } else if (!e.solar) {
                        // The moon is up in the half of the Earth around the point under the moon.
                        val under = subLunarPoint(e.peakUtc)
                        val ring =
                            (0..72).map { destination(under, it * 5.0, Math.PI / 2 * 6_371_008.8) }
                        lines +=
                            MapLine(ring, Shade.rose.copy(alpha = 0.75f), widthDp = 2f, dash = true)
                        dots += MapDot(under, true)
                        center = under
                    }
                    EclipseMarks(lines, dots, center)
                }
        }
    LaunchedEffect(marks) {
        marks?.let {
            model.map.center(
                Observer(it.center.latitudeDeg.coerceIn(-60.0, 60.0), it.center.longitudeDeg),
                1.0,
            )
        }
    }
    val spot = model.spot
    val at = spot?.observer
    val zone = model.zoneAt(at)
    val local by
        produceState<LocalEclipse?>(null, at) {
            value = at?.let { withContext(Dispatchers.Default) { localEclipse(e, it) } }
        }
    val horizon = rememberHorizon(model, if (local?.visible == true) at else null)
    val scope = rememberCoroutineScope()
    MapPage(
        model,
        MapMarks(spot = at, lines = marks?.lines.orEmpty(), dots = marks?.dots.orEmpty()),
        onTap = { p, _ ->
            model.spot = Point(p.latitudeDeg, p.longitudeDeg, model.nameNear(p), null)
            model.save()
            scope.launch {
                val g = model.ground(p)
                if (model.spot?.observer == p) {
                    model.spot = model.spot?.copy(groundM = g)
                    model.save()
                }
            }
        },
    ) {
        Step("02", date(e.peakUtc, zone))
        Title(eclipseName(e), size = 22)
        Muted(
            if (e.solar) {
                if (e.peakPlace != null)
                    "The dark line is the center of the shadow, the band its " +
                        "${if (e.kind == EclipseKind.TOTAL) "total" else "annular"} part."
                else "No place sees the sun fully covered."
            } else "The moon is up inside the dashed line at the peak.",
            small = true,
        )
        val l = local
        when {
            at == null -> Muted("Tap the map to choose your place.")
            l == null -> Muted("Your place sees no part of this eclipse.")
            else -> {
                val subject = if (e.solar) SubjectId.SUN else SubjectId.MOON
                val terrain =
                    when {
                        !l.visible -> "below the horizon"
                        horizon == null -> "checking…"
                        else -> {
                            val free = l.phases.count { overTerrain(horizon, subject, it.unixUtc) }
                            when (free) {
                                0 -> "behind the terrain"
                                l.phases.size -> "all phases over it"
                                else -> "$free of ${l.phases.size} phases over it"
                            }
                        }
                    }
                Exif(
                    listOf(
                        "Here" to
                            (ECLIPSE_NAMES[l.kind]!! +
                                if (e.solar) " · ${percent(l.coverage)}" else ""),
                        "Peak" to "${clock(l.peak.unixUtc, zone)} · ${deg(l.peak.altitudeDeg)} up",
                        "Terrain" to terrain,
                    )
                )
                ShotButton(
                    "See the shot",
                    Modifier.fillMaxWidth(),
                    primary = true,
                    enabled = l.visible,
                ) {
                    val best =
                        if (l.peak.altitudeDeg > 0) l.peak
                        else l.phases.first { it.altitudeDeg > 0 }
                    model.go(
                        Screen.ShotView(
                            Shot(
                                subject,
                                best.unixUtc,
                                at,
                                null,
                                0.0,
                                bodyPosition(subjectOf(subject).body, best.unixUtc, at),
                                l.phases.first().unixUtc,
                                l.phases.last().unixUtc,
                                eclipse = e to l,
                            )
                        )
                    )
                }
            }
        }
    }
}
