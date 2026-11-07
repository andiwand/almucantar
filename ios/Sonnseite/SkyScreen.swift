import Combine
import Almucantar
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
    /// The time zone of the place: the day and all its times are in this zone.
    let timeZone: TimeZone

    init(observer: Observer, date: Date, calendar: Calendar) {
        timeZone = calendar.timeZone
        (start, end) = PlaceTime.day(of: date, calendar: calendar)
        let day = Date(timeIntervalSince1970: start)
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
                    ($0.rising ? "Sunrise " : "Sunset ") + PlaceTime.time($0.unixUtc, calendar.timeZone)
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
    private var key: (Observer, Date, TimeZone)?
    private var day: SkyDay?

    func day(observer: Observer, date: Date, calendar: Calendar) -> SkyDay {
        let start = calendar.startOfDay(for: date)
        if let key, let day, key.0 == observer, key.1 == start, key.2 == calendar.timeZone { return day }
        let day = SkyDay(observer: observer, date: start, calendar: calendar)
        (key, self.day) = ((observer, start, calendar.timeZone), day)
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
    @StateObject private var location = LocationProvider(loadsStoredPlace: ScreenshotMode.scene == nil)
    // The demo mode has no terrain cache, so it never downloads a tile. The terrain and the kept
    // places use the same cache, because both write its index.
    @StateObject private var terrain = TerrainHorizon(cache: ScreenshotMode.scene == nil ? DemCache.shared : nil)
    @StateObject private var kept = KeptPlaces(cache: ScreenshotMode.scene == nil ? DemCache.shared : nil)
    @AppStorage(Settings.terrainHorizon) private var terrainOn = true
    @State private var terrainDayCache = TerrainDayCache()
    @State private var showSettings = false
    // The demo mode of the store screenshots sets the time, the time panel, and the drawn sky.
    @State private var now = ScreenshotMode.scene?.date ?? Date()
    @State private var dayOffset = 0.0
    @State private var minuteOfDay: Double? = ScreenshotMode.scene.map { Double($0.hour * 60 + $0.minute) }
    @State private var showTime = ScreenshotMode.scene?.showTime ?? false
    @State private var cameraOn = ScreenshotMode.scene != nil
    @State private var camera: Almucantar.Camera?
    @State private var aligning: AlignTarget?
    @State private var pickingLandmark = false
    @State private var showPlace = false
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

    /// The calendar in the time zone of the place. All days and times of the screen use it.
    private var calendar: Calendar { PlaceTime.calendar(location.timeZone) }
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
    /// The assumed error of a manual place, in metres.
    private static let placeErrorM = 500.0

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
            let terrainDay = terrainDayCache.day(day, observer: observer, terrain: terrain)
            let time = TimePanel.time(now: now, dayOffset: dayOffset, minuteOfDay: minuteOfDay, calendar: calendar)
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
                Canvas { context, canvasSize in
                    if !cameraOn { SunLook.drawSkyView(&context, view: view, sun: sun, size: canvasSize) }
                    draw(
                        &context, view: view, day: day, terrainDay: terrainDay, sun: sun, moon: moon,
                        landmark: landmarkDirection, center: center, crossing: crossing)
                    if let detected { drawDetected(&context, detected) }
                }
                .ignoresSafeArea()
                .gesture(dragGesture(view: view, detected: detected))

                VStack(alignment: .leading, spacing: 8) {
                    statusPanel(day: day, terrainDay: terrainDay)
                    if let message { Text(message).sunPanel() }
                    if let aligning { Text(hint(aligning)).sunPanel() }
                    Spacer()
                    VStack(spacing: 8) {
                        if showTime {
                            TimePanel(now: now, calendar: calendar, dayOffset: $dayOffset, minuteOfDay: $minuteOfDay)
                                .tint(SunLook.slider)
                                .buttonStyle(SunButtonStyle())
                                .sunPanel()
                        }
                        readout(usable: usable, crossing: crossing).sunPanel()
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
                        deviceToFrame: SkyOrientation.upright(azimuthDeg: scene.azimuthDeg, pitchDeg: scene.pitchDeg),
                        headingSigmaDeg: scene.headingSigmaDeg)
                    location.startDemo(timeZone: ScreenshotScene.timeZone)
                } else {
                    motion.start()
                    location.start()
                }
                // The provider can load the last place in its init, and onChange does not see
                // that first value. The heading filter needs the place for the declination.
                motion.observer = location.observer
                updateTerrain()
            }
            .onReceive(timer) { if ScreenshotMode.scene == nil { now = $0 } }
            .onChange(of: location.observer) {
                motion.observer = location.observer
                updateTerrain()
            }
            .onChange(of: location.hasPlace) { updateTerrain() }
            .onChange(of: terrainOn) { updateTerrain() }
            .onChange(of: location.notice) {
                if let notice = location.notice { message = notice }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .fullScreenCover(isPresented: $showPlace) { PlacePanel(location: location, kept: kept) }
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
            .task(id: center.map { "\(Int($0.azimuthDeg * 2)):\(Int($0.altitudeDeg * 2)):\(observer):\(location.timeZone.identifier)" } ?? "") {
                await searchDates(center: usable ? center : nil)
            }
        }
    }

    /// The demo mode shows no terrain. The most probable place (no location) gets no terrain, so the app waits
    /// for a place before it downloads tiles: the device location, a manual place, or the last place.
    private func updateTerrain() {
        if terrainOn && ScreenshotMode.scene == nil && !location.hasPlace { return }
        terrain.update(observer: location.observer, enabled: terrainOn && ScreenshotMode.scene == nil)
    }

    // MARK: - The view

    private func makeView(size: CGSize) -> Almucantar.View {
        var fov = size.width < size.height ? 60.0 : 90.0
        if ScreenshotMode.scene != nil {
            // 65° across the shorter side of the screen, as on Android.
            // Explicit Double values: the mix of CGFloat, Double, and literals in one expression
            // is too slow for the type checker of the CI compiler.
            let half: Double = 32.5 * Double.pi / 180
            let aspect = Double(size.width) / Double(size.height)
            let wideFov: Double = 2 * atan(tan(half) * aspect) * 180 / Double.pi
            fov = size.width <= size.height ? 65 : wideFov
        }
        let virtualCamera =
            (try? cameraFromFov(width: size.width, height: size.height, horizontalFovDeg: fov)) ?? Almucantar.Camera()
        if motion.available, let q = motion.deviceToFrame {
            return Almucantar.View(
                // The scenes of the demo mode are for a device in portrait.
                deviceToEnu: q, screenRotation: ScreenshotMode.scene != nil ? .rotation0 : SkyOrientation.screenRotation(),
                headingOffsetDeg: motion.estimate.offsetDeg,
                camera: cameraOn ? (camera ?? virtualCamera) : virtualCamera)
        }
        return Almucantar.View(deviceToEnu: SkyOrientation.upright(azimuthDeg: dragAzimuth, pitchDeg: dragPitch), camera: virtualCamera)
    }

    /// The detection runs with the camera on and the sun up.
    private var detecting: Bool {
        cameraOn && ((try? sunPosition(now.timeIntervalSince1970, observer: location.observer))?.altitudeDeg ?? -1) > 0
    }

    /// The detected sun: the saturated region nearest to the predicted sun, within the error of
    /// the heading. A region larger than a few degrees is an overexposed sky, not the sun.
    private func detectSun(view: Almucantar.View) -> DetectedSun? {
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

    private func dragGesture(view: Almucantar.View, detected: DetectedSun?) -> some Gesture {
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

    private func tap(view: Almucantar.View, at point: CGPoint, detected: DetectedSun?) {
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
    private func alignWithBody(view: Almucantar.View, at point: CGPoint, body: Horizontal, name: String) -> String {
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
    private func alignWithLandmark(view: Almucantar.View) -> String {
        guard let landmark else { return "No landmark." }
        let observer = location.observer
        let distance = Geo.distanceM(from: observer, to: landmark)
        if distance < 50 { return "The landmark is too near. Choose one that is farther away." }
        // A manual place has no accuracy: assume the error of a drop on the map.
        let positionError = (location.accuracyM ?? Self.placeErrorM) + 5
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

    /// A label of sonnseite: bold, upright relative to the horizon. It is white with a dark shadow,
    /// or dark with a light halo on a bright sky.
    private func label(
        _ context: inout GraphicsContext, _ view: Almucantar.View, _ direction: Horizontal, _ text: String, gap: Double,
        bright: Bool
    ) {
        SkyDrawing.label(
            &context, view, direction,
            Text(text).font(.system(size: 14, weight: .bold)).foregroundColor(bright ? SunLook.ink : .white), gap: gap,
            shadow: bright ? SunLook.halo : .black)
    }

    private func draw(
        _ context: inout GraphicsContext, view: Almucantar.View, day: SkyDay, terrainDay: TerrainDay?, sun: Horizontal?,
        moon: MoonAt?, landmark: Horizontal?, center: Horizontal?, crossing: AzimuthCrossing?
    ) {
        let yellow = Color(red: 1, green: 0.784, blue: 0.239)
        let blue = Color(red: 0.498, green: 0.847, blue: 1)
        let green = Color(red: 0.6, green: 0.86, blue: 0.56)
        // In the sky view, the sky is bright by day, so the labels and the helpers are dark. On the
        // camera image, they are light.
        let bright = !cameraOn && (sun?.altitudeDeg ?? -90) > -4
        let helper = bright ? SunLook.helper : blue
        let ink = bright ? SunLook.ink : Color.white
        let hasTerrain = !terrain.lines.isEmpty
        if hasTerrain && !cameraOn { SkyDrawing.fillTerrain(&context, view, lines: terrain.lines, color: green.opacity(0.12)) }
        if let horizon = try? horizonLine(view) {
            // The 0° horizon stays, but the terrain line is the one that the user sees.
            context.stroke(SkyDrawing.path(horizon), with: .color(.white.opacity(hasTerrain ? 0.3 : 0.8)), lineWidth: hasTerrain ? 1 : 1.5)
        }
        for line in terrain.lines {
            if let lines = try? projectLine(view, line) {
                context.stroke(SkyDrawing.path(lines), with: .color(green), lineWidth: 2)
            }
        }
        for (az, name) in [(0.0, "N"), (90.0, "E"), (180.0, "S"), (270.0, "W")] {
            label(&context, view, Horizontal(azimuthDeg: az, altitudeDeg: 0), name, gap: 14, bright: bright)
        }
        for guide in day.guides {
            if let lines = try? projectLine(view, guide) {
                context.stroke(SkyDrawing.path(lines), with: .color(.white.opacity(0.35)), style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
            }
        }
        if let lines = try? projectLine(view, day.path) {
            context.stroke(SkyDrawing.path(lines), with: .color(yellow.opacity(0.5)), style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
        }
        for segment in terrainDay?.visible ?? day.daySegments {
            guard let lines = try? projectLine(view, segment) else { continue }
            // A dark edge, so the path is easy to see on a bright sky.
            context.stroke(SkyDrawing.path(lines), with: .color(SunLook.hex(0xb45f06, 0.35)), lineWidth: 6)
            context.stroke(SkyDrawing.path(lines), with: .color(yellow.opacity(0.25)), lineWidth: 12)
            context.stroke(SkyDrawing.path(lines), with: .color(yellow), lineWidth: 3.5)
        }
        // The parts of the path behind the terrain: dimmer and dashed.
        for segment in terrainDay?.hidden ?? [] {
            guard let lines = try? projectLine(view, segment) else { continue }
            context.stroke(SkyDrawing.path(lines), with: .color(yellow.opacity(0.6)), style: StrokeStyle(lineWidth: 2.5, dash: [5, 5]))
        }
        for hour in day.hours {
            guard let p = SkyDrawing.point(view, hour.direction) else { continue }
            context.fill(Path(ellipseIn: CGRect(x: p.x - 4, y: p.y - 4, width: 8, height: 8)), with: .color(yellow))
            label(&context, view, hour.direction, hour.label, gap: 12, bright: bright)
        }
        if let crossing, let center {
            let end = crossing.sun.altitudeDeg
            let steps = max(2, Int(abs(end).rounded(.up)))
            let vertical = (0...steps).map { Horizontal(azimuthDeg: center.azimuthDeg, altitudeDeg: end * Double($0) / Double(steps)) }
            if let lines = try? projectLine(view, vertical) {
                context.stroke(SkyDrawing.path(lines), with: .color(helper), style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
            }
            if let p = SkyDrawing.point(view, crossing.sun) {
                context.stroke(Path(ellipseIn: CGRect(x: p.x - 9, y: p.y - 9, width: 18, height: 18)), with: .color(helper), lineWidth: 2.5)
            }
        }
        // A landmark of the calibration: a marker on the horizon at its bearing.
        if let landmark, let p = SkyDrawing.point(view, landmark) {
            context.stroke(Path(ellipseIn: CGRect(x: p.x - 8, y: p.y - 8, width: 16, height: 16)), with: .color(.red), lineWidth: 2.5)
            label(&context, view, landmark, "landmark", gap: 16, bright: bright)
        }
        // The moon with its phase. The faint disc shows the dark part.
        if let moon, let p = SkyDrawing.point(view, moon.direction) {
            SkyDrawing.drawMoon(&context, view, moon, sun: sun, at: p, radius: 11)
            label(&context, view, moon.direction, "moon", gap: 20, bright: bright)
        }
        // The sun, or an arrow at the border of the screen that points to it.
        let sunColor = Color(red: 1, green: 0.878, blue: 0.541)
        if let sun, let center, let (at, direction) = SkyDrawing.edgeIndicator(view, from: center, to: sun) {
            SkyDrawing.drawArrow(&context, at: at, direction: direction, color: sunColor)
            let dot = CGPoint(x: at.x - direction.x * 26, y: at.y - direction.y * 26)
            context.fill(Path(ellipseIn: CGRect(x: dot.x - 6, y: dot.y - 6, width: 12, height: 12)), with: .color(sunColor))
        } else if let sun, let p = SkyDrawing.point(view, sun) {
            // A light disc with a soft yellow glow.
            var glow = context
            glow.addFilter(.shadow(color: SunLook.hex(0xffbe3c, 0.95), radius: 13))
            glow.fill(Path(ellipseIn: CGRect(x: p.x - 14, y: p.y - 14, width: 28, height: 28)), with: .color(SunLook.hex(0xfff4d2)))
        }
        let c = CGPoint(x: view.camera.cx, y: view.camera.cy)
        var cross = Path()
        cross.move(to: CGPoint(x: c.x - 16, y: c.y))
        cross.addLine(to: CGPoint(x: c.x + 16, y: c.y))
        cross.move(to: CGPoint(x: c.x, y: c.y - 16))
        cross.addLine(to: CGPoint(x: c.x, y: c.y + 16))
        context.stroke(cross, with: .color(ink), lineWidth: 1.5)
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

    private func statusPanel(day: SkyDay, terrainDay: TerrainDay?) -> some SwiftUI.View {
        VStack(alignment: .leading, spacing: 2) {
            Text(location.text).bold()
            headingText
            Text(day.summary).foregroundColor(SunLook.secondary)
            if let terrainDay, !terrainDay.summary.isEmpty {
                Text(terrainDay.summary).foregroundColor(SunLook.good)
            }
            switch terrain.status {
            case .loading(let remaining):
                Text("Loading the terrain (\(remaining) \(remaining == 1 ? "tile" : "tiles"))…")
                    .font(.footnote).foregroundColor(SunLook.secondary)
            case .missing:
                Text(terrain.profile == nil ? "No terrain data (offline)" : "Terrain data incomplete (offline)")
                    .font(.footnote).foregroundColor(SunLook.poor)
            case .off, .ready:
                EmptyView()
            }
        }
        .sunPanel()
    }

    private var headingText: some SwiftUI.View {
        let sigma = sqrt(motion.estimate.varianceDeg2)
        if !motion.available || motion.deviceToFrame == nil {
            return Text("No motion sensors: drag to look around").foregroundColor(SunLook.poor)
        }
        if sigma > 10 { return Text(String(format: "Heading ±%.0f° (align with the sun)", sigma)).foregroundColor(SunLook.poor) }
        if sigma > 3 { return Text(String(format: "Heading ±%.0f°", sigma)).foregroundColor(SunLook.fair) }
        return Text(String(format: "Heading ±%.1f°", sigma)).foregroundColor(SunLook.good)
    }

    private func readout(usable: Bool, crossing: AzimuthCrossing?) -> some SwiftUI.View {
        VStack(spacing: 2) {
            if !usable {
                Text("Hold the phone up to see when the sun is in that direction.")
            } else if let crossing {
                Text(PlaceTime.time(crossing.unixUtc, location.timeZone))
                    .font(.system(size: 34, weight: .bold))
                    .foregroundColor(SunLook.amber)
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
                        + dates.map { PlaceTime.dayAndTime($0.unixUtc, location.timeZone) }.joined(separator: ", ")
                )
                .foregroundColor(SunLook.secondary)
            }
        }
        .multilineTextAlignment(.center)
    }

    /// The align menu. Each item says what happens, or why it is not possible now.
    private var alignMenu: some SwiftUI.View {
        // The time of the timer, which is the time of the scene in the demo mode.
        let t = now.timeIntervalSince1970
        let observer = location.observer
        let sunUp = ((try? sunPosition(t, observer: observer))?.altitudeDeg ?? -1) > 0
        let moonUp = ((try? moonPosition(t, observer: observer))?.altitudeDeg ?? -1) > 0
        let moonBright = ((try? moonIlluminatedFraction(t)) ?? 0) >= Self.minMoonFraction
        let located = location.hasPlace
        return Menu {
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
                Text(located ? "Pick it on a map, then aim at it" : "Needs your place (allow location access, or drop it)")
            }
            .disabled(!located)
        } label: {
            Text("Align")
        }
        .buttonStyle(SunButtonStyle(primary: true))
    }

    /// The sun and the moon need the camera image, so the camera switches on first.
    private func startBodyAlign(_ target: AlignTarget) {
        message = nil
        cameraOn = true
        aligning = target
    }

    private func buttons(view: Almucantar.View) -> some SwiftUI.View {
        HStack(spacing: 8) {
            Button(cameraOn ? "Sky" : "Camera") {
                aligning = nil
                cameraOn.toggle()
            }
            Button(dayOffset == 0 && minuteOfDay == nil ? "Time" : "Time ●") { showTime.toggle() }
            if aligning == .landmark {
                Button("Aligned") {
                    message = alignWithLandmark(view: view)
                    aligning = nil
                }
                .buttonStyle(SunButtonStyle(primary: true))
            } else if aligning != nil {
                Button("Cancel") { aligning = nil }
                    .buttonStyle(SunButtonStyle(primary: true))
            } else {
                // The order of Android: Place before Align, and the menu last.
                Button("Place") {
                    message = nil
                    showPlace = true
                }
                alignMenu
                // The menu of the app: the settings and the credits. It is hidden during an alignment.
                Menu {
                    Button("Settings and credits") { showSettings = true }
                } label: {
                    Image(systemName: "ellipsis").rotationEffect(.degrees(90)).frame(width: 16, height: 22)
                }
                .accessibilityLabel("Menu")
            }
        }
        .buttonStyle(SunButtonStyle())
    }
}
