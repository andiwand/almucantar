import Foundation
import Sunradar

/// The terrain horizon of the current place: it loads the DEM tiles from the DemCache and
/// calculates the horizon profile off the main thread. The profile is calculated again when the
/// place moves more than `moveM`, or when a new tile arrives. Until the profile is ready, the
/// app shows the 0° horizon.
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

    /// The tiles nearer than this are read at full resolution, the others at level 1.
    static let fullResolutionM = 25_000.0
    static let maxDistanceM = 100_000.0
    /// A smaller move of the place does not change the profile.
    static let moveM = 500.0
    /// The number of downloads at the same time.
    private static let parallelDownloads = 3

    private let cache: DemCache?
    private var observer: Observer?
    private var job: Task<Void, Never>?
    /// The grids in memory, by the name of the tile, with their level.
    private var grids: [String: (level: Int, distanceM: Double, grid: ElevationGrid)] = [:]
    private var calculating = false
    private var calculateAgain = false

    init(cache: DemCache? = try? DemCache.standard()) {
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

    private func load(_ observer: Observer) async {
        guard let cache, let tiles = try? elevationTiles(observer: observer, maxDistanceM: Self.maxDistanceM) else {
            status = .missing
            return
        }
        let wanted = tiles.map { (tile: $0, name: DemCache.name($0), level: $0.distanceM < Self.fullResolutionM ? 0 : 1) }
        let keep = Set(wanted.map(\.name))
        // Free the grids of the old place, and the grids at a different level.
        grids = grids.filter { name, value in wanted.contains { $0.name == name && $0.level == value.level } }
        for w in wanted { grids[w.name]?.distanceM = w.tile.distanceM }
        let todo = wanted.filter { grids[$0.name] == nil }
        var remaining = 0
        for w in todo where await !cache.contains(w.tile) { remaining += 1 }
        status = remaining > 0 ? .loading(remaining: remaining) : .ready
        calculate()

        var missing = false
        await withTaskGroup(of: (name: String, level: Int, distanceM: Double, result: DemTile, downloaded: Bool).self) { group in
            var next = 0
            func add() {
                guard next < todo.count else { return }
                let w = todo[next]
                next += 1
                group.addTask {
                    let downloaded = await !cache.contains(w.tile)
                    let result = await cache.tile(w.tile, level: w.level, keep: keep)
                    return (w.name, w.level, w.tile.distanceM, result, downloaded)
                }
            }
            for _ in 0..<Self.parallelDownloads { add() }
            while let done = await group.next() {
                if Task.isCancelled {
                    group.cancelAll()
                    return
                }
                if done.downloaded { remaining -= 1 }
                switch done.result {
                case .grid(let grid):
                    grids[done.name] = (done.level, done.distanceM, grid)
                    calculate()
                case .sea: break
                case .unavailable: missing = true
                }
                if remaining > 0 { status = .loading(remaining: remaining) }
                add()
            }
        }
        if Task.isCancelled { return }
        status = missing ? .missing : .ready
    }

    /// Calculates the profile off the main thread. A request during a calculation runs after it.
    private func calculate() {
        guard let observer else { return }
        // The profile needs the ground at the observer: the tile at the distance 0.
        guard grids.values.contains(where: { $0.distanceM == 0 }) else { return }
        if calculating {
            calculateAgain = true
            return
        }
        calculating = true
        let tiles = grids.values.map(\.grid)
        Task {
            let result = await Task.detached(priority: .userInitiated) {
                try? horizonProfile(observer: observer, tiles: tiles)
            }.value
            calculating = false
            if let result, self.observer == observer {
                profile = result
                lines = Self.lines(result)
                revision += 1
            }
            if calculateAgain {
                calculateAgain = false
                calculate()
            }
        }
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
        let time = { (t: Double) in Date(timeIntervalSince1970: t).formatted(date: .omitted, time: .shortened) }
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
    private var key: (start: Double, observer: Observer, revision: Int)?
    private var value: TerrainDay?

    func day(_ day: SkyDay, observer: Observer, terrain: TerrainHorizon) -> TerrainDay? {
        guard let profile = terrain.profile else { return nil }
        if let key, let value, key.start == day.start, key.observer == observer, key.revision == terrain.revision {
            return value
        }
        let value = TerrainDay(day: day, observer: observer, profile: profile)
        (key, self.value) = ((day.start, observer, terrain.revision), value)
        return value
    }
}
