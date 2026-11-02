package at.stefl.sunradar

import at.stefl.sunradar.core.ElevationGrid
import at.stefl.sunradar.core.ElevationTile
import at.stefl.sunradar.core.HorizonOptions
import at.stefl.sunradar.core.elevationGeotiffPrefixSize
import at.stefl.sunradar.core.readElevationGeotiff

/**
 * A source of elevation tiles of 1° × 1°. The terrain logic ([DemCache], [TerrainLoader], the
 * profile) needs only this description. So another source (for example a bare-earth model without
 * buildings) can replace Copernicus without changes in the terrain logic. The PWA has the same idea
 * in `pwa/src/terrain.ts`.
 */
interface ElevationSource {
    /** The name of the data, for example "Copernicus DEM GLO-90". */
    val name: String

    /** The attribution that the license of the data asks for. The settings show it. */
    val attribution: String

    /**
     * True if the heights include buildings and forests (a surface model). Then the profile ignores
     * the terrain nearer than [NEAR_CLEARANCE_M], because a city gives a false wall around the
     * observer.
     */
    val isSurfaceModel: Boolean

    /** The overview level of the coarse check, for all tiles of the place. */
    val coarseLevel: Int

    /** The level of the full data of the near tiles, if the terrain matters. */
    val fullLevel: Int

    /** A tile nearer than this gets the full data at [fullLevel] if the terrain matters. */
    val fullDistanceM: Double

    /** The URL of the file of [tile]. */
    fun tileUrl(tile: ElevationTile): String

    /**
     * The number of bytes from the start of a file that [level] needs, from the first bytes of the
     * file ([header]). Gives 0 if the header is too short to tell. Throws if the bytes are not a
     * file of the source.
     */
    fun prefixSize(header: ByteArray, level: Int): Long

    /**
     * Reads a file (a full file or the first [prefixSize] bytes) at [level]. Throws if it cannot.
     */
    fun readGrid(bytes: ByteArray, level: Int): ElevationGrid
}

/**
 * The clearance of the near path for a surface model. Buildings smeared over cells of 90 m add at
 * most about 0.4° at this distance, and real mountains are usually farther away.
 */
internal const val NEAR_CLEARANCE_M = 2_000.0

/**
 * The options of the profile for [source]: the clearance of the near path for a surface model. The
 * coarse check and the full profile use the same options.
 */
internal fun horizonOptions(source: ElevationSource): HorizonOptions =
    HorizonOptions(minDistanceM = if (source.isSurfaceModel) NEAR_CLEARANCE_M else 0.0)

/**
 * Copernicus DEM GLO-90 on AWS Open Data: Cloud Optimized GeoTIFFs of 1° × 1° with overviews. A
 * tile has the overview at level 2 (300 × 300 samples, about 370 m) in its first 300 KB. The
 * heights include buildings and forests, so it is a surface model.
 */
object Copernicus : ElevationSource {
    override val name = "Copernicus DEM GLO-90"

    override val attribution =
        "Terrain: Copernicus DEM GLO-90, © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH " +
            "2014-2018, provided under COPERNICUS by the European Union and ESA; all rights reserved."

    override val isSurfaceModel = true

    override val coarseLevel = 2

    override val fullLevel = 0

    override val fullDistanceM = 25_000.0

    override fun tileUrl(tile: ElevationTile): String {
        val id =
            "Copernicus_DSM_COG_30_${DemCache.tileName(tile.latitudeDeg, tile.longitudeDeg)}_DEM"
        return "https://copernicus-dem-90m.s3.amazonaws.com/$id/$id.tif"
    }

    override fun prefixSize(header: ByteArray, level: Int): Long =
        elevationGeotiffPrefixSize(header, level)

    override fun readGrid(bytes: ByteArray, level: Int): ElevationGrid =
        readElevationGeotiff(bytes, level)
}

/**
 * The source of the terrain of the app. A change of the source also needs a new directory of the
 * cache ([TerrainStore]), because the cache names its files by the tile only.
 */
val TERRAIN_SOURCE: ElevationSource = Copernicus
