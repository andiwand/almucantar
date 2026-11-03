package at.stefl.almucantar.shared

import android.content.Context
import android.util.Log
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.elevationTiles
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The state of the last "Keep offline" download. */
data class KeepJob(
    val name: String,
    /**
     * The number of downloads that are done (on the disk, in the sea, or failed): first the coarse
     * part of each tile, then the full files of the near tiles if the terrain matters.
     */
    val done: Int,
    /** The number of downloads, known so far. */
    val total: Int,
    /** True when all downloads are done. */
    val finished: Boolean = false,
    /** The number of tiles that failed. */
    val missing: Int = 0,
    /** The size of the files of the place, in bytes, when the download is finished. */
    val bytes: Long = 0,
    /** The number of tiles of the place. */
    val tiles: Int = total,
) {
    /** For example "Downloading 3/9", "Ready offline: 9 tiles, 44 MB". */
    val text: String
        get() =
            when {
                !finished -> "Downloading $done/$total"
                missing > 0 ->
                    "$missing ${if (missing == 1) "tile" else "tiles"} missing, try again online"
                else -> "Ready offline: ${tileCount(tiles)}, ${megabytes(bytes)}"
            }
}

/** For example "9 tiles". */
internal fun tileCount(n: Int) = "$n ${if (n == 1) "tile" else "tiles"}"

/** For example "44 MB", with 1 MB = 1024 × 1024 bytes as the limit of the cache. */
internal fun megabytes(bytes: Long) =
    String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024.0))

/**
 * The places that the user keeps offline (PLAN.md 13.3, "download this region"). The download runs
 * in a scope of the process, so it continues when the place panel closes. Only one download runs at
 * a time.
 */
object OfflinePlaces {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _job = MutableStateFlow<KeepJob?>(null)
    private val _version = MutableStateFlow(0)

    /** The state of the running or the last download, or null. */
    val job: StateFlow<KeepJob?> = _job

    /** Increments after each change of the kept places or of their tiles. */
    val version: StateFlow<Int> = _version

    /** True while a download runs. */
    val running: Boolean
        get() = _job.value?.finished == false

    /** The kept places with the state of their tiles. Call it off the main thread. */
    fun list(context: Context): List<KeptPlaceStatus> =
        TerrainStore.cache(context.applicationContext).keptPlaces()

    /**
     * Keeps the terrain of [observer] offline: the same data that the terrain horizon uses (see
     * [keepTerrain]). A kept place at the same coordinates gets its tiles again (for example after
     * a failure).
     */
    @Synchronized
    fun keep(context: Context, observer: Observer, name: String) {
        if (running) return
        val app = context.applicationContext
        _job.value = KeepJob(name, 0, 0)
        scope.launch {
            try {
                // The first call reads the index of the cache from the disk, so not on the main
                // thread.
                val cache = TerrainStore.cache(app)
                val tiles =
                    withContext(Dispatchers.Default) {
                        elevationTiles(observer, TERRAIN_DISTANCE_M)
                    }
                val loader =
                    TerrainLoader(
                        cache,
                        tiles,
                        read = cache.source::readGrid,
                        maxAltitudeDeg = { grids, ownTileIsSea, options ->
                            maxAltitudeDeg(calculateProfile(observer, grids, ownTileIsSea, options))
                        },
                        readFull = false,
                    )
                _job.value =
                    keepTerrain(cache, loader, observer, name) {
                        _job.value = it
                        _version.value++
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An error of the process scope crashes the app. The job shows the failure.
                Log.w("OfflinePlaces", "Keep offline failed", e)
            } finally {
                // A failed download must not leave the job running, so "Keep offline" works again.
                _job.value
                    ?.takeIf { !it.finished }
                    ?.let {
                        _job.value =
                            it.copy(finished = true, missing = maxOf(1, it.total - it.done))
                    }
                _version.value++
            }
        }
    }

    /** Removes the kept place [id]. Its tiles become normal tiles of the cache. */
    fun remove(context: Context, id: String) {
        scope.launch {
            TerrainStore.cache(context.applicationContext).removeKeptPlace(id)
            _version.value++
        }
    }
}

/**
 * Keeps the terrain of [observer] offline with [loader] (without readFull): the coarse part of all
 * tiles, and the full files of the near tiles if the coarse profile reaches
 * [FULL_TERRAIN_MIN_ALTITUDE_DEG]. The kept place lists the entries that serve the place, so the
 * cache never evicts them. [onProgress] gets the state during the download. Returns the state at
 * the end. Blocks, so call it off the main thread.
 */
internal fun <G : Any> keepTerrain(
    cache: DemCache,
    loader: TerrainLoader<G>,
    observer: Observer,
    name: String,
    onProgress: (KeepJob) -> Unit,
): KeepJob {
    val tiles = loader.tiles.size
    val id =
        cache
            .keptPlaces()
            .firstOrNull {
                it.place.latitudeDeg == observer.latitudeDeg &&
                    it.place.longitudeDeg == observer.longitudeDeg
            }
            ?.place
            ?.id ?: UUID.randomUUID().toString()
    fun keptPlace(entries: List<String>) =
        KeptPlace(id, name, observer.latitudeDeg, observer.longitudeDeg, entries)
    // Add the place first, so the eviction cannot delete its first files during the download of
    // the others.
    cache.addKeptPlace(keptPlace(loader.names.map { cache.coarseName(it) }))
    onProgress(KeepJob(name, 0, tiles, tiles = tiles))
    val missing =
        loader.loadOnce(
            onStep = { step, index, count ->
                val job =
                    if (step == 1) KeepJob(name, index, count, tiles = tiles)
                    else KeepJob(name, tiles + index, tiles + count, tiles = tiles)
                onProgress(job)
            },
            onChange = {},
        )
    cache.addKeptPlace(keptPlace(loader.entries()))
    val bytes = cache.keptPlaces().firstOrNull { it.place.id == id }?.bytes ?: 0
    val done = tiles + if (loader.full == true) loader.nearTiles.size else 0
    return KeepJob(name, done, done, true, missing, bytes, tiles)
}
