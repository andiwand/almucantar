import Combine
import Sunradar
import SwiftUI
import simd

/// The data of one local day: the path, the parts above the horizon, the hours, and the solstice
/// paths, calculated once per day and place.
struct SkyDay {
    let start: Double
    let end: Double
    let path: [Horizontal]
    let daySegments: [[Horizontal]]
    let hours: [(direction: Horizontal, label: String)]
    let summary: String
    let guides: [[Horizontal]]

    init(observer: Observer, date: Date, calendar: Calendar) {
        let day = calendar.startOfDay(for: date)
        start = day.timeIntervalSince1970
        end = calendar.date(byAdding: .day, value: 1, to: day)!.timeIntervalSince1970
        let points = (try? sunPath(observer: observer, startUtc: start, endUtc: end)) ?? []
        path = points.map(\.position)
        daySegments = Self.segments(points)
        // The hour on 24 hours, as on Android: in a 12-hour locale, "07" would also mean 7 PM.
        hours =
            points
            .filter { ($0.unixUtc - day.timeIntervalSince1970).truncatingRemainder(dividingBy: 3600) == 0 }
            .filter { $0.position.altitudeDeg > -6 }
            .map {
                ($0.position, String(format: "%02d", calendar.component(.hour, from: Date(timeIntervalSince1970: $0.unixUtc))))
            }
        let events = try? sunEvents(observer: observer, startUtc: start, endUtc: end)
        summary =
            switch events?.kind {
            case .polarDay: "Polar day"
            case .polarNight: "Polar night"
            default:
                (events?.crossings ?? []).map {
                    ($0.rising ? "Sunrise " : "Sunset ") + Date(timeIntervalSince1970: $0.unixUtc).formatted(date: .omitted, time: .shortened)
                }.joined(separator: " · ")
            }
        let year = calendar.component(.year, from: date)
        guides = [6, 12].flatMap { month -> [[Horizontal]] in
            let solstice = calendar.date(from: DateComponents(year: year, month: month, day: 21))!
            let s = solstice.timeIntervalSince1970
            return Self.segments((try? sunPath(observer: observer, startUtc: s, endUtc: s + 86400)) ?? [])
        }
    }

    static func segments(_ points: [PathPoint]) -> [[Horizontal]] {
        var result: [[Horizontal]] = []
        var current: [Horizontal] = []
        for p in points {
            if p.position.altitudeDeg >= 0 {
                current.append(p.position)
            } else if !current.isEmpty {
                result.append(current)
                current = []
            }
        }
        if current.count > 1 { result.append(current) }
        return result
    }
}

/// Keeps the last SkyDay, because the body runs for each frame of the motion sensor.
@MainActor
final class SkyDayCache {
    private var key: (Observer, Date)?
    private var day: SkyDay?

    func day(observer: Observer, date: Date, calendar: Calendar) -> SkyDay {
        let start = calendar.startOfDay(for: date)
        if let key, let day, key.0 == observer, key.1 == start { return day }
        let day = SkyDay(observer: observer, date: start, calendar: calendar)
        (key, self.day) = ((observer, start), day)
        return day
    }
}

/// The things that the user can align the heading with.
enum AlignTarget {
    case sun, moon, landmark
}

/// A region of the camera image that is probably the sun.
struct DetectedSun {
    var blob: ViewBlob
    var separationDeg: Double
}

/// The sky view: the path of the sun with the hours, the horizon, the solstice paths, and the
/// time when the sun is in the direction of the crosshair, the moon, and a landmark. The user can
/// align the heading with the sun, the moon, or a landmark; a detected sun gets a box to tap.
/// Without motion sensors (the simulator), drag to look around.
struct SkyScreen: SwiftUI.View {
    @StateObject private var motion = MotionTracker()
    @StateObject private var location = LocationProvider()
    // The demo mode of the store screenshots sets the time, the time panel, and the drawn sky.
    @State private var now = ScreenshotMode.scene?.date ?? Date()
    @State private var dayOffset = 0.0
    @State private var minuteOfDay: Double? = ScreenshotMode.scene.map { Double($0.hour * 60 + $0.minute) }
    @State private var showTime = ScreenshotMode.scene?.showTime ?? false
    @State private var cameraOn = ScreenshotMode.scene != nil
    @State private var camera: Sunradar.Camera?
    @State private var aligning: AlignTarget?
    @State private var pickingLandmark = false
    @State private var landmark: Observer?
    @State private var blobs: [ViewBlob] = []
    @State private var dayCache = SkyDayCache()
    @State private var message: String?
    @State private var dragAzimuth = 180.0
    @State private var dragPitch = 20.0
    @State private var dragStart: (azimuth: Double, pitch: Double)?
    @State private var dates: [TimeMatch] = []
    /// The search for the dates of the crosshair finished at least once. The demo mode is ready then.
    @State private var datesSearched = false

    private let calendar = Calendar.current
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    private static let maxCrosshairAltitude = 75.0
    /// The angular error of a tap on the sun or the moon (both have a diameter of 0.5°).
    private static let bodyTapSigmaDeg = 0.5
    /// A tap more than this far from the altitude of the body is not the body.
    private static let bodyTapMaxAltitudeErrorDeg = 5.0
    /// The moon must be at least this bright to be found in the camera image.
    private static let minMoonFraction = 0.1
    /// A saturated region with a larger angular radius than this is not the sun (0.27°).
    private static let maxSunBlobRadiusDeg = 3.0
    /// Within this angle of the prediction, the detected sun counts as aligned.
    private static let alignedDeg = 1.0

    var body: some SwiftUI.View {
        GeometryReader { geometry in
            // The canvas and the camera ignore the safe area, so the view uses the full screen.
            let insets = geometry.safeAreaInsets
            let size = CGSize(
                width: geometry.size.width + insets.leading + insets.trailing,
                height: geometry.size.height + insets.top + insets.bottom)
            let selectedDate = calendar.date(byAdding: .day, value: Int(dayOffset), to: now)!
            let observer = location.observer
            let day = dayCache.day(observer: observer, date: selectedDate, calendar: calendar)
            let time = selectedTime(day: day)
            let view = makeView(size: size)
            let sun = try? sunPosition(time, observer: observer)
            let moon = (try? moonIlluminatedFraction(time)).flatMap { fraction in
                fraction >= 0.02
                    ? (try? moonPosition(time, observer: observer)).map { MoonAt(direction: $0, fraction: fraction) } : nil
            }
            let landmarkDirection = landmark.map {
                Horizontal(azimuthDeg: Geo.bearingDeg(from: observer, to: $0), altitudeDeg: 0)
            }
            let detected = detectSun(view: view)
            let center = try? unproject(view, ScreenPoint(x: view.camera.cx, y: view.camera.cy))
            let usable = center.map { abs($0.altitudeDeg) <= Self.maxCrosshairAltitude } ?? false
            let crossing =
                usable
                ? (try? findAzimuthTimes(
                    observer: location.observer, azimuthDeg: center!.azimuthDeg, startUtc: day.start, endUtc: day.end))?
                    .first : nil

            ZStack {
                Color(red: 0.043, green: 0.102, blue: 0.188).ignoresSafeArea()
                if cameraOn {
                    if ScreenshotMode.scene != nil {
                        DemoSky(view: view, sun: sun).ignoresSafeArea()
                    } else {
                        CameraView(detectSun: detecting, onBlobs: { blobs = $0 }) { camera = $0 }.ignoresSafeArea()
                    }
                }
                Canvas { context, _ in
                    draw(
                        &context, view: view, day: day, sun: sun, moon: moon, landmark: landmarkDirection, center: center,
                        crossing: crossing)
                    if let detected { drawDetected(&context, detected) }
                }
                .ignoresSafeArea()
                .gesture(dragGesture(view: view, detected: detected))

                VStack(alignment: .leading, spacing: 8) {
                    statusPanel(day: day)
                    if let message { panel { Text(message) } }
                    if let aligning { panel { Text(hint(aligning)) } }
                    Spacer()
                    VStack(spacing: 8) {
                        if showTime { panel { timePanel } }
                        panel { readout(usable: usable, crossing: crossing) }
                        buttons(view: view)
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(12)

                if ScreenshotMode.scene != nil && datesSearched {
                    Rectangle()
                        .fill(Color.white.opacity(0.01))
                        .frame(width: 1, height: 1)
                        .accessibilityElement()
                        .accessibilityLabel("Ready")
                        .accessibilityIdentifier(ScreenshotMode.readyIdentifier)
                }
            }
            .onAppear {
                if let scene = ScreenshotMode.scene {
                    motion.startDemo(
                        deviceToFrame: upright(azimuthDeg: scene.azimuthDeg, pitchDeg: scene.pitchDeg),
                        headingSigmaDeg: scene.headingSigmaDeg)
                    location.startDemo()
                } else {
                    motion.start()
                    location.start()
                }
            }
            .onReceive(timer) { if ScreenshotMode.scene == nil { now = $0 } }
            .onChange(of: location.observer) { motion.observer = location.observer }
            .onChange(of: detecting) { if !detecting { blobs = [] } }
            .fullScreenCover(isPresented: $pickingLandmark) {
                MapPicker(observer: location.observer) { picked in
                    pickingLandmark = false
                    landmark = picked
                    if picked != nil {
                        cameraOn = true
                        aligning = .landmark
                    }
                }
            }
            .task(id: center.map { "\(Int($0.azimuthDeg * 2)):\(Int($0.altitudeDeg * 2)):\(observer)" } ?? "") {
                await searchDates(center: usable ? center : nil)
            }
        }
    }

    // MARK: - The view

    private func makeView(size: CGSize) -> Sunradar.View {
        var fov = size.width < size.height ? 60.0 : 90.0
        if ScreenshotMode.scene != nil {
            // 65° across the shorter side of the screen, as on Android.
            let half = 32.5 * Double.pi / 180
            fov = size.width <= size.height ? 65 : 2 * atan(tan(half) * size.width / size.height) * 180 / .pi
        }
        let virtualCamera =
            (try? cameraFromFov(width: size.width, height: size.height, horizontalFovDeg: fov)) ?? Sunradar.Camera()
        if motion.available, let q = motion.deviceToFrame {
            return Sunradar.View(
                // The scenes of the demo mode are for a device in portrait.
                deviceToEnu: q, screenRotation: ScreenshotMode.scene != nil ? .rotation0 : screenRotation(),
                headingOffsetDeg: motion.estimate.offsetDeg,
                camera: cameraOn ? (camera ?? virtualCamera) : virtualCamera)
        }
        return Sunradar.View(deviceToEnu: upright(azimuthDeg: dragAzimuth, pitchDeg: dragPitch), camera: virtualCamera)
    }

    private func screenRotation() -> ScreenRotation {
        let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        switch scene?.interfaceOrientation {
        case .landscapeRight: return .rotation90  // home button right: the device top is left
        case .landscapeLeft: return .rotation270
        case .portraitUpsideDown: return .rotation180
        default: return .rotation0
        }
    }

    /// A device upright in portrait, with the back camera to the azimuth, pitched up.
    private func upright(azimuthDeg: Double, pitchDeg: Double) -> Sunradar.Quaternion {
        // The rotation about east by 90° + pitch, then about up by -azimuth.
        let a = -azimuthDeg * .pi / 180
        let p = (90 + pitchDeg) * .pi / 180
        let qz = (w: cos(a / 2), z: sin(a / 2))
        let qx = (w: cos(p / 2), x: sin(p / 2))
        return Sunradar.Quaternion(w: qz.w * qx.w, x: qz.w * qx.x, y: qz.z * qx.x, z: qz.z * qx.w)
    }

    private func selectedTime(day: SkyDay) -> Double {
        if minuteOfDay == nil && dayOffset == 0 { return now.timeIntervalSince1970 }
        let components = calendar.dateComponents([.hour, .minute], from: now)
        let minute = minuteOfDay.map { Int($0) } ?? components.hour! * 60 + components.minute!
        // The wall clock time, not midnight + minutes: a day with a DST change has 23 or 25 hours.
        let start = Date(timeIntervalSince1970: day.start)
        return (calendar.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: start) ?? start)
            .timeIntervalSince1970
    }

    /// The detection runs with the camera on and the sun up.
    private var detecting: Bool {
        cameraOn && ((try? sunPosition(now.timeIntervalSince1970, observer: location.observer))?.altitudeDeg ?? -1) > 0
    }

    /// The detected sun: the saturated region nearest to the predicted sun, within the error of
    /// the heading. A region larger than a few degrees is an overexposed sky, not the sun.
    private func detectSun(view: Sunradar.View) -> DetectedSun? {
        guard detecting, let sun = try? sunPosition(now.timeIntervalSince1970, observer: location.observer) else {
            return nil
        }
        let maxSeparation = max(10, 3 * sqrt(motion.estimate.varianceDeg2))
        return blobs.compactMap { blob -> DetectedSun? in
            guard let direction = try? unproject(view, ScreenPoint(x: blob.center.x, y: blob.center.y)),
                let edge = try? unproject(view, ScreenPoint(x: blob.center.x + blob.radius, y: blob.center.y))
            else { return nil }
            let separation = Geo.angleBetweenDeg(direction, sun)
            if Geo.angleBetweenDeg(direction, edge) > Self.maxSunBlobRadiusDeg || separation > maxSeparation { return nil }
            return DetectedSun(blob: blob, separationDeg: separation)
        }
        .min { $0.separationDeg < $1.separationDeg }
    }

    private func dragGesture(view: Sunradar.View, detected: DetectedSun?) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard !motion.available || motion.deviceToFrame == nil else { return }
                if dragStart == nil { dragStart = (dragAzimuth, dragPitch) }
                guard let start = dragStart else { return }
                dragAzimuth = (start.azimuth - value.translation.width * 0.15 + 360).truncatingRemainder(dividingBy: 360)
                dragPitch = min(80, max(-80, start.pitch + value.translation.height * 0.15))
            }
            .onEnded { value in
                dragStart = nil
                guard abs(value.translation.width) < 10, abs(value.translation.height) < 10 else { return }
                tap(view: view, at: value.location, detected: detected)
            }
    }

    private func tap(view: Sunradar.View, at point: CGPoint, detected: DetectedSun?) {
        let now = Date().timeIntervalSince1970
        let observer = location.observer
        if let detected, hypot(point.x - detected.blob.center.x, point.y - detected.blob.center.y) < detected.blob.radius + 48,
            let sun = try? sunPosition(now, observer: observer)
        {
            message = alignWithBody(view: view, at: detected.blob.center, body: sun, name: "sun")
            aligning = nil
        } else if aligning == .sun, let sun = try? sunPosition(now, observer: observer) {
            message = alignWithBody(view: view, at: point, body: sun, name: "sun")
            aligning = nil
        } else if aligning == .moon, let moon = try? moonPosition(now, observer: observer) {
            message = alignWithBody(view: view, at: point, body: moon, name: "moon")
            aligning = nil
        }
    }

    /// Calibrates the heading with a tap on the sun or the moon. Returns the message for the user.
    private func alignWithBody(view: Sunradar.View, at point: CGPoint, body: Horizontal, name: String) -> String {
        let screen = ScreenPoint(x: point.x, y: point.y)
        guard let tapped = try? unproject(view, screen) else { return "That point is not on the sky." }
        let altitudeError = abs(tapped.altitudeDeg - body.altitudeDeg)
        if altitudeError > Self.bodyTapMaxAltitudeErrorDeg {
            return String(format: "That point is %.0f° from the height of the %@. Tap the %@ itself.", altitudeError, name, name)
        }
        return apply(
            try? headingFromDirection(view, point: screen, trueAzimuthDeg: body.azimuthDeg, sigmaDeg: Self.bodyTapSigmaDeg),
            name: name)
    }

    /// Calibrates the heading with a landmark in the crosshair. The error of the bearing comes from
    /// the location error and from the distance: an error e at a distance d is atan(e / d).
    private func alignWithLandmark(view: Sunradar.View) -> String {
        guard let landmark else { return "No landmark." }
        let observer = location.observer
        let distance = Geo.distanceM(from: observer, to: landmark)
        if distance < 50 { return "The landmark is too near. Choose one that is farther away." }
        let positionError = (location.accuracyM ?? 50) + 5
        let sigma = atan(positionError / distance) * 180 / .pi + 0.3
        let center = ScreenPoint(x: view.camera.cx, y: view.camera.cy)
        return apply(
            try? headingFromDirection(
                view, point: center, trueAzimuthDeg: Geo.bearingDeg(from: observer, to: landmark), sigmaDeg: sigma),
            name: "landmark")
    }

    private func apply(_ measurement: HeadingMeasurement?, name: String) -> String {
        guard let measurement else { return "The alignment failed." }
        let change = (measurement.offsetDeg - motion.estimate.offsetDeg + 540).truncatingRemainder(dividingBy: 360) - 180
        motion.apply(measurement)
        return String(
            format: "Heading aligned with the %@ (corrected by %.1f°, now ±%.1f°).", name, change,
            sqrt(motion.estimate.varianceDeg2))
    }

    private func hint(_ target: AlignTarget) -> String {
        switch target {
        case .sun: "Tap the sun in the camera image."
        case .moon: "Tap the moon in the camera image."
        case .landmark: "Point the crosshair at the landmark, then press \"Aligned\"."
        }
    }

    /// The old dates stay until the new ones are ready, so sensor noise does not make them flicker.
    private func searchDates(center: Horizontal?) async {
        guard let center, center.altitudeDeg >= 0 else {
            dates = []
            datesSearched = true
            return
        }
        try? await Task.sleep(nanoseconds: 400_000_000)
        if Task.isCancelled { return }
        let start = calendar.startOfDay(for: now).timeIntervalSince1970
        let observer = location.observer
        let found = await Task.detached {
            (try? findDates(observer: observer, direction: center, startUtc: start, endUtc: start + 365 * 86400)) ?? []
        }.value
        if !Task.isCancelled {
            dates = found
            datesSearched = true
        }
    }

    // MARK: - Drawing

    private func path(_ lines: [[ScreenPoint]]) -> Path {
        var path = Path()
        for line in lines where line.count >= 2 {
            path.move(to: CGPoint(x: line[0].x, y: line[0].y))
            for p in line.dropFirst() { path.addLine(to: CGPoint(x: p.x, y: p.y)) }
        }
        return path
    }

    /// Draws the moon at `at` with the lit part toward the sun. The direction of the sun on the screen
    /// comes from a point 1° from the moon toward the sun, see `MoonPhase.towardSun`.
    private func drawMoon(
        _ context: inout GraphicsContext, _ view: Sunradar.View, _ moon: MoonAt, sun: Horizontal?, at: CGPoint, radius: CGFloat
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

    private func point(_ view: Sunradar.View, _ direction: Horizontal) -> CGPoint? {
        guard let p = try? project(view, direction), p.valid else { return nil }
        return CGPoint(x: p.point.x, y: p.point.y)
    }

    /// Draws a label upright relative to the horizon: its top points to a point higher in the sky.
    private func label(_ context: inout GraphicsContext, _ view: Sunradar.View, _ direction: Horizontal, _ text: String, gap: Double) {
        guard let at = point(view, direction) else { return }
        let higher = point(view, Horizontal(azimuthDeg: direction.azimuthDeg, altitudeDeg: min(direction.altitudeDeg + 0.5, 89.9)))
        let angle = higher.map { atan2($0.x - at.x, -($0.y - at.y)) } ?? 0
        var c = context
        c.translateBy(x: at.x, y: at.y)
        c.rotate(by: .radians(angle))
        c.addFilter(.shadow(color: .black, radius: 3))
        c.draw(Text(text).font(.system(size: 14, weight: .bold)).foregroundColor(.white), at: CGPoint(x: 0, y: -gap))
    }

    /// If `target` is not on the screen, the point on the border of the screen in the direction of
    /// `target`, seen from `from` (the center of the view), and the unit direction to go on. Nil
    /// if the target is visible.
    private func edgeIndicator(_ view: Sunradar.View, from: Horizontal, to target: Horizontal) -> (CGPoint, CGPoint)? {
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

    private func draw(
        _ context: inout GraphicsContext, view: Sunradar.View, day: SkyDay, sun: Horizontal?, moon: MoonAt?,
        landmark: Horizontal?, center: Horizontal?, crossing: AzimuthCrossing?
    ) {
        let yellow = Color(red: 1, green: 0.784, blue: 0.239)
        let blue = Color(red: 0.498, green: 0.847, blue: 1)
        if let horizon = try? horizonLine(view) {
            context.stroke(path(horizon), with: .color(.white.opacity(0.8)), lineWidth: 1.5)
        }
        for (az, name) in [(0.0, "N"), (90.0, "E"), (180.0, "S"), (270.0, "W")] {
            label(&context, view, Horizontal(azimuthDeg: az, altitudeDeg: 0), name, gap: 14)
        }
        for guide in day.guides {
            if let lines = try? projectLine(view, guide) {
                context.stroke(path(lines), with: .color(.white.opacity(0.35)), style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
            }
        }
        if let lines = try? projectLine(view, day.path) {
            context.stroke(path(lines), with: .color(yellow.opacity(0.5)), style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
        }
        for segment in day.daySegments {
            guard let lines = try? projectLine(view, segment) else { continue }
            context.stroke(path(lines), with: .color(yellow.opacity(0.25)), lineWidth: 9)
            context.stroke(path(lines), with: .color(yellow), lineWidth: 3)
        }
        for hour in day.hours {
            guard let p = point(view, hour.direction) else { continue }
            context.fill(Path(ellipseIn: CGRect(x: p.x - 4, y: p.y - 4, width: 8, height: 8)), with: .color(yellow))
            label(&context, view, hour.direction, hour.label, gap: 12)
        }
        if let crossing, let center {
            let end = crossing.sun.altitudeDeg
            let steps = max(2, Int(abs(end).rounded(.up)))
            let vertical = (0...steps).map { Horizontal(azimuthDeg: center.azimuthDeg, altitudeDeg: end * Double($0) / Double(steps)) }
            if let lines = try? projectLine(view, vertical) {
                context.stroke(path(lines), with: .color(blue), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
            }
            if let p = point(view, crossing.sun) {
                context.stroke(Path(ellipseIn: CGRect(x: p.x - 9, y: p.y - 9, width: 18, height: 18)), with: .color(blue), lineWidth: 2.5)
            }
        }
        // A landmark of the calibration: a marker on the horizon at its bearing.
        if let landmark, let p = point(view, landmark) {
            context.stroke(Path(ellipseIn: CGRect(x: p.x - 8, y: p.y - 8, width: 16, height: 16)), with: .color(.red), lineWidth: 2.5)
            label(&context, view, landmark, "landmark", gap: 16)
        }
        // The moon with its phase. The faint disc shows the dark part.
        if let moon, let p = point(view, moon.direction) {
            drawMoon(&context, view, moon, sun: sun, at: p, radius: 11)
            label(&context, view, moon.direction, "moon", gap: 20)
        }
        // The sun, or an arrow at the border of the screen that points to it.
        let sunColor = Color(red: 1, green: 0.878, blue: 0.541)
        if let sun, let center, let (at, direction) = edgeIndicator(view, from: center, to: sun) {
            let back = CGPoint(x: at.x - direction.x * 18, y: at.y - direction.y * 18)
            let side = CGPoint(x: -direction.y * 11, y: direction.x * 11)
            var arrow = Path()
            arrow.move(to: at)
            arrow.addLine(to: CGPoint(x: back.x + side.x, y: back.y + side.y))
            arrow.addLine(to: CGPoint(x: back.x - side.x, y: back.y - side.y))
            arrow.closeSubpath()
            context.fill(arrow, with: .color(sunColor))
            let dot = CGPoint(x: at.x - direction.x * 26, y: at.y - direction.y * 26)
            context.fill(Path(ellipseIn: CGRect(x: dot.x - 6, y: dot.y - 6, width: 12, height: 12)), with: .color(sunColor))
        } else if let sun, let p = point(view, sun) {
            context.fill(Path(ellipseIn: CGRect(x: p.x - 26, y: p.y - 26, width: 52, height: 52)), with: .color(yellow.opacity(0.25)))
            context.fill(Path(ellipseIn: CGRect(x: p.x - 14, y: p.y - 14, width: 28, height: 28)), with: .color(sunColor))
        }
        let c = CGPoint(x: view.camera.cx, y: view.camera.cy)
        var cross = Path()
        cross.move(to: CGPoint(x: c.x - 16, y: c.y))
        cross.addLine(to: CGPoint(x: c.x + 16, y: c.y))
        cross.move(to: CGPoint(x: c.x, y: c.y - 16))
        cross.addLine(to: CGPoint(x: c.x, y: c.y + 16))
        context.stroke(cross, with: .color(.white), lineWidth: 1.5)
    }

    /// A box around the detected sun, with a hint to tap it.
    private func drawDetected(_ context: inout GraphicsContext, _ sun: DetectedSun) {
        let aligned = sun.separationDeg <= Self.alignedDeg
        let color = aligned ? Color(red: 0.647, green: 0.839, blue: 0.655) : Color(red: 1, green: 0.878, blue: 0.51)
        let r = sun.blob.radius + 14
        let c = sun.blob.center
        context.stroke(
            Path(roundedRect: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r), cornerRadius: 8),
            with: .color(color), lineWidth: 2.5)
        context.draw(
            Text(aligned ? "Sun ✓" : "Sun · tap to align").font(.system(size: 13, weight: .bold)).foregroundColor(.white),
            at: CGPoint(x: c.x, y: c.y - r - 10))
    }

    // MARK: - Panels

    private func panel<Content: SwiftUI.View>(@ViewBuilder _ content: () -> Content) -> some SwiftUI.View {
        content()
            .foregroundColor(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
    }

    private func statusPanel(day: SkyDay) -> some SwiftUI.View {
        panel {
            VStack(alignment: .leading, spacing: 2) {
                Text(location.text).bold()
                headingText
                Text(day.summary).foregroundColor(.white.opacity(0.85))
            }
        }
    }

    private var headingText: some SwiftUI.View {
        let sigma = sqrt(motion.estimate.varianceDeg2)
        if !motion.available || motion.deviceToFrame == nil {
            return Text("No motion sensors: drag to look around").foregroundColor(.orange)
        }
        if sigma > 10 { return Text(String(format: "Heading ±%.0f° (align with the sun)", sigma)).foregroundColor(.orange) }
        if sigma > 3 { return Text(String(format: "Heading ±%.0f°", sigma)).foregroundColor(.yellow) }
        return Text(String(format: "Heading ±%.1f°", sigma)).foregroundColor(.green)
    }

    private func readout(usable: Bool, crossing: AzimuthCrossing?) -> some SwiftUI.View {
        VStack(spacing: 2) {
            if !usable {
                Text("Hold the phone up to see when the sun is in that direction.")
            } else if let crossing {
                Text(Date(timeIntervalSince1970: crossing.unixUtc).formatted(date: .omitted, time: .shortened))
                    .font(.system(size: 34, weight: .bold))
                    .foregroundColor(Color(red: 0.498, green: 0.847, blue: 1))
                Text(
                    crossing.sun.altitudeDeg >= 0
                        ? String(format: "The sun is in this direction, %.0f° high", crossing.sun.altitudeDeg)
                        : "The sun is in this direction below the horizon")
            } else {
                Text("The sun does not reach this direction on this day.")
            }
            if !dates.isEmpty {
                Text(
                    "In the crosshair: "
                        + dates.map {
                            Date(timeIntervalSince1970: $0.unixUtc).formatted(.dateTime.day().month(.abbreviated).hour().minute())
                        }.joined(separator: ", ")
                )
                .foregroundColor(Color(red: 1, green: 0.878, blue: 0.541))
            }
        }
        .multilineTextAlignment(.center)
    }

    private var timePanel: some SwiftUI.View {
        VStack(alignment: .leading) {
            HStack {
                Text(calendar.date(byAdding: .day, value: Int(dayOffset), to: now)!.formatted(date: .abbreviated, time: .omitted))
                    .frame(width: 120, alignment: .leading)
                Slider(value: $dayOffset, in: -183...183, step: 1)
                Button("Today") { dayOffset = 0 }
            }
            HStack {
                let minute = minuteOfDay ?? Double(calendar.component(.hour, from: now) * 60 + calendar.component(.minute, from: now))
                Text(String(format: "%02d:%02d", Int(minute) / 60, Int(minute) % 60) + (minuteOfDay == nil ? " (now)" : ""))
                    .frame(width: 120, alignment: .leading)
                Slider(value: Binding(get: { minute }, set: { minuteOfDay = $0 }), in: 0...1439, step: 1)
                Button("Now") { minuteOfDay = nil }
            }
        }
    }

    /// The align menu. Each item says what happens, or why it is not possible now.
    private var alignMenu: some SwiftUI.View {
        // The time of the timer, which is the time of the scene in the demo mode.
        let t = now.timeIntervalSince1970
        let observer = location.observer
        let sunUp = ((try? sunPosition(t, observer: observer))?.altitudeDeg ?? -1) > 0
        let moonUp = ((try? moonPosition(t, observer: observer))?.altitudeDeg ?? -1) > 0
        let moonBright = ((try? moonIlluminatedFraction(t)) ?? 0) >= Self.minMoonFraction
        let located = location.accuracyM != nil
        return Menu("Align") {
            Button {
                startBodyAlign(.sun)
            } label: {
                Text("With the sun")
                Text(sunUp ? "Tap the sun in the camera image" : "The sun is below the horizon")
            }
            .disabled(!sunUp)
            Button {
                startBodyAlign(.moon)
            } label: {
                Text("With the moon")
                Text(
                    !moonUp
                        ? "The moon is below the horizon"
                        : moonBright ? "Tap the moon in the camera image" : "The moon is too dim (new moon)")
            }
            .disabled(!moonUp || !moonBright)
            Button {
                message = nil
                pickingLandmark = true
            } label: {
                Text("With a landmark")
                Text(located ? "Pick it on a map, then aim at it" : "Needs your location (allow location access)")
            }
            .disabled(!located)
        }
        .buttonStyle(.borderedProminent)
    }

    /// The sun and the moon need the camera image, so the camera switches on first.
    private func startBodyAlign(_ target: AlignTarget) {
        message = nil
        cameraOn = true
        aligning = target
    }

    private func buttons(view: Sunradar.View) -> some SwiftUI.View {
        HStack(spacing: 8) {
            Button(cameraOn ? "Sky" : "Camera") {
                aligning = nil
                cameraOn.toggle()
            }
                .buttonStyle(.bordered)
            Button(dayOffset == 0 && minuteOfDay == nil ? "Time" : "Time ●") { showTime.toggle() }
                .buttonStyle(.bordered)
            if aligning == .landmark {
                Button("Aligned") {
                    message = alignWithLandmark(view: view)
                    aligning = nil
                }
                .buttonStyle(.borderedProminent)
            } else if aligning != nil {
                Button("Cancel") { aligning = nil }.buttonStyle(.borderedProminent)
            } else {
                alignMenu
            }
        }
        .tint(Color(red: 0.81, green: 0.74, blue: 1))
    }
}
