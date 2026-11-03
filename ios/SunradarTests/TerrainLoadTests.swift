import Foundation
import Almucantar
import XCTest

@testable import SunradarApp

/// The two steps of the terrain (PLAN.md 14.3): the coarse check, and the near tiles at full
/// resolution only if the terrain is high. The tiles are small GeoTIFF files with level 1 as the
/// coarse level.
/// A bare-earth source for the tests: other URLs, level 1 as the coarse level, and no clearance of
/// the near path.
private struct BareEarthSource: ElevationSource {
    let name = "Bare earth"
    let attribution = "Test data"
    let isSurfaceModel = false
    let coarseLevel = 1
    let fullLevel = 0
    let fullResolutionM = 25_000.0
    func url(_ tile: ElevationTile) -> URL { URL(string: "https://bare.example/\(DemCache.name(tile)).tif")! }
}

@MainActor
final class TerrainLoadTests: XCTestCase {
    private var directory: URL!
    private let server = FakeServer()
    /// The center of the tile N47 E011. Only this tile is nearer than 25 km.
    private let here = Observer(latitudeDeg: 47.5, longitudeDeg: 11.5)
    private var tiles: [ElevationTile] = []

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        tiles = try elevationTiles(observer: here, maxDistanceM: TerrainHorizon.maxDistanceM)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeCache() throws -> DemCache {
        try DemCache(directory: directory, http: server, coarseLevel: 1, headerBytes: 16384)
    }

    /// All tiles at 500 m. If `mountains`, the tile north of the place is at 3000 m: about 2.4°
    /// high at a distance of 55 km. If `wall`, the tile of the place has a ring of 600 m from 700 m
    /// to 1300 m around the place, like the buildings of a city in a surface model: about 5° high.
    private func serveTiles(mountains: Bool, wall: Bool = false, source: any ElevationSource = CopernicusSource()) {
        for tile in tiles {
            let high = mountains && tile.latitudeDeg == 48 && tile.longitudeDeg == 11
            let data: Data
            if wall && tile.distanceM == 0 {
                // Samples of about 220 m x 150 m, and about 430 m x 290 m at level 1.
                data = TestGeotiff.tile(tile, size: 512) { latitude, longitude in
                    let north = (latitude - here.latitudeDeg) * 111_195
                    let east = (longitude - here.longitudeDeg) * 111_195 * cos(here.latitudeDeg * .pi / 180)
                    let distance = (north * north + east * east).squareRoot()
                    return distance > 700 && distance < 1300 ? 600 : 500
                }
            } else {
                data = TestGeotiff.tile(tile, heightM: high ? 3000 : 500)
            }
            server.put(tile, data, source: source)
        }
    }

    private var nearNames: Set<String> { Set(tiles.filter(CopernicusSource().isNear).map(DemCache.name)) }

    private func maxAltitude(_ profile: HorizonProfile?) -> Double {
        profile?.altitudeDeg.filter(\.isFinite).max() ?? -.infinity
    }

    func testTheThreshold() {
        let low = HorizonProfile(azimuthStepDeg: 90, altitudeDeg: [0.99, .nan, -1, 0], distanceM: [1, 1, 1, 1])
        let high = HorizonProfile(azimuthStepDeg: 90, altitudeDeg: [1.0, .nan, -1, 0], distanceM: [1, 1, 1, 1])
        XCTAssertFalse(TerrainHorizon.needsFullResolution(low))
        XCTAssertTrue(TerrainHorizon.needsFullResolution(high))
        XCTAssertFalse(TerrainHorizon.needsFullResolution(nil))
        XCTAssertEqual(nearNames, ["N47_00_E011_00"])
    }

    func testTheOptionsOfTheSource() throws {
        XCTAssertEqual(nearClearanceM, 2000)
        let copernicus = CopernicusSource()
        XCTAssertTrue(copernicus.isSurfaceModel)
        XCTAssertEqual(copernicus.horizonOptions.minDistanceM, 2000)
        XCTAssertEqual(copernicus.coarseLevel, 2)
        XCTAssertEqual(copernicus.fullLevel, 0)
        XCTAssertEqual(BareEarthSource().horizonOptions.minDistanceM, 0)
        // The other options stay at the defaults of the core.
        XCTAssertEqual(copernicus.horizonOptions.maxDistanceM, HorizonOptions().maxDistanceM)
        // The cache takes the coarse level from the source.
        XCTAssertEqual(try DemCache(directory: directory, http: server).coarseLevel, 2)
        XCTAssertEqual(try DemCache(directory: directory, http: server, source: BareEarthSource()).coarseLevel, 1)
    }

    /// Copernicus is a surface model: the coarse check ignores the wall near the place, so the
    /// app downloads only the prefixes.
    func testASurfaceModelIgnoresTheWallNearThePlace() async throws {
        serveTiles(mountains: false, wall: true)
        let terrain = TerrainHorizon(cache: try makeCache())
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .ready)
        XCTAssertLessThan(maxAltitude(terrain.profile), TerrainHorizon.fullTerrainMinDeg)
        XCTAssertEqual(terrain.revision, 1)
        XCTAssertTrue(server.fullRequestNames.isEmpty)
    }

    /// A bare-earth source has no clearance: the same wall is real terrain, so the coarse check
    /// reaches the threshold and the cache downloads the full near tiles from the URLs of the source.
    func testABareEarthSourceKeepsTheWallAndUsesItsURLs() async throws {
        let source = BareEarthSource()
        serveTiles(mountains: false, wall: true, source: source)
        let cache = try DemCache(directory: directory, http: server, source: source, headerBytes: 16384)
        let terrain = TerrainHorizon(cache: cache)
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .ready)
        XCTAssertGreaterThan(maxAltitude(terrain.profile), 4)
        XCTAssertEqual(terrain.revision, 2)
        XCTAssertEqual(server.fullRequestNames, nearNames)
        XCTAssertTrue(server.requests.allSatisfy { $0.url.host == "bare.example" })
        let index = await cache.index
        XCTAssertNotNil(index["N47_00_E011_00"])
        XCTAssertNotNil(index["N48_00_E011_00.l1"])
    }

    /// The full profile uses the same options as the coarse check: the mountains need the full near
    /// tiles, and the wall near the place still does not count.
    func testTheFullProfileOfASurfaceModelIgnoresTheWall() async throws {
        serveTiles(mountains: true, wall: true)
        let terrain = TerrainHorizon(cache: try makeCache())
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .ready)
        XCTAssertEqual(terrain.revision, 2)
        XCTAssertEqual(server.fullRequestNames, nearNames)
        let highest = maxAltitude(terrain.profile)
        XCTAssertGreaterThanOrEqual(highest, TerrainHorizon.fullTerrainMinDeg)
        XCTAssertLessThan(highest, 3)
    }

    /// "Keep offline" uses the same options as the terrain: at the wall it keeps only the prefixes.
    func testKeepOfflineIgnoresTheWallOfASurfaceModel() async throws {
        serveTiles(mountains: false, wall: true)
        let kept = KeptPlaces(cache: try makeCache())
        kept.keep(name: "City", observer: here)
        await kept.waitForDownload()
        guard case .ready(let count, _) = kept.state else { return XCTFail("not ready: \(kept.state)") }
        XCTAssertEqual(count, tiles.count)
        XCTAssertTrue(server.fullRequestNames.isEmpty)
    }

    func testAFlatPlaceDownloadsOnlyThePrefixes() async throws {
        serveTiles(mountains: false)
        let cache = try makeCache()
        let terrain = TerrainHorizon(cache: cache)
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .ready)
        XCTAssertNotNil(terrain.profile)
        XCTAssertLessThan(maxAltitude(terrain.profile), TerrainHorizon.fullTerrainMinDeg)
        XCTAssertEqual(terrain.revision, 1)
        // Each request is a Range request, one per tile, and the cache has only prefix files.
        XCTAssertEqual(server.requests.count, tiles.count)
        XCTAssertTrue(server.fullRequestNames.isEmpty)
        let index = await cache.index
        XCTAssertEqual(Set(index.keys), Set(tiles.map { DemCache.name($0) + ".l1" }))
    }

    func testAPlaceInTheMountainsDownloadsTheFullNearTiles() async throws {
        serveTiles(mountains: true)
        let cache = try makeCache()
        let terrain = TerrainHorizon(cache: cache)
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .ready)
        XCTAssertGreaterThanOrEqual(maxAltitude(terrain.profile), TerrainHorizon.fullTerrainMinDeg)
        // The coarse profile, then the profile with the full near tiles.
        XCTAssertEqual(terrain.revision, 2)
        XCTAssertEqual(server.fullRequestNames, nearNames)
        let index = await cache.index
        XCTAssertNotNil(index["N47_00_E011_00"])
        XCTAssertNil(index["N47_00_E011_00.l1"])
        XCTAssertNotNil(index["N48_00_E011_00.l1"])
        XCTAssertNil(index["N48_00_E011_00"])
        // The second time, all comes from the cache.
        server.clearRequests()
        let again = TerrainHorizon(cache: cache)
        again.update(observer: here, enabled: true)
        await again.waitForLoad()
        XCTAssertEqual(again.status, .ready)
        XCTAssertTrue(server.requests.isEmpty)
        XCTAssertEqual(again.profile?.altitudeDeg, terrain.profile?.altitudeDeg)
    }

    func testOfflineTheTerrainIsMissing() async throws {
        server.online = false
        let terrain = TerrainHorizon(cache: try makeCache())
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .missing)
        XCTAssertNil(terrain.profile)
    }

    /// A place far from the old one does not show the profile of the old place, also if it gets no
    /// profile of its own (offline). A small move keeps the old profile until the new one is ready.
    func testAFarPlaceDoesNotShowTheOldProfile() async throws {
        serveTiles(mountains: true)
        let terrain = TerrainHorizon(cache: try makeCache())
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertTrue(terrain.profile != nil)
        server.online = false
        terrain.update(observer: Observer(latitudeDeg: 47.5, longitudeDeg: 11.52), enabled: true)
        XCTAssertTrue(terrain.profile != nil)
        await terrain.waitForLoad()
        terrain.update(observer: Observer(latitudeDeg: 40.5, longitudeDeg: -3.5), enabled: true)
        XCTAssertTrue(terrain.profile == nil)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .missing)
        XCTAssertTrue(terrain.profile == nil)
        XCTAssertTrue(terrain.lines.isEmpty)
    }

    /// The tile of the place is the sea (404): the profile is calculated at sea level, and the
    /// mountains in the north show. "Keep offline" uses the same rule.
    func testAPlaceAtSeaGetsAProfileAtSeaLevel() async throws {
        for tile in tiles where tile.distanceM > 0 {
            let high = tile.latitudeDeg == 48 && tile.longitudeDeg == 11
            server.put(tile, TestGeotiff.tile(tile, heightM: high ? 3000 : 0))
        }
        let cache = try makeCache()
        let terrain = TerrainHorizon(cache: cache)
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .ready)
        XCTAssertGreaterThan(maxAltitude(terrain.profile), TerrainHorizon.fullTerrainMinDeg)
        let kept = KeptPlaces(cache: cache)
        kept.keep(name: "Sea", observer: here)
        await kept.waitForDownload()
        guard case .ready(let count, _) = kept.state else { return XCTFail("not ready: \(kept.state)") }
        XCTAssertEqual(count, tiles.count)
    }

    /// Waits until the status is `status`, at most 5 s.
    private func wait(for status: TerrainHorizon.Status, _ terrain: TerrainHorizon) async throws {
        for _ in 0..<250 where terrain.status != status { try await Task.sleep(nanoseconds: 20_000_000) }
    }

    /// Offline, the terrain tries the missing tiles again after `retryInterval`, until they are
    /// ready. The terrain off stops the retry.
    func testTheMissingTilesLoadAgainLater() async throws {
        serveTiles(mountains: false)
        server.online = false
        let terrain = TerrainHorizon(cache: try makeCache(), retryInterval: 0.05)
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .missing)
        server.online = true
        try await wait(for: .ready, terrain)
        XCTAssertEqual(terrain.status, .ready)
        XCTAssertTrue(terrain.profile != nil)

        server.online = false
        terrain.update(observer: Observer(latitudeDeg: 40.5, longitudeDeg: -3.5), enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .missing)
        terrain.update(observer: here, enabled: false)
        server.clearRequests()
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertTrue(server.requests.isEmpty)
        XCTAssertEqual(terrain.status, .off)
    }

    func testKeepOfflineAtAFlatPlaceKeepsOnlyThePrefixes() async throws {
        serveTiles(mountains: false)
        let cache = try makeCache()
        let kept = KeptPlaces(cache: cache)
        kept.keep(name: "Flat", observer: here)
        await kept.waitForDownload()
        let prefixBytes = Int64(tiles.count * (TestGeotiff.headerSize + 64))
        XCTAssertEqual(kept.state, .ready(tiles: tiles.count, bytes: prefixBytes))
        XCTAssertTrue(server.fullRequestNames.isEmpty)
        // The terrain then needs no network.
        server.online = false
        let terrain = TerrainHorizon(cache: cache)
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .ready)
    }

    func testKeepOfflineInTheMountainsKeepsTheFullNearTiles() async throws {
        serveTiles(mountains: true)
        let cache = try makeCache()
        let kept = KeptPlaces(cache: cache)
        kept.keep(name: "Mountains", observer: here)
        await kept.waitForDownload()
        let near = Int64(nearNames.count)
        let fullBytes = Int64(TestGeotiff.tile(tiles[0], heightM: 0).count)
        let prefixBytes = Int64(TestGeotiff.headerSize + 64)
        let bytes = (near * fullBytes) + ((Int64(tiles.count) - near) * prefixBytes)
        XCTAssertEqual(kept.state, .ready(tiles: tiles.count, bytes: bytes))
        XCTAssertEqual(server.fullRequestNames, nearNames)
        // The terrain then needs no network, and it has the full near tiles.
        server.online = false
        let terrain = TerrainHorizon(cache: cache)
        terrain.update(observer: here, enabled: true)
        await terrain.waitForLoad()
        XCTAssertEqual(terrain.status, .ready)
        XCTAssertEqual(terrain.revision, 2)
    }
}
