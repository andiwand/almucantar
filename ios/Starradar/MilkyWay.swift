import Almucantar
import Foundation

/// A picture of the Milky Way as many soft points in the galactic system: denser and brighter toward
/// the galactic center and in the star clouds of Cygnus, Scutum, and Carina, with the Great Rift as
/// a dark lane. The positions are J2000, so the core moves them like stars. This is a picture for
/// the eye, not a measurement. The Android app makes the same points (`MilkyWay.kt`), and the web
/// has the same picture (`web/shared/src/milkyway.ts`).
struct MilkyWay: Sendable {
    let catalog: StarCatalog
    /// The brightness of each point, from 0 to 1.
    let weight: [Double]
    /// True for a soft cloud, false for a fine grain.
    let cloud: [Bool]

    private static let deg = Double.pi / 180
    // The north galactic pole and the longitude of the north celestial pole (IAU 1958, J2000).
    private static let poleRa = 192.85948 * deg
    private static let poleDec = 27.12825 * deg
    private static let ncpL = 122.93192 * deg

    /// Converts a galactic direction to J2000 right ascension and declination, in degrees.
    static func galacticToJ2000(_ lDeg: Double, _ bDeg: Double) -> (raDeg: Double, decDeg: Double) {
        let l = lDeg * deg
        let b = bDeg * deg
        let sinDec = sin(poleDec) * sin(b) + cos(poleDec) * cos(b) * cos(ncpL - l)
        let dec = asin(min(1, max(-1, sinDec)))
        let y = cos(b) * sin(ncpL - l)
        let x = cos(poleDec) * sin(b) - sin(poleDec) * cos(b) * cos(ncpL - l)
        let ra = ((poleRa + atan2(y, x)) / deg).truncatingRemainder(dividingBy: 360)
        return ((ra + 360).truncatingRemainder(dividingBy: 360), dec / deg)
    }

    private static func bump(_ l: Double, _ center: Double, _ width: Double) -> Double {
        // truncatingRemainder keeps the sign of the dividend, the same as % in Kotlin.
        let d = ((l - center).truncatingRemainder(dividingBy: 360) + 540).truncatingRemainder(dividingBy: 360) - 180
        return exp(-(d * d) / (2 * width * width))
    }

    /// The brightness of the band at a galactic longitude, from about 0.3 to 1.
    static func bandBrightness(_ lDeg: Double) -> Double {
        min(
            1,
            0.3 + 0.7 * bump(lDeg, 0, 32) + 0.35 * bump(lDeg, 27, 9) + 0.4 * bump(lDeg, 75, 16)
                + 0.3 * bump(lDeg, -75, 14) + 0.15 * bump(lDeg, 180, 30))
    }

    /// The half width of the band (one standard deviation of the latitude), in degrees.
    static func bandWidth(_ lDeg: Double) -> Double {
        3.5 + 5.5 * bump(lDeg, 0, 28) + 1.5 * bump(lDeg, 75, 20)
    }

    /// How much the Great Rift takes away at a galactic direction: 0 (nothing) to 1 (all).
    static func riftDarkness(_ lDeg: Double, _ bDeg: Double) -> Double {
        let l = (lDeg.truncatingRemainder(dividingBy: 360) + 540).truncatingRemainder(dividingBy: 360) - 180
        if l < -15 || l > 80 { return 0 }
        let center = 1.2 + 0.03 * max(0, 40 - l)
        let width = 1.2 + 0.04 * max(0, 45 - l)
        let along = min(1, (l + 15) / 10, (80 - l) / 15)
        return 0.85 * along * exp(-((bDeg - center) * (bDeg - center)) / (2 * width * width))
    }

    /// Makes the points: `clouds` soft clouds and `grains` fine grains, the same each time.
    static func create(clouds: Int = 2400, grains: Int = 3600, seed: Int64 = 11) -> MilkyWay {
        var random = JavaRandom(seed: seed)
        func gauss() -> Double {
            let u = max(1e-9, random.nextDouble())
            return sqrt(-2 * log(u)) * cos(2 * .pi * random.nextDouble())
        }
        let total = clouds + grains
        var ra = [Double](repeating: 0, count: total)
        var dec = [Double](repeating: 0, count: total)
        var weight = [Double](repeating: 0, count: total)
        var cloud = [Bool](repeating: false, count: total)
        var n = 0
        while n < total {
            let l = random.nextDouble() * 360 - 180
            let isCloud = n < clouds
            let width = bandWidth(l)
            let b = gauss() * width * (isCloud ? 1 : 1.25)
            let brightness = bandBrightness(l) * (1 - riftDarkness(l, b))
            if random.nextDouble() > brightness { continue }
            (ra[n], dec[n]) = galacticToJ2000(l, b)
            weight[n] = brightness * exp(-(b * b) / (2 * width * width))
            cloud[n] = isCloud
            n += 1
        }
        return MilkyWay(catalog: StarCatalog(raDeg: ra, decDeg: dec), weight: weight, cloud: cloud)
    }
}

/// The random numbers of `java.util.Random` (a linear congruential generator of 48 bits), so the
/// points are the same as in the Android app.
struct JavaRandom {
    private var seed: UInt64
    private static let multiplier: UInt64 = 0x5_DEEC_E66D
    private static let mask: UInt64 = (1 << 48) - 1

    init(seed: Int64) {
        self.seed = (UInt64(bitPattern: seed) ^ Self.multiplier) & Self.mask
    }

    private mutating func next(_ bits: Int) -> UInt64 {
        seed = (seed &* Self.multiplier &+ 0xB) & Self.mask
        return seed >> (48 - bits)
    }

    /// A value in [0, 1) with 53 random bits.
    mutating func nextDouble() -> Double {
        Double((next(26) << 27) + next(27)) * 0x1.0p-53
    }
}
