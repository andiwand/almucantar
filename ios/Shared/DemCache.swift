import Foundation
import Almucantar

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

/// The persistent cache of the elevation tiles of an `ElevationSource` (Copernicus GLO-90 in the
/// app): GeoTIFF files and a JSON index. A tile has two kinds of entries (PLAN.md 14.3):
///
/// - The full file (`N47_00_E011_00.tif`), for the near tiles at full resolution.
/// - The prefix file (`N47_00_E011_00.l2.tif`): only the first part of the file, with the
///   directories and the overview at `coarseLevel`. An HTTP Range request downloads it. For
///   Copernicus, it is about 300 KB of the 2–5 MB of a tile.
///
/// A full file also serves `coarseLevel`, so the cache downloads no prefix if it has the full
/// file, and it deletes the prefix file when it stores the full file.
///
/// An entry that is older than `maxAge` gets a conditional request. If the network fails, the old
/// entry stays, so the app works offline. If the files are larger than `maxBytes`, the entries
/// that were used least recently are deleted, but not the entries of the tiles in `keep`.
///
/// The user can keep the tiles of a place offline (`KeptPlace`, PLAN.md 13.3). The eviction never
/// deletes an entry of a kept tile, and these entries do not count toward `maxBytes`.
actor DemCache {
    /// The data of one entry in the index: a full file or a prefix file.
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
        /// The tiles that the cache has: a full file, a prefix file, or the sea.
        var tiles: Int
        /// The size of the files on the disk (the full files and the prefix files).
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
    /// The source of the app. Change it here to use other tiles.
    static let defaultSource: any ElevationSource = CopernicusSource()
    /// The first Range request asks for this many bytes. For a Copernicus tile, it has all
    /// directories, so `elevationGeotiffPrefixSize` can give the size of the prefix.
    static let defaultHeaderBytes = 16384
    /// A header that is still too short gets 4 times more bytes, up to this size.
    private static let maxHeaderBytes = 4 * 1024 * 1024

    let directory: URL
    /// The source of the tiles: their URLs and their levels.
    nonisolated let source: any ElevationSource
    private let http: HTTPClient
    private let now: @Sendable () -> Date
    private let maxBytes: Int64
    private let maxAge: TimeInterval
    /// The level of the prefix files: `source.coarseLevel`, if the init gives no other level. The
    /// tests use level 1, because their small files have no level 2.
    nonisolated let coarseLevel: Int
    private let headerBytes: Int
    private(set) var index: [String: Entry]
    /// The places that the user keeps offline, in the order of the "Keep offline" action.
    private(set) var kept: [KeptPlace]
    /// The running downloads, by the name of the entry. The terrain and "Keep offline" can ask for
    /// the same entry at the same time: the second request waits for the first download.
    private var pending: [String: Task<DownloadResult, Never>] = [:]

    init(
        directory: URL, http: HTTPClient, source: any ElevationSource = DemCache.defaultSource,
        now: @escaping @Sendable () -> Date = { Date() },
        maxBytes: Int64 = DemCache.defaultMaxBytes, maxAge: TimeInterval = DemCache.defaultMaxAge,
        coarseLevel: Int? = nil, headerBytes: Int = DemCache.defaultHeaderBytes
    ) throws {
        self.directory = directory
        self.source = source
        self.http = http
        self.now = now
        self.maxBytes = maxBytes
        self.maxAge = maxAge
        self.coarseLevel = coarseLevel ?? source.coarseLevel
        self.headerBytes = headerBytes
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

    /// The name of a tile in the index, for example N47_00_E011_00 (the format of Copernicus).
    static func name(_ tile: ElevationTile) -> String {
        String(
            format: "%@%02d_00_%@%03d_00", tile.latitudeDeg < 0 ? "S" : "N", abs(tile.latitudeDeg),
            tile.longitudeDeg < 0 ? "W" : "E", abs(tile.longitudeDeg))
    }

    /// The name of the prefix file of a tile in the index, for example N47_00_E011_00.l2.
    nonisolated func prefixName(_ tile: ElevationTile) -> String { prefixName(Self.name(tile)) }

    nonisolated func prefixName(_ name: String) -> String { "\(name).l\(coarseLevel)" }

    /// True if the cache has the full file of the tile or the sea, so it does not need the network.
    func contains(_ tile: ElevationTile) -> Bool { has(Self.name(tile)) }

    /// True if the cache has the tile at `coarseLevel`: the full file, the prefix file, or the sea.
    func containsCoarse(_ tile: ElevationTile) -> Bool { has(Self.name(tile)) || has(prefixName(tile)) }

    /// True if the index has the entry and its file exists (or it is the sea).
    private func has(_ name: String) -> Bool {
        guard let entry = index[name] else { return false }
        return entry.sea || FileManager.default.fileExists(atPath: file(name).path)
    }

    /// The total size of the files in the index.
    var totalBytes: Int64 { index.values.reduce(0) { $0 + $1.size } }

    /// The names of the entries of the given tiles: the full files and the prefix files.
    nonisolated func entryNames(_ tiles: some Sequence<String>) -> Set<String> {
        Set(tiles.flatMap { [$0, prefixName($0)] })
    }

    /// The names of the entries of the tiles of all kept places. A kept tile keeps its full file
    /// and its prefix file, because the terrain uses the one that the cache has.
    var keptNames: Set<String> { entryNames(kept.flatMap(\.tiles)) }

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
                let present = [name, prefixName(name)].filter(has)
                if !present.isEmpty { tiles += 1 }
                bytes += present.reduce(0) { $0 + (index[$1]?.size ?? 0) }
            }
            return KeptSummary(place: place, tiles: tiles, bytes: bytes)
        }
    }

    /// Returns a tile from its full file, read at `level`. The tile comes from the cache, or from
    /// the network if the cache does not have it or if it is older than `maxAge`. The names in
    /// `keep` are the tiles of the current place; the eviction does not delete their entries.
    func tile(_ tile: ElevationTile, level: Int, keep: Set<String>) async -> DemTile {
        await load(tile, kind: .full, level: level, keep: keep)
    }

    /// Returns a tile at `coarseLevel`. If the cache has the full file of the tile (or the sea), it
    /// reads the full file. Otherwise it uses the prefix file, and downloads it if necessary.
    func coarseTile(_ tile: ElevationTile, keep: Set<String>) async -> DemTile {
        if has(Self.name(tile)) { return await load(tile, kind: .full, level: coarseLevel, keep: keep) }
        return await load(tile, kind: .prefix, level: coarseLevel, keep: keep)
    }

    /// The two kinds of entries of a tile.
    private enum Kind { case full, prefix }

    private func entryName(_ tile: ElevationTile, _ kind: Kind) -> String {
        kind == .full ? Self.name(tile) : prefixName(tile)
    }

    private func load(_ tile: ElevationTile, kind: Kind, level: Int, keep: Set<String>) async -> DemTile {
        let name = entryName(tile, kind)
        let url = file(name)
        let keep = entryNames(keep)
        // A file that the core cannot read is deleted and downloaded again, one time.
        var retried = false
        while true {
            if var entry = index[name], entry.sea || FileManager.default.fileExists(atPath: url.path) {
                if now().timeIntervalSince(entry.checked) > maxAge {
                    await revalidate(tile, kind: kind, entry: entry, keep: keep)
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
            // A 304 without conditions stores no entry, so only a file or the sea can continue.
            switch await download(tile, kind: kind, conditions: [:], keep: keep) {
            case .file, .sea: continue
            case .notModified, .failed: return .unavailable
            }
        }
    }

    // MARK: - The network

    private enum DownloadResult { case file, sea, notModified, failed }

    /// Downloads an entry and stores it. With `conditions` (If-None-Match, If-Modified-Since),
    /// the server can answer 304. If a download of the entry runs, it waits for that download and
    /// gives its result.
    private func download(
        _ tile: ElevationTile, kind: Kind, conditions: [String: String], keep: Set<String>
    ) async -> DownloadResult {
        let name = entryName(tile, kind)
        if let running = pending[name] { return await running.value }
        let task = Task { await transfer(tile, kind: kind, conditions: conditions, keep: keep) }
        pending[name] = task
        let result = await task.value
        pending[name] = nil
        return result
    }

    private func transfer(
        _ tile: ElevationTile, kind: Kind, conditions: [String: String], keep: Set<String>
    ) async -> DownloadResult {
        switch kind {
        case .full:
            guard let response = try? await http.get(source.url(tile), headers: conditions) else { return .failed }
            if response.status == 304 { return .notModified }
            return store(tile, kind: .full, response: response, keep: keep)
        case .prefix:
            let response = await downloadPrefix(source.url(tile), conditions: conditions)
            if response.status == 304 { return .notModified }
            return store(tile, kind: .prefix, response: response, keep: keep)
        }
    }

    /// Downloads the first part of a file that `coarseLevel` needs, with HTTP Range requests:
    ///
    /// 1. Ask for the first `headerBytes`, with the conditions.
    /// 2. Ask `elevationGeotiffPrefixSize` for the size of the prefix. If it gives 0, the header is
    ///    too short: ask for 4 times more bytes and try again.
    /// 3. Ask for the rest of the prefix and join the parts.
    ///
    /// The answer has the status 200 and the prefix as its body, or the status of the server
    /// (304, 404, an error), or the status 0 if the network or the file fails. A server that sends
    /// the whole file (200) for a Range request is also correct: the cache cuts the prefix from it.
    private func downloadPrefix(_ url: URL, conditions: [String: String]) async -> HTTPResponse {
        let failed = HTTPResponse(status: 0, body: Data())
        var headers = conditions
        headers["Range"] = "bytes=0-\(headerBytes - 1)"
        guard let first = try? await http.get(url, headers: headers) else { return failed }
        guard first.status == 200 || first.status == 206 else { return first }
        var data = first.body
        // The file has no more bytes than `data` if the server sent all of it.
        var complete = first.status == 200 || data.count < headerBytes
        while true {
            guard let size = try? elevationGeotiffPrefixSize(data, level: coarseLevel) else { return failed }
            if size > 0 && size <= data.count {
                return HTTPResponse(
                    status: 200, body: Data(data.prefix(size)), etag: first.etag, lastModified: first.lastModified)
            }
            if complete || data.count >= Self.maxHeaderBytes { return failed }
            // Size 0: the header is too short. Else: the level needs `size` bytes.
            let end = size > 0 ? size : data.count * 4
            guard let rest = try? await http.get(url, headers: ["Range": "bytes=\(data.count)-\(end - 1)"]) else {
                return failed
            }
            switch rest.status {
            case 206:
                // A different ETag is a new file on the server: the parts do not match.
                if let a = first.etag, let b = rest.etag, a != b { return failed }
                let asked = end - data.count
                data.append(rest.body)
                complete = rest.body.count < asked
            case 200:
                data = rest.body
                complete = true
            default:
                return failed
            }
        }
    }

    /// Asks the server if an entry changed. A network error or an unexpected answer keeps the old
    /// entry. For a prefix file, the request is a conditional Range request.
    private func revalidate(_ tile: ElevationTile, kind: Kind, entry: Entry, keep: Set<String>) async {
        let name = entryName(tile, kind)
        var conditions: [String: String] = [:]
        if !entry.sea {
            if let etag = entry.etag { conditions["If-None-Match"] = etag }
            if let lastModified = entry.lastModified { conditions["If-Modified-Since"] = lastModified }
        }
        // 200, 206, and 404 replace the entry in `download`. 304 keeps it.
        if await download(tile, kind: kind, conditions: conditions, keep: keep) == .notModified {
            index[name]?.checked = now()
            saveIndex()
        }
    }

    /// Stores the answer of a request for an entry: a file for 200 (for a prefix file,
    /// `downloadPrefix` gives 200 with the prefix), the sea for 404. A full file replaces the prefix
    /// file of the tile.
    private func store(_ tile: ElevationTile, kind: Kind, response: HTTPResponse, keep: Set<String>) -> DownloadResult {
        let name = entryName(tile, kind)
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
            if kind == .full {
                // The full file also serves the coarse level.
                try? FileManager.default.removeItem(at: file(prefixName(tile)))
                index[prefixName(tile)] = nil
            }
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
