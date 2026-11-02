import Sunradar
import SwiftUI

/// The place panel: a search of the places of the base map, and the offline map (Natural Earth),
/// where a tap drops the place. "Use my location" ends a manual place. The map needs no network.
/// "Keep offline" downloads the terrain tiles of the place, and the list below shows the kept places.
struct PlacePanel: SwiftUI.View {
    @ObservedObject var location: LocationProvider
    @ObservedObject var kept: KeptPlaces
    @AppStorage(Settings.terrainHorizon) private var terrainOn = true
    @Environment(\.dismiss) private var dismiss
    @State private var map: BaseMap?
    @State private var failed = false
    @State private var query = ""
    @State private var viewport: MapViewport
    @State private var dragStart: MapViewport?
    @State private var zoomStart: Double?
    @FocusState private var searching: Bool

    init(location: LocationProvider, kept: KeptPlaces) {
        self.location = location
        self.kept = kept
        _viewport = State(initialValue: MapViewport(center: location.observer, zoom: 5))
    }

    var body: some SwiftUI.View {
        NavigationStack {
            VStack(spacing: 0) {
                TextField("Search a place", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .focused($searching)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
                ZStack(alignment: .top) {
                    mapView
                    let results = map?.search(query) ?? []
                    if !results.isEmpty {
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(Array(results.enumerated()), id: \.offset) { _, place in
                                Button {
                                    choose(place)
                                } label: {
                                    Text("\(place.name), \(place.country)")
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 9)
                                }
                                Divider()
                            }
                        }
                        .background(.regularMaterial)
                    }
                }
                VStack(spacing: 6) {
                    Text(location.text).bold()
                    Text("Time zone: \(location.timeZone.identifier)").font(.footnote).foregroundStyle(.secondary)
                    Text(
                        location.manual
                            ? "The place stays until you choose \"Use my location\"."
                            : "Tap the map to drop the place, or search a place."
                    )
                    .font(.footnote).foregroundStyle(.secondary)
                    HStack {
                        Button("Use my location") {
                            location.useDeviceLocation()
                            dismiss()
                        }
                        if kept.cache != nil && location.hasPlace {
                            Button("Keep offline") { kept.keep(name: location.text, observer: location.observer) }
                                .disabled(kept.downloading || !terrainOn)
                        }
                    }
                    .buttonStyle(.bordered)
                    if kept.cache != nil && location.hasPlace && !terrainOn {
                        Text("The terrain horizon is off in the settings.").font(.footnote).foregroundStyle(.secondary)
                    }
                    if let text = kept.stateText {
                        Text(text).font(.footnote)
                    }
                }
                .multilineTextAlignment(.center)
                .padding()
                if !kept.places.isEmpty { keptList }
            }
            .navigationTitle("Place")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .task { await kept.refresh() }
        .task {
            do {
                map = try await BaseMapStore.shared.load()
            } catch {
                failed = true
            }
        }
    }

    /// The kept places: the name, the tiles that the cache has, the size, and "Remove".
    private var keptList: some SwiftUI.View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Kept offline").font(.footnote.bold()).padding(.horizontal).padding(.bottom, 4)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(kept.places) { summary in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(summary.place.name).font(.footnote).lineLimit(1)
                                Text(keptText(summary)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Remove", role: .destructive) { Task { await kept.remove(summary.id) } }
                                .buttonStyle(.borderless)
                                .font(.footnote)
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 6)
                        Divider()
                    }
                }
            }
            .frame(maxHeight: 150)
        }
        .padding(.bottom, 8)
    }

    /// "9 tiles, 44 MB", or "7/9 tiles, 30 MB" if tiles are missing.
    private func keptText(_ summary: DemCache.KeptSummary) -> String {
        let total = summary.place.tiles.count
        let tiles = summary.tiles == total ? KeptPlaces.tilesText(total) : "\(summary.tiles)/\(total) tiles"
        return "\(tiles), \(KeptPlaces.sizeText(summary.bytes))"
    }

    private var mapView: some SwiftUI.View {
        ZStack {
            if let map {
                GeometryReader { geometry in
                    Canvas { context, size in
                        BaseMapDrawing.draw(&context, map: map, viewport: viewport, size: size, pin: location.observer)
                    }
                    .gesture(dragGesture(size: geometry.size).simultaneously(with: zoomGesture))
                    .accessibilityLabel("The map: tap to drop the place")
                }
            } else {
                Color(red: 0.055, green: 0.141, blue: 0.251)
                Text(failed ? "The map is not available." : "Loading the map…").foregroundColor(.white)
            }
            VStack(spacing: 8) {
                Spacer()
                HStack(alignment: .bottom) {
                    Text(map?.attribution ?? "Map: Natural Earth (public domain)")
                        .font(.caption2)
                        .foregroundColor(.white.opacity(0.8))
                        .padding(4)
                        .background(Color.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 4))
                    Spacer()
                    VStack(spacing: 8) {
                        Button("+") { viewport.setZoom(viewport.zoom.rounded(.down) + 1) }
                            .accessibilityLabel("Zoom in")
                        Button("−") { viewport.setZoom(viewport.zoom.rounded(.up) - 1) }
                            .accessibilityLabel("Zoom out")
                    }
                    .font(.title2.bold())
                    .buttonStyle(.bordered)
                }
            }
            .padding(8)
        }
        .clipped()
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

    private func choose(_ place: BaseMap.Place) {
        query = ""
        searching = false
        viewport = MapViewport(center: place.observer, zoom: max(viewport.zoom, 9))
        drop(place.observer, name: place.name)
    }

    /// Drops the place, with the name of the nearest place and its time zone.
    private func drop(_ observer: Observer, name: String?) {
        guard let map else { return }
        let coordinates = String(format: "%.3f°, %.3f°", observer.latitudeDeg, observer.longitudeDeg)
        let label = name ?? map.nearestPlace(to: observer).map { "near \($0.name)" } ?? coordinates
        location.drop(observer, name: "\(label) · \(coordinates)", timeZone: map.timeZone(at: observer))
    }
}

/// Draws the base map in Web Mercator: the lines, the place names (more of them at a larger zoom),
/// and the pin of the place.
enum BaseMapDrawing {
    private static let background = Color(red: 0.055, green: 0.141, blue: 0.251)
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
        // drawn is skipped, so the larger place wins.
        let maxRank = min(10, Int((viewport.zoom * 1.6).rounded()))
        var used: [CGPoint] = []
        for place in map.places where place.rank <= maxRank {
            let w = Mercator.world(latitudeDeg: place.latitudeDeg, longitudeDeg: place.longitudeDeg)
            for k in copies {
                let p = screen(w, k)
                if p.x < 0 || p.y < 0 || Double(p.x) > width || Double(p.y) > height { continue }
                if used.contains(where: { abs($0.x - p.x) < 70 && abs($0.y - p.y) < 16 }) { continue }
                used.append(p)
                context.fill(Path(CGRect(x: p.x - 1.5, y: p.y - 1.5, width: 3, height: 3)), with: .color(.white.opacity(0.85)))
                context.draw(
                    Text(place.name).font(.system(size: 12)).foregroundColor(.white.opacity(0.85)),
                    at: CGPoint(x: p.x + 4, y: p.y - 2), anchor: .bottomLeading)
            }
        }

        if let pin {
            let w = Mercator.world(latitudeDeg: pin.latitudeDeg, longitudeDeg: pin.longitudeDeg)
            // The copy of the pin that is nearest to the center.
            let k = (viewport.center.x - w.x).rounded()
            let p = screen(w, Int(k))
            let circle = Path(ellipseIn: CGRect(x: p.x - 7, y: p.y - 7, width: 14, height: 14))
            context.fill(circle, with: .color(Color(red: 1, green: 0.78, blue: 0.24)))
            context.stroke(circle, with: .color(.black), lineWidth: 1.5)
        }
    }
}
