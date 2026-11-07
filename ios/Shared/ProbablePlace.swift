import Foundation
import Almucantar

/// The most probable place without a location and without a last place: the main city of the time
/// zone of the device, else the main city of the country of the locale, else the meridian of the
/// offset of the time zone at the equator. The time zone comes first, because it tells where the
/// device is; the locale tells only the region that the user chose. The data is the IANA
/// `zone.tab` of `assets/tzdata/`, the same file as in the Android apps and the PWAs.
struct ProbablePlace: Equatable {
    var observer: Observer
    /// The main city, or nil if only the offset is known.
    var city: String?

    /// A line of zone.tab: the country code, the coordinates (ISO 6709), and the time zone.
    struct ZoneLine: Equatable {
        var country: String
        var coordinates: String
        var zone: String
    }

    /// The lines of the zone.tab in `bundle`, or none if the bundle does not have it.
    static func bundledLines(in bundle: Bundle = .main) -> [ZoneLine] {
        guard let url = bundle.url(forResource: "zone", withExtension: "tab"),
            let text = try? String(contentsOf: url, encoding: .utf8)
        else { return [] }
        return parse(text)
    }

    static func parse(_ text: String) -> [ZoneLine] {
        text.split(separator: "\n").compactMap { line in
            if line.hasPrefix("#") { return nil }
            let columns = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard columns.count >= 3 else { return nil }
            return ZoneLine(country: String(columns[0]), coordinates: String(columns[1]), zone: String(columns[2]))
        }
    }

    static func find(
        lines: [ZoneLine], timeZone: TimeZone = .current, country: String? = Locale.current.region?.identifier,
        now: Date = Date()
    ) -> ProbablePlace {
        if let line = lines.first(where: { $0.zone == timeZone.identifier }), let place = place(of: line) {
            return place
        }
        let offset = timeZone.secondsFromGMT(for: now)
        if let country, !country.isEmpty {
            let inCountry = lines.filter { $0.country.caseInsensitiveCompare(country) == .orderedSame }
            let sameOffset = inCountry.first { TimeZone(identifier: $0.zone)?.secondsFromGMT(for: now) == offset }
            if let line = sameOffset ?? inCountry.first, let place = place(of: line) { return place }
        }
        let longitude = min(180, max(-180, Double(offset) / 240))
        return ProbablePlace(observer: Observer(latitudeDeg: 0, longitudeDeg: longitude), city: nil)
    }

    private static func place(of line: ZoneLine) -> ProbablePlace? {
        guard let observer = parseIso6709(line.coordinates) else { return nil }
        let name = line.zone.split(separator: "/").last.map { $0.replacingOccurrences(of: "_", with: " ") }
        return ProbablePlace(observer: observer, city: name)
    }

    /// Parses "+4813+01620" or "+404251-0740023" (ISO 6709, degrees, minutes, seconds).
    static func parseIso6709(_ text: String) -> Observer? {
        let chars = Array(text)
        guard let split = chars.indices.dropFirst().first(where: { chars[$0] == "+" || chars[$0] == "-" }),
            let latitude = degrees(chars[..<split], degreeDigits: 2),
            let longitude = degrees(chars[split...], degreeDigits: 3)
        else { return nil }
        return Observer(latitudeDeg: latitude, longitudeDeg: longitude)
    }

    private static func degrees(_ part: ArraySlice<Character>, degreeDigits: Int) -> Double? {
        guard let first = part.first, first == "+" || first == "-" else { return nil }
        let digits = String(part.dropFirst())
        guard digits.count == degreeDigits + 2 || digits.count == degreeDigits + 4, digits.allSatisfy(\.isNumber)
        else { return nil }
        let d = Array(digits)
        func number(_ range: Range<Int>) -> Double { Double(String(d[range]))! }
        let seconds = d.count == degreeDigits + 4 ? number(degreeDigits + 2..<degreeDigits + 4) : 0
        let value = number(0..<degreeDigits) + number(degreeDigits..<degreeDigits + 2) / 60 + seconds / 3600
        return first == "-" ? -value : value
    }
}
