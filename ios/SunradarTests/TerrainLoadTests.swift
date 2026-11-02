import Foundation
import Sunradar
import XCTest

@testable import SunradarApp

/// The two steps of the terrain (PLAN.md 14.3): the coarse check, and the near tiles at full
/// resolution only if the terrain is high. The tiles are small GeoTIFF files with level 1 as the
/// coarse level.
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
    /// high at a distance of 55 km.
    private func serveTiles(mountains: Bool) {
        for tile in tiles {
            let high = mountains && tile.latitudeDeg == 48 && tile.longitudeDeg == 11
            server.put(tile, TestGeotiff.tile(tile, heightM: high ? 3000 : 500))
        }
    }

    private var nearNames: Set<String> { Set(tiles.filter(TerrainHorizon.isNear).map(DemCache.name)) }

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
