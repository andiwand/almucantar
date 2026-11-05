import Almucantar
import SwiftUI

/// The bright look of the day of Sonnseite, the same as the PWA (web/sonnseite/app): the sky view
/// in the colors of the height of the sun, warm white panels with dark text, and sun yellow
/// buttons. Sternstunde keeps the dark look of `skyPanel()`.
enum SunLook {
    /// A color from its hex value, for example 0x1f2633.
    static func hex(_ value: UInt32, _ opacity: Double = 1) -> Color {
        Color(
            .sRGB, red: Double((value >> 16) & 0xff) / 255, green: Double((value >> 8) & 0xff) / 255,
            blue: Double(value & 0xff) / 255, opacity: opacity)
    }

    /// The dark text on the panels, and the labels on a bright sky.
    static let ink = hex(0x1f2633)
    static let secondary = hex(0x485264)
    /// The big time of the readout.
    static let amber = hex(0xb45f06)
    /// The background of the panels and the buttons: warm white.
    static let panel = Color(.sRGB, red: 1, green: 252 / 255, blue: 245 / 255, opacity: 0.9)
    static let slider = hex(0xf0a91c)
    /// The text on the sun yellow of the main button.
    static let onSun = hex(0x2b1d00)
    static let good = hex(0x2e7d32)
    static let fair = hex(0x9a6700)
    static let poor = hex(0xc2410c)
    /// The vertical line and the ring of the crosshair on a bright sky.
    static let helper = hex(0x1f4f8f)
    /// The light halo of a dark label on a bright sky.
    static let halo = Color.white.opacity(0.85)
    /// The soft shadow of the panels and the buttons.
    static let shadow = Color(.sRGB, red: 20 / 255, green: 50 / 255, blue: 100 / 255, opacity: 0.2)

    /// The sky view without the camera: a sky in the colors of the height of the sun, a glow
    /// around the sun, and the ground below the horizon, light by day. The PWA has the same
    /// drawing (`drawSkyView`).
    static func drawSkyView(_ context: inout GraphicsContext, view: Almucantar.View, sun: Horizontal?, size: CGSize) {
        let altitude = sun?.altitudeDeg ?? -90
        let (top, low) = DemoSkyDrawing.skyColors(altitude)
        let day = min(1, max(0, (altitude + 6) / 12))
        let full = Path(CGRect(origin: .zero, size: size))
        // The horizon below the middle of the screen gives the end of the gradient.
        let middle = try? unproject(view, ScreenPoint(x: size.width / 2, y: size.height / 2))
        let azimuth = middle.map { $0.azimuthDeg.isFinite ? $0.azimuthDeg : 180 } ?? 180
        let horizon = SkyDrawing.point(view, Horizontal(azimuthDeg: azimuth, altitudeDeg: 0))
        let zenith = SkyDrawing.point(view, Horizontal(azimuthDeg: azimuth, altitudeDeg: 60))
        let y1 = horizon?.y ?? size.height
        let y0 = min(zenith?.y ?? 0, y1 - 1)
        context.fill(
            full,
            with: .linearGradient(
                Gradient(colors: [DemoSkyDrawing.color(top), DemoSkyDrawing.color(low)]),
                startPoint: CGPoint(x: 0, y: y0), endPoint: CGPoint(x: 0, y: y1)))
        if let sun, sun.altitudeDeg > -10, let s = SkyDrawing.point(view, sun) {
            let strength = min(1, (sun.altitudeDeg + 10) / 14)
            context.fill(
                full,
                with: .radialGradient(
                    Gradient(stops: [
                        .init(color: DemoSkyDrawing.color((255, 220, 150), 0.45 * strength), location: 0),
                        .init(color: DemoSkyDrawing.color((255, 180, 90), 0.12 * strength), location: 0.35),
                        .init(color: DemoSkyDrawing.color((255, 180, 90), 0), location: 1),
                    ]), center: s, startRadius: 0, endRadius: max(size.width, size.height) * 0.6))
        }
        // The ground: each piece of the horizon line, moved far toward the nadir, closes the area
        // below it.
        var direction = CGPoint(x: 0, y: 1)
        if let horizon, let below = SkyDrawing.point(view, Horizontal(azimuthDeg: azimuth, altitudeDeg: -30)) {
            let length = hypot(below.x - horizon.x, below.y - horizon.y)
            if length > 1 { direction = CGPoint(x: (below.x - horizon.x) / length, y: (below.y - horizon.y) / length) }
        }
        let far = (size.width + size.height) * 2
        var ground = Path()
        for line in (try? horizonLine(view)) ?? [] where line.count >= 2 {
            ground.move(to: CGPoint(x: line[0].x, y: line[0].y))
            for p in line.dropFirst() { ground.addLine(to: CGPoint(x: p.x, y: p.y)) }
            for p in line.reversed() { ground.addLine(to: CGPoint(x: p.x + direction.x * far, y: p.y + direction.y * far)) }
            ground.closeSubpath()
        }
        context.fill(ground, with: .color(DemoSkyDrawing.color(DemoSkyDrawing.mix((10, 14, 22), (214, 204, 186), day))))
    }
}

extension SwiftUI.View {
    /// The panel of Sonnseite over the sky or the camera image: warm white, with dark text.
    func sunPanel() -> some SwiftUI.View {
        let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        return foregroundColor(SunLook.ink)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background {
                shape.fill(SunLook.panel)
                    .background(.ultraThinMaterial, in: shape)
                    .overlay(shape.strokeBorder(Color.white.opacity(0.7), lineWidth: 1))
                    .shadow(color: SunLook.shadow, radius: 15, y: 10)
            }
            // The panel is always light, so the controls in it take the light look.
            .environment(\.colorScheme, .light)
    }
}

/// The capsule of the buttons: warm white with dark text, or sun yellow for the primary button.
struct SunCapsule: ViewModifier {
    let primary: Bool
    let pressed: Bool
    @Environment(\.isEnabled) private var enabled

    func body(content: Content) -> some SwiftUI.View {
        content
            // The size of the PWA (15 pt), so the five buttons fit in one row on a phone.
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(primary ? SunLook.onSun : SunLook.ink)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background {
                if primary {
                    Capsule()
                        .fill(LinearGradient(colors: [SunLook.hex(0xffd45e), SunLook.hex(0xf0a91c)], startPoint: .top, endPoint: .bottom))
                        .shadow(color: SunLook.hex(0xf0a91c, 0.45), radius: 11, y: 8)
                } else {
                    Capsule().fill(SunLook.panel)
                        .background(.ultraThinMaterial, in: Capsule())
                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.7), lineWidth: 1))
                        .shadow(color: SunLook.shadow, radius: 9, y: 6)
                }
            }
            .contentShape(Capsule())
            .opacity(!enabled ? 0.5 : pressed ? 0.75 : 1)
            .environment(\.colorScheme, .light)
    }
}

/// The button style of Sonnseite: a `SunCapsule`. A menu also takes it.
struct SunButtonStyle: ButtonStyle {
    var primary = false

    func makeBody(configuration: Configuration) -> some SwiftUI.View {
        configuration.label.modifier(SunCapsule(primary: primary, pressed: configuration.isPressed))
    }
}
