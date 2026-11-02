import Foundation
import Sunradar

/// The terrain horizon of the current place: it loads the DEM tiles from the DemCache and
/// calculates the horizon profile off the main thread. The profile is calculated again when the
/// place moves more than `moveM`. Until the profile is ready, the app shows the 0° horizon.
///
/// The terrain loads in two steps (PLAN.md 14.3), so a flat place downloads only about 3 MB:
///
/// 1. The coarse check: all tiles at the coarse level of the cache (the prefix files, about
///    370 m). The app shows this profile at once.
/// 2. If the coarse profile reaches `fullTerrainMinDeg`, the near tiles (`fullResolutionM`) at
///    full resolution, and the profile again.
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
    /// The tiles nearer than this are read at full resolution if the terrain is high. Farther
    /// away, the coarse level is sufficient, because a sample of 370 m is a small angle there.
    nonisolated static let fullResolutionM = 25_000.0
    nonisolated static let maxDistanceM = 100_000.0
    /// A smaller move of the place does not change the profile.
    static let moveM = 500.0
    /// The number of downloads at the same time.
    nonisolated static let parallelDownloads = 3

    private let cache: DemCache?
    private var observer: Observer?
    private var job: Task<Void, Never>?
    /// The grids in memory, by the name of the tile, with their level.
    private var grids: [String: (level: Int, distanceM: Double, grid: ElevationGrid)] = [:]

    init(cache: DemCache? = DemCache.shared) {
        self.cache = cache
    }

    /// Starts the work for a new place, if the place moved more than `moveM`. If `enabled` is
    /// false, it stops the work and removes the profile.
    func update(observer: Observer, enabled: Bool) {
        guard enabled else {
            job?.cancel()
            job = nil
            self.observer = nil
            grids = [:]
            profile = nil
            lines = []
            status = .off
            return
        }
        if let current = self.observer, Geo.distanceM(from: current, to: observer) <= Self.moveM { return }
        self.observer = observer
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

    /// True if the tile is near enough for full resolution.
    nonisolated static func isNear(_ tile: ElevationTile) -> Bool { tile.distanceM < fullResolutionM }

    /// Calculates the profile off the main thread. Nil without the tile at the observer (the
    /// distance 0), because the profile needs the ground there.
    nonisolated static func calculateProfile(
        observer: Observer, grids: [(distanceM: Double, grid: ElevationGrid)]
    ) async -> HorizonProfile? {
        guard grids.contains(where: { $0.distanceM == 0 }) else { return nil }
        // The core takes the first tile with data at a place, so the order of tiles with the same
        // cells changes the profile a little at the edges. A fixed order gives the same profile
        // for the same grids.
        let tiles = grids.map(\.grid).sorted {
            ($0.northLatitudeDeg, $0.westLongitudeDeg) < ($1.northLatitudeDeg, $1.westLongitudeDeg)
        }
        return await Task.detached(priority: .userInitiated) {
            try? horizonProfile(observer: observer, tiles: tiles)
        }.value
    }

    private func load(_ observer: Observer) async {
        guard let cache, let tiles = try? elevationTiles(observer: observer, maxDistanceM: Self.maxDistanceM) else {
            status = .missing
            return
        }
        let coarseLevel = cache.coarseLevel
        let names = Set(tiles.map(DemCache.name))
        // Free the grids of the old place, and the full grids of the tiles that are not near now.
        grids = grids.filter { name, value in
            tiles.contains { DemCache.name($0) == name && (value.level == coarseLevel || Self.isNear($0)) }
        }
        for tile in tiles { grids[DemCache.name(tile)]?.distanceM = tile.distanceM }

        // Step 1: the coarse check.
        let coarse = tiles.filter { grids[DemCache.name($0)] == nil }
        let first = await fetch(coarse, full: false, keep: names, cache: cache)
        if Task.isCancelled { return }
        var missing = first.missing
        let sea = first.sea
        let coarseProfile = await publishProfile(observer)
        if Task.isCancelled { return }

        // Step 2: the near tiles at full resolution, if the terrain is high.
        if Self.needsFullResolution(coarseProfile) {
            let near = tiles.filter {
                let name = DemCache.name($0)
                return Self.isNear($0) && grids[name]?.level != 0 && !sea.contains(name)
            }
            if !near.isEmpty {
                let second = await fetch(near, full: true, keep: names, cache: cache)
                if Task.isCancelled { return }
                missing = missing || second.missing
                _ = await publishProfile(observer)
                if Task.isCancelled { return }
            }
        }
        status = missing ? .missing : .ready
    }

    /// Loads tiles from the cache, `parallelDownloads` at the same time, and keeps their grids.
    /// It shows the number of tiles that are not in the cache in `status`.
    private func fetch(
        _ tiles: [ElevationTile], full: Bool, keep: Set<String>, cache: DemCache
    ) async -> (missing: Bool, sea: Set<String>) {
        let level = full ? 0 : cache.coarseLevel
        var remaining = 0
        for tile in tiles where await !(full ? cache.contains(tile) : cache.containsCoarse(tile)) { remaining += 1 }
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
                        ? await cache.tile(tile, level: 0, keep: keep) : await cache.coarseTile(tile, keep: keep)
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
    private func publishProfile(_ observer: Observer) async -> HorizonProfile? {
        let input = grids.values.map { (distanceM: $0.distanceM, grid: $0.grid) }
        guard let result = await Self.calculateProfile(observer: observer, grids: input) else { return nil }
        if self.observer == observer && !Task.isCancelled {
            profile = result
            lines = Self.lines(result)
            revision += 1
        }
        return result
    }

    /// The parts of the profile with data, as lines from azimuth 0 to 360.
    nonisolated static func lines(_ profile: HorizonProfile) -> [[Horizontal]] {
        let count = profile.altitudeDeg.count
        var result: [[Horizontal]] = []
        var current: [Horizontal] = []
        for i in 0...count where count > 0 {
            let altitude = profile.altitudeDeg[i % count]
            if altitude.isFinite {
                current.append(Horizontal(azimuthDeg: Double(i) * profile.azimuthStepDeg, altitudeDeg: altitude))
            } else if !current.isEmpty {
                result.append(current)
                current = []
            }
        }
        if current.count > 1 { result.append(current) }
        return result
    }
}

/// What the sky view derives from the terrain profile for one day: the parts of the day path
/// in front of and behind the terrain, and the visible sunrise and sunset.
struct TerrainDay {
    /// The parts of the path above 0° that are not behind the terrain.
    let visible: [[Horizontal]]
    /// The parts of the path above 0° that are behind the terrain.
    let hidden: [[Horizontal]]
    /// The visible sunrise and sunset, for the status panel.
    let summary: String

    init(day: SkyDay, observer: Observer, profile: HorizonProfile) {
        var visible: [[Horizontal]] = []
        var hidden: [[Horizontal]] = []
        for segment in day.daySegments {
            var run: [Horizontal] = []
            var runHidden: Bool?
            for p in segment {
                let terrain = (try? terrainAltitudeDeg(profile, azimuthDeg: p.azimuthDeg)) ?? .nan
                let behind = terrain.isFinite && p.altitudeDeg < terrain
                if let h = runHidden, h != behind {
                    // The point joins both runs, so the drawing has no gap.
                    run.append(p)
                    if h { hidden.append(run) } else { visible.append(run) }
                    run = []
                }
                run.append(p)
                runHidden = behind
            }
            if run.count > 1, let h = runHidden {
                if h { hidden.append(run) } else { visible.append(run) }
            }
        }
        self.visible = visible
        self.hidden = hidden
        let events = try? terrainEvents(observer: observer, profile: profile, startUtc: day.start, endUtc: day.end)
        let time = { (t: Double) in PlaceTime.time(t, day.timeZone) }
        switch events?.kind {
        case nil: summary = ""
        case .polarDay: summary = "Above the terrain all day"
        case .polarNight: summary = "Behind the terrain all day"
        case .normal:
            let crossings = events?.crossings ?? []
            summary = [
                crossings.first(where: \.rising).map { "Visible sunrise " + time($0.unixUtc) },
                crossings.last(where: { !$0.rising }).map { "Visible sunset " + time($0.unixUtc) },
            ].compactMap { $0 }.joined(separator: " · ")
        }
    }
}

/// Keeps the last TerrainDay, because the body runs for each frame of the motion sensor.
@MainActor
final class TerrainDayCache {
    private var key: (start: Double, timeZone: TimeZone, observer: Observer, revision: Int)?
    private var value: TerrainDay?

    func day(_ day: SkyDay, observer: Observer, terrain: TerrainHorizon) -> TerrainDay? {
        guard let profile = terrain.profile else { return nil }
        if let key, let value, key.start == day.start, key.timeZone == day.timeZone, key.observer == observer, key.revision == terrain.revision {
            return value
        }
        let value = TerrainDay(day: day, observer: observer, profile: profile)
        (key, self.value) = ((day.start, day.timeZone, observer, terrain.revision), value)
        return value
    }
}
