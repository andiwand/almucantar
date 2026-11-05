package at.stefl.almucantar.shared

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
import at.stefl.almucantar.core.DirectionLine
import at.stefl.almucantar.core.ElevationGrid
import at.stefl.almucantar.core.HorizonOptions
import at.stefl.almucantar.core.HorizonProfile
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.elevationTiles
import at.stefl.almucantar.core.horizonProfile
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The terrain that the profile uses: the tiles nearer than this distance. */
internal const val TERRAIN_DISTANCE_M = 100_000.0

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

/** The cache of the elevation tiles of [TERRAIN_SOURCE], one for the process. */
object TerrainStore {
    private var cache: DemCache? = null

    @Synchronized
    fun cache(context: Context): DemCache =
        cache
            ?: DemCache(File(context.noBackupFilesDir, "dem"), source = TERRAIN_SOURCE).also {
                cache = it
            }
}

/**
 * The terrain horizon of [observer], from the tiles of [TERRAIN_SOURCE]. The download, the read of
 * the tiles, and the calculation run in the background. A coarse check comes first, and the full
 * files only where the terrain matters (see [TerrainLoader]). The profile is calculated again when
 * the place moves more than 500 m, and when a new tile arrives. A null [observer] or a false
 * [enabled] gives no profile and no download.
 */
@Composable
fun rememberTerrain(observer: Observer?, enabled: Boolean): State<TerrainState> {
    val context = LocalContext.current.applicationContext
    val state = remember { mutableStateOf(TerrainState()) }
    // The grids in memory of the last place, by tile name, with their level. The next place takes
    // the grids of its tiles from them.
    val grids = remember { AtomicReference<Map<String, Pair<Int, ElevationGrid>>>(emptyMap()) }
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
            grids.set(emptyMap())
            state.value = TerrainState()
            return@LaunchedEffect
        }
        val old = state.value.observer
        if (old == null || Geo.distanceM(old, place) > KEEP_PROFILE_DISTANCE_M) {
            state.value = TerrainState()
        }
        // The first call reads the index of the cache from the disk.
        val cache = withContext(Dispatchers.IO) { TerrainStore.cache(context) }
        loadTerrain(cache, place, grids, state)
    }
    return state
}

/**
 * Loads the terrain of [place] with a [TerrainLoader]: first the coarse level of all tiles, then
 * the full files of the near tiles if the terrain matters. The profile is calculated after each new
 * tile. A missing tile (offline) is tried again every minute. The function returns only when it is
 * cancelled.
 *
 * The loader has its own map of grids, with the grids of its tiles from [grids]. So a loader of the
 * place before, which can still finish a download after its cancellation, cannot add a grid to it.
 */
private suspend fun loadTerrain(
    cache: DemCache,
    place: Observer,
    grids: AtomicReference<Map<String, Pair<Int, ElevationGrid>>>,
    state: MutableState<TerrainState>,
) = coroutineScope {
    val main = this
    val tiles = withContext(Dispatchers.Default) { elevationTiles(place, TERRAIN_DISTANCE_M) }
    val loader =
        TerrainLoader(
            cache,
            tiles,
            read = cache.source::readGrid,
            maxAltitudeDeg = { g, ownTileIsSea, options ->
                maxAltitudeDeg(calculateProfile(place, g, ownTileIsSea, options))
            },
        )
    loader.grids += grids.get().filterKeys { it in loader.names }
    grids.set(loader.grids)

    // The calculation runs in its own coroutine. The state flow gives it only the newest version,
    // so the tiles that arrive during a calculation cause only one more calculation.
    val version = MutableStateFlow(0)
    launch {
        version.collect {
            if (!loader.hasOwnTile) return@collect
            val profile =
                withContext(Dispatchers.Default) {
                    calculateProfile(
                        place,
                        loader.grids.values.map { it.second },
                        loader.ownTileIsSea,
                        loader.options,
                    )
                }
            state.value = state.value.copy(observer = place, profile = profile)
        }
    }

    while (true) {
        val missing =
            withContext(Dispatchers.IO) {
                loader.loadOnce(
                    onStep = { _, index, count ->
                        // Stop between two tiles when the place changes.
                        ensureActive()
                        // The state changes on the main thread only, so this change cannot undo
                        // a new profile.
                        main.launch {
                            state.value =
                                state.value.copy(
                                    status = "Terrain: loading elevation data (${index + 1}/$count)"
                                )
                        }
                    },
                    onChange = { version.value++ },
                )
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
 * The profile with [options] (from [horizonOptions] of the source). If the tiles have no data at
 * the observer (the sea, or a coast), the observer is at 0 m.
 */
internal fun calculateProfile(
    place: Observer,
    grids: List<ElevationGrid>,
    ownTileIsSea: Boolean,
    options: HorizonOptions,
): HorizonProfile? {
    val atSeaLevel = options.copy(useGridElevation = false, observerElevationM = 0.0)
    return try {
        horizonProfile(place, grids, if (ownTileIsSea) atSeaLevel else options)
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
