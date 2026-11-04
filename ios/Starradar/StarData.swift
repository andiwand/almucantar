import Almucantar
import Foundation
import SwiftUI

/// The star data of starradar (`assets/stars/stars.json`, made by `scripts/stars/build_stars.py`):
/// the stars as columns for the core, their names, and the constellations. The stars are sorted by
/// magnitude, the brightest first. The Android app and the web app read the same file.
struct StarData: Sendable {
    let catalog: StarCatalog
    let mag: [Double]
    let bv: [Double]
    /// The proper name of a star, by its index.
    let names: [Int: String]
    let constellations: [Constellation]
    let attribution: String
    /// The places of the names of the constellations as a small catalogue, so the core moves them too.
    let labels: StarCatalog

    var count: Int { mag.count }

    /// The index of the star with this name, or nil.
    func index(of name: String) -> Int? { names.first { $0.value == name }?.key }

    struct DecodeError: Error, CustomStringConvertible {
        let description: String
    }

    /// The JSON of stars.json, version 1.
    private struct File: Decodable {
        struct Stars: Decodable {
            let ra, dec, pm_ra, pm_dec, mag, bv: [Double]
        }
        struct Entry: Decodable {
            let index: Int
            let name: String
            init(from decoder: Decoder) throws {
                var c = try decoder.unkeyedContainer()
                index = try c.decode(Int.self)
                name = try c.decode(String.self)
            }
        }
        struct Item: Decodable {
            let id: String
            let name: String
            let label: [Double]
            let lines: [[Int]]
        }
        let version: Int
        let stars: Stars?
        let names: [Entry]?
        let constellations: [Item]?
        let attribution: String?
    }

    /// Decodes stars.json, version 1.
    static func decode(_ data: Data) throws -> StarData {
        let file = try JSONDecoder().decode(File.self, from: data)
        guard file.version == 1 else { throw DecodeError(description: "stars.json has the unknown version \(file.version)") }
        guard let stars = file.stars, let names = file.names, let constellations = file.constellations,
            let attribution = file.attribution
        else { throw DecodeError(description: "stars.json is not complete") }
        let n = stars.ra.count
        guard [stars.dec, stars.pm_ra, stars.pm_dec, stars.mag, stars.bv].allSatisfy({ $0.count == n }) else {
            throw DecodeError(description: "the columns of stars.json do not have the same length")
        }
        guard constellations.allSatisfy({ $0.label.count == 2 }) else {
            throw DecodeError(description: "a constellation has no place for its name")
        }
        let items = constellations.map {
            Constellation(id: $0.id, name: $0.name, labelRaDeg: $0.label[0], labelDecDeg: $0.label[1], lines: $0.lines)
        }
        guard items.allSatisfy({ $0.lines.allSatisfy { $0.allSatisfy { (0..<n).contains($0) } } }) else {
            throw DecodeError(description: "a constellation line has a star that is not in stars.json")
        }
        return StarData(
            catalog: StarCatalog(raDeg: stars.ra, decDeg: stars.dec, pmRaArcsecPerYear: stars.pm_ra, pmDecArcsecPerYear: stars.pm_dec),
            mag: stars.mag, bv: stars.bv,
            names: Dictionary(names.map { ($0.index, $0.name) }, uniquingKeysWith: { a, _ in a }),
            constellations: items,
            attribution: attribution,
            labels: StarCatalog(raDeg: items.map(\.labelRaDeg), decDeg: items.map(\.labelDecDeg)))
    }
}

struct Constellation: Sendable {
    /// The IAU abbreviation, for example "Ori".
    let id: String
    /// The IAU name in Latin, for example "Orion".
    let name: String
    /// Where the name goes: right ascension and declination (J2000), in degrees.
    let labelRaDeg: Double
    let labelDecDeg: Double
    /// The lines: each line is a list of indices of stars.
    let lines: [[Int]]
}

/// A color as red, green, and blue from 0 to 255, so the tests can compare it.
struct RGB: Equatable, Sendable {
    var r, g, b: Double
    var color: Color { Color(red: r / 255, green: g / 255, blue: b / 255) }
}

/// The planets with their names and colors, the same as in the web app and the Android app.
let planetStyles: [(planet: Planet, name: String, color: RGB)] = [
    (.mercury, "Mercury", RGB(r: 255, g: 236, b: 210)),
    (.venus, "Venus", RGB(r: 255, g: 252, b: 236)),
    (.mars, "Mars", RGB(r: 255, g: 170, b: 120)),
    (.jupiter, "Jupiter", RGB(r: 255, g: 240, b: 214)),
    (.saturn, "Saturn", RGB(r: 255, g: 230, b: 170)),
    (.uranus, "Uranus", RGB(r: 200, g: 240, b: 255)),
    (.neptune, "Neptune", RGB(r: 170, g: 200, b: 255)),
]

/// How a star looks: the same rules as `web/shared/src/stars.ts` and the Android app.
enum StarLook {
    /// The faintest magnitude that the eye sees, from the altitude of the sun: about 6 in the
    /// astronomical night, and only the brightest stars and planets in the twilight.
    static func limitingMagnitude(_ sunAltitudeDeg: Double) -> Double {
        if sunAltitudeDeg >= 0 { return -2 }
        if sunAltitudeDeg >= -6 { return -1 + (sunAltitudeDeg / -6) * 2 }
        if sunAltitudeDeg >= -12 { return 1 + ((sunAltitudeDeg + 6) / -6) * 2.5 }
        if sunAltitudeDeg >= -18 { return 3.5 + ((sunAltitudeDeg + 12) / -6) * 2.5 }
        return 6
    }

    /// The radius of a star on the screen in points: a brighter star is larger.
    static func radius(_ mag: Double) -> Double { min(4.5, max(0.6, 3.2 - 0.45 * mag)) }

    private static let stops: [(Double, RGB)] = [
        (-0.4, RGB(r: 170, g: 191, b: 255)),
        (0.0, RGB(r: 202, g: 215, b: 255)),
        (0.3, RGB(r: 248, g: 247, b: 255)),
        (0.65, RGB(r: 255, g: 244, b: 234)),
        (1.0, RGB(r: 255, g: 222, b: 180)),
        (1.5, RGB(r: 255, g: 196, b: 140)),
        (2.0, RGB(r: 255, g: 170, b: 110)),
    ]

    /// The color of a star from its B-V color index, pale as the eye sees it.
    static func color(_ bv: Double) -> RGB {
        let x = min(stops.last!.0, max(stops.first!.0, bv))
        for i in 1..<stops.count where x <= stops[i].0 {
            let (x0, c0) = stops[i - 1]
            let (x1, c1) = stops[i]
            let t = (x - x0) / (x1 - x0)
            return RGB(r: c0.r + (c1.r - c0.r) * t, g: c0.g + (c1.g - c0.g) * t, b: c0.b + (c1.b - c0.b) * t)
        }
        return stops.last!.1
    }

    /// The phase of the twilight from the altitude of the sun.
    static func darkness(_ sunAltitudeDeg: Double) -> String {
        if sunAltitudeDeg >= 0 { return "Day" }
        if sunAltitudeDeg >= -6 { return "Civil twilight" }
        if sunAltitudeDeg >= -12 { return "Nautical twilight" }
        if sunAltitudeDeg >= -18 { return "Astronomical twilight" }
        return "Night"
    }
}
