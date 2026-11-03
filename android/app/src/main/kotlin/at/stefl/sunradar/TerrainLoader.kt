package at.stefl.sunradar

import at.stefl.almucantar.core.ElevationTile
import at.stefl.almucantar.core.HorizonOptions
import at.stefl.almucantar.core.HorizonProfile
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The app downloads the full files only if the coarse profile reaches this altitude somewhere. A
 * terrain of 1° moves the sunrise and the sunset by about 5–8 minutes in Europe. Below it, the
 * terrain does not matter, and the coarse profile is good enough.
 */
internal const val FULL_TERRAIN_MIN_ALTITUDE_DEG = 1.0

/** The highest altitude of [profile], NaN if it has no data. */
internal fun maxAltitudeDeg(profile: HorizonProfile?): Double =
    profile?.altitudeDeg?.filter { !it.isNaN() }?.maxOrNull() ?: Double.NaN

/**
 * Loads the terrain of one place in two steps (PLAN.md 14.3):
 * 1. The coarse check: the coarse level of all [tiles] ([DemCache.loadCoarse]).
 * 2. If the coarse profile ([maxAltitudeDeg] of the coarse grids) reaches
 *    [FULL_TERRAIN_MIN_ALTITUDE_DEG], the full files of the tiles nearer than
 *    [ElevationSource.fullDistanceM]. Below it, the loader downloads nothing more.
 *
 * The levels, the distance, and the [options] of the profile come from the source of the cache
 * ([DemCache.source]). The coarse check and the profile of the terrain use the same [options], so
 * the clearance of the near path of a surface model applies to both.
 *
 * With [readFull], the loader reads the full files at the full level and puts them in [grids] (the
 * terrain horizon). Without it, the loader only puts the full files on the disk ("Keep offline").
 * The grid type [G] is a parameter, so the JVM tests can use a fake grid. The functions block, so
 * call them off the main thread.
 */
internal class TerrainLoader<G : Any>(
    private val cache: DemCache,
    /** The tiles of the place, the nearest (the own tile) first. */
    val tiles: List<ElevationTile>,
    /** Reads a file (a full file or a coarse part) at a level. Throws if it cannot. */
    private val read: (bytes: ByteArray, level: Int) -> G,
    /**
     * The highest altitude of the profile of the grids with [options], NaN if there is no profile.
     */
    private val maxAltitudeDeg:
        (grids: List<G>, ownTileIsSea: Boolean, options: HorizonOptions) -> Double,
    private val readFull: Boolean = true,
    /** The grids in memory, by tile name, with their level. They can stay from the place before. */
    val grids: MutableMap<String, Pair<Int, G>> = ConcurrentHashMap(),
) {
    val names = tiles.map { DemCache.tileName(it.latitudeDeg, it.longitudeDeg) }

    /** The options of the profile for the source of the cache ([horizonOptions]). */
    val options: HorizonOptions = horizonOptions(cache.source)

    private val coarseLevel = cache.source.coarseLevel
    private val fullLevel = cache.source.fullLevel
    private val sea = ConcurrentHashMap.newKeySet<String>()
    private val unreadable = ConcurrentHashMap.newKeySet<String>()

    /** All entries that the place can use, so a download does not evict one of them. */
    private val keep = names.flatMap { listOf(it, cache.coarseName(it)) }.toSet()

    /** Null until the coarse check is done, then true if the full files are needed. */
    @Volatile
    var full: Boolean? = null
        private set

    /** True if the own tile is in the sea. */
    val ownTileIsSea: Boolean
        get() = names.firstOrNull()?.let { it in sea } ?: false

    /** True if a profile can be calculated: the own tile has a grid, or it is in the sea. */
    val hasOwnTile: Boolean
        get() = names.firstOrNull()?.let { it in grids || it in sea } ?: false

    /** The tiles that need the full file if the terrain matters. */
    val nearTiles: List<ElevationTile>
        get() = tiles.filter { it.distanceM < cache.source.fullDistanceM }

    /**
     * Loads the missing data once: the coarse check, and then the full files if they are needed.
     * [onStep] is called before each download or read with the step (1 for the coarse check, 2 for
     * the full files), the index, and the number of tiles of the step. It can throw to stop the
     * loader (for example a cancellation). [onChange] is called after each new grid or sea tile.
     * Returns the number of tiles that are missing (for example offline). Call it again later to
     * try them again.
     */
    fun loadOnce(onStep: (step: Int, index: Int, count: Int) -> Unit, onChange: () -> Unit): Int {
        // 1. The coarse check. A grid of the full level also serves the check.
        val coarse =
            tiles.indices.filter { i ->
                val name = names[i]
                name !in grids && name !in sea && name !in unreadable
            }
        var missing = 0
        for ((n, i) in coarse.withIndex()) {
            onStep(1, n, coarse.size)
            val tile = tiles[i]
            val result = safely {
                cache.loadCoarse(tile.latitudeDeg, tile.longitudeDeg, keep) {
                    read(it, coarseLevel)
                }
            }
            if (accept(names[i], coarseLevel, result, onChange)) missing++
        }
        // The decision needs the coarse level of all tiles.
        if (missing > 0) return missing

        // 2. The threshold, once for the place.
        val needFull =
            full
                ?: (maxAltitudeDeg(grids.values.map { it.second }, ownTileIsSea, options) >=
                        FULL_TERRAIN_MIN_ALTITUDE_DEG)
                    .also { full = it }

        // 3. The full files of the near tiles, or the coarse level for a grid that has the wrong
        // level (for example a grid of the place before).
        val near = nearTiles.toSet()
        val steps =
            tiles.indices.filter { i ->
                val name = names[i]
                val level = if (needFull && tiles[i] in near) fullLevel else coarseLevel
                name !in sea &&
                    name !in unreadable &&
                    // Without readFull, fetch() checks a full file that is on the disk already
                    // without a download, so the loader asks it for each near tile.
                    (if (readFull) grids[name]?.first != level else level == fullLevel)
            }
        for ((n, i) in steps.withIndex()) {
            onStep(2, n, steps.size)
            val tile = tiles[i]
            val name = names[i]
            if (needFull && tile in near) {
                if (readFull) {
                    val result = safely {
                        cache.load(tile.latitudeDeg, tile.longitudeDeg, keep) {
                            read(it, fullLevel)
                        }
                    }
                    if (accept(name, fullLevel, result, onChange)) missing++
                } else {
                    val result =
                        try {
                            cache.fetch(tile.latitudeDeg, tile.longitudeDeg, keep)
                        } catch (_: IOException) {
                            TileFetch.MISSING
                        }
                    if (result == TileFetch.MISSING) missing++
                }
            } else {
                val result = safely {
                    cache.loadCoarse(tile.latitudeDeg, tile.longitudeDeg, keep) {
                        read(it, coarseLevel)
                    }
                }
                if (accept(name, coarseLevel, result, onChange)) missing++
            }
        }
        return missing
    }

    /**
     * The entries that serve the place now: the coarse level of each tile, and the full files of
     * the near tiles if they are needed. They are the tiles of a kept place.
     */
    fun entries(): List<String> {
        val coarse = tiles.map { cache.coarseEntryName(it.latitudeDeg, it.longitudeDeg) }
        val fullFiles =
            if (full == true) nearTiles.map { DemCache.tileName(it.latitudeDeg, it.longitudeDeg) }
            else emptyList()
        return (coarse + fullFiles).distinct()
    }

    /** Stores the result of a load. Returns true if the tile is missing. */
    private fun accept(
        name: String,
        level: Int,
        result: TileLoad<G>,
        onChange: () -> Unit,
    ): Boolean {
        when (result) {
            is TileLoad.Data -> {
                grids[name] = level to result.value
                onChange()
            }
            TileLoad.Sea -> {
                sea += name
                onChange()
            }
            TileLoad.Missing -> return true
            TileLoad.Unreadable -> unreadable += name
        }
        return false
    }

    private inline fun safely(load: () -> TileLoad<G>): TileLoad<G> =
        try {
            load()
        } catch (_: IOException) {
            // The cache cannot write the file, for example because the disk is full. Try again
            // later, as for a network error.
            TileLoad.Missing
        }
}
