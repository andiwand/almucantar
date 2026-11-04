import Almucantar
import SwiftUI
import simd

/// The drawing helpers of the sky views: points, lines, upright labels, the arrow at the border of
/// the screen, the terrain fill, and the moon with its phase.
enum SkyDrawing {
    /// The point of `direction` on the screen, or nil if it is not in front of the camera.
    static func point(_ view: Almucantar.View, _ direction: Horizontal) -> CGPoint? {
        guard let p = try? project(view, direction), p.valid else { return nil }
        return CGPoint(x: p.point.x, y: p.point.y)
    }

    /// One path with all polylines of a projection.
    static func path(_ lines: [[ScreenPoint]]) -> Path {
        var path = Path()
        for line in lines where line.count >= 2 {
            path.move(to: CGPoint(x: line[0].x, y: line[0].y))
            for p in line.dropFirst() { path.addLine(to: CGPoint(x: p.x, y: p.y)) }
        }
        return path
    }

    /// Draws `text` upright relative to the horizon: its top points to a point higher in the sky.
    /// The text is `gap` points above the direction.
    static func label(
        _ context: inout GraphicsContext, _ view: Almucantar.View, _ direction: Horizontal, _ text: Text, gap: Double
    ) {
        guard let at = point(view, direction) else { return }
        let higher = point(view, Horizontal(azimuthDeg: direction.azimuthDeg, altitudeDeg: min(direction.altitudeDeg + 0.5, 89.9)))
        let angle = higher.map { atan2($0.x - at.x, -($0.y - at.y)) } ?? 0
        var c = context
        c.translateBy(x: at.x, y: at.y)
        c.rotate(by: .radians(angle))
        c.addFilter(.shadow(color: .black, radius: 3))
        c.draw(text, at: CGPoint(x: 0, y: -gap))
    }

    /// If `target` is not on the screen, the point on the border of the screen in the direction of
    /// `target`, seen from `from` (the center of the view), and the unit direction to go on. Nil
    /// if the target is visible.
    static func edgeIndicator(_ view: Almucantar.View, from: Horizontal, to target: Horizontal) -> (CGPoint, CGPoint)? {
        let bounds = CGRect(x: 0, y: 0, width: view.camera.width, height: view.camera.height).insetBy(dx: 28, dy: 28)
        let inside = { (h: Horizontal) in point(view, h).map { bounds.contains($0) } ?? false }
        if inside(target) { return nil }
        let a = Geo.enu(from)
        var b = Geo.enu(target)
        // Directly behind the user, the way is not defined; take a way to the right.
        if simd_length_squared(a + b) < 1e-6 {
            b = Geo.enu(Horizontal(azimuthDeg: from.azimuthDeg + 179, altitudeDeg: from.altitudeDeg))
        }
        let along = { (t: Double) in Geo.horizontal(a + (b - a) * t) }
        var (lo, hi) = (0.0, 1.0)
        for _ in 0..<24 {
            let mid = (lo + hi) / 2
            if inside(along(mid)) { lo = mid } else { hi = mid }
        }
        guard let edge = point(view, along(lo)), let before = point(view, along(lo * 0.9)) else { return nil }
        let (dx, dy) = (edge.x - before.x, edge.y - before.y)
        let length = hypot(dx, dy)
        return length > 0 ? (edge, CGPoint(x: dx / length, y: dy / length)) : nil
    }

    /// Draws an arrow with its tip at `at` that points in the unit `direction`.
    static func drawArrow(_ context: inout GraphicsContext, at: CGPoint, direction: CGPoint, color: Color) {
        let back = CGPoint(x: at.x - direction.x * 18, y: at.y - direction.y * 18)
        let side = CGPoint(x: -direction.y * 11, y: direction.x * 11)
        var arrow = Path()
        arrow.move(to: at)
        arrow.addLine(to: CGPoint(x: back.x + side.x, y: back.y + side.y))
        arrow.addLine(to: CGPoint(x: back.x - side.x, y: back.y - side.y))
        arrow.closeSubpath()
        context.fill(arrow, with: .color(color))
    }

    /// Fills the area below the terrain line, in narrow parts of the azimuth. The projection cuts a
    /// part at the border of the view, so a narrow part keeps the error of the cut small.
    static func fillTerrain(_ context: inout GraphicsContext, _ view: Almucantar.View, lines: [[Horizontal]], color: Color) {
        let chunk = 12
        var fill = Path()
        for line in lines {
            var start = 0
            while start < line.count - 1 {
                let top = Array(line[start...min(start + chunk, line.count - 1)])
                start += chunk
                let bottom = [top.last!, top.first!].map { Horizontal(azimuthDeg: $0.azimuthDeg, altitudeDeg: -89) }
                guard let pieces = try? projectLine(view, top + bottom + [top[0]]), !pieces.isEmpty else { continue }
                // The last piece continues into the first one, because the polygon is closed.
                let ordered = pieces.count > 1 ? [pieces.last!] + pieces.dropLast() : pieces
                let points = ordered.flatMap { $0 }
                fill.move(to: CGPoint(x: points[0].x, y: points[0].y))
                for p in points.dropFirst() { fill.addLine(to: CGPoint(x: p.x, y: p.y)) }
                fill.closeSubpath()
            }
        }
        context.fill(fill, with: .color(color))
    }

    /// Draws the moon at `at` with the lit part toward the sun. The direction of the sun on the screen
    /// comes from a point 1° from the moon toward the sun, see `MoonPhase.towardSun`.
    static func drawMoon(
        _ context: inout GraphicsContext, _ view: Almucantar.View, _ moon: MoonAt, sun: Horizontal?, at: CGPoint, radius: CGFloat
    ) {
        var u = CGPoint(x: 0, y: -1)
        if let sun, let q = point(view, MoonPhase.towardSun(moon.direction, sun)) {
            let length = hypot(q.x - at.x, q.y - at.y)
            if length > 1e-3 { u = CGPoint(x: (q.x - at.x) / length, y: (q.y - at.y) / length) }
        }
        let v = CGPoint(x: -u.y, y: u.x)
        let color = Color(white: 0.88)
        let disc = CGRect(x: at.x - radius, y: at.y - radius, width: 2 * radius, height: 2 * radius)
        context.fill(Path(ellipseIn: disc), with: .color(color.opacity(0.22)))
        var lit = Path()
        for (i, p) in MoonPhase.litOutline(moon.fraction).enumerated() {
            let q = CGPoint(x: at.x + (u.x * p.x + v.x * p.y) * radius, y: at.y + (u.y * p.x + v.y * p.y) * radius)
            if i == 0 { lit.move(to: q) } else { lit.addLine(to: q) }
        }
        lit.closeSubpath()
        context.fill(lit, with: .color(color))
    }
}

/// The orientation of the view without the sensors and from the interface.
enum SkyOrientation {
    /// The rotation of the screen content, from the orientation of the interface.
    @MainActor
    static func screenRotation() -> ScreenRotation {
        let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        switch scene?.interfaceOrientation {
        case .landscapeRight: return .rotation90  // home button right: the device top is left
        case .landscapeLeft: return .rotation270
        case .portraitUpsideDown: return .rotation180
        default: return .rotation0
        }
    }

    /// A device upright in portrait, with the back camera to the azimuth, pitched up.
    static func upright(azimuthDeg: Double, pitchDeg: Double) -> Almucantar.Quaternion {
        // The rotation about east by 90° + pitch, then about up by -azimuth.
        let a = -azimuthDeg * .pi / 180
        let p = (90 + pitchDeg) * .pi / 180
        let qz = (w: cos(a / 2), z: sin(a / 2))
        let qx = (w: cos(p / 2), x: sin(p / 2))
        return Almucantar.Quaternion(w: qz.w * qx.w, x: qz.w * qx.x, y: qz.z * qx.x, z: qz.z * qx.w)
    }
}

extension SwiftUI.View {
    /// The dark panel of the sky views, for a text over the sky or the camera image.
    func skyPanel() -> some SwiftUI.View {
        foregroundColor(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
    }
}
