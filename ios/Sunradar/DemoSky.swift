import Sunradar
import SwiftUI

/// The drawn sky of the store screenshots: it replaces the camera image in the demo mode. It is the
/// sky of the website (website/src/sky.ts), drawn with the projection of the app: the gradient from
/// the altitude of the sun, the glow, the stars, and the skyline with its lit windows.
struct DemoSky: SwiftUI.View {
    let view: Sunradar.View
    let sun: Horizontal?

    var body: some SwiftUI.View {
        Canvas { context, size in
            DemoSkyDrawing.draw(&context, size: size, view: view, sun: sun)
        }
    }
}

/// A small random generator with a seed: the generator of the website, with the same numbers. The
/// arithmetic wraps at 32 bits, as Math.imul and the bit operators of JavaScript do.
struct SeededRandom {
    private var state: UInt32

    init(seed: UInt32) { state = seed }

    mutating func next() -> Double {
        state = state &+ 0x6d2b_79f5
        var t = state
        t = (t ^ (t >> 15)) &* (t | 1)
        t ^= t &+ ((t ^ (t >> 7)) &* (t | 61))
        return Double(t ^ (t >> 14)) / 4_294_967_296
    }
}

enum DemoSkyDrawing {
    typealias Rgb = (Double, Double, Double)

    /// The colors of the sky at the top and at the horizon, for a sun altitude.
    static let stops: [(Double, Rgb, Rgb)] = [
        (-18, (3, 6, 15), (10, 18, 38)),
        (-8, (10, 20, 46), (52, 40, 86)),
        (-2, (24, 42, 86), (196, 104, 92)),
        (3, (34, 70, 132), (242, 160, 96)),
        (12, (44, 96, 168), (236, 200, 150)),
        (30, (40, 106, 190), (150, 202, 238)),
    ]

    struct Star {
        let azimuth, altitude, size, phase: Double
    }

    struct Light {
        let x, y: Double
    }

    struct Building {
        let azimuth, width, height, roof: Double
        let lights: [Light]
    }

    static let stars: [Star] = {
        var random = SeededRandom(seed: 7)
        return (0..<420).map { _ in
            let azimuth = random.next() * 360
            // More stars high in the sky: the area of a band of altitude goes with its cosine.
            let altitude = asin(random.next()) * 180 / .pi
            let size = random.next() < 0.92 ? 0.6 + random.next() * 0.6 : 1.3 + random.next() * 0.7
            return Star(azimuth: azimuth, altitude: altitude, size: size, phase: random.next() * 2 * .pi)
        }
    }()

    static let buildings: [Building] = {
        var random = SeededRandom(seed: 42)
        var result: [Building] = []
        var azimuth = 0.0
        while azimuth < 360 {
            let width = 2 + random.next() * 7
            // Lower buildings in the south, so the low winter sun stays visible.
            let south = cos((azimuth - 180) * .pi / 180)
            let height = (0.6 + random.next() * 4.2) * (1 - 0.45 * max(0, south))
            var lights: [Light] = []
            var i = 0.0
            while i < width * height * 0.5 {
                if random.next() < 0.35 {
                    let x = random.next()
                    lights.append(Light(x: x, y: 0.15 + random.next() * 0.75))
                }
                i += 1
            }
            result.append(Building(azimuth: azimuth, width: width, height: height, roof: random.next(), lights: lights))
            azimuth += width + (random.next() < 0.25 ? random.next() * 3 : 0)
        }
        return result
    }()

    static func mix(_ a: Rgb, _ b: Rgb, _ t: Double) -> Rgb {
        (a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t, a.2 + (b.2 - a.2) * t)
    }

    static func color(_ c: Rgb, _ alpha: Double = 1) -> Color {
        Color(.sRGB, red: c.0 / 255, green: c.1 / 255, blue: c.2 / 255, opacity: alpha)
    }

    static func skyColors(_ altitudeDeg: Double) -> (Rgb, Rgb) {
        if altitudeDeg <= stops[0].0 { return (stops[0].1, stops[0].2) }
        for i in 1..<stops.count where altitudeDeg <= stops[i].0 {
            let t = (altitudeDeg - stops[i - 1].0) / (stops[i].0 - stops[i - 1].0)
            return (mix(stops[i - 1].1, stops[i].1, t), mix(stops[i - 1].2, stops[i].2, t))
        }
        return (stops[stops.count - 1].1, stops[stops.count - 1].2)
    }

    private static func point(_ view: Sunradar.View, _ azimuth: Double, _ altitude: Double) -> CGPoint? {
        guard let p = try? project(view, Horizontal(azimuthDeg: azimuth, altitudeDeg: altitude)), p.valid else {
            return nil
        }
        return CGPoint(x: p.point.x, y: p.point.y)
    }

    static func draw(_ context: inout GraphicsContext, size: CGSize, view: Sunradar.View, sun: Horizontal?) {
        let sunAltitude = sun?.altitudeDeg ?? -30
        let day = min(1, max(0, (sunAltitude + 6) / 12))
        let full = CGRect(origin: .zero, size: size)

        // The horizon is a horizontal line, because the view has no roll. Its height comes from the
        // direction in the middle of the view.
        let center = (try? unproject(view, ScreenPoint(x: view.camera.cx, y: view.camera.cy)))?.azimuthDeg ?? 180
        let horizonY = point(view, center, 0)?.y ?? size.height
        let ground = mix((5, 7, 13), (22, 30, 44), day)

        // The sky.
        let (top, low) = skyColors(sunAltitude)
        context.fill(
            Path(CGRect(x: 0, y: 0, width: size.width, height: max(0, horizonY))),
            with: .linearGradient(
                Gradient(colors: [color(top), color(low)]), startPoint: .zero,
                endPoint: CGPoint(x: 0, y: max(1, horizonY))))

        // The glow around the sun, also when it is a little below the horizon.
        if let sun, sun.altitudeDeg > -12, let s = point(view, sun.azimuthDeg, sun.altitudeDeg) {
            let strength = min(1, (sun.altitudeDeg + 12) / 14)
            context.fill(
                Path(full),
                with: .radialGradient(
                    Gradient(stops: [
                        .init(color: color((255, 214, 140), 0.42 * strength), location: 0),
                        .init(color: color((255, 170, 90), 0.12 * strength), location: 0.35),
                        .init(color: color((255, 170, 90), 0), location: 1),
                    ]), center: s, startRadius: 0, endRadius: size.height * 0.7))
        }

        // The stars.
        let starAlpha = min(1, max(0, (-sunAltitude - 4) / 8))
        if starAlpha > 0 {
            for star in stars {
                guard let p = point(view, star.azimuth, star.altitude), full.insetBy(dx: -2, dy: -2).contains(p),
                    p.y < horizonY
                else { continue }
                let twinkle = 0.7 + 0.3 * sin(star.phase)
                let alpha = starAlpha * twinkle * (star.size > 1.2 ? 0.95 : 0.6)
                let r = star.size
                context.fill(
                    Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r)),
                    with: .color(.white.opacity(alpha)))
            }
        }

        // The ground and the skyline.
        context.fill(
            Path(CGRect(x: 0, y: horizonY, width: size.width, height: max(0, size.height - horizonY))),
            with: .color(color(ground)))
        let lightAlpha = day < 0.7 ? 0.75 * (1 - day / 0.7) : 0
        for building in buildings {
            let a0 = building.azimuth
            let a1 = building.azimuth + building.width
            let h = building.height
            // The outline in sky directions. A flat roof gets points between its corners, because
            // a line of constant altitude is curved in the projection.
            var outline: [(Double, Double)] = [(a0, -0.5)]
            if building.roof < 0.2 {
                outline += [(a0, h * 0.85), ((a0 + a1) / 2, h * 1.12), (a1, h * 0.85)]
            } else {
                outline += (0...4).map { (a0 + (a1 - a0) * Double($0) / 4, h) }
            }
            outline.append((a1, -0.5))
            let points = outline.compactMap { point(view, $0.0, $0.1) }
            guard points.count == outline.count, points.contains(where: { full.contains($0) }) else { continue }
            var shape = Path()
            shape.addLines(points)
            shape.closeSubpath()
            context.fill(shape, with: .color(color(ground)))
            if lightAlpha > 0 {
                for light in building.lights {
                    guard
                        let p = point(
                            view, a0 + light.x * (building.width - 0.4), h * (1 - light.y))
                    else { continue }
                    context.fill(
                        Path(CGRect(x: p.x, y: p.y, width: 2, height: 2.5)),
                        with: .color(color((255, 206, 120), lightAlpha)))
                }
            }
        }
    }
}
