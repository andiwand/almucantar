import Foundation
import Sunradar

/// The places that the user keeps offline (PLAN.md 13.3, "download this region"). "Keep offline"
/// downloads the terrain data of a place, the same as `TerrainHorizon` loads, and the DemCache
/// keeps it out of the eviction.
/// The sky screen owns this object, so the download continues when the place panel closes.
@MainActor
final class KeptPlaces: ObservableObject {
    enum State: Equatable {
        case idle
        /// The download runs: `done` of `total` tiles are ready or failed.
        case downloading(done: Int, total: Int)
        /// All tiles are ready. The sea tiles count, but they have no size.
        case ready(tiles: Int, bytes: Int64)
        /// Some tiles failed, for example without a network.
        case missing(count: Int)
    }

    @Published private(set) var state = State.idle
    @Published private(set) var places: [DemCache.KeptSummary] = []

    /// The number of downloads at the same time, the same as for the terrain.
    private static let parallelDownloads = 3

    /// Nil in the demo mode: then the panel shows no kept places.
    let cache: DemCache?
    private var job: Task<Void, Never>?

    init(cache: DemCache?) {
        self.cache = cache
    }

    var downloading: Bool {
        if case .downloading = state { return true }
        return false
    }

    /// The text of the state for the place panel, or nil.
    var stateText: String? {
        switch state {
        case .idle: return nil
        case .downloading(let done, let total): return "Downloading \(done)/\(total)"
        case .ready(let tiles, let bytes): return "Ready offline: \(Self.tilesText(tiles)), \(Self.sizeText(bytes))"
        case .missing(let count): return "\(Self.tilesText(count)) missing, try again online"
        }
    }

    static func tilesText(_ count: Int) -> String { count == 1 ? "1 tile" : "\(count) tiles" }

    /// The size in MB (1024 × 1024 bytes, the same unit as the limit of the cache).
    static func sizeText(_ bytes: Int64) -> String {
        let mb = Double(bytes) / 1_048_576
        return "\(Int(mb.rounded())) MB"
    }

    /// Reads the kept places from the cache again.
    func refresh() async {
        guard let cache else { return }
        places = await cache.keptSummaries()
    }

    /// Keeps the terrain tiles of a place offline: the same data as the terrain horizon uses
    /// (PLAN.md 14.3). It downloads the prefix files of all tiles. If the coarse profile reaches
    /// `TerrainHorizon.fullTerrainMinDeg`, it also downloads the full files of the near tiles. A
    /// second call during a download does nothing.
    func keep(name: String, observer: Observer) {
        guard let cache, !downloading else { return }
        // The list fails only for a place that is not valid.
        guard let tiles = try? elevationTiles(observer: observer, maxDistanceM: TerrainHorizon.maxDistanceM) else { return }
        state = .downloading(done: 0, total: tiles.count)
        let source = cache.source
        job = Task {
            let place = await cache.keep(name: name, observer: observer, tiles: tiles)
            await refresh()
            let keep = Set(tiles.map(DemCache.name))
            var done = 0
            var total = tiles.count
            // A tile that fails in both steps counts one time.
            var missing = Set<String>()
            var grids: [(distanceM: Double, grid: ElevationGrid)] = []
            var sea = Set<String>()

            /// Loads the tiles, `parallelDownloads` at the same time. A sea tile (404) counts as
            /// ready.
            @MainActor func fetch(_ tiles: [ElevationTile], full: Bool) async {
                await withTaskGroup(of: (tile: ElevationTile, result: DemTile).self) { group in
                    var next = 0
                    func add() {
                        guard next < tiles.count else { return }
                        let tile = tiles[next]
                        next += 1
                        group.addTask {
                            let result =
                                full
                                ? await cache.tile(tile, level: source.fullLevel, keep: keep)
                                : await cache.coarseTile(tile, keep: keep)
                            return (tile, result)
                        }
                    }
                    for _ in 0..<Self.parallelDownloads { add() }
                    while let finished = await group.next() {
                        done += 1
                        switch finished.result {
                        case .grid(let grid): grids.append((finished.tile.distanceM, grid))
                        case .sea: sea.insert(DemCache.name(finished.tile))
                        case .unavailable: missing.insert(DemCache.name(finished.tile))
                        }
                        state = .downloading(done: done, total: total)
                        add()
                    }
                }
            }

            // Step 1: the coarse check, with the prefix files of all tiles.
            await fetch(tiles, full: false)
            // Step 2: the full files of the near tiles, if the terrain is high. The check uses the
            // same options as the terrain, so it downloads the same tiles.
            let ownTileIsSea = tiles.contains { $0.distanceM == 0 && sea.contains(DemCache.name($0)) }
            let profile = await TerrainHorizon.calculateProfile(
                observer: observer, grids: grids, ownTileIsSea: ownTileIsSea, options: source.horizonOptions)
            if TerrainHorizon.needsFullResolution(profile) {
                let near = tiles.filter { source.isNear($0) && !sea.contains(DemCache.name($0)) }
                total += near.count
                state = .downloading(done: done, total: total)
                await fetch(near, full: true)
            }
            await refresh()
            if !missing.isEmpty {
                state = .missing(count: missing.count)
            } else {
                let summary = places.first { $0.id == place.id }
                state = .ready(tiles: summary?.tiles ?? tiles.count, bytes: summary?.bytes ?? 0)
            }
        }
    }

    /// Waits for the end of the download. The tests use it.
    func waitForDownload() async { await job?.value }

    /// Removes a kept place. Its tiles become normal cache tiles.
    func remove(_ id: String) async {
        guard let cache else { return }
        await cache.release(id)
        await refresh()
    }
}
