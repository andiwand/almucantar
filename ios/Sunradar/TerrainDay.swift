import Foundation
import Almucantar

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
