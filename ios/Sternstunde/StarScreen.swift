import Almucantar
import Combine
import SwiftUI

/// An object on the screen, for a tap. A brighter object wins a tap between two.
struct Hit {
    let target: Target
    let at: CGPoint
    let mag: Double
}

/// The objects of the last frame. The canvas writes them when it draws, and a tap reads them.
final class HitList {
    var hits: [Hit] = []

    /// The object nearest to `at` within `radius` points; a brighter object wins a near tie.
    func nearest(_ at: CGPoint, radius: Double, accept: (Hit) -> Bool = { _ in true }) -> Hit? {
        func distance(_ h: Hit) -> Double { hypot(h.at.x - at.x, h.at.y - at.y) }
        return hits.filter { accept($0) && distance($0) <= radius }.min { distance($0) + 3 * $0.mag < distance($1) + 3 * $1.mag }
    }
}

/// The sky view of sternstunde: the stars, the constellations, the planets, the moon, and the Milky
/// Way in the direction of the phone, over the terrain of the place. With the camera, the same
/// overlay lies on the camera image. A tap on an object shows its name and its rise and set; Find
/// leads to an object; Align corrects the heading with a bright star. For a selected sun, moon, or
/// planet, the user can show its path in the 24 hours around the selected time. Without motion
/// sensors (the simulator), drag to look around.
struct StarScreen: SwiftUI.View {
    let stars: StarData

    @StateObject private var motion = MotionTracker()
    @StateObject private var location = LocationProvider()
    // The terrain and the kept places use the same cache, because both write its index.
    @StateObject private var terrain = TerrainHorizon(cache: DemCache.shared)
    @StateObject private var kept = KeptPlaces(cache: DemCache.shared)
    @AppStorage(Settings.terrainHorizon) private var terrainOn = true
    @AppStorage(StarSettings.lines) private var linesOn = true
    @AppStorage(StarSettings.red) private var red = false
    @AppStorage(StarSettings.paths) private var pathsOn = false
    @State private var now = Date()
    @State private var dayOffset = 0.0
    @State private var minuteOfDay: Double?
    @State private var showTime = false
    @State private var showPlace = false
    @State private var showFind = false
    @State private var showSettings = false
    @State private var cameraOn = false
    @State private var camera: Almucantar.Camera?
    @State private var aligning = false
    @State private var selected: Target?
    @State private var riseSet: RiseSet?
    @State private var path: BodyPath?
    @State private var message: String?
    // The zoom of the sky view: a pinch changes it, a double tap resets it. The camera view has the
    // field of view of the lens, so the zoom does not change it.
    @State private var zoom = 1.0
    @State private var zoomStart: Double?
    /// A pinch happened during the current touch, so its end is not a tap.
    @State private var pinched = false
    @State private var dragAzimuth = 180.0
    @State private var dragPitch = 30.0
    @State private var dragStart: (azimuth: Double, pitch: Double)?
    @State private var lastTap: (time: Date, at: CGPoint)?
    @State private var skyCache = SkyCache()
    @State private var hits = HitList()

    /// The points of the Milky Way. They do not change, so the app makes them once.
    private static let milkyWay = MilkyWay.create()
    /// The horizontal field of view of the sky view without the camera, in portrait and landscape.
    private static let fovPortraitDeg = 70.0
    private static let fovLandscapeDeg = 100.0
    /// The field of view of a pinch in the sky view.
    private static let minFovDeg = 10.0
    private static let maxFovDeg = 120.0
    /// The alignment takes only objects to this magnitude, because the user must see them.
    private static let alignMaxMag = 2.5
    /// The error of a tap on a star in the camera image, in degrees.
    private static let starTapSigmaDeg = 0.3

    /// The calendar in the time zone of the place. All days and times of the screen use it.
    private var calendar: Calendar { PlaceTime.calendar(location.timeZone) }
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some SwiftUI.View {
        GeometryReader { geometry in
            // The canvas and the camera ignore the safe area, so the view uses the full screen.
            let insets = geometry.safeAreaInsets
            let size = CGSize(
                width: geometry.size.width + insets.leading + insets.trailing,
                height: geometry.size.height + insets.top + insets.bottom)
            let time = TimePanel.time(now: now, dayOffset: dayOffset, minuteOfDay: minuteOfDay, calendar: calendar)
            // The sky changes slowly: the stars move 0.004° in a second, so it is calculated once per
            // second (a few milliseconds), and each frame only projects it.
            let sky = skyCache.sky(data: stars, milkyWay: Self.milkyWay, unixUtc: time, observer: location.observer)
            let view = makeView(size: size)
            let profile = terrainOn ? terrain.profile : nil
            let pathTarget = selected.flatMap { $0.hasPath && pathsOn ? $0 : nil }

            ZStack {
                Color(red: 0.02, green: 0.035, blue: 0.086).ignoresSafeArea()
                if cameraOn {
                    CameraView { camera = $0 }.ignoresSafeArea()
                }
                if let sky {
                    Canvas { context, _ in
                        draw(
                            &context, view: view, sky: sky, profile: profile, terrainLines: terrainOn ? terrain.lines : [],
                            path: path.flatMap { pathTarget != nil && $0.target == pathTarget ? $0 : nil })
                    }
                    .ignoresSafeArea()
                    .gesture(dragGesture(view: view, size: size))
                    .simultaneousGesture(zoomGesture(size: size))
                }

                VStack(alignment: .leading, spacing: 8) {
                    if let sky { statusPanel(sky) }
                    if let message { Text(message).skyPanel() }
                    if aligning { Text("Point at a bright star, a planet, or the moon, and tap it.").skyPanel() }
                    Spacer()
                    VStack(spacing: 8) {
                        if showTime {
                            TimePanel(now: now, calendar: calendar, dayOffset: $dayOffset, minuteOfDay: $minuteOfDay)
                                .skyPanel()
                        }
                        if let selected, let sky { infoCard(selected, sky: sky, profile: profile) }
                        buttons
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(12)
            }
            // The red light: everything turns red, so the eyes stay adapted to the dark.
            .overlay {
                if red {
                    Color(red: 1, green: 0.125, blue: 0.063).blendMode(.multiply).ignoresSafeArea().allowsHitTesting(false)
                }
            }
            .onAppear {
                motion.start()
                location.start()
                // The provider can load the last place in its init, and onChange does not see
                // that first value. The heading filter needs the place for the declination.
                motion.observer = location.observer
                updateTerrain()
            }
            .onReceive(timer) { now = $0 }
            .onChange(of: location.observer) {
                motion.observer = location.observer
                updateTerrain()
            }
            .onChange(of: location.hasPlace) { updateTerrain() }
            .onChange(of: terrainOn) { updateTerrain() }
            .onChange(of: location.notice) {
                if let notice = location.notice { message = notice }
            }
            .sheet(isPresented: $showSettings) { SettingsView(starAttribution: stars.attribution) }
            .fullScreenCover(isPresented: $showPlace) { PlacePanel(location: location, kept: kept) }
            .fullScreenCover(isPresented: $showFind) {
                FindPanel(stars: stars) {
                    selected = $0
                    showFind = false
                }
            }
            // The rise and the set take some milliseconds: calculate them in the background, again
            // for a new target, a new place, another hour, or a new terrain.
            .task(id: RiseSetKey(
                    target: selected, latitudeDeg: location.observer.latitudeDeg, longitudeDeg: location.observer.longitudeDeg,
                    hour: Int(time / 3600), terrain: profile == nil ? -1 : terrain.revision)) {
                await updateRiseSet(time: time, profile: profile)
            }
            // The path of the selected body takes some milliseconds: calculate it in the background,
            // again for a new target, a new place, a new terrain, a new time zone, or after 10 minutes
            // of the selected time.
            .task(id: PathKey(
                    target: pathTarget, latitudeDeg: location.observer.latitudeDeg, longitudeDeg: location.observer.longitudeDeg,
                    step: Int((time / Target.pathStepS).rounded(.down)), terrain: profile == nil ? -1 : terrain.revision,
                    timeZone: location.timeZone.identifier)) {
                await updatePath(target: pathTarget, time: time, profile: profile)
            }
        }
    }

    /// The most probable place (no location) gets no terrain, so the app waits for a place before it downloads
    /// tiles: the device location, a manual place, or the last place.
    private func updateTerrain() {
        if terrainOn && !location.hasPlace { return }
        terrain.update(observer: location.observer, enabled: terrainOn)
    }

    private struct RiseSetKey: Hashable {
        let target: Target?
        let latitudeDeg: Double
        let longitudeDeg: Double
        let hour: Int
        let terrain: Int
    }

    private struct PathKey: Hashable {
        let target: Target?
        let latitudeDeg: Double
        let longitudeDeg: Double
        let step: Int
        let terrain: Int
        let timeZone: String
    }

    /// Calculates the path of `target` for the 10-minute step of `time`. The screen keeps the old
    /// path until the new one is ready, but it draws a path only for the current target.
    private func updatePath(target: Target?, time: Double, profile: HorizonProfile?) async {
        guard let target else { return }
        let at = (time / Target.pathStepS).rounded(.down) * Target.pathStepS
        let (data, observer, timeZone) = (stars, location.observer, location.timeZone)
        let result = await Task.detached {
            try? target.path(data, unixUtc: at, observer: observer, profile: profile, timeZone: timeZone)
        }.value
        if !Task.isCancelled, let result { path = result }
    }

    private func updateRiseSet(time: Double, profile: HorizonProfile?) async {
        riseSet = nil
        guard let target = selected else { return }
        let (data, observer) = (stars, location.observer)
        let result = await Task.detached {
            try? target.riseAndSet(data, unixUtc: time, observer: observer, profile: profile)
        }.value
        if !Task.isCancelled { riseSet = result }
    }

    // MARK: - The view and the gestures

    private func baseFov(_ size: CGSize) -> Double {
        size.width < size.height ? Self.fovPortraitDeg : Self.fovLandscapeDeg
    }

    private func makeView(size: CGSize) -> Almucantar.View {
        let fov = min(Self.maxFovDeg, max(Self.minFovDeg, baseFov(size) / zoom))
        let virtualCamera =
            (try? cameraFromFov(width: size.width, height: size.height, horizontalFovDeg: fov)) ?? Almucantar.Camera()
        if motion.available, let q = motion.deviceToFrame {
            return Almucantar.View(
                deviceToEnu: q, screenRotation: SkyOrientation.screenRotation(), headingOffsetDeg: motion.shownOffsetDeg,
                camera: cameraOn ? (camera ?? virtualCamera) : virtualCamera)
        }
        return Almucantar.View(
            deviceToEnu: SkyOrientation.upright(azimuthDeg: dragAzimuth, pitchDeg: dragPitch), camera: virtualCamera)
    }

    private func dragGesture(view: Almucantar.View, size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard !motion.available || motion.deviceToFrame == nil, zoomStart == nil else { return }
                if dragStart == nil { dragStart = (dragAzimuth, dragPitch) }
                guard let start = dragStart else { return }
                // The sky follows the finger: one point is the angle of one point of the view.
                let degreesPerPoint = min(Self.maxFovDeg, max(Self.minFovDeg, baseFov(size) / zoom)) / max(1, size.width)
                dragAzimuth = (start.azimuth - value.translation.width * degreesPerPoint + 360).truncatingRemainder(dividingBy: 360)
                dragPitch = min(85, max(-85, start.pitch + value.translation.height * degreesPerPoint))
            }
            .onEnded { value in
                dragStart = nil
                defer { pinched = false }
                guard !pinched, abs(value.translation.width) < 10, abs(value.translation.height) < 10 else { return }
                tap(view: view, at: value.location)
            }
    }

    private func zoomGesture(size: CGSize) -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                pinched = true
                guard !cameraOn else { return }
                if zoomStart == nil { zoomStart = zoom }
                let base = baseFov(size)
                zoom = min(base / Self.minFovDeg, max(base / Self.maxFovDeg, (zoomStart ?? 1) * value.magnification))
            }
            .onEnded { _ in zoomStart = nil }
    }

    private func tap(view: Almucantar.View, at point: CGPoint) {
        let time = Date()
        // A second tap near the first one in a short time is a double tap: it resets the zoom.
        if let last = lastTap, time.timeIntervalSince(last.time) < 0.3, hypot(point.x - last.at.x, point.y - last.at.y) < 40 {
            zoom = 1
            lastTap = nil
            return
        }
        lastTap = (time, point)
        if aligning {
            aligning = false
            message = align(view: view, at: point)
        } else {
            selected = hits.nearest(point, radius: 32)?.target
        }
    }

    /// Aligns the heading with a tapped bright object. Returns the message for the user.
    private func align(view: Almucantar.View, at point: CGPoint) -> String {
        guard
            let hit = hits.nearest(
                point, radius: 40,
                accept: {
                    if case .constellation = $0.target { return false }
                    return $0.mag <= Self.alignMaxMag
                })
        else { return "Tap a bright star, a planet, or the moon." }
        // The direction now: the alignment sets the time to now, but the sky of the frame can be up
        // to one second old.
        guard let truth = try? hit.target.direction(stars, unixUtc: Date().timeIntervalSince1970, observer: location.observer),
            let measurement = try? headingFromDirection(
                view, point: ScreenPoint(x: point.x, y: point.y), trueAzimuthDeg: truth.azimuthDeg, sigmaDeg: Self.starTapSigmaDeg)
        else { return "No alignment: the direction gives no heading." }
        motion.apply(measurement)
        return String(
            format: "Heading aligned with %@ (now ±%.1f°).", hit.target.name(stars), sqrt(motion.estimate.varianceDeg2))
    }

    // MARK: - Drawing

    private static let constellationColor = Color(red: 0.498, green: 0.667, blue: 1)
    private static let selectionColor = Color(red: 0.498, green: 0.847, blue: 1)

    /// The color of the sky without the camera, from the altitude of the sun.
    static func skyColor(_ sunAltitudeDeg: Double) -> RGB {
        let stops: [(Double, RGB)] = [
            (-18, RGB(r: 5, g: 9, b: 22)),
            (-12, RGB(r: 10, g: 18, b: 42)),
            (-6, RGB(r: 24, g: 40, b: 82)),
            (0, RGB(r: 44, g: 74, b: 130)),
            (10, RGB(r: 52, g: 104, b: 170)),
        ]
        let a = min(10, max(-18, sunAltitudeDeg))
        for i in 1..<stops.count where a <= stops[i].0 {
            let (a0, c0) = stops[i - 1]
            let (a1, c1) = stops[i]
            let t = (a - a0) / (a1 - a0)
            return RGB(r: c0.r + (c1.r - c0.r) * t, g: c0.g + (c1.g - c0.g) * t, b: c0.b + (c1.b - c0.b) * t)
        }
        return stops.last!.1
    }

    private func disc(_ at: CGPoint, _ radius: Double) -> Path {
        Path(ellipseIn: CGRect(x: at.x - radius, y: at.y - radius, width: 2 * radius, height: 2 * radius))
    }

    private func label(
        _ context: inout GraphicsContext, _ view: Almucantar.View, _ direction: Horizontal, _ text: String, size: CGFloat,
        weight: Font.Weight = .regular, color: Color, gap: Double, italic: Bool = false
    ) {
        var font = Font.system(size: size, weight: weight)
        if italic { font = font.italic() }
        SkyDrawing.label(&context, view, direction, Text(text).font(font).foregroundColor(color), gap: gap)
    }

    /// Draws the sky: the Milky Way, the constellation lines, the stars, the names, the path of the
    /// selected body, the planets, the moon, the sun, the horizon, the terrain, and the selection.
    /// Objects below the horizon or behind the terrain are dim, so the user can still find them. Each
    /// drawn object goes into `hits`.
    private func draw(
        _ context: inout GraphicsContext, view: Almucantar.View, sky: SkyAt, profile: HorizonProfile?, terrainLines: [[Horizontal]],
        path: BodyPath?
    ) {
        let bounds = CGRect(x: 0, y: 0, width: view.camera.width, height: view.camera.height)
        if !cameraOn { context.fill(Path(bounds), with: .color(Self.skyColor(sky.sun.altitudeDeg).color)) }
        let limit = StarLook.limitingMagnitude(sky.sun.altitudeDeg)
        let hidden = { (d: Horizontal) in d.altitudeDeg < horizonAt(profile, azimuthDeg: d.azimuthDeg) }
        var found: [Hit] = []
        let milkyWay = Self.milkyWay

        // The Milky Way: soft clouds and fine grains, added on top of each other, only in a dark sky.
        // The air near the horizon dims it.
        let milky = min(1, max(0, (limit - 3.5) / 2.5))
        if milky > 0.01, let points = try? projectPoints(view, sky.dust) {
            // A cloud is about 2.8° across, so its size follows the zoom of the view.
            let radius = view.camera.fx * 1.4 * .pi / 180
            var clouds = context
            clouds.blendMode = .plusLighter
            let cloudColor = Color(red: 0.84, green: 0.863, blue: 1)
            var grains = Path()
            for i in points.indices where points[i].valid {
                let altitude = sky.dust[i].altitudeDeg
                if altitude < 0 { continue }
                let alpha = milky * milkyWay.weight[i] * min(1, altitude / 14)
                let at = CGPoint(x: points[i].point.x, y: points[i].point.y)
                if milkyWay.cloud[i] {
                    clouds.fill(disc(at, radius), with: .color(cloudColor.opacity(0.05 * alpha)))
                } else if alpha > 0.15 {
                    grains.addPath(disc(at, 0.6))
                }
            }
            context.fill(grains, with: .color(Color(red: 0.88, green: 0.9, blue: 1).opacity(0.4)))
        }

        guard let points = try? projectPoints(view, sky.stars) else { return }
        func screen(_ i: Int) -> CGPoint? {
            points[i].valid ? CGPoint(x: points[i].point.x, y: points[i].point.y) : nil
        }
        // The constellation lines between projected stars. A segment needs both ends in front of
        // the camera.
        if linesOn && limit > 1 {
            var path = Path()
            for c in stars.constellations {
                for line in c.lines {
                    for k in 1..<max(1, line.count) {
                        guard let a = screen(line[k - 1]), let b = screen(line[k]) else { continue }
                        path.move(to: a)
                        path.addLine(to: b)
                    }
                }
            }
            context.stroke(path, with: .color(Self.constellationColor.opacity(0.38)), lineWidth: 1)
        }

        // The stars, the faintest first. The stars of the same look go into one path, because one
        // fill for each of 5000 stars is slow.
        var count = 0
        while count < stars.count && stars.mag[count] <= limit { count += 1 }
        var groups: [Int: (path: Path, halo: Path, color: RGB, alpha: Double)] = [:]
        for i in (0..<count).reversed() {
            guard let at = screen(i) else { continue }
            let mag = stars.mag[i]
            let fade = min(1, max(0, 0.35 + (limit - mag) / 1.5))
            let alpha = hidden(sky.stars[i]) ? 0.22 * fade : fade
            // 11 steps of the brightness (0 to 1) and 13 steps of the color (B-V from -0.4 to 2).
            let alphaStep = Int((alpha * 10).rounded())
            let colorStep = Int(((min(2, max(-0.4, stars.bv[i])) + 0.4) / 0.2).rounded())
            let key = alphaStep * 16 + colorStep
            var group = groups[key] ?? (Path(), Path(), StarLook.color(Double(colorStep) * 0.2 - 0.4), Double(alphaStep) / 10)
            let r = StarLook.radius(mag)
            group.path.addPath(disc(at, r))
            if mag < 1.5 { group.halo.addPath(disc(at, r * 2.6)) }
            groups[key] = group
            found.append(Hit(target: .star(i), at: at, mag: mag))
        }
        for group in groups.values {
            context.fill(group.halo, with: .color(group.color.color.opacity(0.18 * group.alpha)))
            context.fill(group.path, with: .color(group.color.color.opacity(group.alpha)))
        }

        // The names of the brightest stars, and of the constellations.
        let starName = Color(red: 0.863, green: 0.894, blue: 1)
        for i in 0..<count {
            if stars.mag[i] > min(limit - 1, 1.6) { break }
            guard let name = stars.names[i], screen(i) != nil else { continue }
            let d = sky.stars[i]
            label(&context, view, d, name, size: 12, color: starName.opacity(hidden(d) ? 0.3 : 0.85), gap: 8)
        }
        if linesOn && limit > 1 {
            for (i, c) in stars.constellations.enumerated() {
                let d = sky.labels[i]
                guard let at = SkyDrawing.point(view, d) else { continue }
                label(&context, view, d, c.name, size: 12, color: Self.constellationColor.opacity(0.75), gap: 0, italic: true)
                found.append(Hit(target: .constellation(i), at: at, mag: 7))
            }
        }
        if milky > 0.3, let at = SkyDrawing.point(view, sky.galacticCenter) {
            label(
                &context, view, sky.galacticCenter, "Milky Way center", size: 12,
                color: Color(red: 0.784, green: 0.824, blue: 0.941).opacity(0.6), gap: 6)
            found.append(Hit(target: .galacticCenter, at: at, mag: 6.5))
        }

        // The path of the selected body, under the bodies.
        if let path { drawBodyPath(&context, view, path, color: Self.pathColor(path.target, sky)) }

        // The planets that the eye sees now.
        for planet in sky.planets {
            if planet.mag > limit + 0.5 { continue }
            guard let at = SkyDrawing.point(view, planet.direction) else { continue }
            context.fill(
                disc(at, StarLook.radius(planet.mag) + 1),
                with: .color(planet.color.color.opacity(hidden(planet.direction) ? 0.3 : 1)))
            label(
                &context, view, planet.direction, planet.name, size: 13, weight: .bold,
                color: Color(red: 1, green: 0.863, blue: 0.627), gap: 9)
            found.append(Hit(target: .planet(planet.planet), at: at, mag: planet.mag - 1))
        }

        // The moon with its phase, and the sun.
        if sky.moon.fraction >= 0.01, let at = SkyDrawing.point(view, sky.moon.direction) {
            SkyDrawing.drawMoon(&context, view, sky.moon, sun: sky.sun, at: at, radius: 11)
            label(&context, view, sky.moon.direction, "Moon", size: 12, color: .white, gap: 16)
            found.append(Hit(target: .moon, at: at, mag: -12))
        }
        if let at = SkyDrawing.point(view, sky.sun) {
            context.fill(disc(at, 13), with: .color(Color(red: 1, green: 0.878, blue: 0.541)))
            label(&context, view, sky.sun, "Sun", size: 12, color: .white, gap: 18)
            found.append(Hit(target: .sun, at: at, mag: -26))
        }

        // The horizon, the terrain, and the cardinal points.
        if let horizon = try? horizonLine(view) {
            context.stroke(SkyDrawing.path(horizon), with: .color(.white.opacity(profile != nil ? 0.3 : 0.7)), lineWidth: 1)
        }
        if profile != nil {
            if !cameraOn {
                SkyDrawing.fillTerrain(
                    &context, view, lines: terrainLines, color: Color(red: 0.055, green: 0.086, blue: 0.078).opacity(0.72))
            }
            for line in terrainLines {
                if let lines = try? projectLine(view, line) {
                    context.stroke(
                        SkyDrawing.path(lines), with: .color(Color(red: 0.667, green: 0.824, blue: 0.627).opacity(0.85)),
                        lineWidth: 1.5)
                }
            }
        }
        for (az, name) in [(0.0, "N"), (90.0, "E"), (180.0, "S"), (270.0, "W")] {
            label(&context, view, Horizontal(azimuthDeg: az, altitudeDeg: 0), name, size: 14, weight: .bold, color: .white, gap: 12)
        }

        // The selected object: a ring, or an arrow at the border of the screen that leads to it.
        if let selected {
            let d = selected.direction(sky)
            if let center = try? unproject(view, ScreenPoint(x: view.camera.width / 2, y: view.camera.height / 2)),
                let (at, direction) = SkyDrawing.edgeIndicator(view, from: center, to: d)
            {
                SkyDrawing.drawArrow(&context, at: at, direction: direction, color: Self.selectionColor)
            } else if let at = SkyDrawing.point(view, d) {
                context.stroke(disc(at, 16), with: .color(Self.selectionColor), lineWidth: 2)
            }
        }
        hits.hits = found
    }

    /// The color of the path of a body: the color of the planet, of the moon, or of the sun.
    private static func pathColor(_ target: Target, _ sky: SkyAt) -> Color {
        switch target {
        case .planet(let p): sky.planets.first { $0.planet == p }!.color.color
        case .sun: Color(red: 1, green: 0.878, blue: 0.541)  // #FFE08A
        default: Color(red: 0.878, green: 0.902, blue: 0.941)  // #E0E6F0
        }
    }

    /// The path of a body: a line over the horizon or the terrain, a faint dashed line below it, and
    /// a dot with the hour at each full hour. Only the hours over the horizon have a label.
    private func drawBodyPath(_ context: inout GraphicsContext, _ view: Almucantar.View, _ path: BodyPath, color: Color) {
        for line in path.hidden {
            if let lines = try? projectLine(view, line) {
                context.stroke(
                    SkyDrawing.path(lines), with: .color(color.opacity(0.3)),
                    style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [5, 4]))
            }
        }
        for line in path.visible {
            if let lines = try? projectLine(view, line) {
                context.stroke(SkyDrawing.path(lines), with: .color(color.opacity(0.8)), lineWidth: 2)
            }
        }
        for hour in path.hours {
            guard let at = SkyDrawing.point(view, hour.direction) else { continue }
            if hour.hidden {
                context.fill(disc(at, 2), with: .color(color.opacity(0.3)))
            } else {
                context.fill(disc(at, 3), with: .color(color))
                label(&context, view, hour.direction, hour.label, size: 11, color: color.opacity(0.9), gap: 7)
            }
        }
    }

    // MARK: - Panels

    private func statusPanel(_ sky: SkyAt) -> some SwiftUI.View {
        VStack(alignment: .leading, spacing: 2) {
            Button {
                message = nil
                showPlace = true
            } label: {
                Text(location.text).bold().foregroundColor(.white)
            }
            headingText
            let moon =
                sky.moon.direction.altitudeDeg > 0 ? String(format: "moon up, %d%% lit", Int(sky.moon.fraction * 100)) : "moon down"
            Text("\(StarLook.darkness(sky.sun.altitudeDeg)) · \(moon)").foregroundColor(.white.opacity(0.85))
            switch terrain.status {
            case .loading(let remaining):
                Text("Loading the terrain (\(remaining) \(remaining == 1 ? "tile" : "tiles"))…")
                    .font(.footnote).foregroundColor(.white.opacity(0.7))
            case .missing:
                Text(terrain.profile == nil ? "No terrain data (offline)" : "Terrain data incomplete (offline)")
                    .font(.footnote).foregroundColor(.orange)
            case .off, .ready:
                EmptyView()
            }
        }
        .skyPanel()
    }

    private var headingText: some SwiftUI.View {
        let sigma = sqrt(motion.estimate.varianceDeg2)
        if !motion.available || motion.deviceToFrame == nil {
            return Text("No motion sensors: drag to look around").foregroundColor(.orange)
        }
        if sigma > 10 { return Text(String(format: "Heading ±%.0f° (align with a bright star)", sigma)).foregroundColor(.orange) }
        if sigma > 3 { return Text(String(format: "Heading ±%.0f°", sigma)).foregroundColor(.yellow) }
        return Text(String(format: "Heading ±%.1f°", sigma)).foregroundColor(.green)
    }

    /// The card of a tapped or found object: its name, kind, height, and rise and set.
    private func infoCard(_ target: Target, sky: SkyAt, profile: HorizonProfile?) -> some SwiftUI.View {
        let d = target.direction(sky)
        let kind: String =
            switch target {
            case .star(let i): String(format: "Star · magnitude %.1f", stars.mag[i])
            case .planet(let p): String(format: "Planet · magnitude %.1f", sky.planets.first { $0.planet == p }!.mag)
            case .moon: String(format: "Moon · %d%% lit", Int(sky.moon.fraction * 100))
            case .sun: "Sun"
            case .constellation: "Constellation"
            case .galacticCenter: "Milky Way · Sagittarius A*"
            }
        let times = riseSet?.text(location.timeZone, overTerrain: profile != nil) ?? "…"
        let gray = Color(red: 0.706, green: 0.753, blue: 0.824)
        return VStack(alignment: .leading, spacing: 2) {
            Text(target.name(stars)).font(.system(size: 17, weight: .semibold))
            Text(kind).foregroundColor(gray)
            Text(times)
            Text(String(format: "%.0f° high, azimuth %.0f°", d.altitudeDeg, d.azimuthDeg)).foregroundColor(gray)
            if target.hasPath {
                Button(pathsOn ? "Hide path" : "Show path (24 h)") { pathsOn.toggle() }
                    .padding(.vertical, 4)
                    .tint(Color(red: 0.81, green: 0.74, blue: 1))
            }
        }
        .frame(maxWidth: 440, alignment: .leading)
        .skyPanel()
        .onTapGesture { selected = nil }
    }

    private var buttons: some SwiftUI.View {
        HStack(spacing: 6) {
            Button(cameraOn ? "Sky" : "Camera") { cameraOn.toggle() }
                .buttonStyle(.bordered)
            Button(dayOffset == 0 && minuteOfDay == nil ? "Time" : "Time ●") { showTime.toggle() }
                .buttonStyle(.bordered)
            Button("Find") { showFind = true }
                .buttonStyle(.bordered)
            Button("Place") {
                message = nil
                showPlace = true
            }
            .buttonStyle(.bordered)
            Button(aligning ? "Cancel" : "Align") {
                message = nil
                aligning.toggle()
                // The alignment needs the sky of now, because the user points at the real sky.
                if aligning {
                    dayOffset = 0
                    minuteOfDay = nil
                }
            }
            .buttonStyle(.borderedProminent)
            .foregroundStyle(Self.onTint)
            // The menu of the app: the settings and the credits.
            Menu {
                Button("Settings and credits") { showSettings = true }
            } label: {
                Image(systemName: "ellipsis").rotationEffect(.degrees(90)).frame(width: 12, height: 22)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("Menu")
        }
        .controlSize(.small)
        .tint(Color(red: 0.81, green: 0.74, blue: 1))
    }

    /// The label on the light tint of a prominent button: dark, as on Android, so it can be read.
    private static let onTint = Color(red: 0.22, green: 0.12, blue: 0.40)
}

/// Find: the stars with a name, the planets, the moon, the sun, the constellations, and the center
/// of the Milky Way.
struct FindPanel: SwiftUI.View {
    let stars: StarData
    let onPick: (Target) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private struct Entry: Identifiable {
        let name: String
        let kind: String
        let target: Target
        var id: Target { target }
    }

    private var entries: [Entry] {
        planetStyles.map { Entry(name: $0.name, kind: "Planet", target: .planet($0.planet)) }
            + [
                Entry(name: "Moon", kind: "Moon", target: .moon),
                Entry(name: "Sun", kind: "Star", target: .sun),
                Entry(name: "Milky Way center", kind: "Milky Way", target: .galacticCenter),
            ]
            + stars.constellations.enumerated().map { Entry(name: $1.name, kind: "Constellation", target: .constellation($0)) }
            + stars.names.sorted { $0.key < $1.key }.map {
                Entry(name: $0.value, kind: String(format: "Star · %.1f", stars.mag[$0.key]), target: .star($0.key))
            }
    }

    /// Without accents and in lower case, so "alnilam" finds "Alnilam".
    static func fold(_ text: String) -> String {
        text.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
    }

    /// The entries whose name starts with the query, then the entries whose name contains it.
    private func search(_ query: String) -> [Entry] {
        let q = Self.fold(query.trimmingCharacters(in: .whitespaces))
        if q.isEmpty { return [] }
        var starts: [Entry] = []
        var contains: [Entry] = []
        for entry in entries {
            let name = Self.fold(entry.name)
            if name.hasPrefix(q) { starts.append(entry) } else if name.contains(q) { contains.append(entry) }
        }
        return Array((starts + contains).prefix(20))
    }

    var body: some SwiftUI.View {
        let found = search(query)
        NavigationStack {
            List(found) { entry in
                Button {
                    onPick(entry.target)
                } label: {
                    HStack {
                        Text(entry.name).foregroundColor(.primary)
                        Spacer()
                        Text(entry.kind).font(.footnote).foregroundColor(.secondary)
                    }
                }
            }
            .overlay {
                if found.isEmpty {
                    Text(query.isEmpty ? "A star, a planet, or a constellation" : "Nothing found").foregroundColor(.secondary)
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "A star, a planet, or a constellation")
            .navigationTitle("Find")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
    }
}
