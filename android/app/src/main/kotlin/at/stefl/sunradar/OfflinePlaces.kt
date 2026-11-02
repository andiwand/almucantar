package at.stefl.sunradar

import android.content.Context
import at.stefl.sunradar.core.Observer
import at.stefl.sunradar.core.elevationTiles
import java.io.IOException
import java.util.Locale
import java.util.UUID
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
    /** The number of tiles that are done (on the disk, in the sea, or failed). */
    val done: Int,
    val total: Int,
    /** True when all tiles are done. */
    val finished: Boolean = false,
    /** The number of tiles that failed. */
    val missing: Int = 0,
    /** The size of the files of the place, in bytes, when the download is finished. */
    val bytes: Long = 0,
) {
    /** For example "Downloading 3/9", "Ready offline: 9 tiles, 44 MB". */
    val text: String
        get() =
            when {
                !finished -> "Downloading $done/$total"
                missing > 0 ->
                    "$missing ${if (missing == 1) "tile" else "tiles"} missing, try again online"
                else -> "Ready offline: ${tileCount(total)}, ${megabytes(bytes)}"
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
     * Keeps the terrain tiles of [observer] offline: the tiles of the terrain horizon within 100
     * km. A kept place at the same coordinates gets its tiles again (for example after a failure).
     */
    @Synchronized
    fun keep(context: Context, observer: Observer, name: String) {
        if (running) return
        val cache = TerrainStore.cache(context.applicationContext)
        _job.value = KeepJob(name, 0, 0)
        scope.launch {
            val tiles =
                withContext(Dispatchers.Default) { elevationTiles(observer, TERRAIN_DISTANCE_M) }
            val names = tiles.map { DemCache.tileName(it.latitudeDeg, it.longitudeDeg) }
            val id =
                cache
                    .keptPlaces()
                    .firstOrNull {
                        it.place.latitudeDeg == observer.latitudeDeg &&
                            it.place.longitudeDeg == observer.longitudeDeg
                    }
                    ?.place
                    ?.id ?: UUID.randomUUID().toString()
            // Add the place first, so the eviction cannot delete its first tiles during the
            // download of the others.
            cache.addKeptPlace(
                KeptPlace(id, name, observer.latitudeDeg, observer.longitudeDeg, names)
            )
            _version.value++
            var missing = 0
            for ((i, tile) in tiles.withIndex()) {
                _job.value = KeepJob(name, i, tiles.size)
                val result =
                    try {
                        cache.fetch(tile.latitudeDeg, tile.longitudeDeg)
                    } catch (_: IOException) {
                        // The cache cannot write the tile, for example because the disk is full.
                        TileFetch.MISSING
                    }
                if (result == TileFetch.MISSING) missing++
                _version.value++
            }
            val bytes = cache.keptPlaces().firstOrNull { it.place.id == id }?.bytes ?: 0
            _job.value = KeepJob(name, tiles.size, tiles.size, true, missing, bytes)
            _version.value++
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
