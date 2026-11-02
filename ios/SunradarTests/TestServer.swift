import Foundation
import Sunradar

@testable import SunradarApp

/// A fake server of the tiles, like S3: it answers Range requests with 206, If-None-Match with
/// 304, and a missing file with 404. It records the requests.
final class FakeServer: HTTPClient, @unchecked Sendable {
    private struct File {
        var data: Data
        var etag: String
    }

    private let lock = NSLock()
    private var files: [URL: File] = [:]
    private var _requests: [(url: URL, headers: [String: String])] = []
    private var _online = true
    private var _ignoresRange = false

    var requests: [(url: URL, headers: [String: String])] { lock.withLock { _requests } }
    /// False: each request fails as without a network.
    var online: Bool {
        get { lock.withLock { _online } }
        set { lock.withLock { _online = newValue } }
    }
    /// True: the server sends the whole file (200) for a Range request.
    var ignoresRange: Bool {
        get { lock.withLock { _ignoresRange } }
        set { lock.withLock { _ignoresRange = newValue } }
    }

    func put(_ tile: ElevationTile, _ data: Data, etag: String = "\"a\"") {
        lock.withLock { files[DemCache.url(tile)] = File(data: data, etag: etag) }
    }

    func clearRequests() { lock.withLock { _requests = [] } }

    /// The requests for the tile without a Range header: the downloads of the full file.
    func fullRequests(_ tile: ElevationTile) -> Int {
        requests.filter { $0.url == DemCache.url(tile) && $0.headers["Range"] == nil }.count
    }

    /// The names of the tiles that had a request without a Range header.
    var fullRequestNames: Set<String> {
        Set(
            requests.filter { $0.headers["Range"] == nil }.map {
                $0.url.lastPathComponent.replacingOccurrences(of: "Copernicus_DSM_COG_30_", with: "")
                    .replacingOccurrences(of: "_DEM.tif", with: "")
            })
    }

    func get(_ url: URL, headers: [String: String]) async throws -> HTTPResponse {
        let (file, online, ignoresRange) = lock.withLock {
            _requests.append((url, headers))
            return (files[url], _online, _ignoresRange)
        }
        guard online else { throw URLError(.notConnectedToInternet) }
        let lastModified = "Tue, 01 Jan 2030 00:00:00 GMT"
        guard let file else { return HTTPResponse(status: 404, body: Data()) }
        if headers["If-None-Match"] == file.etag {
            return HTTPResponse(status: 304, body: Data(), etag: file.etag, lastModified: lastModified)
        }
        if !ignoresRange, let range = headers["Range"], range.hasPrefix("bytes=") {
            let parts = range.dropFirst(6).split(separator: "-").compactMap { Int($0) }
            let start = parts[0]
            let end = min(parts[1] + 1, file.data.count)
            let body = start < end ? file.data.subdata(in: start..<end) : Data()
            return HTTPResponse(status: 206, body: body, etag: file.etag, lastModified: lastModified)
        }
        return HTTPResponse(status: 200, body: file.data, etag: file.etag, lastModified: lastModified)
    }
}

/// Writes small GeoTIFF tiles for the tests: a tile of 1° with `size` x `size` samples (level 0)
/// and one overview (level 1), as uncompressed float strips. The layout is the one of a Cloud
/// Optimized GeoTIFF: the directories first, then the overview, then the full image.
enum TestGeotiff {
    /// The size of the directories: the data of the overview starts here.
    static let headerSize = 320

    static func tile(_ tile: ElevationTile, size: Int = 8, heightM: Float) -> Data {
        let half = size / 2
        let full = [Float](repeating: heightM, count: size * size)
        let overview = [Float](repeating: heightM, count: half * half)
        let ifd0 = 8
        let doubles = ifd0 + 2 + (12 * 10) + 4
        let ifd1 = doubles + (9 * 8)
        let overviewData = ifd1 + 2 + (12 * 9) + 4
        let fullData = overviewData + (overview.count * 4)
        var d = Data()
        func u16(_ v: Int) { withUnsafeBytes(of: UInt16(v).littleEndian) { d.append(contentsOf: $0) } }
        func u32(_ v: Int) { withUnsafeBytes(of: UInt32(v).littleEndian) { d.append(contentsOf: $0) } }
        func f64(_ v: Double) { withUnsafeBytes(of: v.bitPattern.littleEndian) { d.append(contentsOf: $0) } }
        func f32(_ v: Float) { withUnsafeBytes(of: v.bitPattern.littleEndian) { d.append(contentsOf: $0) } }
        /// A tag with one SHORT (type 3) or LONG (type 4) value, or an offset for DOUBLE (type 12).
        func entry(_ tag: Int, _ type: Int, _ count: Int, _ value: Int) {
            u16(tag)
            u16(type)
            u32(count)
            if type == 3 {
                u16(value)
                u16(0)
            } else {
                u32(value)
            }
        }
        d.append(contentsOf: Array("II".utf8))
        u16(42)
        u32(ifd0)
        // The full image, with the georeferencing.
        u16(10)
        entry(256, 4, 1, size)
        entry(257, 4, 1, size)
        entry(258, 3, 1, 32)
        entry(273, 4, 1, fullData)
        entry(277, 3, 1, 1)
        entry(278, 4, 1, size)
        entry(279, 4, 1, size * size * 4)
        entry(339, 3, 1, 3)
        entry(33550, 12, 3, doubles)
        entry(33922, 12, 6, doubles + 24)
        u32(ifd1)
        let step = 1.0 / Double(size)
        for v in [step, step, 0] { f64(v) }
        for v in [0, 0, 0, Double(tile.longitudeDeg), Double(tile.latitudeDeg + 1), 0] { f64(v) }
        // The overview.
        u16(9)
        entry(254, 4, 1, 1)
        entry(256, 4, 1, half)
        entry(257, 4, 1, half)
        entry(258, 3, 1, 32)
        entry(273, 4, 1, overviewData)
        entry(277, 3, 1, 1)
        entry(278, 4, 1, half)
        entry(279, 4, 1, half * half * 4)
        entry(339, 3, 1, 3)
        u32(0)
        precondition(d.count == overviewData && overviewData == headerSize)
        for v in overview { f32(v) }
        for v in full { f32(v) }
        precondition(d.count == fullData + (full.count * 4))
        return d
    }
}
