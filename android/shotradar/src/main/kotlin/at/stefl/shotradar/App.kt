package at.stefl.shotradar

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject

// The state of shotradar and the screens. Every way through the app ends in the shot: the
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

    data object Eclipses : Screen

    data class EclipseMap(val event: Eclipse) : Screen
}

enum class Flow(val steps: List<String>) {
    LANDMARK(listOf("Subject", "Landmark", "From where", "Shots")),
    PLACE(listOf("Place", "Days", "Shots")),
    ECLIPSE(listOf("Eclipses", "The map", "Shot")),
}

/** The step of the strip of film for a screen, or -1. */
private fun stepOf(flow: Flow, screen: Screen): Int =
    when (screen) {
        is Screen.ShotView,
        is Screen.ShotMapView -> flow.steps.size - 1
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
            }
    }

/** The state of the app: the choices of the user (kept on the disk), and the loaded data. */
class Model(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("shotradar", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var subject by mutableStateOf(SubjectId.MOON)
    var landmark by mutableStateOf<Point?>(null)
    var extraM by mutableIntStateOf(0)
    var spot by mutableStateOf<Point?>(null)
    /** The days of "From where I am": the first day, or null for now. */
    var fromDay by mutableStateOf<LocalDate?>(null)
    var days by mutableIntStateOf(14)
    val map = MapState(47.27, 11.39, 10.0)

    var baseMap by mutableStateOf<BaseMap?>(null)
    var sky by mutableStateOf<Sky?>(null)

    val stack = mutableStateListOf<Screen>(Screen.Home)
    var flow by mutableStateOf(Flow.LANDMARK)

    /** The progress of the horizons that are in work: the bands that are ready, by key. */
    val progress = mutableStateMapOf<String, Int>()
    private val horizons = mutableMapOf<String, Deferred<Horizon>>()

    init {
        load()
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

    /** The time zone of a place: all dates and times of a shot use the zone of its spot. */
    fun zoneAt(at: Observer?): ZoneId =
        at?.let { baseMap?.timeZoneAt(it, ZoneId.systemDefault()) } ?: ZoneId.systemDefault()

    fun nameNear(at: Observer): String = baseMap?.nearest(at)?.name ?: ""

    private fun key(at: Observer) = "%.5f,%.5f".format(at.latitudeDeg, at.longitudeDeg)

    /** The horizon of a place, calculated once. [progress] shows the bands that are ready. */
    suspend fun horizon(at: Observer): Horizon {
        val k = key(at)
        val job =
            synchronized(horizons) {
                horizons.getOrPut(k) {
                    scope.async {
                        computeHorizon(app, at) { done, _ ->
                            scope.async(Dispatchers.Main) { progress[k] = done }
                        }
                    }
                }
            }
        return try {
            job.await()
        } catch (e: Exception) {
            synchronized(horizons) { horizons.remove(k) }
            throw e
        }
    }

    fun progressOf(at: Observer): Int = progress[key(at)] ?: 0

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
            withContext(Dispatchers.Main) {
                sky = Sky(stars, milkyWay)
                baseMap = map
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

    private fun load() {
        val text = prefs.getString(STATE_KEY, null) ?: return
        try {
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
        } catch (_: Exception) {
            // A damaged entry: start again.
        }
    }

    private fun pointJson(p: Point) =
        JSONObject().apply {
            put("lat", p.latitudeDeg)
            put("lon", p.longitudeDeg)
            put("name", p.name)
            p.groundM?.let { put("ground", it) }
        }

    private fun pointOf(j: JSONObject) =
        Point(
            j.getDouble("lat"),
            j.getDouble("lon"),
            j.optString("name"),
            if (j.has("ground")) j.getDouble("ground") else null,
        )

    private companion object {
        const val STATE_KEY = "state-v1"
    }
}

@Composable
fun ShotradarApp(locationGranted: Boolean) {
    val context = LocalContext.current
    val model = remember { Model(context) }
    LaunchedEffect(Unit) { model.loadData() }
    val screen = model.stack.last()
    BackHandler(enabled = model.stack.size > 1) { model.back() }
    ShotTheme {
        Box(Modifier.fillMaxSize().background(Shade.paper)) {
            ScreenOf(model, screen, locationGranted)
            Bar(model, screen, Modifier.align(Alignment.TopStart))
        }
    }
}

/** The bar: back, the brand, and the strip of film with the steps of the way. */
@Composable
private fun Bar(model: Model, screen: Screen, modifier: Modifier) {
    val onMap = isMapScreen(screen)
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onMap) it else it.background(Shade.paper) }
            .safeDrawingPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (model.stack.size > 1) {
            Box(
                Modifier.size(36.dp)
                    .background(Shade.print, RoundedCornerShape(2.dp))
                    .border(1.dp, Shade.line, RoundedCornerShape(2.dp))
                    .clickable { model.back() },
                contentAlignment = Alignment.Center,
            ) {
                Text("←", color = Shade.ink, fontSize = 18.sp)
            }
        }
        Row(Modifier.clickable { model.home() }, verticalAlignment = Alignment.CenterVertically) {
            // The adaptive icon is not a painter: its vector foreground on its background color.
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Shotradar",
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Shade.ink),
            )
            if (!onMap && screen == Screen.Home) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "SHOTRADAR",
                    color = Shade.ink,
                    fontFamily = Mono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (screen != Screen.Home) Trail(model.flow, stepOf(model.flow, screen))
    }
}

/** The strip of film: the steps of the way, the done ones and the active one. */
@Composable
private fun Trail(flow: Flow, at: Int) {
    Row(
        Modifier.background(Shade.night, RoundedCornerShape(3.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        flow.steps.forEachIndexed { i, step ->
            val active = i == at
            val done = i < at
            Row(
                Modifier.border(1.dp, Shade.cream.copy(alpha = if (active) 0.6f else 0.14f))
                    .background(if (active) Shade.rose else Shade.night)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "%02d".format(i + 1),
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
        screen is Screen.EclipseMap

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
        Screen.Eclipses -> EclipsesScreen(model)
        is Screen.EclipseMap -> EclipseScreen(model, screen.event)
    }
}
