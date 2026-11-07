package at.stefl.alidade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.SightOptions
import at.stefl.almucantar.core.bodyPosition
import at.stefl.almucantar.core.lineOfSight
import at.stefl.almucantar.core.moonIlluminatedFraction
import at.stefl.almucantar.core.sight
import at.stefl.almucantar.shared.PrivacyLink
import at.stefl.almucantar.shared.wallClockTime
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The screens of alidade that are pages: the start, the subject, the choices, the lists of
// dates, moments, shots, and eclipses. The screens on the map are in MapScreens.kt, the shot in
// ShotScreen.kt.

/** A page that scrolls, below the bar. */
@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 84.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** A small picture of a subject, like the label on a box of film. */
@Composable
fun SubjectArt(id: SubjectId?, modifier: Modifier = Modifier, side: Dp = 44.dp) {
    Box(modifier.size(side).background(Shade.night, RoundedCornerShape(2.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.minDimension / 40f
            val c = Offset(size.width / 2, size.height / 2)
            when (id) {
                null -> {
                    drawCircle(Color(0x40F0F2FF), 15 * u, c)
                    drawCircle(Color(0xFFFFFAF0), 10 * u, c)
                    drawCircle(Shade.night, 10 * u, c + Offset(2.5f * u, -1 * u))
                }
                SubjectId.MOON -> {
                    drawCircle(Shade.cream, 11 * u, c)
                    drawCircle(Color(0xFFE9DCC0), 2.5f * u, c + Offset(-4 * u, -3 * u))
                    drawCircle(Color(0xFFE9DCC0), 3 * u, c + Offset(3 * u, 3 * u))
                }
                SubjectId.MILKY_WAY -> {
                    drawLine(
                        Color(0x73E7DCF2),
                        c + Offset(-16 * u, 10 * u),
                        c + Offset(16 * u, -12 * u),
                        7 * u,
                        StrokeCap.Round,
                    )
                    for ((x, y, r) in
                        listOf(
                            Triple(-10f, 6f, 1.2f),
                            Triple(-2f, 0f, 1.6f),
                            Triple(5f, -4f, 1f),
                            Triple(11f, -9f, 1.3f),
                            Triple(-6f, -6f, 0.9f),
                            Triple(8f, 5f, 0.9f),
                        )) {
                        drawCircle(Shade.cream, r * u, c + Offset(x * u, y * u))
                    }
                }
                SubjectId.SUN -> {
                    val sun = Color(0xFFFFD9A0)
                    drawCircle(sun, 8 * u, c)
                    for (k in 0 until 8) {
                        val a = Math.toRadians(45.0 * k)
                        val d = Offset(cos(a).toFloat(), sin(a).toFloat())
                        drawLine(sun, c + d * 12f * u, c + d * 16f * u, 2 * u, StrokeCap.Round)
                    }
                }
                SubjectId.SATURN -> {
                    val color = Color(0xFFF6DCB0)
                    drawCircle(color, 7 * u, c)
                    rotate(-18f, c) {
                        drawOval(
                            color,
                            c + Offset(-15 * u, -4.5f * u),
                            androidx.compose.ui.geometry.Size(30 * u, 9 * u),
                            style = Stroke(1.6f * u),
                        )
                    }
                }
                else -> {
                    val color =
                        when (id) {
                            SubjectId.VENUS -> Color(0xFFFFFCEC)
                            SubjectId.JUPITER -> Color(0xFFFFF0D6)
                            else -> Color(0xFFFFAA78)
                        }
                    drawCircle(color.copy(alpha = 0.18f), 12 * u, c)
                    drawCircle(color, 6 * u, c)
                }
            }
        }
    }
}

/** A big choice: a way through the app. */
@Composable
fun Choice(
    letter: String,
    label: String,
    title: String,
    text: String,
    art: SubjectId? = SubjectId.MOON,
    showArt: Boolean = false,
    onClick: () -> Unit,
) {
    Print(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Step(letter, label)
                    Title(title, size = 22)
                }
                if (showArt) SubjectArt(art)
            }
            Muted(text)
        }
    }
}

@Composable
fun HomeScreen(model: Model) {
    Page {
        Gap(8)
        Title("Plan the shot.", size = 40)
        Muted(
            "The full moon behind a summit, the Milky Way over a ridge, the sun in the gap of the " +
                "mountains. Start from what you want in the frame, or from where you are."
        )
        Choice(
            "A",
            "From where I am",
            "I am here, or I will be",
            "Alidade reads the real horizon of the place: when the moon, the Milky Way, the sun, " +
                "and the planets rise over it, and which peaks they pass.",
            SubjectId.MILKY_WAY,
            showArt = true,
        ) {
            model.flow = Flow.PLACE
            model.go(Screen.Spot(Screen.Purpose.PLACE))
        }
        Choice(
            "B",
            "Behind a landmark",
            "I have a landmark",
            "Pick the subject and the peak or the tower. Alidade finds when the subject is behind " +
                "it, and where to stand.",
            SubjectId.MOON,
            showArt = true,
        ) {
            model.flow = Flow.LANDMARK
            model.go(Screen.Subject)
        }
        Choice(
            "C",
            "An eclipse",
            "The next eclipses",
            "The solar and the lunar eclipses: the path of the shadow on the map of the Earth, and " +
                "how much your place sees of them, over the real horizon.",
            null,
            showArt = true,
        ) {
            model.flow = Flow.ECLIPSE
            model.go(Screen.Eclipses)
        }
        Favorites(model)
        PrivacyLink(color = Shade.inkSoft)
    }
}

/** The favorite shots and places of the start page. A tap opens one, the cross removes it. */
@Composable
private fun Favorites(model: Model) {
    if (model.favoriteShots.isEmpty() && model.favoritePlaces.isEmpty()) {
        Muted(
            "Tap the star of a shot or of a place to keep it here.",
            small = true,
        )
        return
    }
    if (model.favoriteShots.isNotEmpty()) {
        Text("Your shots", style = Section)
        for (shot in model.favoriteShots.toList()) {
            val zone = model.zoneAt(shot.spot)
            FavoriteRow(
                art = {
                    SubjectArt(
                        if (shot.eclipse != null && shot.subject == SubjectId.SUN) null
                        else shot.subject,
                        side = 40.dp,
                    )
                },
                title = shotTitle(shot),
                detail = "${date(shot.unixUtc, zone)} · ${clock(shot.unixUtc, zone)}",
                what = "shot",
                onOpen = { model.openShot(shot) },
                onRemove = { model.toggleFavorite(shot) },
            )
        }
    }
    if (model.favoritePlaces.isNotEmpty()) {
        Text("Your places", style = Section)
        for (place in model.favoritePlaces.toList()) {
            FavoriteRow(
                art = { SubjectArt(SubjectId.MILKY_WAY, side = 40.dp) },
                title = if (place.name.isNotEmpty()) "Near ${place.name}" else "A place",
                detail =
                    "%.4f°, %.4f°".format(Locale.ROOT, place.latitudeDeg, place.longitudeDeg) +
                        (place.groundM?.let { " · ${it.toInt()} m" } ?: ""),
                what = "place",
                onOpen = { model.openPlace(place) },
                onRemove = { model.toggleFavorite(place) },
            )
        }
    }
}

@Composable
private fun FavoriteRow(
    art: @Composable () -> Unit,
    title: String,
    detail: String,
    what: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Print(Modifier.fillMaxWidth(), onClick = onOpen, padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            art()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontFamily = Serif,
                    fontSize = 17.sp,
                    color = Shade.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    color = Shade.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onRemove) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = "Remove the $what from the favorites",
                    tint = Shade.muted,
                )
            }
        }
    }
}

@Composable
fun SubjectScreen(model: Model) {
    Page {
        Step("01", "The subject")
        Title("What do you want in the frame?")
        // Seven boxes of the same size, two in a row.
        for (row in SUBJECTS.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in row) {
                    val active = s.id == model.subject
                    Box(Modifier.weight(1f)) {
                        Print(
                            // An apricot frame marks the subject, as in the PWA. It draws over the
                            // background of the card.
                            Modifier.fillMaxWidth().let {
                                if (active) it.border(2.dp, Shade.apricot, RoundedCornerShape(2.dp))
                                else it
                            },
                            onClick = {
                                model.subject = s.id
                                model.save()
                            },
                            padding = 12.dp,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SubjectArt(s.id)
                                Text(
                                    s.name,
                                    fontFamily = Serif,
                                    fontSize = 18.sp,
                                    color = if (active) Shade.rose else Shade.ink,
                                    fontWeight =
                                        if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                                Text(
                                    s.note,
                                    color = Shade.muted,
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    minLines = 2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        ShotButton("Next: the landmark", Modifier.fillMaxWidth(), primary = true) {
            model.go(Screen.Landmark)
        }
    }
}

@Composable
fun FromScreen(model: Model) {
    val s = subjectOf(model.subject)
    val name = model.landmark?.name?.takeIf { it.isNotEmpty() }
    Page {
        Step("03", "From where")
        Title(
            "${s.name} behind ${if (name != null) "the landmark near $name" else "your landmark"}. From where?"
        )
        Choice(
            "A",
            "I know my spot",
            "I stand here",
            "Tap your spot or use your location. Alidade lists the dates of the next 12 months, " +
                "as photos.",
        ) {
            model.go(Screen.Spot(Screen.Purpose.DATES))
        }
        Choice(
            "B",
            "Find me a spot",
            "Where must I stand?",
            "Pick a moment, for example the next rise of the full moon. Alidade draws the line of " +
                "places and the spots with a clear view.",
        ) {
            model.go(Screen.Moments)
        }
    }
}

/** The status of the calculation of a horizon: the bands that are ready. */
@Composable
fun HorizonStatus(model: Model, at: Observer, what: String) {
    val done = model.progressOf(at)
    Developing("$what ${if (done < BANDS.size) "${done + 1} of ${BANDS.size}" else ""}".trim())
}

/** The horizon of a place, or null while it is in work or if it failed. */
@Composable
fun rememberHorizon(model: Model, at: Observer?): Horizon? {
    // A calculated horizon is there in the first frame, so a list that needs it keeps its scroll
    // position on "back". A new place must not show the horizon of the last place.
    val state = remember(at) { mutableStateOf(at?.let(model::horizonIfReady)) }
    LaunchedEffect(at) {
        if (at == null || state.value != null) return@LaunchedEffect
        state.value =
            try {
                model.horizon(at)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
    }
    return state.value
}

@Composable
fun DatesScreen(model: Model) {
    val spot = model.spot ?: return
    val landmark = model.landmark ?: return
    val eye = spot.site(EYE_HEIGHT_M) ?: return
    val top = landmark.site(model.extraM.toDouble()) ?: return
    val s = subjectOf(model.subject)
    val zone = model.zoneAt(spot.observer)
    val view = remember(eye, top) { sight(eye, top) }
    val name = if (landmark.name.isNotEmpty()) "Landmark near ${landmark.name}" else "Your landmark"
    val shots =
        rememberCached(model.results, listOf("dates", model.subject, eye, top, name)) {
            datesFor(model.subject, eye, top, name, model.now())
        }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clear =
        rememberCached(model.results, listOf("clear", eye, top)) {
            if (view.distanceM > 40_000) "too far to check"
            else
                withContext(Dispatchers.IO) {
                    val grids = gridsAlong(context, spot.observer, landmark.observer)
                    if (grids.isEmpty()) "no terrain data"
                    else {
                        val v =
                            lineOfSight(
                                eye,
                                top,
                                grids,
                                SightOptions(targetMarginM = targetMarginM(view.distanceM)),
                            )
                        if (v.visible) "clear" else "blocked ${km(v.blockingDistanceM)} from you"
                    }
                }
        } ?: "checking…"
    val horizon = rememberHorizon(model, spot.observer)
    Page {
        Step("04", "The dates")
        Title("${s.name} behind the landmark, from your spot")
        Exif(
            listOf(
                "Landmark" to "${km(view.distanceM)} · ${top.elevationM.toInt()} m",
                "Direction" to
                    "${deg(view.direction.azimuthDeg)} · ${deg(view.direction.altitudeDeg, 2)} up",
                "The view" to clear,
                "Search" to "the next 12 months",
            )
        )
        val list = shots
        when {
            list == null -> Developing("Searching the next 12 months…")
            horizon == null ->
                HorizonStatus(model, spot.observer, "Reading the horizon of your spot…")
            list.isEmpty() -> {
                val reason =
                    rememberCached(
                        model.results,
                        listOf("reason", model.subject, view.direction, spot.observer),
                    ) {
                        noMatchReason(model.subject, view.direction, spot.observer, model.now())
                    }
                Muted(reason ?: "…")
            }
            else -> {
                Muted(
                    "${list.size} ${if (list.size == 1) "date" else "dates"}. Tap a photo for the shot."
                )
                ContactSheet(model, list, horizon, zone)
            }
        }
    }
}

@Composable
fun MomentsScreen(model: Model) {
    val landmark = model.landmark ?: return
    val at = landmark.observer
    val zone = model.zoneAt(at)
    val s = subjectOf(model.subject)
    val moments =
        rememberCached(model.results, listOf("moments", model.subject, at)) {
            momentsFor(model.subject, at, model.now())
        }
    val note =
        when {
            s.id == SubjectId.MOON -> ", near the full moon"
            s.maxSunDeg != null -> ", in a dark sky"
            else -> ""
        }
    Page {
        Step("03", "The moment")
        Title("When should the ${s.name.lowercase()} be behind the landmark?")
        Muted(
            "The next times when it is low over the horizon, so a landmark can stand in front of it$note."
        )
        val list = moments
        if (list == null) Developing("Searching the next months…")
        else if (list.isEmpty()) Muted("No good moment in the next months.")
        else
            for (m in list) {
                Print(
                    Modifier.fillMaxWidth(),
                    onClick = { model.go(Screen.Spots(m.unixUtc)) },
                    padding = 14.dp,
                ) {
                    Column {
                        Text(
                            date(m.unixUtc, zone),
                            fontFamily = Serif,
                            fontSize = 18.sp,
                            color = Shade.ink,
                        )
                        Text(
                            "${clock(m.unixUtc, zone)} · ${if (m.rising) "after the rise" else "before the set"}",
                            fontFamily = Mono,
                            fontSize = 13.sp,
                            color = Shade.inkSoft,
                        )
                        Muted(
                            m.fraction?.let { "${percent(it)} lit" } ?: light(m.unixUtc, at).label,
                            small = true,
                        )
                    }
                }
            }
        CustomMoment(zone, model.today(zone)) { model.go(Screen.Spots(it)) }
    }
}

/** Another time for the line of spots: a day and a time of the place, from [today]. */
@Composable
private fun CustomMoment(zone: ZoneId, today: LocalDate, onPick: (Double) -> Unit) {
    var day by remember { mutableStateOf(today) }
    var minutes by remember { mutableStateOf(20 * 60) }
    Print(Modifier.fillMaxWidth(), padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Step("…", "Or another time")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StepButton("−", "The day before") { day = day.minusDays(1) }
                Text(
                    day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
                    Modifier.weight(1f),
                    fontFamily = Mono,
                    fontSize = 14.sp,
                    color = Shade.ink,
                )
                StepButton("+", "The next day") { day = day.plusDays(1) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StepButton("−", "15 minutes earlier") { minutes = (minutes - 15 + 1440) % 1440 }
                Text(
                    "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60),
                    Modifier.weight(1f),
                    fontFamily = Mono,
                    fontSize = 14.sp,
                    color = Shade.ink,
                )
                StepButton("+", "15 minutes later") { minutes = (minutes + 15) % 1440 }
            }
            ShotButton("Show the spots", Modifier.fillMaxWidth()) {
                onPick(wallClockTime(day, minutes, zone))
            }
        }
    }
}

@Composable
fun WhenScreen(model: Model) {
    val spot = model.spot ?: return
    val zone = model.zoneAt(spot.observer)
    var from by remember { mutableStateOf(model.fromDay ?: model.today(zone)) }
    var days by remember { mutableStateOf(if (model.fromDay == null) 3 else model.days) }
    Page {
        Step("02", "The days")
        Title("When are you there?")
        Choice("A", "I am here", "Now", "The next 14 days, from today.") {
            model.fromDay = null
            model.days = 14
            model.save()
            model.go(Screen.Place)
        }
        Print(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step("B", "I will be here")
                Title("On these days", size = 22)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StepButton("−", "The day before") { from = from.minusDays(1) }
                    Text(
                        from.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
                        Modifier.weight(1f),
                        fontFamily = Mono,
                        fontSize = 14.sp,
                        color = Shade.ink,
                    )
                    StepButton("+", "The next day") { from = from.plusDays(1) }
                }
                // Two rows of three that fill the width, so all choices show at once.
                for (row in listOf(listOf(1, 2, 3), listOf(7, 14, 30))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (d in row) {
                            Chip(
                                if (d == 1) "1 night" else "$d days",
                                d == days,
                                Modifier.weight(1f),
                                centered = true,
                            ) {
                                days = d
                            }
                        }
                    }
                }
                ShotButton("Find the shots", Modifier.fillMaxWidth(), primary = true) {
                    model.fromDay = from
                    model.days = days
                    model.save()
                    model.go(Screen.Place)
                }
            }
        }
    }
}

/** The shots of "From where I am": the sky of the place, and the shots behind its peaks. */
private class PlaceShots(
    val start: Double,
    val sky: List<Shot>,
    val behind: List<Shot>,
    val peaks: Int,
)

@Composable
fun PlaceScreen(model: Model) {
    val spot = model.spot ?: return
    val at = spot.observer
    val zone = model.zoneAt(at)
    val horizon = rememberHorizon(model, at)
    // From noon of the first day, so its night counts. "Now" is the time of the search, so the
    // cached result keeps its own start.
    val firstDay = model.fromDay
    val shots =
        rememberCached(
            model.results,
            listOf("place", at, firstDay, model.days),
            ready = horizon != null,
        ) {
            val h = horizon!!
            val start = firstDay?.let { wallClockTime(it, 12 * 60, zone) } ?: model.now()
            val end = start + model.days * 86_400.0
            val targets = peakTargets(h)
            PlaceShots(
                start,
                skyFromPlace(h, start, end),
                shotsFromPlace(h, targets, start, end),
                targets.size,
            )
        }
    val start = shots?.start ?: firstDay?.let { wallClockTime(it, 12 * 60, zone) } ?: model.now()
    // Saved with the screen, so "back" from a shot keeps the filter.
    var filter by rememberSaveable { mutableStateOf<SubjectId?>(null) }
    Page {
        Step("03", "The shots")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title(
                "From ${if (spot.name.isNotEmpty()) "near ${spot.name}" else "your place"}",
                Modifier.weight(1f),
            )
            FavoriteButton(model.isFavorite(spot), "place") { model.toggleFavorite(spot) }
        }
        Exif(
            listOf(
                "Days" to "${date(start, zone)} + ${model.days}",
                "Ground" to "${(horizon?.groundM ?: spot.groundM ?: 0.0).toInt()} m",
                "Peaks" to (shots?.peaks?.toString() ?: "…"),
            )
        )
        // The filter: all subjects, or one.
        ChipRow {
            for (id in listOf<SubjectId?>(null) + SUBJECTS.map { it.id }) {
                Chip(id?.let { subjectOf(it).name } ?: "All", filter == id) { filter = id }
            }
        }
        val list = shots
        if (horizon == null) {
            HorizonStatus(model, at, "Reading the horizon of the place…")
            return@Page
        }
        if (list == null) {
            Developing("Searching the shots…")
            return@Page
        }
        val keep = { x: Shot -> filter == null || x.subject == filter }
        // With all subjects, the sky has the first night of each subject; the others are a tap
        // away.
        val sky = if (filter == null) list.sky.distinctBy { it.subject } else list.sky.filter(keep)
        val behind = list.behind.filter(keep)
        Muted("${list.sky.count(keep) + behind.size} shots. Tap a photo.")
        Text("The sky from here", style = Section)
        Muted(
            "Each subject over the real horizon, in a sky that is dark enough for it.",
            small = true,
        )
        if (sky.isEmpty()) Muted("Nothing over the horizon in these days.")
        else
            ContactSheet(model, sky, horizon, zone) { shot ->
                if (filter != null) null
                else {
                    val count = list.sky.count { it.subject == shot.subject } - 1
                    if (count > 0)
                        "+ $count more ${if (count == 1) "night" else "nights"}" to
                            {
                                filter = shot.subject
                            }
                    else null
                }
            }
        Text("Behind a peak", style = Section)
        Muted("The subjects that pass a summit of your horizon.", small = true)
        if (behind.isEmpty()) Muted("No subject passes a peak of this horizon in these days.")
        else ContactSheet(model, behind, horizon, zone)
    }
}

/**
 * The contact sheet: a small photo for each shot, two in a row, all of the same size. A tap opens
 * the shot. [more] gives, for a shot, a link to more nights of the same subject.
 */
@Composable
fun ContactSheet(
    model: Model,
    shots: List<Shot>,
    horizon: Horizon,
    zone: ZoneId,
    more: (Shot) -> Pair<String, () -> Unit>? = { null },
) {
    for (row in shots.chunked(2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (shot in row) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Print(
                        Modifier.fillMaxWidth(),
                        onClick = { model.go(Screen.ShotView(shot)) },
                        padding = 6.dp,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Thumbnail(model, shot, horizon, zone, model.sky)
                            Caption(shot, zone)
                        }
                    }
                    more(shot)?.let { (label, action) ->
                        ShotButton(label, Modifier.fillMaxWidth(), onClick = action)
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Thumbnail(model: Model, shot: Shot, horizon: Horizon, zone: ZoneId, sky: Sky?) {
    val density = LocalDensity.current.density
    val painter = remember { PhotoPainter(density) }
    // A sheet has many photos, so their frames are calculated in the background, and kept for
    // "back" from a shot.
    val frame =
        rememberCached(model.frames, listOf(shot, horizon.observer, zone, sky != null)) {
            frameOf(
                shot,
                horizon,
                shot.unixUtc,
                if (shot.target != null) 200 else 35,
                true,
                zone,
                sky,
            )
        }
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.5f).background(Shade.night)) {
        val f = frame ?: return@Canvas
        drawIntoCanvas { c ->
            val r = android.graphics.RectF(0f, 0f, size.width, size.height)
            c.nativeCanvas.save()
            c.nativeCanvas.clipRect(r)
            painter.draw(c.nativeCanvas, r, f)
            c.nativeCanvas.restore()
        }
    }
}

@Composable
private fun Caption(shot: Shot, zone: ZoneId) {
    val what =
        when {
            shot.target != null -> shot.target.name
            shot.riseUtc != null -> "rises over the terrain"
            else -> "over the horizon"
        }
    val tags =
        listOf(
            if (shot.subject == SubjectId.MOON)
                "Moon ${percent(moonIlluminatedFraction(shot.unixUtc))}"
            else subjectOf(shot.subject).name,
            light(shot.unixUtc, shot.spot).label,
        )
    Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(
            date(shot.unixUtc, zone),
            fontFamily = Serif,
            fontSize = 15.sp,
            color = Shade.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${clock(shot.unixUtc, zone)} · $what",
            fontFamily = Mono,
            fontSize = 11.sp,
            color = Shade.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            tags.joinToString(" · "),
            fontSize = 11.sp,
            color = Shade.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EclipsesScreen(model: Model) {
    val spot = model.spot
    val at = spot?.observer
    val zone = model.zoneAt(at)
    val events = rememberCached(model.results, listOf("eclipses")) { upcomingEclipses(model.now()) }
    val locals =
        rememberCached(model.results, listOf("eclipses at", events, at), ready = events != null) {
            if (at == null) emptyMap() else events!!.associateWith { localEclipse(it, at) }
        } ?: emptyMap()
    Page {
        Step("01", "The eclipses")
        Title("The next eclipses")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Muted("Seen from")
            Text(
                if (spot == null) "no place yet"
                else if (spot.name.isNotEmpty()) "near ${spot.name}" else "your place",
                Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                color = Shade.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ShotButton(if (spot == null) "Pick your place" else "Change") {
                model.go(Screen.Spot(Screen.Purpose.ECLIPSE))
            }
        }
        val list = events
        if (list == null) {
            Developing("Searching the next eclipses…")
            return@Page
        }
        for (e in list) {
            val local = locals[e]
            var good = false
            val here =
                when {
                    at == null -> "Pick a place to see more"
                    !locals.containsKey(e) -> "…"
                    local == null -> "Not visible here"
                    !local.visible ->
                        if (e.solar) "The sun is down here" else "The moon is down here"
                    else -> {
                        good = true
                        val what =
                            if (e.solar) "${percent(local.coverage)} of the sun"
                            else ECLIPSE_NAMES[local.kind]!!.lowercase()
                        "$what · ${deg(local.peak.altitudeDeg, 0)} up at the peak"
                    }
                }
            Print(
                Modifier.fillMaxWidth(),
                onClick = { model.go(Screen.EclipseMap(e)) },
                padding = 14.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            date(e.peakUtc, zone),
                            fontFamily = Serif,
                            fontSize = 18.sp,
                            color = Shade.ink,
                        )
                        Text(
                            eclipseName(e),
                            fontFamily = Mono,
                            fontSize = 13.sp,
                            color = Shade.inkSoft,
                        )
                        Text(here, fontSize = 13.sp, color = if (good) Shade.good else Shade.muted)
                    }
                    Spacer(Modifier.width(8.dp))
                    SubjectArt(if (e.solar) null else SubjectId.MOON, side = 36.dp)
                }
            }
        }
    }
}

/** True if the subject of a phase is over the terrain of the horizon. */
fun overTerrain(horizon: Horizon, subject: SubjectId, t: Double): Boolean {
    val d = bodyPosition(subjectOf(subject).body, t, horizon.observer)
    val ground = profileAltitude(horizon.skyline, d.azimuthDeg)
    return d.altitudeDeg > (if (ground.isFinite()) ground else 0.0)
}
