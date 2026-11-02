import Foundation
import Sunradar

/// The moon at the selected time: its direction and the illuminated fraction, from 0 to 1.
struct MoonAt {
    let direction: Horizontal
    let fraction: Double
}

/// The shape of the lit part of the moon. The bright side of the moon faces the sun, along the great
/// circle from the moon to the sun. The Android app and the PWA use the same calculation.
enum MoonPhase {
    /// The direction at `angleDeg` from `moon` toward `sun`, on the great circle through both. The app
    /// projects the moon and this direction to get the direction of the sun on the screen. The two
    /// points are near each other, so the projection works also when the sun is behind the camera. If
    /// the sun is exactly opposite the moon, the direction goes up.
    static func towardSun(_ moon: Horizontal, _ sun: Horizontal, angleDeg: Double = 1) -> Horizontal {
        let m = Geo.enu(moon)
        let s = Geo.enu(sun)
        var t = s - (m * s).sum() * m
        if (t * t).sum() < 1e-18 {
            // Up, without the part along the moon.
            t = SIMD3(0, 0, 1) - m.z * m
            if (t * t).sum() < 1e-18 { return moon }
        }
        let a = angleDeg * .pi / 180
        return Geo.horizontal(m * cos(a) + t / (t * t).sum().squareRoot() * sin(a))
    }

    /// The outline of the lit part on a unit disc. The x axis points to the sun. The outline is the half
    /// of the limb that faces the sun, then the terminator back: a half ellipse with the semi-axis
    /// 1 - 2 * `fraction` along x. So the area is pi * `fraction`.
    static func litOutline(_ fraction: Double, steps: Int = 24) -> [CGPoint] {
        let k = 1 - 2 * min(1, max(0, fraction))
        let limb = (0...steps).map { i -> CGPoint in
            let angle = -Double.pi / 2 + Double.pi * Double(i) / Double(steps)
            return CGPoint(x: cos(angle), y: sin(angle))
        }
        let terminator = (0...steps).map { i -> CGPoint in
            let angle = Double.pi / 2 - Double.pi * Double(i) / Double(steps)
            return CGPoint(x: k * cos(angle), y: sin(angle))
        }
        return limb + terminator
    }
}
