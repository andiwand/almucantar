import Almucantar
import Foundation

/// A planet at the selected time.
struct PlanetAt: Sendable {
    let planet: Planet
    let name: String
    let color: RGB
    let direction: Horizontal
    let mag: Double
}

/// The sky at one time and place: the directions of all stars, of the names of the constellations,
/// and of the points of the Milky Way, the planets, the sun, the moon, and the center of the Milky
/// Way. It takes a few milliseconds, so the screen makes it once per second; a frame only projects
/// it.
struct SkyAt: Sendable {
    let unixUtc: Double
    let observer: Observer
    let stars: [Horizontal]
    let labels: [Horizontal]
    let dust: [Horizontal]
    let planets: [PlanetAt]
    let sun: Horizontal
    let moon: MoonAt
    let galacticCenter: Horizontal

    static func compute(data: StarData, milkyWay: MilkyWay, unixUtc: Double, observer: Observer)
        -> SkyAt?
    {
        do {
            return SkyAt(
                unixUtc: unixUtc, observer: observer,
                stars: try starPositions(data.catalog, unixUtc: unixUtc, observer: observer),
                labels: try starPositions(data.labels, unixUtc: unixUtc, observer: observer),
                dust: try starPositions(milkyWay.catalog, unixUtc: unixUtc, observer: observer),
                planets: try planetStyles.map {
                    PlanetAt(
                        planet: $0.planet, name: $0.name, color: $0.color,
                        direction: try planetPosition($0.planet, unixUtc: unixUtc, observer: observer),
                        mag: try planetMagnitude($0.planet, unixUtc: unixUtc))
                },
                sun: try sunPosition(unixUtc, observer: observer),
                moon: MoonAt(
                    direction: try moonPosition(unixUtc, observer: observer), fraction: try moonIlluminatedFraction(unixUtc)),
                galacticCenter: try galacticPosition(
                    longitudeDeg: galacticCenterLongitudeDeg, latitudeDeg: galacticCenterLatitudeDeg, unixUtc: unixUtc,
                    observer: observer))
        } catch {
            return nil
        }
    }
}

/// Keeps the last sky, because the body runs for each frame of the motion sensor.
@MainActor
final class SkyCache {
    private var key: (Int, Observer)?
    private var sky: SkyAt?

    func sky(data: StarData, milkyWay: MilkyWay, unixUtc: Double, observer: Observer) -> SkyAt? {
        let second = Int(unixUtc.rounded(.down))
        if let key, let sky, key.0 == second, key.1 == observer { return sky }
        sky = SkyAt.compute(data: data, milkyWay: milkyWay, unixUtc: Double(second), observer: observer)
        key = (second, observer)
        return sky
    }
}

/// An object that the user can tap, find, or align with.
enum Target: Hashable, Sendable {
    case star(Int)
    case planet(Planet)
    case moon
    case sun
    case constellation(Int)
    case galacticCenter

    func name(_ data: StarData) -> String {
        switch self {
        case .star(let i): data.names[i] ?? "Star"
        case .planet(let p): planetStyles.first { $0.planet == p }!.name
        case .moon: "Moon"
        case .sun: "Sun"
        case .constellation(let i): data.constellations[i].name
        case .galacticCenter: "Center of the Milky Way"
        }
    }

    func direction(_ sky: SkyAt) -> Horizontal {
        switch self {
        case .star(let i): sky.stars[i]
        case .planet(let p): sky.planets.first { $0.planet == p }!.direction
        case .moon: sky.moon.direction
        case .sun: sky.sun
        case .constellation(let i): sky.labels[i]
        case .galacticCenter: sky.galacticCenter
        }
    }

    /// The direction of the target at another time, for its rise and set.
    func direction(_ data: StarData, unixUtc: Double, observer: Observer) throws -> Horizontal {
        switch self {
        case .star(let i):
            let c = data.catalog
            let one = StarCatalog(
                raDeg: [c.raDeg[i]], decDeg: [c.decDeg[i]], pmRaArcsecPerYear: [c.pmRaArcsecPerYear[i]],
                pmDecArcsecPerYear: [c.pmDecArcsecPerYear[i]])
            return try starPositions(one, unixUtc: unixUtc, observer: observer)[0]
        case .planet(let p): return try planetPosition(p, unixUtc: unixUtc, observer: observer)
        case .moon: return try moonPosition(unixUtc, observer: observer)
        case .sun: return try sunPosition(unixUtc, observer: observer)
        case .constellation(let i):
            let c = data.constellations[i]
            return try starPositions(StarCatalog(raDeg: [c.labelRaDeg], decDeg: [c.labelDecDeg]), unixUtc: unixUtc, observer: observer)[0]
        case .galacticCenter:
            return try galacticPosition(
                longitudeDeg: galacticCenterLongitudeDeg, latitudeDeg: galacticCenterLatitudeDeg, unixUtc: unixUtc, observer: observer)
        }
    }

    /// The next rise and set in the 24 hours after `unixUtc`, over the terrain if there is a
    /// `profile`: samples every 10 minutes, and a linear interpolation between them (about a minute).
    func riseAndSet(_ data: StarData, unixUtc: Double, observer: Observer, profile: HorizonProfile?) throws -> RiseSet {
        let step = 600.0
        var rise: Double?
        var set: Double?
        var up = 0
        var previous: (time: Double, height: Double)? = nil
        for k in 0...144 {
            let t = unixUtc + Double(k) * step
            let d = try direction(data, unixUtc: t, observer: observer)
            let height = d.altitudeDeg - horizonAt(profile, azimuthDeg: d.azimuthDeg)
            if height > 0 { up += 1 }
            if let previous, (previous.height > 0) != (height > 0) {
                let crossing = previous.time + step * previous.height / (previous.height - height)
                if height > 0 && rise == nil { rise = crossing }
                if height <= 0 && set == nil { set = crossing }
            }
            previous = (t, height)
        }
        return RiseSet(rise: rise, set: set, always: up == 145 ? true : up == 0 ? false : nil)
    }
}

/// The next rise and set in 24 hours; `always` is true (up), false (down), or nil (both).
struct RiseSet: Equatable, Sendable {
    let rise: Double?
    let set: Double?
    let always: Bool?

    /// The text of the info card, with the times in `timeZone`, the earlier event first.
    func text(_ timeZone: TimeZone, overTerrain: Bool) -> String {
        let over = overTerrain ? " over the terrain" : ""
        // Optional patterns, because Swift 6.1 (Xcode 16.4 in CI) does not see `true`, `false`,
        // and `nil` as all the cases of `Bool?`.
        switch always {
        case .some(true): return "Up all day" + over
        case .some(false): return "Below the horizon all day" + over
        case .none:
            let parts = [rise.map { ($0, "rises " + PlaceTime.time($0, timeZone)) }, set.map { ($0, "sets " + PlaceTime.time($0, timeZone)) }]
                .compactMap { $0 }.sorted { $0.0 < $1.0 }.map(\.1).joined(separator: " · ")
            return parts.prefix(1).uppercased() + String(parts.dropFirst()) + over
        }
    }
}

/// The altitude of the visible horizon in a direction: the terrain, or 0°. It has the linear
/// interpolation of `terrainAltitudeDeg()` of the core, but in Swift: a frame asks for each star,
/// and the core would convert the whole profile for each call.
func horizonAt(_ profile: HorizonProfile?, azimuthDeg: Double) -> Double {
    guard let profile, !profile.altitudeDeg.isEmpty, profile.azimuthStepDeg > 0, azimuthDeg.isFinite else { return 0 }
    let a = profile.altitudeDeg
    var wrapped = azimuthDeg.truncatingRemainder(dividingBy: 360)
    if wrapped < 0 { wrapped += 360 }
    let position = wrapped / profile.azimuthStepDeg
    let i = min(Int(position), a.count - 1)
    let t = position - Double(i)
    let value = a[i] + t * (a[(i + 1) % a.count] - a[i])
    return value.isNaN ? 0 : value
}

extension Target {
    /// True for the sun, the moon, and the planets: the objects with a path among the stars.
    var hasPath: Bool {
        switch self {
        case .sun, .moon, .planet: true
        case .star, .constellation, .galacticCenter: false
        }
    }

    /// The samples of a path: every 10 minutes, from 12 hours before the time to 12 hours after it.
    static let pathStepS = 600.0
    static let pathHalfSpanS = 12 * 3600.0

    /// The path of the target in the 24 hours around `unixUtc`: see `BodyPath`.
    func path(_ data: StarData, unixUtc: Double, observer: Observer, profile: HorizonProfile?, timeZone: TimeZone) throws
        -> BodyPath
    {
        let start = unixUtc - Self.pathHalfSpanS
        let end = unixUtc + Self.pathHalfSpanS
        let count = Int(2 * Self.pathHalfSpanS / Self.pathStepS)
        let samples = try (0...count).map { try direction(data, unixUtc: start + Double($0) * Self.pathStepS, observer: observer) }
        let (visible, hidden) = splitAtHorizon(samples, profile: profile)
        // The full hours of the place. The calendar truncates to the hour of the place, so this also
        // works in a time zone with an offset of 30 or 45 minutes.
        let calendar = PlaceTime.calendar(timeZone)
        let first = calendar.dateInterval(of: .hour, for: Date(timeIntervalSince1970: start))!.start
        var hour = first.timeIntervalSince1970 + 3600
        var hours: [PathHour] = []
        while hour < end {
            let d = try direction(data, unixUtc: hour, observer: observer)
            let label = String(format: "%02d", calendar.component(.hour, from: Date(timeIntervalSince1970: hour)))
            hours.append(
                PathHour(direction: d, label: label, hidden: d.altitudeDeg < horizonAt(profile, azimuthDeg: d.azimuthDeg)))
            hour += 3600
        }
        return BodyPath(target: self, visible: visible, hidden: hidden, hours: hours)
    }
}

/// A full hour on a path: the direction, the hour ("21"), and true if the terrain hides it.
struct PathHour: Sendable {
    let direction: Horizontal
    let label: String
    let hidden: Bool
}

/// The path of a body in the 24 hours around a time: the parts over the horizon or the terrain
/// (`visible`), the parts below it (`hidden`), and the full hours on the time of the place. The
/// screen calculates it in the background and only projects it in a frame.
struct BodyPath: Sendable {
    let target: Target
    let visible: [[Horizontal]]
    let hidden: [[Horizontal]]
    let hours: [PathHour]
}

/// Splits a line of directions into the parts over the visible horizon (`visible`) and the parts
/// below it (`hidden`). The two parts meet at the crossing, which is interpolated between the
/// samples, so the line has no gap.
func splitAtHorizon(_ line: [Horizontal], profile: HorizonProfile?) -> (visible: [[Horizontal]], hidden: [[Horizontal]]) {
    var visible: [[Horizontal]] = []
    var hidden: [[Horizontal]] = []
    var part: [Horizontal] = []
    var up: Bool?
    var previous: (direction: Horizontal, height: Double)?
    for d in line {
        let height = d.altitudeDeg - horizonAt(profile, azimuthDeg: d.azimuthDeg)
        let isUp = height > 0
        if let wasUp = up, isUp != wasUp, let (p, previousHeight) = previous {
            let t = previousHeight / (previousHeight - height)
            // The shortest turn of the azimuth, also over north.
            let turn = (d.azimuthDeg - p.azimuthDeg + 540).truncatingRemainder(dividingBy: 360) - 180
            var azimuth = (p.azimuthDeg + t * turn).truncatingRemainder(dividingBy: 360)
            if azimuth < 0 { azimuth += 360 }
            let crossing = Horizontal(azimuthDeg: azimuth, altitudeDeg: p.altitudeDeg + t * (d.altitudeDeg - p.altitudeDeg))
            part.append(crossing)
            if wasUp { visible.append(part) } else { hidden.append(part) }
            part = [crossing]
        }
        part.append(d)
        up = isUp
        previous = (d, height)
    }
    if let up, part.count > 1 {
        if up { visible.append(part) } else { hidden.append(part) }
    }
    return (visible, hidden)
}
