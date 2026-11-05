package at.stefl.sternstunde

import androidx.compose.ui.graphics.Color
import at.stefl.almucantar.core.DirectionLine
import at.stefl.almucantar.core.GALACTIC_CENTER_LATITUDE_DEG
import at.stefl.almucantar.core.GALACTIC_CENTER_LONGITUDE_DEG
import at.stefl.almucantar.core.HorizonProfile
import at.stefl.almucantar.core.Horizontal
import at.stefl.almucantar.core.Observer
import at.stefl.almucantar.core.Planet
import at.stefl.almucantar.core.StarCatalog
import at.stefl.almucantar.core.galacticPlane
import at.stefl.almucantar.core.galacticPosition
import at.stefl.almucantar.core.moonIlluminatedFraction
import at.stefl.almucantar.core.moonPosition
import at.stefl.almucantar.core.planetMagnitude
import at.stefl.almucantar.core.planetPosition
import at.stefl.almucantar.core.starPositions
import at.stefl.almucantar.core.sunPosition
import at.stefl.almucantar.shared.MilkyWay
import at.stefl.almucantar.shared.MoonAt

/** A planet at the selected time. */
data class PlanetAt(
    val planet: Planet,
    val name: String,
    val color: Color,
    val direction: Horizontal,
    val mag: Double,
)

/**
 * The sky at one time and place: the directions of all stars, of the names of the constellations,
 * and of the points of the Milky Way (flat arrays [azimuth0, altitude0, ...]), the planets, the
 * sun, the moon, and the center of the Milky Way. It takes a few milliseconds, so the screen makes
 * it once per second; a frame only projects it.
 */
class SkyAt(
    val unixUtc: Double,
    val observer: Observer,
    val stars: DoubleArray,
    val labels: DoubleArray,
    val dust: DoubleArray,
    val planets: List<PlanetAt>,
    val sun: Horizontal,
    val moon: MoonAt,
    val milkyWay: DirectionLine,
    val galacticCenter: Horizontal,
) {
    fun star(index: Int) = Horizontal(stars[2 * index], stars[2 * index + 1])

    fun label(index: Int) = Horizontal(labels[2 * index], labels[2 * index + 1])

    companion object {
        fun compute(
            data: StarData,
            milkyWay: MilkyWay,
            labels: StarCatalog,
            unixUtc: Double,
            observer: Observer,
        ) =
            SkyAt(
                unixUtc,
                observer,
                starPositions(data.catalog, unixUtc, observer),
                starPositions(labels, unixUtc, observer),
                starPositions(milkyWay.catalog, unixUtc, observer),
                PLANETS.map { (planet, style) ->
                    PlanetAt(
                        planet,
                        style.first,
                        style.second,
                        planetPosition(planet, unixUtc, observer),
                        planetMagnitude(planet, unixUtc),
                    )
                },
                sunPosition(unixUtc, observer),
                MoonAt(moonPosition(unixUtc, observer), moonIlluminatedFraction(unixUtc)),
                galacticPlane(unixUtc, observer, 2.0),
                galacticPosition(
                    GALACTIC_CENTER_LONGITUDE_DEG,
                    GALACTIC_CENTER_LATITUDE_DEG,
                    unixUtc,
                    observer,
                ),
            )

        /** The names of the constellations as a small catalogue, so the core moves them too. */
        fun labelCatalog(data: StarData) =
            StarCatalog(
                data.constellations.map { it.labelRaDeg }.toDoubleArray(),
                data.constellations.map { it.labelDecDeg }.toDoubleArray(),
            )
    }
}

/** An object that the user can tap, find, or align with. */
sealed interface Target {
    data class Star(val index: Int) : Target

    data class PlanetTarget(val planet: Planet) : Target

    data object Moon : Target

    data object Sun : Target

    data class ConstellationTarget(val index: Int) : Target

    data object GalacticCenter : Target
}

fun Target.name(data: StarData): String =
    when (this) {
        is Target.Star -> data.names[index] ?: "HR ${data.hr[index]}"
        is Target.PlanetTarget -> PLANETS.first { it.first == planet }.second.first
        Target.Moon -> "Moon"
        Target.Sun -> "Sun"
        is Target.ConstellationTarget -> data.constellations[index].name
        Target.GalacticCenter -> "Center of the Milky Way"
    }

fun Target.direction(sky: SkyAt): Horizontal =
    when (this) {
        is Target.Star -> sky.star(index)
        is Target.PlanetTarget -> sky.planets.first { it.planet == planet }.direction
        Target.Moon -> sky.moon.direction
        Target.Sun -> sky.sun
        is Target.ConstellationTarget -> sky.label(index)
        Target.GalacticCenter -> sky.galacticCenter
    }

/** The direction of a target at another time, for its rise and set. */
fun Target.directionAt(data: StarData, unixUtc: Double, observer: Observer): Horizontal =
    when (this) {
        is Target.Star -> {
            val c = data.catalog
            val one =
                StarCatalog(
                    doubleArrayOf(c.raDeg[index]),
                    doubleArrayOf(c.decDeg[index]),
                    doubleArrayOf(c.pmRaArcsecPerYear[index]),
                    doubleArrayOf(c.pmDecArcsecPerYear[index]),
                )
            starPositions(one, unixUtc, observer).let { Horizontal(it[0], it[1]) }
        }
        is Target.PlanetTarget -> planetPosition(planet, unixUtc, observer)
        Target.Moon -> moonPosition(unixUtc, observer)
        Target.Sun -> sunPosition(unixUtc, observer)
        is Target.ConstellationTarget -> {
            val c = data.constellations[index]
            starPositions(
                    StarCatalog(doubleArrayOf(c.labelRaDeg), doubleArrayOf(c.labelDecDeg)),
                    unixUtc,
                    observer,
                )
                .let { Horizontal(it[0], it[1]) }
        }
        Target.GalacticCenter ->
            galacticPosition(
                GALACTIC_CENTER_LONGITUDE_DEG,
                GALACTIC_CENTER_LATITUDE_DEG,
                unixUtc,
                observer,
            )
    }

/** The next rise and set in 24 hours; [always] is true (up), false (down), or null (both). */
data class RiseSet(val rise: Double?, val set: Double?, val always: Boolean?)

/**
 * The altitude of the visible horizon in a direction: the terrain, or 0° without data. It has the
 * interpolation of `terrainAltitudeDeg` of the core in Kotlin, because each native call copies the
 * whole profile, and the screen asks for each star in each frame.
 */
fun horizonAt(profile: HorizonProfile?, azimuthDeg: Double): Double {
    val altitude = profile?.altitudeDeg ?: return 0.0
    val n = altitude.size
    val position = ((azimuthDeg % 360 + 360) % 360) / profile.azimuthStepDeg
    val i = minOf(position.toInt(), n - 1)
    val a = altitude[i]
    val h = a + (position - i) * (altitude[(i + 1) % n] - a)
    return if (h.isNaN()) 0.0 else h
}

/**
 * The next rise and set of a target in the 24 hours after [unixUtc], over the terrain if there is a
 * [profile]: samples every 10 minutes, and a linear interpolation between them (about a minute).
 */
fun Target.riseAndSet(
    data: StarData,
    unixUtc: Double,
    observer: Observer,
    profile: HorizonProfile?,
): RiseSet {
    val step = 600.0
    var rise: Double? = null
    var set: Double? = null
    var up = 0
    var previousTime = 0.0
    var previousHeight = Double.NaN
    for (k in 0..144) {
        val t = unixUtc + k * step
        val d = directionAt(data, t, observer)
        val height = d.altitudeDeg - horizonAt(profile, d.azimuthDeg)
        if (height > 0) up++
        if (!previousHeight.isNaN() && (previousHeight > 0) != (height > 0)) {
            val crossing = previousTime + step * previousHeight / (previousHeight - height)
            if (height > 0 && rise == null) rise = crossing
            if (height <= 0 && set == null) set = crossing
        }
        previousTime = t
        previousHeight = height
    }
    return RiseSet(
        rise,
        set,
        when (up) {
            145 -> true
            0 -> false
            else -> null
        },
    )
}
