import Foundation
import Almucantar

/// The terrain horizon of the current place: it loads the DEM tiles from the DemCache and
/// calculates the horizon profile off the main thread. The profile is calculated again when the
/// place moves more than `moveM`. Until the first profile of a place is ready, the app shows the
/// 0° horizon, or the old profile after a move of less than `keepProfileM`.
///
/// The terrain loads in two steps (PLAN.md 14.3), so a flat place downloads only about 3 MB:
///
/// 1. The coarse check: all tiles at the coarse level of the cache (the prefix files, about
///    370 m). The app shows this profile at once.
/// 2. If the coarse profile reaches `fullTerrainMinDeg`, the near tiles
///    (`ElevationSource.fullResolutionM`) at full resolution, and the profile again.
///
/// The facts of the data come from the `ElevationSource` of the cache. Both steps use its
/// `horizonOptions`, so for a surface model the coarse check and the full profile ignore the
/// terrain nearer than `nearClearanceM`.
@MainActor
final class TerrainHorizon: ObservableObject {
    enum Status: Equatable {
        /// The setting is off, or the demo mode runs.
        case off
        /// Tiles download. `remaining` tiles are not ready yet.
        case loading(remaining: Int)
        /// A tile is not in the cache and the download failed (offline).
        case missing
        case ready
    }

    /// The profile of the current place, or nil.
    @Published private(set) var profile: HorizonProfile?
    /// The parts of the profile with data, for the drawing. The last point repeats the first one.
    @Published private(set) var lines: [[Horizontal]] = []
    /// Increases with each new profile, so the views can cache what they derive from it.
    @Published private(set) var revision = 0
    @Published private(set) var status = Status.off

    /// If the terrain is higher than this, the app loads the near tiles at full resolution. A
    /// terrain of 1° moves the sunrise by about 5–8 minutes in Europe. Below it, the coarse
    /// profile is sufficient.
    nonisolated static let fullTerrainMinDeg = 1.0
    nonisolated static let maxDistanceM = 100_000.0
    /// A smaller move of the place does not change the profile.
    static let moveM = 500.0
    /// The old profile stays until the new one is ready, if the place moved less than this. A
    /// farther place shows the 0° horizon until its own profile is ready. Android uses the same
    /// distance.
    static let keepProfileM = 5_000.0
    /// The number of downloads at the same time.
    nonisolated static let parallelDownloads = 3
    /// The time between two tries to load the missing tiles of the place, in seconds. Android
    /// uses the same time.
    let retryInterval: TimeInterval

    private let cache: DemCache?
    private var observer: Observer?
    /// The place of `profile`.
    private var profileObserver: Observer?
    private var job: Task<Void, Never>?
    /// The next try to load the missing tiles of the current place.
    private var retry: Task<Void, Never>?
    /// The grids in memory, by the name of the tile, with their level.
    private var grids: [String: (level: Int, distanceM: Double, grid: ElevationGrid)] = [:]

    init(cache: DemCache? = DemCache.shared, retryInterval: TimeInterval = 60) {
        self.cache = cache
        self.retryInterval = retryInterval
    }

    /// Starts the work for a new place, if the place moved more than `moveM`. If `enabled` is
    /// false, it stops the work and removes the profile.
    func update(observer: Observer, enabled: Bool) {
        guard enabled else {
            retry?.cancel()
            job?.cancel()
            job = nil
            self.observer = nil
            grids = [:]
            clearProfile()
            status = .off
            return
        }
        if let current = self.observer, Geo.distanceM(from: current, to: observer) <= Self.moveM { return }
        self.observer = observer
        if let shown = profileObserver, Geo.distanceM(from: shown, to: observer) > Self.keepProfileM { clearProfile() }
        retry?.cancel()
        job?.cancel()
        job = Task { await load(observer) }
    }

    /// Waits for the end of the work for the current place. The tests use it.
    func waitForLoad() async { await job?.value }

    /// True if the terrain of the profile is high enough for the near tiles at full resolution.
    nonisolated static func needsFullResolution(_ profile: HorizonProfile?) -> Bool {
        guard let profile else { return false }
        return profile.altitudeDeg.contains { $0.isFinite && $0 >= fullTerrainMinDeg }
    }

    /// Calculates the profile off the main thread, with the options of the source
    /// (`ElevationSource.horizonOptions`). The profile needs the ground at the observer, so it is
    /// nil without the tile at the observer (the distance 0). If that tile is the sea, or if the
    /// core has no data at the observer (a coast), the observer is at sea level, as on Android.
    nonisolated static func calculateProfile(
        observer: Observer, grids: [(distanceM: Double, grid: ElevationGrid)], ownTileIsSea: Bool,
        options: HorizonOptions
    ) async -> HorizonProfile? {
        guard ownTileIsSea || grids.contains(where: { $0.distanceM == 0 }) else { return nil }
        var atSeaLevel = options
        atSeaLevel.useGridElevation = false
        atSeaLevel.observerElevationM = 0
        // The core takes the first tile with data at a place, so the order of tiles with the same
        // cells changes the profile a little at the edges. A fixed order gives the same profile
        // for the same grids.
        let tiles = grids.map(\.grid).sorted {
            ($0.northLatitudeDeg, $0.westLongitudeDeg) < ($1.northLatitudeDeg, $1.westLongitudeDeg)
        }
        return await Task.detached(priority: .userInitiated) {
            if !ownTileIsSea, let profile = try? horizonProfile(observer: observer, tiles: tiles, options: options) {
                return profile
            }
            return try? horizonProfile(observer: observer, tiles: tiles, options: atSeaLevel)
        }.value
    }

    private func load(_ observer: Observer) async {
        guard let cache, let tiles = try? elevationTiles(observer: observer, maxDistanceM: Self.maxDistanceM) else {
            status = .missing
            return
        }
        let coarseLevel = cache.coarseLevel
        let source = cache.source
        let names = Set(tiles.map(DemCache.name))
        // Free the grids of the old place, and the full grids of the tiles that are not near now.
        grids = grids.filter { name, value in
            tiles.contains { DemCache.name($0) == name && (value.level == coarseLevel || source.isNear($0)) }
        }
        for tile in tiles { grids[DemCache.name(tile)]?.distanceM = tile.distanceM }

        // Step 1: the coarse check.
        let coarse = tiles.filter { grids[DemCache.name($0)] == nil }
        let first = await fetch(coarse, full: false, keep: names, cache: cache)
        if Task.isCancelled { return }
        var missing = first.missing
        let sea = first.sea
        let ownTileIsSea = tiles.contains { $0.distanceM == 0 && sea.contains(DemCache.name($0)) }
        let coarseProfile = await publishProfile(observer, ownTileIsSea: ownTileIsSea, options: source.horizonOptions)
        if Task.isCancelled { return }

        // Step 2: the near tiles at full resolution, if the terrain is high.
        if Self.needsFullResolution(coarseProfile) {
            let near = tiles.filter {
                let name = DemCache.name($0)
                return source.isNear($0) && grids[name]?.level != source.fullLevel && !sea.contains(name)
            }
            if !near.isEmpty {
                let second = await fetch(near, full: true, keep: names, cache: cache)
                if Task.isCancelled { return }
                missing = missing || second.missing
                _ = await publishProfile(observer, ownTileIsSea: ownTileIsSea, options: source.horizonOptions)
                if Task.isCancelled { return }
            }
        }
        status = missing ? .missing : .ready
        if missing { scheduleRetry(observer) }
    }

    /// Loads the place again after `retryInterval`, for the tiles that failed. A new place or the
    /// setting off cancels it.
    private func scheduleRetry(_ observer: Observer) {
        retry = Task { [weak self, retryInterval] in
            try? await Task.sleep(nanoseconds: UInt64(retryInterval * 1_000_000_000))
            guard let self, !Task.isCancelled, self.observer == observer else { return }
            self.job = Task { await self.load(observer) }
        }
    }

    /// Loads tiles from the cache, `parallelDownloads` at the same time, and keeps their grids.
    /// It shows the number of tiles that are not in the cache in `status`.
    private func fetch(
        _ tiles: [ElevationTile], full: Bool, keep: Set<String>, cache: DemCache
    ) async -> (missing: Bool, sea: Set<String>) {
        let level = full ? cache.source.fullLevel : cache.coarseLevel
        var remaining = 0
        for tile in tiles where await !(full ? cache.contains(tile) : cache.containsCoarse(tile)) { remaining += 1 }
        // A newer place can start during the count. Its status stays.
        if Task.isCancelled { return (false, []) }
        status = remaining > 0 ? .loading(remaining: remaining) : .ready
        var missing = false
        var sea = Set<String>()
        await withTaskGroup(of: (tile: ElevationTile, result: DemTile, downloaded: Bool).self) { group in
            var next = 0
            func add() {
                guard next < tiles.count else { return }
                let tile = tiles[next]
                next += 1
                group.addTask {
                    let downloaded = await !(full ? cache.contains(tile) : cache.containsCoarse(tile))
                    let result =
                        full
                        ? await cache.tile(tile, level: level, keep: keep) : await cache.coarseTile(tile, keep: keep)
                    return (tile, result, downloaded)
                }
            }
            for _ in 0..<Self.parallelDownloads { add() }
            while let done = await group.next() {
                if Task.isCancelled {
                    group.cancelAll()
                    return
                }
                if done.downloaded { remaining -= 1 }
                let name = DemCache.name(done.tile)
                switch done.result {
                case .grid(let grid): grids[name] = (level, done.tile.distanceM, grid)
                case .sea: sea.insert(name)
                case .unavailable: missing = true
                }
                if remaining > 0 { status = .loading(remaining: remaining) }
                add()
            }
        }
        return (missing, sea)
    }

    /// Calculates the profile from the grids in memory and shows it. Returns the profile.
    private func publishProfile(_ observer: Observer, ownTileIsSea: Bool, options: HorizonOptions) async -> HorizonProfile? {
        let input = grids.values.map { (distanceM: $0.distanceM, grid: $0.grid) }
        guard
            let result = await Self.calculateProfile(
                observer: observer, grids: input, ownTileIsSea: ownTileIsSea, options: options)
        else {
            return nil
        }
        if self.observer == observer && !Task.isCancelled {
            profile = result
            profileObserver = observer
            lines = Self.lines(result)
            revision += 1
        }
        return result
    }

    private func clearProfile() {
        profile = nil
        profileObserver = nil
        lines = []
    }

    /// The parts of the profile with data, as lines from azimuth 0 to 360. A part needs at least two
    /// points.
    nonisolated static func lines(_ profile: HorizonProfile) -> [[Horizontal]] {
        let count = profile.altitudeDeg.count
        var result: [[Horizontal]] = []
        var current: [Horizontal] = []
        for i in 0...count where count > 0 {
            let altitude = profile.altitudeDeg[i % count]
            if altitude.isFinite {
                current.append(Horizontal(azimuthDeg: Double(i) * profile.azimuthStepDeg, altitudeDeg: altitude))
            } else {
                if current.count > 1 { result.append(current) }
                current = []
            }
        }
        if current.count > 1 { result.append(current) }
        return result
    }
}
