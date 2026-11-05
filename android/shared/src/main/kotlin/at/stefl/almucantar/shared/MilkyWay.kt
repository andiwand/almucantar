package at.stefl.almucantar.shared

import at.stefl.almucantar.core.StarCatalog
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A picture of the Milky Way as many soft points in the galactic system: denser and brighter toward
 * the galactic center and in the star clouds of Cygnus, Scutum, and Carina, with the Great Rift as
 * a dark lane. The positions are J2000, so the core moves them like stars. This is a picture for
 * the eye, not a measurement. The web has the same picture (`web/shared/src/milkyway.ts`).
 * starradar and shotradar use it.
 */
class MilkyWay(
    val catalog: StarCatalog,
    /** The brightness of each point, from 0 to 1. */
    val weight: FloatArray,
    /** True for a soft cloud, false for a fine grain. */
    val cloud: BooleanArray,
) {
    companion object {
        private const val DEG = PI / 180

        // The north galactic pole and the longitude of the north celestial pole (IAU 1958, J2000).
        private const val POLE_RA = 192.85948 * DEG
        private const val POLE_DEC = 27.12825 * DEG
        private const val NCP_L = 122.93192 * DEG

        /** Converts a galactic direction to J2000 right ascension and declination, in degrees. */
        fun galacticToJ2000(lDeg: Double, bDeg: Double): Pair<Double, Double> {
            val l = lDeg * DEG
            val b = bDeg * DEG
            val sinDec = sin(POLE_DEC) * sin(b) + cos(POLE_DEC) * cos(b) * cos(NCP_L - l)
            val dec = asin(sinDec.coerceIn(-1.0, 1.0))
            val y = cos(b) * sin(NCP_L - l)
            val x = cos(POLE_DEC) * sin(b) - sin(POLE_DEC) * cos(b) * cos(NCP_L - l)
            val ra = ((POLE_RA + atan2(y, x)) / DEG % 360 + 360) % 360
            return ra to dec / DEG
        }

        private fun bump(l: Double, center: Double, width: Double): Double {
            val d = ((l - center) % 360 + 540) % 360 - 180
            return exp(-(d * d) / (2 * width * width))
        }

        /** The brightness of the band at a galactic longitude, from about 0.3 to 1. */
        fun bandBrightness(lDeg: Double): Double =
            minOf(
                1.0,
                0.3 +
                    0.7 * bump(lDeg, 0.0, 32.0) +
                    0.35 * bump(lDeg, 27.0, 9.0) +
                    0.4 * bump(lDeg, 75.0, 16.0) +
                    0.3 * bump(lDeg, -75.0, 14.0) +
                    0.15 * bump(lDeg, 180.0, 30.0),
            )

        /** The half width of the band (one standard deviation of the latitude), in degrees. */
        fun bandWidth(lDeg: Double): Double =
            3.5 + 5.5 * bump(lDeg, 0.0, 28.0) + 1.5 * bump(lDeg, 75.0, 20.0)

        /** How much the Great Rift takes away at a galactic direction: 0 (nothing) to 1 (all). */
        fun riftDarkness(lDeg: Double, bDeg: Double): Double {
            val l = ((lDeg % 360) + 540) % 360 - 180
            if (l < -15 || l > 80) return 0.0
            val center = 1.2 + 0.03 * maxOf(0.0, 40 - l)
            val width = 1.2 + 0.04 * maxOf(0.0, 45 - l)
            val along = minOf(1.0, (l + 15) / 10, (80 - l) / 15)
            return 0.85 * along * exp(-((bDeg - center) * (bDeg - center)) / (2 * width * width))
        }

        /** Makes the points: [clouds] soft clouds and [grains] fine grains, the same each time. */
        fun create(clouds: Int = 2400, grains: Int = 3600, seed: Long = 11): MilkyWay {
            val random = java.util.Random(seed)
            val gauss = {
                val u = maxOf(1e-9, random.nextDouble())
                sqrt(-2 * ln(u)) * cos(2 * PI * random.nextDouble())
            }
            val total = clouds + grains
            val ra = DoubleArray(total)
            val dec = DoubleArray(total)
            val weight = FloatArray(total)
            val cloud = BooleanArray(total)
            var n = 0
            while (n < total) {
                val l = random.nextDouble() * 360 - 180
                val isCloud = n < clouds
                val width = bandWidth(l)
                val b = gauss() * width * (if (isCloud) 1.0 else 1.25)
                val brightness = bandBrightness(l) * (1 - riftDarkness(l, b))
                if (random.nextDouble() > brightness) continue
                val (r, d) = galacticToJ2000(l, b)
                ra[n] = r
                dec[n] = d
                weight[n] = (brightness * exp(-(b * b) / (2 * width * width))).toFloat()
                cloud[n] = isCloud
                n++
            }
            return MilkyWay(StarCatalog(ra, dec), weight, cloud)
        }
    }
}
