package at.stefl.sunradar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import at.stefl.sunradar.core.DayKind
import at.stefl.sunradar.core.DirectionLine
import at.stefl.sunradar.core.ElevationGrid
import at.stefl.sunradar.core.HorizonOptions
import at.stefl.sunradar.core.HorizonProfile
import at.stefl.sunradar.core.Horizontal
import at.stefl.sunradar.core.Observer
import at.stefl.sunradar.core.elevationTiles
import at.stefl.sunradar.core.horizonProfile
import at.stefl.sunradar.core.readElevationGeotiff
import at.stefl.sunradar.core.sunPosition
import at.stefl.sunradar.core.terrainAltitudeDeg
import at.stefl.sunradar.core.terrainEvents
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The terrain that the profile uses: the tiles nearer than this distance. */
private const val TERRAIN_DISTANCE_M = 100_000.0

/** A tile nearer than this uses the full resolution, a tile farther away the first overview. */
private const val FULL_RESOLUTION_DISTANCE_M = 25_000.0

/** The profile is calculated again when the place moves more than this distance. */
private const val MOVE_DISTANCE_M = 500.0

/** The old profile stays until the new one is ready, if the place moved less than this. */
private const val KEEP_PROFILE_DISTANCE_M = 5_000.0

/** The time between two tries to download the missing tiles. */
private const val RETRY_MS = 60_000L

/** The terrain horizon of [observer], and a short status for the user (null if all is ready). */
data class TerrainState(
    val observer: Observer? = null,
    val profile: HorizonProfile? = null,
    val status: String? = null,
)

/** The cache of the elevation tiles, one for the process. */
private object TerrainStore {
    private var cache: DemCache? = null

    @Synchronized
    fun cache(context: Context): DemCache =
        cache ?: DemCache(File(context.noBackupFilesDir, "dem")).also { cache = it }
}

/**
 * The terrain horizon of [observer], from the tiles of Copernicus GLO-90. The download, the read of
 * the tiles, and the calculation run in the background. The profile is calculated again when the
 * place moves more than 500 m, and when a new tile arrives. A null [observer] or a false [enabled]
 * gives no profile and no download.
 */
@Composable
fun rememberTerrain(observer: Observer?, enabled: Boolean): State<TerrainState> {
    val context = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(TerrainState()) }
    // The grids in memory, by tile name, with their level. They stay when the place moves.
    val grids = remember { ConcurrentHashMap<String, Pair<Int, ElevationGrid>>() }
    var anchor by remember { mutableStateOf<Observer?>(null) }
    LaunchedEffect(observer) {
        val old = anchor
        if (observer == null || old == null || Geo.distanceM(old, observer) > MOVE_DISTANCE_M) {
            anchor = observer
        }
    }
    LaunchedEffect(anchor, enabled) {
        val place = anchor
        if (!enabled || place == null) {
            grids.clear()
            state.value = TerrainState()
            return@LaunchedEffect
        }
        val old = state.value.observer
        if (old == null || Geo.distanceM(old, place) > KEEP_PROFILE_DISTANCE_M) {
            state.value = TerrainState()
        }
        loadTerrain(TerrainStore.cache(context), place, grids, state)
    }
    return state
}

/**
 * Loads the tiles of [place], the nearest first, and calculates the profile after each new tile. A
 * missing tile (offline) is tried again every minute. The function returns only when it is
 * cancelled.
 */
private suspend fun loadTerrain(
    cache: DemCache,
    place: Observer,
    grids: ConcurrentHashMap<String, Pair<Int, ElevationGrid>>,
    state: MutableState<TerrainState>,
) = coroutineScope {
    val tiles = withContext(Dispatchers.Default) { elevationTiles(place, TERRAIN_DISTANCE_M) }
    val names = tiles.map { DemCache.tileName(it.latitudeDeg, it.longitudeDeg) }
    val needed = names.toSet()
    grids.keys.retainAll(needed)
    val sea = ConcurrentHashMap.newKeySet<String>()
    val unreadable = mutableSetOf<String>()

    // The calculation runs in its own coroutine. The state flow gives it only the newest version,
    // so the tiles that arrive during a calculation cause only one more calculation.
    val version = MutableStateFlow(0)
    launch {
        version.collect {
            val own = names.firstOrNull() ?: return@collect
            if (!grids.containsKey(own) && own !in sea) return@collect
            val profile =
                withContext(Dispatchers.Default) {
                    calculateProfile(place, grids.values.map { it.second }, own in sea)
                }
            state.value = state.value.copy(observer = place, profile = profile)
        }
    }

    while (true) {
        var missing = 0
        for ((i, tile) in tiles.withIndex()) {
            val name = names[i]
            val level = if (tile.distanceM < FULL_RESOLUTION_DISTANCE_M) 0 else 1
            if (grids[name]?.first == level || name in sea || name in unreadable) continue
            state.value =
                state.value.copy(
                    status = "Terrain: loading elevation data (${i + 1}/${tiles.size})"
                )
            val result =
                try {
                    withContext(Dispatchers.IO) {
                        cache.load(tile.latitudeDeg, tile.longitudeDeg, needed) {
                            readElevationGeotiff(it, level)
                        }
                    }
                } catch (_: IOException) {
                    // The cache cannot write the tile, for example because the disk is full.
                    // Try again later, as for a network error.
                    TileLoad.Missing
                }
            when (result) {
                is TileLoad.Data -> {
                    grids[name] = level to result.value
                    version.value++
                }
                TileLoad.Sea -> {
                    sea += name
                    version.value++
                }
                TileLoad.Missing -> missing++
                TileLoad.Unreadable -> unreadable += name
            }
        }
        if (missing == 0) {
            state.value = state.value.copy(status = null)
            break
        }
        state.value =
            state.value.copy(
                status = "Terrain: offline, elevation data missing ($missing/${tiles.size})"
            )
        delay(RETRY_MS)
    }
}

/**
 * The profile with the default options. If the tiles have no data at the observer (the sea, or a
 * coast), the observer is at 0 m.
 */
private fun calculateProfile(
    place: Observer,
    grids: List<ElevationGrid>,
    ownTileIsSea: Boolean,
): HorizonProfile? {
    val atSeaLevel = HorizonOptions(useGridElevation = false, observerElevationM = 0.0)
    return try {
        horizonProfile(place, grids, if (ownTileIsSea) atSeaLevel else HorizonOptions())
    } catch (_: IllegalArgumentException) {
        try {
            horizonProfile(place, grids, atSeaLevel)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * The lines of a terrain profile for the drawing: the terrain line, and closed rings for the fill
 * below it. A direction without data breaks the line.
 */
class TerrainLines(profile: HorizonProfile) {
    /** The terrain line, in one or more parts. */
    val lines: List<DirectionLine>

    /** Rings from the terrain line down to -85°, each at most 30° wide. */
    val fill: List<DirectionLine>

    init {
        val step = profile.azimuthStepDeg
        val altitude = profile.altitudeDeg
        val n = altitude.size
        // The runs of directions with data, as indices that can go past n (a run across north).
        val runs = mutableListOf<IntRange>()
        val start = altitude.indexOfFirst { it.isNaN() }
        if (start < 0) {
            runs += 0..n
        } else {
            var first = -1
            for (k in start + 1..start + n) {
                val valid = !altitude[k % n].isNaN()
                if (valid && first < 0) first = k
                if (!valid && first >= 0) {
                    runs += first until k
                    first = -1
                }
            }
            if (first >= 0) runs += first until start + n
        }
        fun at(k: Int) = Horizontal((k % n) * step, altitude[k % n])
        lines = runs.filter { it.count() >= 2 }.map { run -> DirectionLine.of(run.map(::at)) }

        val chunk = maxOf(2, (30 / step).toInt())
        fill = buildList {
            for (run in runs) {
                if (run.count() < 2) continue
                var a = run.first
                while (a < run.last) {
                    val b = minOf(a + chunk, run.last)
                    add(DirectionLine.of(ring((a..b).map(::at), (a % n) * step, (b - a) * step)))
                    a = b
                }
            }
        }
    }

    /**
     * A closed ring: [top] (from west to east), down to -85° at its east end, back to its west end
     * at -85°, and up again. [westDeg] is the azimuth of the west end, [widthDeg] the width.
     */
    private fun ring(top: List<Horizontal>, westDeg: Double, widthDeg: Double): List<Horizontal> {
        val bottom = -85.0
        val east = top.last()
        val west = top.first()
        val out = top.toMutableList()
        fun side(azimuthDeg: Double, from: Double, to: Double) {
            val steps = maxOf(2, (kotlin.math.abs(to - from) / 5).toInt() + 1)
            for (i in 1..steps) out += Horizontal(azimuthDeg, from + (to - from) * i / steps)
        }
        side(east.azimuthDeg, east.altitudeDeg, bottom)
        val steps = maxOf(2, (widthDeg / 5).toInt() + 1)
        for (i in 1 until steps) {
            out += Horizontal((westDeg + widthDeg * (steps - i) / steps) % 360, bottom)
        }
        side(west.azimuthDeg, bottom, west.altitudeDeg)
        return out
    }
}

/**
 * The terrain on one day: the parts of the path above the horizon that the terrain hides or not,
 * and the visible sunrise and sunset. It uses the native library, so make it off the main thread.
 */
class TerrainDay(val day: SkyDay, profile: HorizonProfile, zone: ZoneId) {
    /** The parts of the path above the horizon and above the terrain. */
    val visibleSegments: List<DirectionLine>

    /** The parts of the path above the horizon but behind the terrain. */
    val hiddenSegments: List<DirectionLine>

    /** The times when the sun appears and goes away behind the terrain, on the terrain line. */
    val marks: List<HourMark>

    /** For example "Visible sunrise 07:52 · visible sunset 16:10". */
    val summary: String

    init {
        val visible = mutableListOf<DirectionLine>()
        val hidden = mutableListOf<DirectionLine>()
        var run = mutableListOf<Horizontal>()
        var runHidden = false
        fun flush() {
            if (run.size >= 2) (if (runHidden) hidden else visible) += DirectionLine.of(run)
            run = mutableListOf()
        }
        for (point in day.path) {
            val p = point.position
            if (p.altitudeDeg < 0) {
                flush()
                continue
            }
            val terrain = terrainAltitudeDeg(profile, p.azimuthDeg)
            val behind = !terrain.isNaN() && p.altitudeDeg < terrain
            if (run.isNotEmpty() && behind != runHidden) {
                // The point of the change belongs to both parts, so the line has no gap.
                val last = run.last()
                flush()
                run += last
            }
            runHidden = behind
            run += p
        }
        flush()
        visibleSegments = visible
        hiddenSegments = hidden

        val events = terrainEvents(day.observer, profile, day.startUtc, day.endUtc)
        marks =
            events.crossings.map {
                HourMark(
                    sunPosition(it.unixUtc, day.observer),
                    (if (it.rising) "↑ " else "↓ ") + SkyDay.formatTime(it.unixUtc, zone),
                )
            }
        val rise = events.crossings.firstOrNull { it.rising }
        val set = events.crossings.lastOrNull { !it.rising }
        // The sun goes away behind a peak and appears again: each setting but the last.
        val hides = events.crossings.count { !it.rising } - 1
        summary =
            when (events.kind) {
                DayKind.POLAR_DAY -> "The terrain does not hide the sun on this day"
                DayKind.POLAR_NIGHT -> "The terrain hides the sun all day"
                DayKind.NORMAL ->
                    listOfNotNull(
                            rise?.let { "Visible sunrise " + SkyDay.formatTime(it.unixUtc, zone) },
                            set?.let { "visible sunset " + SkyDay.formatTime(it.unixUtc, zone) },
                            if (hides > 0) "hidden ${hides}× during the day" else null,
                        )
                        .joinToString(" · ")
                        .replaceFirstChar { it.uppercase() }
            }
    }
}
