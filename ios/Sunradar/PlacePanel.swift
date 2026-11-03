import Almucantar
import SwiftUI

/// The place panel: the offline map (Natural Earth) fills the screen, and a tap drops the place.
/// The search bar is at the top, the map buttons are on the right, and the card at the bottom shows
/// the place, "Keep offline", and the kept places. The layout is the one of a map app, the same as
/// on Android. The map needs no network.
struct PlacePanel: SwiftUI.View {
    @ObservedObject var location: LocationProvider
    @ObservedObject var kept: KeptPlaces
    @AppStorage(Settings.terrainHorizon) private var terrainOn = true
    @Environment(\.dismiss) private var dismiss
    @State private var map: BaseMap?
    @State private var failed = false
    @State private var query = ""
    /// The result of the search. It changes only with the query, not with each frame of a drag.
    @State private var results: [BaseMap.Place] = []
    @State private var viewport: MapViewport
    @State private var dragStart: MapViewport?
    @State private var zoomStart: Double?
    /// True after "Use my location", so the map follows the next device location.
    @State private var followDevice = false
    @FocusState private var searching: Bool

    init(location: LocationProvider, kept: KeptPlaces) {
        self.location = location
        self.kept = kept
        _viewport = State(initialValue: MapViewport(center: location.observer, zoom: 7))
    }

    var body: some SwiftUI.View {
        ZStack {
            mapView.ignoresSafeArea()
            VStack(spacing: 0) {
                searchBar
                Spacer(minLength: 0)
                mapButtons
                card
            }
        }
        .environment(\.colorScheme, .dark)
        .preferredColorScheme(.dark)
        .statusBarHidden(false)
        .task { await kept.refresh() }
        .task {
            do {
                map = try await BaseMapStore.shared.load()
                search()
            } catch {
                failed = true
            }
        }
        .onChange(of: query) { search() }
        .onChange(of: location.observer) {
            if followDevice && !location.manual {
                followDevice = false
                viewport.center = MapViewport(center: location.observer, zoom: viewport.zoom).center
            }
        }
    }

    // MARK: - The search

    /// The search: a rounded bar with the back arrow, and the results in a card below it.
    private var searchBar: some SwiftUI.View {
        VStack(spacing: 8) {
            HStack(spacing: 0) {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "arrow.left").font(.system(size: 20, weight: .medium))
                        .frame(width: 52, height: 52)
                }
                .accessibilityLabel("Close")
                TextField(
                    "", text: $query,
                    prompt: Text("Search a place").foregroundColor(PanelColors.muted)
                )
                .font(.system(size: 17))
                .foregroundColor(PanelColors.text)
                .tint(PanelColors.text)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                .focused($searching)
                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark").font(.system(size: 17, weight: .medium))
                            .frame(width: 52, height: 52)
                    }
                    .accessibilityLabel("Clear")
                } else {
                    Spacer().frame(width: 16)
                }
            }
            .foregroundColor(PanelColors.text)
            .frame(height: 52)
            .background(Capsule().fill(PanelColors.surface).shadow(color: .black.opacity(0.35), radius: 6, y: 2))
            if !results.isEmpty { resultList }
            if map == nil {
                Text(failed ? "The map is not available." : "Loading the map…").foregroundColor(PanelColors.text)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }

    /// The results: for each place a pin, the name, and the country in a second line.
    private var resultList: some SwiftUI.View {
        VStack(spacing: 0) {
            ForEach(Array(results.enumerated()), id: \.offset) { _, place in
                Button {
                    choose(place)
                } label: {
                    HStack(spacing: 16) {
                        Image(systemName: "mappin.and.ellipse").font(.system(size: 18))
                            .foregroundColor(PanelColors.muted)
                            .frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(place.name).foregroundColor(PanelColors.text)
                            if !place.country.isEmpty {
                                Text(place.country).font(.system(size: 13)).foregroundColor(PanelColors.muted)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: 16).fill(PanelColors.surface)
                .shadow(color: .black.opacity(0.35), radius: 6, y: 2))
    }

    // MARK: - The map

    private var mapView: some SwiftUI.View {
        ZStack {
            BaseMapDrawing.background
            if let map {
                GeometryReader { geometry in
                    Canvas { context, size in
                        BaseMapDrawing.draw(&context, map: map, viewport: viewport, size: size, pin: location.observer)
                    }
                    .gesture(dragGesture(size: geometry.size).simultaneously(with: zoomGesture))
                    .accessibilityLabel("The map: tap to drop the place")
                }
            }
        }
    }

    /// The map buttons, above the card on the right, as in other map apps.
    private var mapButtons: some SwiftUI.View {
        HStack {
            Spacer()
            VStack(spacing: 8) {
                MapButton(symbol: "plus", label: "Zoom in") { viewport.setZoom(viewport.zoom.rounded() + 1) }
                MapButton(symbol: "minus", label: "Zoom out") { viewport.setZoom(viewport.zoom.rounded() - 1) }
                MapButton(symbol: "scope", label: "Use my location") {
                    viewport.center = MapViewport(center: location.observer, zoom: viewport.zoom).center
                    followDevice = true
                    location.useDeviceLocation()
                }
            }
        }
        .padding(.trailing, 12)
        .padding(.bottom, 12)
    }

    /// A drag moves the map; a tap without a move drops the place.
    private func dragGesture(size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                searching = false
                if dragStart == nil { dragStart = viewport }
                guard let start = dragStart, hypot(value.translation.width, value.translation.height) >= 5 else { return }
                var next = start
                next.zoom = viewport.zoom
                next.pan(by: value.translation)
                viewport = next
            }
            .onEnded { value in
                dragStart = nil
                if hypot(value.translation.width, value.translation.height) < 5 {
                    drop(viewport.observer(at: value.location, size: size), name: nil)
                }
            }
    }

    private var zoomGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                if zoomStart == nil { zoomStart = viewport.zoom }
                viewport.setZoom((zoomStart ?? viewport.zoom) + log2(Double(value.magnification)))
                dragStart = nil
            }
            .onEnded { _ in zoomStart = nil }
    }

    // MARK: - The card

    /// The card at the bottom: the place, its coordinates and time zone, the hint, "Keep offline",
    /// the kept places, and the attribution.
    private var card: some SwiftUI.View {
        VStack(alignment: .leading, spacing: 4) {
            Text(placeName)
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(PanelColors.text)
                .lineLimit(1)
            Text("\(coordinates(location.observer)) · \(zoneName)")
                .font(.system(size: 14))
                .foregroundColor(PanelColors.muted)
            Text("Tap the map to move the place, or search for it.")
                .font(.system(size: 13))
                .foregroundColor(PanelColors.muted)
                .padding(.top, 4)
            if kept.cache != nil { offlineSection }
            Text(map?.attribution ?? "Map: Natural Earth (public domain)")
                .font(.system(size: 11))
                .foregroundColor(PanelColors.muted.opacity(0.6))
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.vertical, 6)
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            UnevenRoundedRectangle(topLeadingRadius: 24, topTrailingRadius: 24)
                .fill(PanelColors.surface)
                .shadow(color: .black.opacity(0.4), radius: 8)
                .ignoresSafeArea(edges: .bottom))
    }

    /// The name of the place: the part before " · " of a dropped or searched place, else
    /// "Your location" for the device location.
    private var placeName: String {
        if location.hasPlace && !location.manual { return "Your location" }
        let text = location.text
        guard let range = text.range(of: " · ") else { return text }
        return String(text[..<range.lowerBound])
    }

    /// The readable name of the time zone ("Central European Time"), not the IANA ID.
    private var zoneName: String {
        let zone = location.timeZone
        return zone.localizedName(for: .generic, locale: .current)
            ?? zone.localizedName(for: .standard, locale: .current) ?? zone.identifier
    }

    /// "Keep offline" with its status, and the list of the kept places.
    private var offlineSection: some SwiftUI.View {
        VStack(alignment: .leading, spacing: 4) {
            Rectangle().fill(PanelColors.divider).frame(height: 1).padding(.vertical, 8)
            HStack(spacing: 12) {
                if location.hasPlace {
                    Button("Keep offline") { kept.keep(name: location.text, observer: location.observer) }
                        .font(.system(size: 15, weight: .semibold))
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .tint(PanelColors.text)
                        .disabled(kept.downloading || !terrainOn)
                }
                Text(offlineText)
                    .font(.system(size: 13))
                    .foregroundColor(PanelColors.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !kept.places.isEmpty { keptList }
        }
    }

    /// The status of "Keep offline", or what it does when it is idle.
    private var offlineText: String {
        if let text = kept.stateText { return text }
        if location.hasPlace && !terrainOn { return "The terrain horizon is off in the settings." }
        return "Keep the terrain of this place for a trip without a network."
    }

    /// The kept places: the name, the tiles that the cache has, the size, and "Remove".
    private var keptList: some SwiftUI.View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Kept offline")
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(PanelColors.text)
                .padding(.top, 8)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(kept.places) { summary in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(summary.place.name).foregroundColor(PanelColors.text).lineLimit(1)
                                Text(keptText(summary)).font(.system(size: 12)).foregroundColor(PanelColors.muted)
                            }
                            Spacer()
                            Button("Remove", role: .destructive) { Task { await kept.remove(summary.id) } }
                                .buttonStyle(.borderless)
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            .frame(maxHeight: 140)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// "9 tiles, 44 MB", or "7/9 tiles, 30 MB" if tiles are missing.
    private func keptText(_ summary: DemCache.KeptSummary) -> String {
        let total = summary.place.tiles.count
        let tiles = summary.tiles == total ? KeptPlaces.tilesText(total) : "\(summary.tiles)/\(total) tiles"
        return "\(tiles), \(KeptPlaces.sizeText(summary.bytes))"
    }

    // MARK: - The actions

    private func search() { results = map?.search(query) ?? [] }

    private func choose(_ place: BaseMap.Place) {
        query = ""
        searching = false
        viewport = MapViewport(center: place.observer, zoom: max(viewport.zoom, 8))
        drop(place.observer, name: place.name)
    }

    private func coordinates(_ observer: Observer) -> String {
        String(format: "%.3f°, %.3f°", observer.latitudeDeg, observer.longitudeDeg)
    }

    /// Drops the place, with the name of the nearest place and its time zone.
    private func drop(_ observer: Observer, name: String?) {
        guard let map else { return }
        followDevice = false
        let text = coordinates(observer)
        let label = name ?? map.nearestPlace(to: observer).map { "near \($0.name)" } ?? text
        location.drop(observer, name: "\(label) · \(text)", timeZone: map.timeZone(at: observer))
    }
}

/// The colors of the surfaces of the panel. Android has the same colors.
private enum PanelColors {
    static let surface = Color(red: 0x1B / 255.0, green: 0x24 / 255.0, blue: 0x33 / 255.0)
    static let text = Color(red: 0xE8 / 255.0, green: 0xEC / 255.0, blue: 0xF2 / 255.0)
    static let muted = Color(red: 0xA9 / 255.0, green: 0xB3 / 255.0, blue: 0xC2 / 255.0)
    static let divider = Color.white.opacity(0.2)
}

/// A small round button of the map with an SF Symbol.
private struct MapButton: SwiftUI.View {
    let symbol: String
    let label: String
    let action: () -> Void

    var body: some SwiftUI.View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(PanelColors.text)
                .frame(width: 44, height: 44)
                .background(
                    Circle().fill(PanelColors.surface).shadow(color: .black.opacity(0.35), radius: 4, y: 2))
        }
        .accessibilityLabel(label)
    }
}

/// Draws the base map in Web Mercator: the lines, the place names (more of them at a larger zoom),
/// and the pin of the place.
enum BaseMapDrawing {
    static let background = Color(red: 0.055, green: 0.141, blue: 0.251)
    private static let styles: [BaseMap.Layer: (color: Color, width: CGFloat, dash: [CGFloat])] = [
        .rivers: (Color(red: 0.43, green: 0.67, blue: 0.9).opacity(0.5), 1, []),
        .lakes: (Color(red: 0.43, green: 0.67, blue: 0.9).opacity(0.8), 1, []),
        .coastline: (Color(red: 0.86, green: 0.9, blue: 0.94).opacity(0.9), 1.2, []),
        .borders: (Color(red: 1, green: 0.78, blue: 0.24).opacity(0.6), 1, [4, 3]),
    ]

    static func draw(_ context: inout GraphicsContext, map: BaseMap, viewport: MapViewport, size: CGSize, pin: Observer?) {
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(background))
        let s: Double = Mercator.worldSize(zoom: viewport.zoom)
        let width = Double(size.width)
        let height = Double(size.height)
        let half = SIMD2<Double>(width / 2 / s, height / 2 / s)
        // The copies of the world that the screen shows: the view can be near the date line.
        let copies = (-1...1).filter { k in
            let lo = viewport.center.x - half.x - Double(k)
            let hi = viewport.center.x + half.x - Double(k)
            return hi >= 0 && lo <= 1
        }
        let screen = { (p: SIMD2<Double>, k: Int) -> CGPoint in
            let x: Double = (p.x + Double(k) - viewport.center.x) * s + width / 2
            let y: Double = (p.y - viewport.center.y) * s + height / 2
            return CGPoint(x: x, y: y)
        }

        for layer in BaseMap.Layer.allCases {
            guard let style = styles[layer], let lines = map.layers[layer] else { continue }
            var path = Path()
            for k in copies {
                // The box of the screen in the world units of this copy.
                let lo = SIMD2<Float>(Float(viewport.center.x - half.x - Double(k)), Float(viewport.center.y - half.y))
                let hi = SIMD2<Float>(Float(viewport.center.x + half.x - Double(k)), Float(viewport.center.y + half.y))
                let ox: Double = (Double(k) - viewport.center.x) * s + width / 2
                let oy: Double = height / 2 - viewport.center.y * s
                for line in lines {
                    if line.max.x < lo.x || line.min.x > hi.x || line.max.y < lo.y || line.min.y > hi.y { continue }
                    var last = CGPoint(x: -1e9, y: -1e9)
                    for (i, p) in line.points.enumerated() {
                        let qx: Double = Double(p.x) * s + ox
                        let qy: Double = Double(p.y) * s + oy
                        let q = CGPoint(x: qx, y: qy)
                        if i == 0 {
                            path.move(to: q)
                        } else if abs(q.x - last.x) + abs(q.y - last.y) >= 1 || i == line.points.count - 1 {
                            // Skip the points less than a point away: at a small zoom they add no detail.
                            path.addLine(to: q)
                        } else {
                            continue
                        }
                        last = q
                    }
                }
            }
            context.stroke(path, with: .color(style.color), style: StrokeStyle(lineWidth: style.width, dash: style.dash))
        }

        // The place names: more of them at a larger zoom. A label near a label that is already
        // drawn is skipped, so the larger place wins. A dark halo (the color of the sea) keeps the
        // names readable over the lines.
        let maxRank = min(10, Int((viewport.zoom * 1.6).rounded()))
        var labels = context
        labels.addFilter(.shadow(color: background, radius: 1.5))
        labels.addFilter(.shadow(color: background, radius: 1.5))
        var used: [CGPoint] = []
        for place in map.places where place.rank <= maxRank {
            let w = Mercator.world(latitudeDeg: place.latitudeDeg, longitudeDeg: place.longitudeDeg)
            for k in copies {
                let p = screen(w, k)
                if p.x < 0 || p.y < 0 || Double(p.x) > width || Double(p.y) > height { continue }
                if used.contains(where: { abs($0.x - p.x) < 90 && abs($0.y - p.y) < 22 }) { continue }
                used.append(p)
                let dot = CGRect(x: p.x - 2, y: p.y - 2, width: 4, height: 4)
                context.fill(Path(ellipseIn: dot), with: .color(.white))
                let name = Text(place.name).font(.system(size: 12)).foregroundColor(.white.opacity(0.9))
                let at = CGPoint(x: p.x + 5, y: p.y - 2)
                labels.draw(name, at: at, anchor: .bottomLeading)
            }
            if used.count > 200 { break }
        }

        if let pin {
            let w = Mercator.world(latitudeDeg: pin.latitudeDeg, longitudeDeg: pin.longitudeDeg)
            // The copy of the pin that is nearest to the center.
            let k = (viewport.center.x - w.x).rounded()
            drawPin(&context, tip: screen(w, Int(k)))
        }
    }

    private static let pinColor = Color(red: 1, green: 0.784, blue: 0.239)
    private static let pinOutline = Color(red: 0.227, green: 0.165, blue: 0)

    /// A map pin with its tip at `tip`, and a small shadow on the ground. Android draws the same pin.
    private static func drawPin(_ context: inout GraphicsContext, tip: CGPoint) {
        let shadow = CGRect(x: tip.x - 5, y: tip.y - 2, width: 10, height: 4)
        context.fill(Path(ellipseIn: shadow), with: .color(.black.opacity(0.4)))
        let r: CGFloat = 11
        let headY: CGFloat = tip.y - 1.75 * r
        let head = CGPoint(x: tip.x, y: headY)
        let sideX: CGFloat = tip.x - 0.87 * r
        let sideY: CGFloat = headY + 0.5 * r
        var outline = Path()
        outline.move(to: tip)
        // The two sides touch the head; the arc closes the top.
        outline.addLine(to: CGPoint(x: sideX, y: sideY))
        outline.addArc(center: head, radius: r, startAngle: .degrees(150), endAngle: .degrees(30), clockwise: false)
        outline.closeSubpath()
        context.fill(outline, with: .color(pinColor))
        context.stroke(outline, with: .color(pinOutline), lineWidth: 1.5)
        let eye = CGRect(x: tip.x - 4, y: headY - 4, width: 8, height: 8)
        context.fill(Path(ellipseIn: eye), with: .color(pinOutline))
    }
}
