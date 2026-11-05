package at.stefl.shotradar

import android.content.Context
import android.util.LruCache
import at.stefl.almucantar.core.ElevationGrid
import at.stefl.almucantar.core.HorizonOptions
import at.stefl.almucantar.core.HorizonProfile
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.elevationTiles
import at.stefl.almucantar.core.horizonProfile
import at.stefl.almucantar.shared.DemCache
import at.stefl.almucantar.shared.Geo
import at.stefl.almucantar.shared.TerrainStore
import at.stefl.almucantar.shared.TileLoad
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The terrain of shotradar: the horizon of a spot in bands of distance for the first-person photo,
// the skyline and its peaks, the ground at a place, the summit near a tap, and the grids for the
// line of spots and the line of sight. The grids come from the shared cache of Copernicus GLO-90
// tiles: the full level near the spot, the coarse level far away. The PWA has the same bands
// (web/alidade/app/src/horizon.ts).

/** The bands of the terrain, from near to far: the minimum and the maximum distance in meters. */
val BANDS = listOf(250.0 to 2_500.0, 2_500.0 to 9_000.0, 9_000.0 to 30_000.0, 30_000.0 to 90_000.0)

/** The step of the azimuth of the bands. 0.2° is about 6 samples across a lens of 600 mm. */
const val STEP_DEG = 0.2

/** The full grids are used for the terrain nearer than this; the coarse grids farther away. */
private const val FINE_RADIUS_M = 10_000.0

/** The horizon of a spot. */
class Horizon(
    val observer: Observer,
    /** The ground at the spot, from the full grid; 0 at sea or without data. */
    val groundM: Double,
    val bands: List<HorizonProfile>,
    /** The highest terrain of all bands, and its distance: the profile for darkWindows(). */
    val skyline: HorizonProfile,
)

/** The value of a profile at an azimuth, with a linear interpolation; NaN without data. */
fun profileAltitude(profile: HorizonProfile, azimuthDeg: Double): Double {
    val values = profile.altitudeDeg
    val n = values.size
    val position = (((azimuthDeg % 360) + 360) % 360) / profile.azimuthStepDeg
    val i = floor(position).toInt()
    val t = position - i
    val a = values[((i % n) + n) % n]
    val b = values[(((i + 1) % n) + n) % n]
    return a + t * (b - a)
}

/** The skyline of the bands: for each azimuth the highest altitude, and its distance. */
fun combine(bands: List<HorizonProfile>, observerElevationM: Double): HorizonProfile {
    val n = bands[0].altitudeDeg.size
    val altitude = DoubleArray(n) { Double.NaN }
    val distance = DoubleArray(n) { Double.NaN }
    for (band in bands) {
        for (i in 0 until n) {
            val a = band.altitudeDeg[i]
            if (a.isFinite() && !(altitude[i] >= a)) {
                altitude[i] = a
                distance[i] = band.distanceM[i]
            }
        }
    }
    return HorizonProfile(bands[0].azimuthStepDeg, altitude, distance, observerElevationM)
}

/** A prominent peak on the skyline of a spot. */
data class Peak(
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val distanceM: Double,
    /** How far the peak stands out of the skyline next to it. */
    val prominenceDeg: Double,
)

/**
 * The peaks of a skyline: the azimuths whose altitude is the highest within [windowDeg] on both
 * sides, and that stand out of the lowest point on each side by at least [minProminenceDeg]. A peak
 * nearer than [minDistanceM] is a hill next to the spot, not a landmark. The most prominent first.
 */
fun findPeaks(
    skyline: HorizonProfile,
    windowDeg: Double = 5.0,
    minProminenceDeg: Double = 0.6,
    minDistanceM: Double = 1_500.0,
    count: Int = 10,
): List<Peak> {
    val a = skyline.altitudeDeg
    val n = a.size
    val w = max(1, (windowDeg / skyline.azimuthStepDeg).roundToInt())
    fun at(i: Int) = a[((i % n) + n) % n]
    val peaks = mutableListOf<Peak>()
    for (i in 0 until n) {
        val h = a[i]
        if (!h.isFinite() || !(skyline.distanceM[i] >= minDistanceM)) continue
        var highest = true
        var lowLeft = h
        var lowRight = h
        var k = 1
        while (k <= w && highest) {
            val left = at(i - k)
            val right = at(i + k)
            // A plateau counts once: the first sample of equal values wins.
            if (left >= h || right > h) highest = false
            if (left.isFinite()) lowLeft = min(lowLeft, left)
            if (right.isFinite()) lowRight = min(lowRight, right)
            k++
        }
        if (!highest) continue
        val prominence = h - max(lowLeft, lowRight)
        if (prominence < minProminenceDeg) continue
        peaks += Peak(i * skyline.azimuthStepDeg, h, skyline.distanceM[i], prominence)
    }
    return peaks.sortedByDescending { it.prominenceDeg }.take(count)
}

/**
 * The grids of the app in memory, by the name of the tile and the level. A full grid of GLO-90 has
 * up to 1200 × 1200 heights (about 6 MB), so the cache keeps only a few of them: at most a quarter
 * of the heap, because a phone can have a heap of 256 MB.
 */
object Grids {
    private val cache =
        object :
            LruCache<String, ElevationGrid>(
                min(160L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4).toInt()
            ) {
            override fun sizeOf(key: String, value: ElevationGrid) = value.heightsM.size * 4
        }

    /** The tiles that are in the sea or that failed; they give no grid. */
    private val none = mutableSetOf<String>()

    /**
     * The grid of the tile with the south-west corner [lat], [lon], full or coarse, or null if the
     * tile is in the sea or not available (offline). It blocks: call it on [Dispatchers.IO].
     */
    fun grid(context: Context, lat: Int, lon: Int, fine: Boolean): ElevationGrid? {
        val cacheOf = TerrainStore.cache(context)
        val source = cacheOf.source
        val level = if (fine) source.fullLevel else source.coarseLevel
        val key = "${DemCache.tileName(lat, lon)}.$level"
        cache.get(key)?.let {
            return it
        }
        synchronized(none) { if (key in none) return null }
        val decode = { bytes: ByteArray -> source.readGrid(bytes, level) }
        val load =
            if (fine) cacheOf.load(lat, lon, decode = decode)
            else cacheOf.loadCoarse(lat, lon, decode = decode)
        return when (load) {
            is TileLoad.Data -> load.value.also { cache.put(key, it) }
            TileLoad.Sea,
            TileLoad.Unreadable -> {
                synchronized(none) { none += key }
                null
            }
            TileLoad.Missing -> null
        }
    }

    /** The grids of the tiles within [radiusM] of [center]. Tiles without data are left out. */
    fun around(
        context: Context,
        center: Observer,
        radiusM: Double,
        fine: Boolean,
    ): List<ElevationGrid> =
        elevationTiles(center, radiusM).mapNotNull {
            grid(context, it.latitudeDeg, it.longitudeDeg, fine)
        }
}

/**
 * Calculates the horizon of a spot: the full grids for the near bands, the coarse grids for the far
 * bands. It downloads the missing tiles first, so it can take some seconds. [onProgress] gets the
 * number of bands that are ready.
 */
suspend fun computeHorizon(
    context: Context,
    observer: Observer,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): Horizon =
    withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val fine = Grids.around(app, observer, FINE_RADIUS_M, fine = true)
        val coarse = Grids.around(app, observer, BANDS.last().second + 1_000, fine = false)
        val bands = mutableListOf<HorizonProfile>()
        var groundM = Double.NaN
        for ((b, band) in BANDS.withIndex()) {
            onProgress(b, BANDS.size)
            val (minDistanceM, maxDistanceM) = band
            val tiles = if (maxDistanceM <= FINE_RADIUS_M) fine else coarse
            // The near band has the ground at the spot; the other bands use the same eye.
            val fromGrid = b == 0 || !groundM.isFinite()
            val profile =
                try {
                    horizonProfile(
                        observer,
                        tiles,
                        HorizonOptions(
                            azimuthStepDeg = STEP_DEG,
                            minDistanceM = minDistanceM,
                            maxDistanceM = maxDistanceM,
                            useGridElevation = fromGrid,
                            observerElevationM = if (groundM.isFinite()) groundM else 0.0,
                        ),
                    )
                } catch (_: IllegalArgumentException) {
                    // No tile has data here (the sea, or offline): an empty band.
                    val n = (360 / STEP_DEG).roundToInt()
                    HorizonProfile(
                        STEP_DEG,
                        DoubleArray(n) { Double.NaN },
                        DoubleArray(n) { Double.NaN },
                        if (groundM.isFinite()) groundM else 0.0,
                    )
                }
            if (b == 0 && profile.altitudeDeg.any { it.isFinite() })
                groundM = profile.observerElevationM
            bands += profile
        }
        onProgress(BANDS.size, BANDS.size)
        val ground = if (groundM.isFinite()) groundM else 0.0
        Horizon(observer, ground, bands, combine(bands, ground))
    }

/** The grid that has the place, or null. */
private fun gridOf(grids: List<ElevationGrid>, at: Observer): ElevationGrid? =
    grids.firstOrNull { g ->
        val r = (g.northLatitudeDeg - at.latitudeDeg) / g.latitudeStepDeg
        val c = (at.longitudeDeg - g.westLongitudeDeg) / g.longitudeStepDeg
        r >= 0 && c >= 0 && r <= g.rows - 1 && c <= g.columns - 1
    }

/** The height of a grid at a place, with a bilinear interpolation; null outside or without data. */
fun groundAt(grids: List<ElevationGrid>, at: Observer): Double? {
    val g = gridOf(grids, at) ?: return null
    val r = (g.northLatitudeDeg - at.latitudeDeg) / g.latitudeStepDeg
    val c = (at.longitudeDeg - g.westLongitudeDeg) / g.longitudeStepDeg
    val r0 = min(floor(r).toInt(), g.rows - 2)
    val c0 = min(floor(c).toInt(), g.columns - 2)
    val tr = r - r0
    val tc = c - c0
    fun h(rr: Int, cc: Int) = g.heightsM[rr * g.columns + cc].toDouble()
    val v =
        (1 - tr) * ((1 - tc) * h(r0, c0) + tc * h(r0, c0 + 1)) +
            tr * ((1 - tc) * h(r0 + 1, c0) + tc * h(r0 + 1, c0 + 1))
    return v.takeIf { it.isFinite() }
}

/** The ground at a place from the full grid, or null offline or at sea. It blocks. */
fun groundAt(context: Context, at: Observer): Double? {
    val lat = floor(at.latitudeDeg).toInt()
    val lon = floor(at.longitudeDeg).toInt()
    val g = Grids.grid(context.applicationContext, lat, lon, fine = true) ?: return null
    return groundAt(listOf(g), at)
}

/** The highest point of the full grid within [radiusM] of [at], or null offline. It blocks. */
fun summitNear(context: Context, at: Observer, radiusM: Double): Observer? {
    val lat = floor(at.latitudeDeg).toInt()
    val lon = floor(at.longitudeDeg).toInt()
    val g = Grids.grid(context.applicationContext, lat, lon, fine = true) ?: return null
    val metersPerRow = g.latitudeStepDeg * 111_195.0
    val metersPerColumn = g.longitudeStepDeg * 111_195.0 * cos(Math.toRadians(at.latitudeDeg))
    val r0 = ((g.northLatitudeDeg - at.latitudeDeg) / g.latitudeStepDeg).roundToInt()
    val c0 = ((at.longitudeDeg - g.westLongitudeDeg) / g.longitudeStepDeg).roundToInt()
    val dr = max(1, (radiusM / metersPerRow).roundToInt())
    val dc = max(1, (radiusM / metersPerColumn).roundToInt())
    var best = Float.NEGATIVE_INFINITY
    var bestR = r0
    var bestC = c0
    for (r in max(0, r0 - dr)..min(g.rows - 1, r0 + dr)) {
        for (c in max(0, c0 - dc)..min(g.columns - 1, c0 + dc)) {
            val y = (r - r0) * metersPerRow
            val x = (c - c0) * metersPerColumn
            if (x * x + y * y > radiusM * radiusM) continue
            val h = g.heightsM[r * g.columns + c]
            if (h > best) {
                best = h
                bestR = r
                bestC = c
            }
        }
    }
    if (best == Float.NEGATIVE_INFINITY) return null
    return Observer(
        g.northLatitudeDeg - bestR * g.latitudeStepDeg,
        g.westLongitudeDeg + bestC * g.longitudeStepDeg,
    )
}

/**
 * The full grids for a line from [a] to [b] (a line of sight, or a line of spots): the tiles within
 * [marginM] of the points along the line. It blocks.
 */
fun gridsAlong(
    context: Context,
    a: Observer,
    b: Observer,
    marginM: Double = 2_000.0,
): List<ElevationGrid> {
    val length = Geo.distanceM(a, b)
    val steps = max(1, (length / 20_000).toInt())
    val names = linkedSetOf<Pair<Int, Int>>()
    for (i in 0..steps) {
        val t = i.toDouble() / steps
        val lon = a.longitudeDeg + t * lonDelta(a, b)
        val p =
            Observer(a.latitudeDeg + t * (b.latitudeDeg - a.latitudeDeg), (lon + 540) % 360 - 180)
        for (tile in elevationTiles(p, marginM + length / steps)) names +=
            tile.latitudeDeg to tile.longitudeDeg
    }
    return names.mapNotNull { (lat, lon) ->
        Grids.grid(context.applicationContext, lat, lon, fine = true)
    }
}

/** The difference of longitude from [a] to [b], across the date line if that is shorter. */
private fun lonDelta(a: Observer, b: Observer): Double {
    var d = b.longitudeDeg - a.longitudeDeg
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
}

/**
 * The ground before a landmark that is the landmark itself, for lineOfSight(): the DEM smooths a
 * summit, so its flanks just below the top can hide the top. 8 % of the distance, 300 m to 1.5 km.
 */
fun targetMarginM(distanceM: Double) = min(1_500.0, max(300.0, 0.08 * abs(distanceM)))
