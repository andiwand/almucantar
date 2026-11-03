import CoreGraphics
import Foundation
import Almucantar

/// The offline base map (assets/basemap/basemap.json, Natural Earth): the decode, the search of
/// the places, the time zone of a dropped place, and the Web Mercator projection. The format is in
/// the docstring of scripts/basemap/build_basemap.py. The PWA has the same logic in
/// web/shared/src/basemap.ts.
struct BaseMap: Sendable {
    /// The line layers, in the order of the drawing.
    enum Layer: String, CaseIterable, Sendable {
        case rivers, lakes, coastline, borders
    }

    /// A line in Web Mercator units: x and y go from 0 to 1, x to the east and y to the south.
    struct Line: Sendable {
        let points: [SIMD2<Float>]
        /// The box of the points, for the culling.
        let min: SIMD2<Float>
        let max: SIMD2<Float>
    }

    struct Place: Sendable, Equatable {
        let name: String
        let latitudeDeg: Double
        let longitudeDeg: Double
        /// 0 is the largest.
        let rank: Int
        let country: String
        /// An IANA time zone, or "".
        let timeZone: String
        /// The name in lower case without accents, for the search.
        let folded: String

        var observer: Observer { Observer(latitudeDeg: latitudeDeg, longitudeDeg: longitudeDeg) }
    }

    enum DecodeError: Error {
        case format
        case version(Int)
    }

    let layers: [Layer: [Line]]
    /// The places, the largest first (the file has this order).
    let places: [Place]
    let attribution: String

    init(data: Data) throws {
        guard let raw = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let version = raw["version"] as? Int, let scale = raw["scale"] as? Double,
            let rawLayers = raw["layers"] as? [String: Any], let rawPlaces = raw["places"] as? [[Any]]
        else { throw DecodeError.format }
        if version != 1 { throw DecodeError.version(version) }
        var layers: [Layer: [Line]] = [:]
        for layer in Layer.allCases {
            let lines = rawLayers[layer.rawValue] as? [[Int]] ?? []
            layers[layer] = lines.map { Self.decodeLine($0, scale: scale) }
        }
        self.layers = layers
        places = rawPlaces.compactMap { p -> Place? in
            guard p.count >= 6, let name = p[0] as? String, let lon = p[1] as? Double, let lat = p[2] as? Double,
                let rank = p[3] as? Int, let country = p[4] as? String, let zone = p[5] as? String
            else { return nil }
            return Place(
                name: name, latitudeDeg: lat / scale, longitudeDeg: lon / scale, rank: rank, country: country,
                timeZone: zone, folded: Self.fold(name))
        }
        attribution = raw["attribution"] as? String ?? "Map: Natural Earth (public domain)"
    }

    /// The base map in a bundle: the app bundle, or the bundle of the tests.
    static func bundled(in bundle: Bundle = .main) throws -> BaseMap {
        guard let url = bundle.url(forResource: "basemap", withExtension: "json") else {
            throw CocoaError(.fileNoSuchFile)
        }
        return try BaseMap(data: Data(contentsOf: url))
    }

    /// A flat list [lon0, lat0, dlon1, dlat1, ...]: the first point is absolute, and each next point
    /// is the difference to the point before.
    private static func decodeLine(_ flat: [Int], scale: Double) -> Line {
        var points: [SIMD2<Float>] = []
        points.reserveCapacity(flat.count / 2)
        var (lon, lat) = (0, 0)
        var low = SIMD2<Float>(repeating: .infinity)
        var high = SIMD2<Float>(repeating: -.infinity)
        var i = 0
        while i + 1 < flat.count {
            lon += flat[i]
            lat += flat[i + 1]
            let p = Mercator.world(latitudeDeg: Double(lat) / scale, longitudeDeg: Double(lon) / scale)
            let q = SIMD2<Float>(Float(p.x), Float(p.y))
            points.append(q)
            low = pointwiseMin(low, q)
            high = pointwiseMax(high, q)
            i += 2
        }
        return Line(points: points, min: low, max: high)
    }

    // MARK: - The search

    /// Lower case without accents, so "wien" finds "Wien" and "zurich" finds "Zürich".
    static func fold(_ text: String) -> String {
        text.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
    }

    /// The places whose name starts with the text, then the places whose name contains it. Each
    /// group has the largest places first.
    func search(_ text: String, limit: Int = 8) -> [Place] {
        let query = Self.fold(text.trimmingCharacters(in: .whitespacesAndNewlines))
        if query.isEmpty { return [] }
        var starts: [Place] = []
        var contains: [Place] = []
        for place in places {
            if place.folded.hasPrefix(query) {
                starts.append(place)
                if starts.count >= limit { break }
            } else if contains.count < limit && place.folded.contains(query) {
                contains.append(place)
            }
        }
        return Array((starts + contains).prefix(limit))
    }

    /// The nearest place to `observer`. If `withTimeZone` is true, only a place with a time zone.
    func nearestPlace(to observer: Observer, withTimeZone: Bool = false) -> Place? {
        var best: Place?
        var bestM = Double.infinity
        for place in places where !withTimeZone || !place.timeZone.isEmpty {
            let d = Geo.distanceM(from: observer, to: place.observer)
            if d < bestM {
                best = place
                bestM = d
            }
        }
        return best
    }

    /// The time zone of a dropped place: the zone of the nearest place that has one. If Foundation
    /// does not know that zone, the result is `fallback`.
    func timeZone(at observer: Observer, fallback: TimeZone = .current) -> TimeZone {
        nearestPlace(to: observer, withTimeZone: true).flatMap { TimeZone(identifier: $0.timeZone) } ?? fallback
    }
}

/// Web Mercator in world units: x and y go from 0 to 1 for the whole world, x to the east and y to
/// the south. At zoom z, the world is 256 * 2^z points wide.
enum Mercator {
    static let maxLatitudeDeg = 85.0511287798

    static func world(latitudeDeg: Double, longitudeDeg: Double) -> SIMD2<Double> {
        let phi = min(maxLatitudeDeg, max(-maxLatitudeDeg, latitudeDeg)) * .pi / 180
        return SIMD2((longitudeDeg + 180) / 360, (1 - log(tan(phi) + 1 / cos(phi)) / .pi) / 2)
    }

    /// The place at a world point. The longitude is in -180 to 180, also for an x outside 0 to 1.
    static func observer(world p: SIMD2<Double>) -> Observer {
        let lon = p.x * 360 - 180
        let lat = atan(sinh(.pi * (1 - 2 * p.y))) * 180 / .pi
        let wrapped = ((lon + 180).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) - 180
        return Observer(latitudeDeg: lat, longitudeDeg: wrapped)
    }

    /// The width of the world in points at a zoom.
    static func worldSize(zoom: Double) -> Double { 256 * pow(2, zoom) }
}

/// A view of the base map: the center in world units and the zoom.
struct MapViewport: Equatable {
    var center: SIMD2<Double>
    var zoom: Double

    static let zoomRange = 1.0...12.0

    init(center: Observer, zoom: Double) {
        self.center = Mercator.world(latitudeDeg: center.latitudeDeg, longitudeDeg: center.longitudeDeg)
        self.zoom = zoom
    }

    /// The place at a point of a view of the given size.
    func observer(at point: CGPoint, size: CGSize) -> Observer {
        let s: Double = Mercator.worldSize(zoom: zoom)
        let dx = (Double(point.x) - Double(size.width) / 2) / s
        let dy = (Double(point.y) - Double(size.height) / 2) / s
        return Mercator.observer(world: SIMD2(center.x + dx, center.y + dy))
    }

    /// Moves the view by a translation in points, so the map follows the finger.
    mutating func pan(by translation: CGSize) {
        let s: Double = Mercator.worldSize(zoom: zoom)
        center.x -= Double(translation.width) / s
        let y: Double = center.y - Double(translation.height) / s
        center.y = min(1, max(0, y))
        // Keep x near the world, so the drawing needs at most one copy on each side.
        center.x -= center.x.rounded(.down)
    }

    mutating func setZoom(_ value: Double) {
        zoom = min(Self.zoomRange.upperBound, max(Self.zoomRange.lowerBound, value))
    }
}

/// Decodes the base map of the bundle once, off the main thread, and keeps it.
actor BaseMapStore {
    static let shared = BaseMapStore()
    private var map: BaseMap?

    func load() throws -> BaseMap {
        if let map { return map }
        let map = try BaseMap.bundled()
        self.map = map
        return map
    }
}
