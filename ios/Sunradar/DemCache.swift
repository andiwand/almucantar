import Foundation
import Sunradar

/// The answer of an HTTP GET request.
struct HTTPResponse: Sendable {
    var status: Int
    var body: Data
    var etag: String?
    var lastModified: String?
}

/// The HTTP layer of the DEM cache. The unit tests replace it with a fake.
protocol HTTPClient: Sendable {
    /// Sends a GET request. Throws if the network fails.
    func get(_ url: URL, headers: [String: String]) async throws -> HTTPResponse
}

/// The HTTP layer with URLSession. It has no URL cache, so a 304 comes back to the DEM cache.
struct URLSessionHTTPClient: HTTPClient {
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 20
        return URLSession(configuration: configuration)
    }()

    func get(_ url: URL, headers: [String: String]) async throws -> HTTPResponse {
        var request = URLRequest(url: url)
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return HTTPResponse(
            status: http.statusCode, body: data, etag: http.value(forHTTPHeaderField: "ETag"),
            lastModified: http.value(forHTTPHeaderField: "Last-Modified"))
    }
}

/// The result of a tile from the DEM cache.
enum DemTile {
    /// The heights of the tile, at the level of the request.
    case grid(ElevationGrid)
    /// The tile does not exist (HTTP 404): it is the sea.
    case sea
    /// The tile is not in the cache and the download failed, or the file is not valid.
    case unavailable
}

/// The persistent cache of the Copernicus GLO-90 tiles: one GeoTIFF file per tile and a JSON
/// index. A tile that is older than `maxAge` gets a conditional request. If the network fails,
/// the old tile stays, so the app works offline. If the files are larger than `maxBytes`, the
/// tiles that were used least recently are deleted, but not the tiles in `keep`.
///
/// The user can keep the tiles of a place offline (`KeptPlace`, PLAN.md 13.3). The eviction never
/// deletes a tile of a kept place, and these tiles do not count toward `maxBytes`.
actor DemCache {
    /// The data of one tile in the index.
    struct Entry: Codable, Equatable {
        var etag: String?
        var lastModified: String?
        /// The time of the download or of the last check with the server.
        var checked: Date
        /// The time of the last use.
        var used: Date
        /// The size of the file in bytes, 0 for the sea.
        var size: Int64
        /// The server has no tile (HTTP 404).
        var sea: Bool
    }

    /// A place that the user keeps offline: the eviction does not delete its tiles.
    struct KeptPlace: Codable, Equatable, Identifiable, Sendable {
        var id: String
        /// The name of the place, as the place panel shows it.
        var name: String
        var latitudeDeg: Double
        var longitudeDeg: Double
        /// The names of the tiles (`DemCache.name`), also of the sea tiles.
        var tiles: [String]
    }

    /// A kept place with the tiles that the cache has now.
    struct KeptSummary: Equatable, Identifiable, Sendable {
        var place: KeptPlace
        /// The tiles that the cache has: files on the disk and the sea.
        var tiles: Int
        /// The size of the files on the disk.
        var bytes: Int64
        var id: String { place.id }
    }

    /// The index file. An old index is only the dictionary of the tiles, without kept places.
    private struct IndexFile: Codable {
        var tiles: [String: Entry]
        var kept: [KeptPlace]
    }

    static let defaultMaxBytes: Int64 = 400 * 1024 * 1024
    static let defaultMaxAge: TimeInterval = 180 * 86400

    let directory: URL
    private let http: HTTPClient
    private let now: @Sendable () -> Date
    private let maxBytes: Int64
    private let maxAge: TimeInterval
    private(set) var index: [String: Entry]
    /// The places that the user keeps offline, in the order of the "Keep offline" action.
    private(set) var kept: [KeptPlace]

    init(
        directory: URL, http: HTTPClient, now: @escaping @Sendable () -> Date = { Date() },
        maxBytes: Int64 = DemCache.defaultMaxBytes, maxAge: TimeInterval = DemCache.defaultMaxAge
    ) throws {
        self.directory = directory
        self.http = http
        self.now = now
        self.maxBytes = maxBytes
        self.maxAge = maxAge
        let manager = FileManager.default
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        // The tiles come from the network again, so they are not in the backup. The flag of the
        // directory applies to all its files.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try url.setResourceValues(values)
        let indexURL = directory.appendingPathComponent("index.json")
        let data = try? Data(contentsOf: indexURL)
        if let data, let file = try? JSONDecoder().decode(IndexFile.self, from: data) {
            index = file.tiles
            kept = file.kept
        } else {
            // The index of an older version has no kept places.
            index = data.flatMap { try? JSONDecoder().decode([String: Entry].self, from: $0) } ?? [:]
            kept = []
        }
    }

    /// The cache of the app. `TerrainHorizon` and `KeptPlaces` must use the same actor, because
    /// both write the index.
    static let shared: DemCache? = try? standard()

    /// The cache in Application Support/dem.
    static func standard() throws -> DemCache {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return try DemCache(directory: support.appendingPathComponent("dem", isDirectory: true), http: URLSessionHTTPClient())
    }

    /// The Copernicus name of a tile, for example N47_00_E011_00.
    static func name(_ tile: ElevationTile) -> String {
        String(
            format: "%@%02d_00_%@%03d_00", tile.latitudeDeg < 0 ? "S" : "N", abs(tile.latitudeDeg),
            tile.longitudeDeg < 0 ? "W" : "E", abs(tile.longitudeDeg))
    }

    /// The URL of a tile of Copernicus GLO-90 on AWS Open Data.
    static func url(_ tile: ElevationTile) -> URL {
        let file = "Copernicus_DSM_COG_30_\(name(tile))_DEM"
        return URL(string: "https://copernicus-dem-90m.s3.amazonaws.com/\(file)/\(file).tif")!
    }

    /// True if the cache has the tile (a file or the sea), so it does not need the network.
    func contains(_ tile: ElevationTile) -> Bool {
        guard let entry = index[Self.name(tile)] else { return false }
        return entry.sea || FileManager.default.fileExists(atPath: file(Self.name(tile)).path)
    }

    /// The total size of the files in the index.
    var totalBytes: Int64 { index.values.reduce(0) { $0 + $1.size } }

    /// The names of the tiles of all kept places.
    var keptNames: Set<String> { Set(kept.flatMap(\.tiles)) }

    /// The size of the files that are not kept. The limit `maxBytes` applies to this size.
    var cacheBytes: Int64 {
        let keptNames = keptNames
        return index.filter { !keptNames.contains($0.key) }.values.reduce(0) { $0 + $1.size }
    }

    // MARK: - The kept places

    /// A kept place nearer than this to a new kept place is the same place: the new one replaces it.
    static let samePlaceM = 500.0

    /// Adds a kept place, or replaces the kept place at the same place. Call it before the
    /// download of the tiles, so the eviction does not delete the first tiles during the download.
    func keep(name: String, observer: Observer, tiles: [ElevationTile]) -> KeptPlace {
        let names = tiles.map(Self.name)
        let same = kept.first {
            let other = Observer(latitudeDeg: $0.latitudeDeg, longitudeDeg: $0.longitudeDeg)
            return Geo.distanceM(from: other, to: observer) < Self.samePlaceM
        }
        let id = same?.id ?? UUID().uuidString
        let place = KeptPlace(
            id: id, name: name, latitudeDeg: observer.latitudeDeg, longitudeDeg: observer.longitudeDeg, tiles: names)
        if let i = kept.firstIndex(where: { $0.id == id }) {
            kept[i] = place
        } else {
            kept.append(place)
        }
        saveIndex()
        return place
    }

    /// Removes a kept place. Its tiles stay on the disk as normal cache tiles, so the eviction can
    /// delete them later.
    func release(_ id: String) {
        kept.removeAll { $0.id == id }
        saveIndex()
    }

    /// The kept places with the number and the size of the tiles that the cache has now.
    func keptSummaries() -> [KeptSummary] {
        kept.map { place in
            var tiles = 0
            var bytes: Int64 = 0
            for name in place.tiles {
                guard let entry = index[name] else { continue }
                if entry.sea || FileManager.default.fileExists(atPath: file(name).path) {
                    tiles += 1
                    bytes += entry.size
                }
            }
            return KeptSummary(place: place, tiles: tiles, bytes: bytes)
        }
    }

    /// Returns a tile, read at `level`. The tile comes from the cache, or from the network if the
    /// cache does not have it or if it is older than `maxAge`. The names in `keep` are the tiles
    /// of the current place; the eviction does not delete them.
    func tile(_ tile: ElevationTile, level: Int, keep: Set<String>) async -> DemTile {
        let name = Self.name(tile)
        let url = file(name)
        // A file that the core cannot read is deleted and downloaded again, one time.
        var retried = false
        while true {
            if var entry = index[name], entry.sea || FileManager.default.fileExists(atPath: url.path) {
                if now().timeIntervalSince(entry.checked) > maxAge {
                    await revalidate(tile, entry: entry, keep: keep)
                    guard let updated = index[name] else { return .unavailable }
                    entry = updated
                }
                entry.used = now()
                index[name] = entry
                saveIndex()
                if entry.sea { return .sea }
                if let grid = read(url, level: level) { return .grid(grid) }
                remove(name)
                if retried { return .unavailable }
                retried = true
            }
            if await download(tile, keep: keep) == .failed { return .unavailable }
        }
    }

    // MARK: - The network

    private enum DownloadResult { case file, sea, failed }

    /// Downloads a tile without a condition and stores it.
    private func download(_ tile: ElevationTile, keep: Set<String>) async -> DownloadResult {
        guard let response = try? await http.get(Self.url(tile), headers: [:]) else { return .failed }
        return store(tile, response: response, keep: keep)
    }

    /// Asks the server if a tile changed. A network error or an unexpected answer keeps the old tile.
    private func revalidate(_ tile: ElevationTile, entry: Entry, keep: Set<String>) async {
        let name = Self.name(tile)
        var headers: [String: String] = [:]
        if !entry.sea {
            if let etag = entry.etag { headers["If-None-Match"] = etag }
            if let lastModified = entry.lastModified { headers["If-Modified-Since"] = lastModified }
        }
        guard let response = try? await http.get(Self.url(tile), headers: headers) else { return }
        if response.status == 304 || (entry.sea && response.status == 404) {
            index[name]?.checked = now()
            saveIndex()
        } else {
            _ = store(tile, response: response, keep: keep)
        }
    }

    /// Stores the answer of a full request: a file for 200, the sea for 404.
    private func store(_ tile: ElevationTile, response: HTTPResponse, keep: Set<String>) -> DownloadResult {
        let name = Self.name(tile)
        let t = now()
        switch response.status {
        case 200:
            do {
                try response.body.write(to: file(name), options: .atomic)
            } catch {
                return .failed
            }
            index[name] = Entry(
                etag: response.etag, lastModified: response.lastModified, checked: t, used: t,
                size: Int64(response.body.count), sea: false)
            evict(keep: keep.union([name]))
            saveIndex()
            return .file
        case 404:
            try? FileManager.default.removeItem(at: file(name))
            index[name] = Entry(etag: nil, lastModified: nil, checked: t, used: t, size: 0, sea: true)
            saveIndex()
            return .sea
        default:
            return .failed
        }
    }

    // MARK: - The files

    private func file(_ name: String) -> URL { directory.appendingPathComponent("\(name).tif") }

    private func read(_ url: URL, level: Int) -> ElevationGrid? {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        return try? readElevationGeotiff(data, level: level)
    }

    private func remove(_ name: String) {
        try? FileManager.default.removeItem(at: file(name))
        index[name] = nil
        saveIndex()
    }

    /// Deletes the tiles that were used least recently until the files that are not kept fit into
    /// `maxBytes`. The tiles of the kept places stay and do not count.
    private func evict(keep: Set<String>) {
        var total = cacheBytes
        let protected = keep.union(keptNames)
        let candidates = index.filter { !$0.value.sea && !protected.contains($0.key) }.sorted { $0.value.used < $1.value.used }
        for (name, entry) in candidates where total > maxBytes {
            try? FileManager.default.removeItem(at: file(name))
            index[name] = nil
            total -= entry.size
        }
    }

    private func saveIndex() {
        guard let data = try? JSONEncoder().encode(IndexFile(tiles: index, kept: kept)) else { return }
        try? data.write(to: directory.appendingPathComponent("index.json"), options: .atomic)
    }
}
