import Foundation
import Almucantar

/// Geodesy on a sphere. For the distances of a landmark (up to some kilometres) it is sufficient.
enum Geo {
    static let earthRadiusM = 6_371_000.0

    /// The initial true bearing from `from` to `to`, in degrees from north, 0 to 360.
    static func bearingDeg(from: Observer, to: Observer) -> Double {
        let p1 = from.latitudeDeg * .pi / 180
        let p2 = to.latitudeDeg * .pi / 180
        let dl = (to.longitudeDeg - from.longitudeDeg) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    /// The great-circle distance in metres (haversine).
    static func distanceM(from: Observer, to: Observer) -> Double {
        let p1 = from.latitudeDeg * .pi / 180
        let p2 = to.latitudeDeg * .pi / 180
        let dp = p2 - p1
        let dl = (to.longitudeDeg - from.longitudeDeg) * .pi / 180
        let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * earthRadiusM * asin(sqrt(a))
    }

    /// The unit vector of a direction in east, north, up.
    static func enu(_ h: Horizontal) -> SIMD3<Double> {
        let (az, alt) = (h.azimuthDeg * .pi / 180, h.altitudeDeg * .pi / 180)
        return SIMD3(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
    }

    /// The direction of a vector in east, north, up (it need not be a unit vector).
    static func horizontal(_ v: SIMD3<Double>) -> Horizontal {
        let az = atan2(v.x, v.y) * 180 / .pi
        return Horizontal(azimuthDeg: az < 0 ? az + 360 : az, altitudeDeg: atan2(v.z, hypot(v.x, v.y)) * 180 / .pi)
    }

    /// The angle between two directions on the sky, in degrees.
    static func angleBetweenDeg(_ a: Horizontal, _ b: Horizontal) -> Double {
        let (a1, a2) = (a.altitudeDeg * .pi / 180, b.altitudeDeg * .pi / 180)
        let dz = (a.azimuthDeg - b.azimuthDeg) * .pi / 180
        let c = sin(a1) * sin(a2) + cos(a1) * cos(a2) * cos(dz)
        return acos(min(1, max(-1, c))) * 180 / .pi
    }
}
