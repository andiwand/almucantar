import Sunradar
import SwiftUI

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
        let hourFormat = Date.FormatStyle().hour(.twoDigits(amPM: .omitted))
        hours =
            points
            .filter { ($0.unixUtc - day.timeIntervalSince1970).truncatingRemainder(dividingBy: 3600) == 0 }
            .filter { $0.position.altitudeDeg > -6 }
            .map { ($0.position, Date(timeIntervalSince1970: $0.unixUtc).formatted(hourFormat)) }
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

/// The sky view: the path of the sun with the hours, the horizon, the solstice paths, and the
/// time when the sun is in the direction of the crosshair. Without motion sensors (the
/// simulator), drag to look around.
struct SkyScreen: SwiftUI.View {
    @StateObject private var motion = MotionTracker()
    @StateObject private var location = LocationProvider()
    @State private var now = Date()
    @State private var dayOffset = 0.0
    @State private var minuteOfDay: Double?
    @State private var showTime = false
    @State private var cameraOn = false
    @State private var camera: Sunradar.Camera?
    @State private var aligning = false
    @State private var message: String?
    @State private var dragAzimuth = 180.0
    @State private var dragPitch = 20.0
    @State private var dates: [TimeMatch] = []
    @State private var datesKey = ""

    private let calendar = Calendar.current
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    private static let maxCrosshairAltitude = 75.0

    var body: some SwiftUI.View {
        GeometryReader { geometry in
            let size = geometry.size
            let selectedDate = calendar.date(byAdding: .day, value: Int(dayOffset), to: now)!
            let day = SkyDay(observer: location.observer, date: selectedDate, calendar: calendar)
            let time = selectedTime(day: day)
            let view = makeView(size: size)
            let sun = try? sunPosition(time, observer: location.observer)
            let center = try? unproject(view, ScreenPoint(x: view.camera.cx, y: view.camera.cy))
            let usable = center.map { abs($0.altitudeDeg) <= Self.maxCrosshairAltitude } ?? false
            let crossing =
                usable
                ? (try? findAzimuthTimes(
                    observer: location.observer, azimuthDeg: center!.azimuthDeg, startUtc: day.start, endUtc: day.end))?
                    .first : nil

            ZStack {
                Color(red: 0.043, green: 0.102, blue: 0.188).ignoresSafeArea()
                if cameraOn { CameraView { camera = $0 }.ignoresSafeArea() }
                Canvas { context, _ in
                    draw(&context, view: view, day: day, sun: sun, center: center, crossing: crossing)
                }
                .ignoresSafeArea()
                .gesture(dragGesture(view: view))

                VStack(alignment: .leading, spacing: 8) {
                    statusPanel(day: day)
                    if let message { panel { Text(message) } }
                    Spacer()
                    VStack(spacing: 8) {
                        if showTime { panel { timePanel } }
                        panel { readout(usable: usable, crossing: crossing) }
                        buttons(view: view)
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(12)
            }
            .onAppear {
                motion.start()
                location.start()
            }
            .onReceive(timer) { now = $0 }
            .onChange(of: location.observer) { motion.observer = $0 }
            .task(id: center.map { "\(Int($0.azimuthDeg * 2)):\(Int($0.altitudeDeg * 2))" } ?? "") {
                await searchDates(center: usable ? center : nil)
            }
        }
    }

    // MARK: - The view

    private func makeView(size: CGSize) -> Sunradar.View {
        let fov = size.width < size.height ? 60.0 : 90.0
        let virtualCamera =
            (try? cameraFromFov(width: size.width, height: size.height, horizontalFovDeg: fov)) ?? Sunradar.Camera()
        if motion.available, let q = motion.deviceToFrame {
            return Sunradar.View(
                deviceToEnu: q, screenRotation: screenRotation(), headingOffsetDeg: motion.estimate.offsetDeg,
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
        let minute = minuteOfDay ?? Double(components.hour! * 60 + components.minute!)
        return day.start + minute * 60
    }

    private func dragGesture(view: Sunradar.View) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard !motion.available || motion.deviceToFrame == nil else { return }
                dragAzimuth = (dragAzimuth - value.velocity.width * 0.0025 + 360).truncatingRemainder(dividingBy: 360)
                dragPitch = min(80, max(-80, dragPitch + value.velocity.height * 0.0025))
            }
            .onEnded { value in
                if aligning, abs(value.translation.width) < 10, abs(value.translation.height) < 10 {
                    alignWithSun(view: view, at: value.location)
                }
            }
    }

    private func alignWithSun(view: Sunradar.View, at point: CGPoint) {
        aligning = false
        let now = Date().timeIntervalSince1970
        guard let sun = try? sunPosition(now, observer: location.observer),
            let tapped = try? unproject(view, ScreenPoint(x: point.x, y: point.y))
        else { return }
        if abs(tapped.altitudeDeg - sun.altitudeDeg) > 5 {
            message = "That point is not at the height of the sun. Tap the sun itself."
            return
        }
        if let m = try? headingFromDirection(
            view, point: ScreenPoint(x: point.x, y: point.y), trueAzimuthDeg: sun.azimuthDeg, sigmaDeg: 0.5)
        {
            motion.apply(m)
            message = String(format: "Heading aligned with the sun (±%.1f°).", sqrt(motion.estimate.varianceDeg2))
        }
    }

    private func searchDates(center: Horizontal?) async {
        dates = []
        guard let center, center.altitudeDeg >= 0 else { return }
        try? await Task.sleep(nanoseconds: 400_000_000)
        if Task.isCancelled { return }
        let start = calendar.startOfDay(for: Date()).timeIntervalSince1970
        let observer = location.observer
        dates = await Task.detached {
            (try? findDates(observer: observer, direction: center, startUtc: start, endUtc: start + 365 * 86400)) ?? []
        }.value
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

    private func draw(
        _ context: inout GraphicsContext, view: Sunradar.View, day: SkyDay, sun: Horizontal?, center: Horizontal?,
        crossing: AzimuthCrossing?
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
        if let sun, let p = point(view, sun) {
            context.fill(Path(ellipseIn: CGRect(x: p.x - 26, y: p.y - 26, width: 52, height: 52)), with: .color(yellow.opacity(0.25)))
            context.fill(Path(ellipseIn: CGRect(x: p.x - 14, y: p.y - 14, width: 28, height: 28)), with: .color(Color(red: 1, green: 0.878, blue: 0.541)))
        }
        let c = CGPoint(x: view.camera.cx, y: view.camera.cy)
        var cross = Path()
        cross.move(to: CGPoint(x: c.x - 16, y: c.y))
        cross.addLine(to: CGPoint(x: c.x + 16, y: c.y))
        cross.move(to: CGPoint(x: c.x, y: c.y - 16))
        cross.addLine(to: CGPoint(x: c.x, y: c.y + 16))
        context.stroke(cross, with: .color(.white), lineWidth: 1.5)
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

    private func buttons(view: Sunradar.View) -> some SwiftUI.View {
        HStack(spacing: 8) {
            Button(cameraOn ? "Sky" : "Camera") { cameraOn.toggle() }
                .buttonStyle(.bordered)
            Button(showTime ? "Time ●" : "Time") { showTime.toggle() }
                .buttonStyle(.bordered)
            Button(aligning ? "Cancel" : "Align with the sun") {
                let sunUp = ((try? sunPosition(Date().timeIntervalSince1970, observer: location.observer))?.altitudeDeg ?? -1) > 0
                if !sunUp {
                    message = "The sun is below the horizon."
                } else {
                    cameraOn = true
                    aligning.toggle()
                    message = aligning ? "Tap the sun in the camera image." : nil
                }
            }
            .buttonStyle(.borderedProminent)
        }
        .tint(Color(red: 0.81, green: 0.74, blue: 1))
    }
}
