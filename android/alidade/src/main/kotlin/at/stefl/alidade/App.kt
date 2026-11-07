package at.stefl.alidade

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.Site
import at.stefl.almucantar.shared.BaseMap
import at.stefl.almucantar.shared.BaseMapStore
import at.stefl.almucantar.shared.MilkyWay
import at.stefl.almucantar.shared.deviceLocation
import at.stefl.almucantar.shared.fallbackPlace
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// The state of alidade and the screens. Every way through the app ends in the shot: the
// first-person photo from the spot, with the terrain, the landmark, and the subject.
//
// - "From where I am": the place and the days, then the sky of the place (each subject over the
//   real horizon) and the shots behind the peaks of its horizon.
// - "I have a landmark": the subject, the landmark on the map, then "I know my spot" (the dates)
//   or "Find me a spot" (a moment, then the line of the spots on the map).
// - "An eclipse": the next eclipses, the path of the shadow on the map of the Earth, and the shot.

/** A place that the user tapped, with the height of the ground once it is known. */
data class Point(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val name: String,
    val groundM: Double?,
) {
    val observer
        get() = Observer(latitudeDeg, longitudeDeg)

    fun site(aboveM: Double): Site? = groundM?.let { Site(latitudeDeg, longitudeDeg, it + aboveM) }
}

const val EYE_HEIGHT_M = 1.6

sealed interface Screen {
    data object Home : Screen

    data object Subject : Screen

    data object Landmark : Screen

    data object From : Screen

    enum class Purpose {
        DATES,
        PLACE,
        ECLIPSE,
    }

    data class Spot(val purpose: Purpose) : Screen

    data object Dates : Screen

    data object Moments : Screen

    data class Spots(val moment: Double) : Screen

    data object When : Screen

    data object Place : Screen

    data class ShotView(val shot: Shot) : Screen

    data class ShotMapView(val shot: Shot) : Screen

    /** The shot in first person through the camera, at the time [unixUtc]. */
    data class Live(val shot: Shot, val unixUtc: Double) : Screen

    data object Eclipses : Screen

    data class EclipseMap(val event: Eclipse) : Screen
}

enum class Flow(val steps: List<String>) {
    LANDMARK(listOf("Subject", "Landmark", "From where", "Shots")),
    PLACE(listOf("Place", "Days", "Shots")),
    ECLIPSE(listOf("Eclipses", "The map", "Shot")),
    /** A favorite of the start page. */
    SAVED(listOf("Saved", "Shot")),
}

/** The step of the strip of film for a screen, or -1. */
private fun stepOf(flow: Flow, screen: Screen): Int =
    when (screen) {
        is Screen.ShotView,
        is Screen.ShotMapView,
        is Screen.Live -> flow.steps.size - 1
        else ->
            when (flow) {
                Flow.LANDMARK ->
                    when (screen) {
                        Screen.Subject -> 0
                        Screen.Landmark -> 1
                        Screen.From,
                        is Screen.Spot,
                        Screen.Moments -> 2
                        is Screen.Spots,
                        Screen.Dates -> 3
                        else -> -1
                    }
                Flow.PLACE ->
                    when (screen) {
                        is Screen.Spot -> 0
                        Screen.When -> 1
                        Screen.Place -> 2
                        else -> -1
                    }
                Flow.ECLIPSE ->
                    when (screen) {
                        Screen.Eclipses,
                        is Screen.Spot -> 0
                        is Screen.EclipseMap -> 1
                        else -> -1
                    }
                Flow.SAVED -> if (screen == Screen.Home) 0 else -1
            }
    }

/**
 * The state of the app: the choices of the user (kept on the disk), and the loaded data. If
 * [persistent] is false (the demo mode of the store screenshots), the model does not read or write
 * the choices.
 */
class Model(context: Context, private val persistent: Boolean = true) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("alidade", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var subject by mutableStateOf(SubjectId.MOON)
    var landmark by mutableStateOf<Point?>(null)
    var extraM by mutableIntStateOf(0)
    var spot by mutableStateOf<Point?>(null)
    /** The days of "From where I am": the first day, or null for now. */
    var fromDay by mutableStateOf<LocalDate?>(null)
    var days by mutableIntStateOf(14)
    /** The map. Without a saved map, it shows the most probable place (see [load]). */
    val map = MapState(0.0, 0.0, 2.0)

    /** True while the map shows a guessed place and the user has chosen no place yet. */
    private var mapGuessed = false

    var baseMap by mutableStateOf<BaseMap?>(null)
    var sky by mutableStateOf<Sky?>(null)

    val stack = mutableStateListOf<Screen>(Screen.Home)
    var flow by mutableStateOf(Flow.LANDMARK)

    /** The time of the demo mode, or null for the clock of the device. */
    var clockUtc by mutableStateOf<Double?>(null)

    /** Now, in seconds since 1970. All screens take the time from here. */
    fun now(): Double = clockUtc ?: (System.currentTimeMillis() / 1000.0)

    /** The day of [now] in [zone]. */
    fun today(zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(now().toLong()).atZone(zone).toLocalDate()

    /**
     * The results of the searches (the dates, the moments, the shots of a place, the eclipses), so
     * "back" from a shot shows them at once. A result that uses the time "now" is kept one hour.
     */
    val results = ResultCache(48, 3600.0, ::now)

    /** The frames of the small photos of the contact sheets, for the same reason. */
    val frames = ResultCache(240, 3600.0, ::now)

    /** The favorite places, the newest first. */
    val favoritePlaces = mutableStateListOf<Point>()

    /** The favorite shots, the newest first. */
    val favoriteShots = mutableStateListOf<Shot>()

    /** False until [loadData] has read the favorites, so a change cannot overwrite them. */
    private var favoritesLoaded = false

    /** The progress of the horizons that are in work: the bands that are ready, by key. */
    val progress = mutableStateMapOf<String, Int>()
    private val horizons = mutableMapOf<String, Deferred<Horizon>>()

    init {
        if (!load() && persistent) {
            // No saved map: the main city of the time zone or of the country of the device. The
            // location of the phone replaces it (centerOnDevice).
            map.center(fallbackPlace(app).observer, 9.0)
            mapGuessed = true
        }
    }

    /**
     * Moves a guessed map to the location of the phone (the last known one first), if the user has
     * chosen no place yet.
     */
    suspend fun centerOnDevice() {
        if (!mapGuessed) return
        val at = deviceLocation(app) ?: return
        if (mapGuessed && spot == null && landmark == null) {
            map.center(at, 11.0)
            save()
        }
        mapGuessed = false
    }

    fun go(screen: Screen) {
        stack += screen
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun home() {
        stack.clear()
        stack += Screen.Home
    }

    /** Opens a favorite place: the days of "From where I am" at this place. */
    fun openPlace(p: Point) {
        spot = p
        save()
        flow = Flow.PLACE
        home()
        stack += Screen.Spot(Screen.Purpose.PLACE)
        stack += Screen.When
    }

    /** Opens a favorite shot. */
    fun openShot(s: Shot) {
        flow = Flow.SAVED
        home()
        stack += Screen.ShotView(s)
    }

    fun isFavorite(p: Point) = favoritePlaces.any { placeKey(it) == placeKey(p) }

    fun isFavorite(s: Shot) = favoriteShots.any { shotKey(it) == shotKey(s) }

    fun toggleFavorite(p: Point) {
        if (!favoritesLoaded) return
        val key = placeKey(p)
        if (!favoritePlaces.removeAll { placeKey(it) == key }) favoritePlaces.add(0, p)
        saveFavorites()
    }

    fun toggleFavorite(s: Shot) {
        if (!favoritesLoaded) return
        val key = shotKey(s)
        if (!favoriteShots.removeAll { shotKey(it) == key }) favoriteShots.add(0, s)
        saveFavorites()
    }

    private fun saveFavorites() {
        if (!persistent) return
        val text = favoritesJson(favoritePlaces, favoriteShots)
        prefs.edit { putString(FAVORITES_KEY, text) }
    }

    /** Goes back to the last screen of a done step of the strip. */
    fun backToStep(step: Int) {
        val index = stack.indexOfLast { stepOf(flow, it) == step }
        if (index >= 0) stack.removeRange(index + 1, stack.size)
    }

    /** The time zone of a place: all dates and times of a shot use the zone of its spot. */
    fun zoneAt(at: Observer?): ZoneId =
        at?.let { baseMap?.timeZoneAt(it, ZoneId.systemDefault()) } ?: ZoneId.systemDefault()

    fun nameNear(at: Observer): String = baseMap?.nearest(at)?.name ?: ""

    private fun key(at: Observer) = "%.5f,%.5f".format(Locale.ROOT, at.latitudeDeg, at.longitudeDeg)

    /** The horizon of a place, calculated once. [progress] shows the bands that are ready. */
    suspend fun horizon(at: Observer): Horizon {
        val k = key(at)
        val job =
            synchronized(horizons) {
                horizons.getOrPut(k) {
                    scope.async {
                        computeHorizon(app, at) { done, _ ->
                            scope.launch(Dispatchers.Main) { progress[k] = done }
                        }
                    }
                }
            }
        return try {
            job.await()
        } catch (e: Exception) {
            // A failed job goes, so the next call tries again. A cancelled caller keeps the job.
            if (job.isCompleted) synchronized(horizons) { horizons.remove(k, job) }
            throw e
        }
    }

    fun progressOf(at: Observer): Int = progress[key(at)] ?: 0

    /** The horizon of a place if it is already calculated, else null. It does not wait. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun horizonIfReady(at: Observer): Horizon? {
        val job = synchronized(horizons) { horizons[key(at)] } ?: return null
        return if (
            job.isCompleted && !job.isCancelled && job.getCompletionExceptionOrNull() == null
        )
            job.getCompleted()
        else null
    }

    /** The ground at a point, from the terrain tiles; null offline. */
    suspend fun ground(at: Observer): Double? = withContext(Dispatchers.IO) { groundAt(app, at) }

    suspend fun summit(at: Observer, radiusM: Double): Observer? =
        withContext(Dispatchers.IO) { summitNear(app, at, radiusM) }

    suspend fun loadData() {
        withContext(Dispatchers.IO) {
            val stars =
                try {
                    Stars.decode(
                        app.assets.open("stars.json").bufferedReader().use { it.readText() }
                    )
                } catch (_: Exception) {
                    null
                }
            val milkyWay = MilkyWay.create()
            val map = BaseMapStore.load(app)
            // An eclipse in a favorite shot needs the core, so the favorites load here.
            val favorites =
                if (persistent) prefs.getString(FAVORITES_KEY, null)?.let(::favoritesOf) else null
            withContext(Dispatchers.Main) {
                sky = Sky(stars, milkyWay)
                baseMap = map
                favorites?.let {
                    favoritePlaces.addAll(it.places)
                    favoriteShots.addAll(it.shots)
                }
                favoritesLoaded = true
            }
        }
        // The heights of the saved points come back once their tiles are on the disk.
        landmark?.let { p ->
            if (p.groundM == null) ground(p.observer)?.let { landmark = p.copy(groundM = it) }
        }
        spot?.let { p ->
            if (p.groundM == null) ground(p.observer)?.let { spot = p.copy(groundM = it) }
        }
        save()
    }

    fun save() {
        if (!persistent) return
        val json =
            JSONObject().apply {
                put("subject", subject.name)
                landmark?.let { put("landmark", pointJson(it)) }
                put("extraM", extraM)
                spot?.let { put("spot", pointJson(it)) }
                put("lat", map.latitudeDeg)
                put("lon", map.longitudeDeg)
                put("zoom", map.zoom)
                fromDay?.let { put("fromDay", it.toString()) }
                put("days", days)
            }
        prefs.edit { putString(STATE_KEY, json.toString()) }
    }

    /** Reads the saved choices. Returns false if there are none. */
    private fun load(): Boolean {
        if (!persistent) return false
        val text = prefs.getString(STATE_KEY, null) ?: return false
        return try {
            val json = JSONObject(text)
            subject = SubjectId.valueOf(json.optString("subject", "MOON"))
            landmark = json.optJSONObject("landmark")?.let(::pointOf)
            extraM = json.optInt("extraM", 0)
            spot = json.optJSONObject("spot")?.let(::pointOf)
            map.center(
                Observer(json.getDouble("lat"), json.getDouble("lon")),
                json.getDouble("zoom"),
            )
            fromDay = json.optString("fromDay").takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
            days = json.optInt("days", 14)
            true
        } catch (_: Exception) {
            // A damaged entry: start again.
            false
        }
    }

    private companion object {
        const val STATE_KEY = "state-v1"
        const val FAVORITES_KEY = "favorites-v1"
    }
}

@Composable
fun AlidadeApp(locationGranted: Boolean, scene: ScreenshotScene? = null) {
    val context = LocalContext.current
    // The demo mode of the store screenshots: a fixed time and a fixed stack of screens.
    val model = remember { Model(context, persistent = scene == null).also { scene?.apply(it) } }
    LaunchedEffect(Unit) { model.loadData() }
    LaunchedEffect(locationGranted) { if (locationGranted) model.centerOnDevice() }
    val screen = model.stack.last()
    BackHandler(enabled = model.stack.size > 1) { model.back() }
    // Each screen of the stack keeps its saved state (for example the scroll position of a list)
    // while a screen on top of it is open. The state goes with the screen.
    val states = rememberSaveableStateHolder()
    val keys = model.stack.mapIndexed { i, s -> stateKey(i, s) }
    val known = remember { mutableSetOf<String>() }
    LaunchedEffect(keys) {
        for (k in known - keys.toSet()) states.removeState(k)
        known.clear()
        known += keys
    }
    ShotTheme {
        Box(Modifier.fillMaxSize().background(Shade.paper)) {
            states.SaveableStateProvider(keys.last()) { ScreenOf(model, screen, locationGranted) }
            Bar(model, screen, Modifier.align(Alignment.TopStart))
        }
    }
}

/** The key of the saved state of the screen at [index] of the stack. */
private fun stateKey(index: Int, screen: Screen) = "$index:${screen.hashCode()}"

/** The top bar: back, the logo, and the strip of film with the steps of the way. */
@Composable
private fun Bar(model: Model, screen: Screen, modifier: Modifier) {
    val overlay = isMapScreen(screen)
    Row(
        modifier
            .fillMaxWidth()
            .let { if (overlay) it else it.background(Shade.paper) }
            .safeDrawingPadding()
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.stack.size > 1) {
            // On the map and on the camera image, the button has a background, so it shows on
            // any picture.
            IconButton(
                onClick = model::back,
                colors =
                    if (overlay)
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = Shade.print,
                            contentColor = Shade.ink,
                        )
                    else IconButtonDefaults.iconButtonColors(contentColor = Shade.ink),
            ) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Row(
            Modifier.clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Start page") { model.home() }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(R.drawable.logo),
                contentDescription = "Alidade",
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
            )
            if (screen == Screen.Home) {
                Spacer(Modifier.width(12.dp))
                Text("Alidade", color = Shade.ink, style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.weight(1f))
        if (screen != Screen.Home && screen !is Screen.Live) {
            Trail(model.flow, stepOf(model.flow, screen), model::backToStep)
        }
    }
}

/** The strip of film: the steps of the way. A tap on a done step goes back to it. */
@Composable
private fun Trail(flow: Flow, at: Int, onStep: (Int) -> Unit) {
    Row(
        Modifier.background(Shade.night, RoundedCornerShape(8.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        flow.steps.forEachIndexed { i, step ->
            val active = i == at
            val done = i < at
            Row(
                Modifier.clip(RoundedCornerShape(5.dp))
                    .let { if (done) it.clickable { onStep(i) } else it }
                    .border(
                        1.dp,
                        Shade.cream.copy(alpha = if (active) 0.6f else 0.14f),
                        RoundedCornerShape(5.dp),
                    )
                    .background(if (active) Shade.rose else Shade.night, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "%02d".format(Locale.ROOT, i + 1),
                    color = Shade.cream.copy(alpha = if (active || done) 0.95f else 0.45f),
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (active) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        step.uppercase(),
                        color = Shade.cream,
                        fontFamily = Mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

fun isMapScreen(screen: Screen) =
    screen == Screen.Landmark ||
        screen is Screen.Spot ||
        screen is Screen.Spots ||
        screen is Screen.ShotMapView ||
        screen is Screen.EclipseMap ||
        screen is Screen.Live

@Composable
private fun ScreenOf(model: Model, screen: Screen, locationGranted: Boolean) {
    when (screen) {
        Screen.Home -> HomeScreen(model)
        Screen.Subject -> SubjectScreen(model)
        Screen.Landmark -> LandmarkScreen(model)
        Screen.From -> FromScreen(model)
        is Screen.Spot -> SpotScreen(model, screen.purpose, locationGranted)
        Screen.Dates -> DatesScreen(model)
        Screen.Moments -> MomentsScreen(model)
        is Screen.Spots -> SpotsScreen(model, screen.moment)
        Screen.When -> WhenScreen(model)
        Screen.Place -> PlaceScreen(model)
        is Screen.ShotView -> ShotScreen(model, screen.shot)
        is Screen.ShotMapView -> ShotMapScreen(model, screen.shot)
        is Screen.Live -> LiveScreen(model, screen.shot, screen.unixUtc, locationGranted)
        Screen.Eclipses -> EclipsesScreen(model)
        is Screen.EclipseMap -> EclipseScreen(model, screen.event)
    }
}
