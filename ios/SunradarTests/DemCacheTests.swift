import Foundation
import Almucantar
import XCTest

@testable import SunradarApp

/// A fake HTTP layer: it gives the next answer of a queue, or a network error if the queue is
/// empty, and it records the requests.
private final class FakeHTTP: HTTPClient, @unchecked Sendable {
    private let lock = NSLock()
    private var answers: [HTTPResponse?] = []
    private(set) var requests: [(url: URL, headers: [String: String])] = []
    /// The time of each answer, so two requests can run at the same time.
    var delayNs: UInt64 = 0

    /// A nil answer is a network error.
    func enqueue(_ answer: HTTPResponse?) { lock.withLock { answers.append(answer) } }

    var requestCount: Int { lock.withLock { requests.count } }

    func get(_ url: URL, headers: [String: String]) async throws -> HTTPResponse {
        let answer: HTTPResponse? = lock.withLock {
            requests.append((url, headers))
            return answers.isEmpty ? nil : answers.removeFirst()
        }
        if delayNs > 0 { try await Task.sleep(nanoseconds: delayNs) }
        guard let answer else { throw URLError(.notConnectedToInternet) }
        return answer
    }
}

/// A clock that the test moves.
private final class Clock: @unchecked Sendable {
    private let lock = NSLock()
    private var time = Date(timeIntervalSince1970: 1_800_000_000)
    var now: Date { lock.withLock { time } }
    func advance(days: Double) { lock.withLock { time += days * 86400 } }
}

final class DemCacheTests: XCTestCase {
    private var directory: URL!
    private let http = FakeHTTP()
    private let clock = Clock()
    private let innsbruck = ElevationTile(latitudeDeg: 47, longitudeDeg: 11)
    private let munich = ElevationTile(latitudeDeg: 48, longitudeDeg: 11)
    private let verona = ElevationTile(latitudeDeg: 45, longitudeDeg: 10)

    /// A GeoTIFF test file of the core (core/test/data/geotiff).
    private func geotiff(_ name: String) throws -> Data {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../..").standardized
        return try Data(contentsOf: root.appendingPathComponent("core/test/data/geotiff/\(name)"))
    }

    private func ok(_ body: Data, etag: String = "\"a\"") -> HTTPResponse {
        HTTPResponse(status: 200, body: body, etag: etag, lastModified: "Tue, 01 Jan 2030 00:00:00 GMT")
    }

    private func makeCache(maxBytes: Int64 = DemCache.defaultMaxBytes) throws -> DemCache {
        let clock = clock
        return try DemCache(directory: directory, http: http, now: { clock.now }, maxBytes: maxBytes)
    }

    private func isGrid(_ tile: DemTile) -> Bool {
        if case .grid = tile { return true }
        return false
    }

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testTheNameAndTheURLOfATile() {
        XCTAssertEqual(DemCache.name(innsbruck), "N47_00_E011_00")
        XCTAssertEqual(DemCache.name(ElevationTile(latitudeDeg: -9, longitudeDeg: -75)), "S09_00_W075_00")
        XCTAssertEqual(
            CopernicusSource().url(innsbruck).absoluteString,
            "https://copernicus-dem-90m.s3.amazonaws.com/Copernicus_DSM_COG_30_N47_00_E011_00_DEM/"
                + "Copernicus_DSM_COG_30_N47_00_E011_00_DEM.tif")
    }

    func testADownloadedTileComesFromTheCacheAfterwards() async throws {
        let cache = try makeCache()
        http.enqueue(ok(try geotiff("float_tiles_deflate_p3.tif")))
        guard case .grid(let grid) = await cache.tile(innsbruck, level: 0, keep: []) else { return XCTFail("no grid") }
        XCTAssertEqual(grid.rows, 7)
        guard case .grid(let overview) = await cache.tile(innsbruck, level: 1, keep: []) else { return XCTFail("no grid") }
        XCTAssertEqual(overview.rows, 4)
        XCTAssertEqual(http.requestCount, 1)
        let entry = await cache.index["N47_00_E011_00"]
        XCTAssertEqual(entry?.etag, "\"a\"")
        XCTAssertEqual(entry?.sea, false)
        // The index persists, and the directory is not in the backup.
        let reopened = try makeCache()
        let contains = await reopened.contains(innsbruck)
        XCTAssertTrue(contains)
        XCTAssertEqual(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    func testA404IsTheSea() async throws {
        let cache = try makeCache()
        http.enqueue(HTTPResponse(status: 404, body: Data()))
        guard case .sea = await cache.tile(innsbruck, level: 0, keep: []) else { return XCTFail("not the sea") }
        guard case .sea = await cache.tile(innsbruck, level: 0, keep: []) else { return XCTFail("not the sea") }
        XCTAssertEqual(http.requestCount, 1)
        // After the maximum age, the sea gets a new request too.
        clock.advance(days: 181)
        http.enqueue(HTTPResponse(status: 404, body: Data()))
        guard case .sea = await cache.tile(innsbruck, level: 0, keep: []) else { return XCTFail("not the sea") }
        XCTAssertEqual(http.requestCount, 2)
        let checked = await cache.index["N47_00_E011_00"]?.checked
        XCTAssertEqual(checked, clock.now)
    }

    func testAnOldTileGetsAConditionalRequest() async throws {
        let cache = try makeCache()
        http.enqueue(ok(try geotiff("float_tiles_deflate_p3.tif")))
        _ = await cache.tile(innsbruck, level: 0, keep: [])
        // Younger than 180 days: no request.
        clock.advance(days: 179)
        _ = await cache.tile(innsbruck, level: 0, keep: [])
        XCTAssertEqual(http.requestCount, 1)
        // Older: a conditional request. 304 updates the time of the check.
        clock.advance(days: 2)
        http.enqueue(HTTPResponse(status: 304, body: Data()))
        let result = await cache.tile(innsbruck, level: 0, keep: [])
        XCTAssertTrue(isGrid(result))
        XCTAssertEqual(http.requestCount, 2)
        XCTAssertEqual(http.requests[1].headers["If-None-Match"], "\"a\"")
        XCTAssertEqual(http.requests[1].headers["If-Modified-Since"], "Tue, 01 Jan 2030 00:00:00 GMT")
        let checked = await cache.index["N47_00_E011_00"]?.checked
        XCTAssertEqual(checked, clock.now)
        // 200 replaces the file.
        clock.advance(days: 181)
        http.enqueue(ok(try geotiff("float_strips_none.tif"), etag: "\"b\""))
        guard case .grid(let grid) = await cache.tile(innsbruck, level: 0, keep: []) else { return XCTFail("no grid") }
        let replaced = try readElevationGeotiff(try geotiff("float_strips_none.tif"))
        XCTAssertEqual(grid.rows, replaced.rows)
        XCTAssertEqual(grid.heightsM[0], replaced.heightsM[0])
        let etag = await cache.index["N47_00_E011_00"]?.etag
        XCTAssertEqual(etag, "\"b\"")
    }

    /// Two requests for the same tile at the same time give one download: the second request waits
    /// for the first one and gets its result.
    func testTwoRequestsForOneTileShareTheDownload() async throws {
        let cache = try makeCache()
        http.delayNs = 100_000_000
        http.enqueue(ok(try geotiff("float_tiles_deflate_p3.tif")))
        async let first = cache.tile(innsbruck, level: 0, keep: [])
        async let second = cache.tile(innsbruck, level: 0, keep: [])
        let results = await [first, second]
        XCTAssertTrue(results.allSatisfy(isGrid))
        XCTAssertEqual(http.requestCount, 1)
    }

    /// A 304 for a request without conditions has no file to keep: the tile is unavailable, and the
    /// cache does not ask again in a loop.
    func testA304WithoutAnEntryIsUnavailable() async throws {
        let cache = try makeCache()
        for _ in 0..<3 { http.enqueue(HTTPResponse(status: 304, body: Data())) }
        let result = await cache.tile(innsbruck, level: 0, keep: [])
        guard case .unavailable = result else { return XCTFail("not unavailable: \(result)") }
        XCTAssertEqual(http.requestCount, 1)
    }

    func testANetworkErrorKeepsTheOldTile() async throws {
        let cache = try makeCache()
        http.enqueue(ok(try geotiff("float_tiles_deflate_p3.tif")))
        _ = await cache.tile(innsbruck, level: 0, keep: [])
        let downloaded = clock.now
        clock.advance(days: 200)
        // The queue is empty, so the request fails: the app works offline.
        let result = await cache.tile(innsbruck, level: 0, keep: [])
        XCTAssertTrue(isGrid(result))
        let entry = await cache.index["N47_00_E011_00"]
        XCTAssertEqual(entry?.checked, downloaded)
        XCTAssertEqual(entry?.used, clock.now)
        // A tile that is not in the cache is unavailable offline.
        guard case .unavailable = await cache.tile(munich, level: 0, keep: []) else { return XCTFail("not unavailable") }
        // A server error is not the sea.
        http.enqueue(HTTPResponse(status: 503, body: Data()))
        guard case .unavailable = await cache.tile(munich, level: 0, keep: []) else { return XCTFail("not unavailable") }
        let contains = await cache.contains(munich)
        XCTAssertFalse(contains)
    }

    func testACorruptFileIsDownloadedAgainOneTime() async throws {
        let cache = try makeCache()
        let good = try geotiff("float_tiles_deflate_p3.tif")
        http.enqueue(ok(Data("not a tiff".utf8)))
        http.enqueue(ok(good))
        let result = await cache.tile(innsbruck, level: 0, keep: [])
        XCTAssertTrue(isGrid(result))
        XCTAssertEqual(http.requestCount, 2)
        // A file that breaks on the disk: one download again.
        try Data("broken".utf8).write(to: directory.appendingPathComponent("N47_00_E011_00.tif"))
        http.enqueue(ok(good))
        let again = await cache.tile(innsbruck, level: 0, keep: [])
        XCTAssertTrue(isGrid(again))
        XCTAssertEqual(http.requestCount, 3)
        // A bad file on the disk and a bad download: the tile is unavailable after one download.
        try Data("broken".utf8).write(to: directory.appendingPathComponent("N47_00_E011_00.tif"))
        http.enqueue(ok(Data("bad".utf8)))
        guard case .unavailable = await cache.tile(innsbruck, level: 0, keep: []) else { return XCTFail("not unavailable") }
        XCTAssertEqual(http.requestCount, 4)
        // Two bad downloads: the tile is unavailable and the cache deletes the file.
        http.enqueue(ok(Data("bad".utf8)))
        http.enqueue(ok(Data("bad".utf8)))
        guard case .unavailable = await cache.tile(munich, level: 0, keep: []) else { return XCTFail("not unavailable") }
        XCTAssertEqual(http.requestCount, 6)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("N48_00_E011_00.tif").path))
        let entry = await cache.index["N48_00_E011_00"]
        XCTAssertNil(entry)
    }

    func testTheEvictionDeletesTheLeastRecentlyUsedTiles() async throws {
        let body = try geotiff("float_tiles_deflate_p3.tif")
        // Space for two tiles.
        let cache = try makeCache(maxBytes: Int64(body.count * 2))
        for tile in [innsbruck, munich] {
            http.enqueue(ok(body))
            _ = await cache.tile(tile, level: 0, keep: [])
            clock.advance(days: 1)
        }
        // Innsbruck is used again, so Munich is the least recently used tile.
        _ = await cache.tile(innsbruck, level: 0, keep: [])
        clock.advance(days: 1)
        http.enqueue(ok(body))
        _ = await cache.tile(verona, level: 0, keep: [])
        var contains = await (cache.contains(innsbruck), cache.contains(munich), cache.contains(verona))
        XCTAssertTrue(contains.0)
        XCTAssertFalse(contains.1)
        XCTAssertTrue(contains.2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent("N48_00_E011_00.tif").path))
        // A tile of the current place stays, even if it is the least recently used tile.
        clock.advance(days: 1)
        _ = await cache.tile(verona, level: 0, keep: [])
        clock.advance(days: 1)
        http.enqueue(ok(body))
        _ = await cache.tile(munich, level: 0, keep: ["N47_00_E011_00"])
        contains = await (cache.contains(innsbruck), cache.contains(munich), cache.contains(verona))
        XCTAssertTrue(contains.0)
        XCTAssertTrue(contains.1)
        XCTAssertFalse(contains.2)
        let total = await cache.totalBytes
        XCTAssertEqual(total, Int64(body.count * 2))
    }

    // MARK: - The kept places

    private let here = Observer(latitudeDeg: 47.26, longitudeDeg: 11.39)

    func testAKeptTileSurvivesTheEviction() async throws {
        let body = try geotiff("float_tiles_deflate_p3.tif")
        // Space for one tile that is not kept.
        let cache = try makeCache(maxBytes: Int64(body.count))
        _ = await cache.keep(name: "Innsbruck", observer: here, tiles: [innsbruck])
        http.enqueue(ok(body))
        _ = await cache.tile(innsbruck, level: 0, keep: [])
        // Innsbruck is the least recently used tile, but it is kept.
        for tile in [munich, verona] {
            clock.advance(days: 1)
            http.enqueue(ok(body))
            _ = await cache.tile(tile, level: 0, keep: [])
        }
        let contains = await (cache.contains(innsbruck), cache.contains(munich), cache.contains(verona))
        XCTAssertTrue(contains.0)
        XCTAssertFalse(contains.1)
        XCTAssertTrue(contains.2)
    }

    func testKeptTilesDoNotCountTowardTheLimit() async throws {
        let body = try geotiff("float_tiles_deflate_p3.tif")
        let cache = try makeCache(maxBytes: Int64(body.count))
        _ = await cache.keep(name: "Innsbruck", observer: here, tiles: [innsbruck])
        http.enqueue(ok(body))
        _ = await cache.tile(innsbruck, level: 0, keep: [])
        clock.advance(days: 1)
        http.enqueue(ok(body))
        _ = await cache.tile(munich, level: 0, keep: [])
        // Two files, but only Munich counts toward the limit, so both stay.
        let contains = await (cache.contains(innsbruck), cache.contains(munich))
        XCTAssertTrue(contains.0)
        XCTAssertTrue(contains.1)
        let sizes = await (cache.totalBytes, cache.cacheBytes)
        XCTAssertEqual(sizes.0, Int64(body.count * 2))
        XCTAssertEqual(sizes.1, Int64(body.count))
    }

    func testRemoveReleasesTheTiles() async throws {
        let body = try geotiff("float_tiles_deflate_p3.tif")
        let cache = try makeCache(maxBytes: Int64(body.count))
        let place = await cache.keep(name: "Innsbruck", observer: here, tiles: [innsbruck])
        http.enqueue(ok(body))
        _ = await cache.tile(innsbruck, level: 0, keep: [])
        await cache.release(place.id)
        // The tile stays on the disk as a normal cache tile.
        var contains = await cache.contains(innsbruck)
        XCTAssertTrue(contains)
        let kept = await cache.kept
        XCTAssertTrue(kept.isEmpty)
        // The next download evicts it, because it is the least recently used tile.
        clock.advance(days: 1)
        http.enqueue(ok(body))
        _ = await cache.tile(munich, level: 0, keep: [])
        contains = await cache.contains(innsbruck)
        XCTAssertFalse(contains)
    }

    func testTheKeptPlacesSurviveARestart() async throws {
        let body = try geotiff("float_tiles_deflate_p3.tif")
        let cache = try makeCache()
        let place = await cache.keep(name: "Innsbruck", observer: here, tiles: [innsbruck, munich])
        http.enqueue(ok(body))
        _ = await cache.tile(innsbruck, level: 0, keep: [])
        http.enqueue(HTTPResponse(status: 404, body: Data()))
        _ = await cache.tile(munich, level: 0, keep: [])
        // The same place again replaces the record.
        let again = await cache.keep(name: "Innsbruck", observer: here, tiles: [innsbruck, munich])
        XCTAssertEqual(again.id, place.id)
        let reopened = try makeCache()
        let kept = await reopened.kept
        XCTAssertEqual(kept, [place])
        XCTAssertEqual(kept.first?.tiles, ["N47_00_E011_00", "N48_00_E011_00"])
        // The sea counts as a tile without a size.
        let summaries = await reopened.keptSummaries()
        XCTAssertEqual(summaries.first?.tiles, 2)
        XCTAssertEqual(summaries.first?.bytes, Int64(body.count))
        let contains = await reopened.contains(innsbruck)
        XCTAssertTrue(contains)
    }

    func testAnOldIndexWithoutKeptPlacesLoads() async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let body = try geotiff("float_tiles_deflate_p3.tif")
        try body.write(to: directory.appendingPathComponent("N47_00_E011_00.tif"))
        // The index of the earlier version: only the dictionary of the tiles.
        let entry = DemCache.Entry(
            etag: "\"a\"", lastModified: nil, checked: clock.now, used: clock.now, size: Int64(body.count), sea: false)
        try JSONEncoder().encode(["N47_00_E011_00": entry]).write(to: directory.appendingPathComponent("index.json"))
        let cache = try makeCache()
        let state = await (cache.index["N47_00_E011_00"], cache.kept, cache.contains(innsbruck))
        XCTAssertEqual(state.0, entry)
        XCTAssertTrue(state.1.isEmpty)
        XCTAssertTrue(state.2)
        // The next save writes the new index, and it loads again.
        _ = await cache.keep(name: "Innsbruck", observer: here, tiles: [innsbruck])
        let reopened = try makeCache()
        let reloaded = await (reopened.index["N47_00_E011_00"], reopened.kept.count)
        XCTAssertEqual(reloaded.0, entry)
        XCTAssertEqual(reloaded.1, 1)
    }

    @MainActor
    func testKeepOfflineCountsTheSeaAsReadyAndReportsMissingTiles() async throws {
        let cache = try makeCache()
        let tiles = try elevationTiles(observer: here, maxDistanceM: TerrainHorizon.maxDistanceM)
        for _ in tiles { http.enqueue(HTTPResponse(status: 404, body: Data())) }
        let kept = KeptPlaces(cache: cache)
        kept.keep(name: "Innsbruck", observer: here)
        await kept.waitForDownload()
        XCTAssertEqual(kept.state, .ready(tiles: tiles.count, bytes: 0))
        XCTAssertEqual(kept.stateText, "Ready offline: \(tiles.count) tiles, 0 MB")
        XCTAssertEqual(kept.places.count, 1)
        // A second place far away, without a network: all its tiles are missing.
        kept.keep(name: "Verona", observer: Observer(latitudeDeg: 45.44, longitudeDeg: 10.99))
        await kept.waitForDownload()
        guard case .missing(let count) = kept.state else { return XCTFail("not missing") }
        XCTAssertGreaterThan(count, 0)
        XCTAssertEqual(kept.stateText, "\(count) tiles missing, try again online")
        XCTAssertEqual(kept.places.count, 2)
        await kept.remove(kept.places[0].id)
        let remaining = await cache.kept.map(\.name)
        XCTAssertEqual(remaining, ["Verona"])
    }
}

final class TerrainHorizonTests: XCTestCase {
    func testTheLinesOfAProfileStopWhereTheDataStops() {
        let profile = HorizonProfile(
            azimuthStepDeg: 90, altitudeDeg: [1, 2, .nan, 4], distanceM: [1000, 1000, .nan, 1000])
        let lines = TerrainHorizon.lines(profile)
        XCTAssertEqual(lines.map { $0.map(\.azimuthDeg) }, [[0, 90], [270, 360]])
        XCTAssertEqual(lines[1].map(\.altitudeDeg), [4, 1])
        // A single point between two gaps is no line, also in the middle of the profile.
        let single = HorizonProfile(
            azimuthStepDeg: 90, altitudeDeg: [1, .nan, 3, .nan], distanceM: [1000, .nan, 1000, .nan])
        XCTAssertTrue(TerrainHorizon.lines(single).isEmpty)
    }
}

/// The prefix files of the coarse check (PLAN.md 14.3), with a fake server that supports Range.
/// The test files have no level 2, so the cache uses level 1 as the coarse level.
final class DemCachePrefixTests: XCTestCase {
    private var directory: URL!
    private let server = FakeServer()
    private let clock = Clock()
    private let innsbruck = ElevationTile(latitudeDeg: 47, longitudeDeg: 11)

    private func makeCache(headerBytes: Int) throws -> DemCache {
        let clock = clock
        return try DemCache(
            directory: directory, http: server, now: { clock.now }, coarseLevel: 1, headerBytes: headerBytes)
    }

    private func rows(_ tile: DemTile) -> Int? {
        if case .grid(let grid) = tile { return grid.rows }
        return nil
    }

    private var prefixFile: URL { directory.appendingPathComponent("N47_00_E011_00.l1.tif") }

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testThePrefixDownloadsInTwoSteps() async throws {
        let body = TestGeotiff.tile(innsbruck, heightM: 500)
        let size = try elevationGeotiffPrefixSize(body, level: 1)
        XCTAssertEqual(size, TestGeotiff.headerSize + 64)
        XCTAssertLessThan(size, body.count)
        server.put(innsbruck, body)
        // The header has all directories, but not the data of the overview.
        let cache = try makeCache(headerBytes: TestGeotiff.headerSize)
        let result = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(rows(result), 4)
        XCTAssertEqual(server.requests.map { $0.headers["Range"] }, ["bytes=0-319", "bytes=320-383"])
        XCTAssertEqual(try Data(contentsOf: prefixFile), body.prefix(size))
        let entry = await cache.index["N47_00_E011_00.l1"]
        XCTAssertEqual(entry?.size, Int64(size))
        XCTAssertEqual(entry?.etag, "\"a\"")
        // The prefix file is not the full file.
        let state = await (cache.contains(innsbruck), cache.containsCoarse(innsbruck))
        XCTAssertFalse(state.0)
        XCTAssertTrue(state.1)
        // The next time, it comes from the cache.
        let again = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(rows(again), 4)
        XCTAssertEqual(server.requests.count, 2)
    }

    func testAShortHeaderGetsALongerOne() async throws {
        // A file of the core: 16 bytes are too short to tell the size of the prefix.
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../..").standardized
        let body = try Data(contentsOf: root.appendingPathComponent("core/test/data/geotiff/float_tiles_deflate_p3.tif"))
        XCTAssertEqual(try elevationGeotiffPrefixSize(body.prefix(16), level: 1), 0)
        server.put(innsbruck, body)
        let cache = try makeCache(headerBytes: 16)
        let result = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(rows(result), 4)
        XCTAssertEqual(server.requests.first?.headers["Range"], "bytes=0-15")
        XCTAssertEqual(server.requests[1].headers["Range"], "bytes=16-63")
        XCTAssertEqual(server.fullRequests(innsbruck), 0)
        let size = try elevationGeotiffPrefixSize(body, level: 1)
        XCTAssertEqual(try Data(contentsOf: prefixFile), body.prefix(size))
    }

    func testA404IsTheSeaAlsoForThePrefix() async throws {
        let cache = try makeCache(headerBytes: 16384)
        guard case .sea = await cache.coarseTile(innsbruck, keep: []) else { return XCTFail("not the sea") }
        guard case .sea = await cache.coarseTile(innsbruck, keep: []) else { return XCTFail("not the sea") }
        XCTAssertEqual(server.requests.count, 1)
        XCTAssertEqual(server.requests[0].headers["Range"], "bytes=0-16383")
        let contains = await cache.containsCoarse(innsbruck)
        XCTAssertTrue(contains)
    }

    func testAServerWithoutRangeSendsTheWholeFile() async throws {
        let body = TestGeotiff.tile(innsbruck, heightM: 500)
        server.put(innsbruck, body)
        server.ignoresRange = true
        let cache = try makeCache(headerBytes: 64)
        let result = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(rows(result), 4)
        XCTAssertEqual(server.requests.count, 1)
        // The cache keeps only the prefix.
        let size = try elevationGeotiffPrefixSize(body, level: 1)
        XCTAssertEqual(try Data(contentsOf: prefixFile).count, size)
    }

    func testAnOldPrefixGetsAConditionalRangeRequest() async throws {
        let body = TestGeotiff.tile(innsbruck, heightM: 500)
        server.put(innsbruck, body)
        let cache = try makeCache(headerBytes: 16384)
        _ = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(server.requests.count, 1)
        // Younger than 180 days: no request.
        clock.advance(days: 179)
        _ = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(server.requests.count, 1)
        // Older: a conditional Range request. 304 keeps the file.
        clock.advance(days: 2)
        let result = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(rows(result), 4)
        XCTAssertEqual(server.requests.count, 2)
        XCTAssertEqual(server.requests[1].headers["If-None-Match"], "\"a\"")
        XCTAssertEqual(server.requests[1].headers["Range"], "bytes=0-16383")
        var entry = await cache.index["N47_00_E011_00.l1"]
        XCTAssertEqual(entry?.checked, clock.now)
        // A new file on the server: 206 replaces the prefix.
        clock.advance(days: 181)
        let changed = TestGeotiff.tile(innsbruck, size: 16, heightM: 700)
        server.put(innsbruck, changed, etag: "\"b\"")
        let replaced = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(rows(replaced), 8)
        entry = await cache.index["N47_00_E011_00.l1"]
        XCTAssertEqual(entry?.etag, "\"b\"")
        XCTAssertEqual(entry?.checked, clock.now)
        XCTAssertEqual(try Data(contentsOf: prefixFile), changed.prefix(try elevationGeotiffPrefixSize(changed, level: 1)))
        // Offline, the old prefix stays.
        clock.advance(days: 181)
        server.online = false
        let offline = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(rows(offline), 8)
    }

    func testAFullFileServesTheCoarseLevel() async throws {
        let body = TestGeotiff.tile(innsbruck, heightM: 500)
        server.put(innsbruck, body)
        let cache = try makeCache(headerBytes: 16384)
        // First the prefix, then the full file: the full file replaces the prefix file.
        _ = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertTrue(FileManager.default.fileExists(atPath: prefixFile.path))
        let full = await cache.tile(innsbruck, level: 0, keep: [])
        XCTAssertEqual(rows(full), 8)
        XCTAssertFalse(FileManager.default.fileExists(atPath: prefixFile.path))
        let prefixEntry = await cache.index["N47_00_E011_00.l1"]
        XCTAssertNil(prefixEntry)
        // The coarse level comes from the full file, without a request.
        server.clearRequests()
        let coarse = await cache.coarseTile(innsbruck, keep: [])
        XCTAssertEqual(rows(coarse), 4)
        XCTAssertTrue(server.requests.isEmpty)
        let total = await cache.totalBytes
        XCTAssertEqual(total, Int64(body.count))
    }

    func testAKeptPrefixSurvivesTheEviction() async throws {
        let munich = ElevationTile(latitudeDeg: 48, longitudeDeg: 11)
        let body = TestGeotiff.tile(innsbruck, heightM: 500)
        server.put(innsbruck, body)
        server.put(munich, TestGeotiff.tile(munich, heightM: 500))
        let clock = clock
        // Space for no file that is not kept.
        let cache = try DemCache(
            directory: directory, http: server, now: { clock.now }, maxBytes: 1, coarseLevel: 1, headerBytes: 16384)
        _ = await cache.keep(name: "Innsbruck", observer: Observer(latitudeDeg: 47.26, longitudeDeg: 11.39), tiles: [innsbruck])
        _ = await cache.coarseTile(innsbruck, keep: [])
        clock.advance(days: 1)
        _ = await cache.coarseTile(munich, keep: [])
        clock.advance(days: 1)
        _ = await cache.tile(munich, level: 0, keep: [])
        // A third tile evicts Munich, but not the kept prefix of Innsbruck.
        let verona = ElevationTile(latitudeDeg: 45, longitudeDeg: 10)
        server.put(verona, TestGeotiff.tile(verona, heightM: 500))
        clock.advance(days: 1)
        _ = await cache.coarseTile(verona, keep: [])
        let contains = await (cache.containsCoarse(innsbruck), cache.containsCoarse(munich), cache.containsCoarse(verona))
        XCTAssertTrue(contains.0)
        XCTAssertFalse(contains.1)
        XCTAssertTrue(contains.2)
        let summary = await cache.keptSummaries().first
        XCTAssertEqual(summary?.tiles, 1)
        XCTAssertEqual(summary?.bytes, Int64(try elevationGeotiffPrefixSize(body, level: 1)))
    }
}
