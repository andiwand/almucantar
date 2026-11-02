import Foundation
import Sunradar

/// A source of elevation tiles of 1° (`ElevationTile`), as Cloud Optimized GeoTIFF files with
/// overviews. The terrain and the cache use only this description, so another source (for example
/// a bare-earth model without buildings) can replace Copernicus without changes in the terrain
/// logic. The PWA has the same idea in `pwa/src/terrain.ts` (`ElevationSource`).
///
/// The cache keeps the tiles by their name (`DemCache.name`), not by their source. A new source
/// must use a new directory of the cache, or the cache gives the files of the old source.
protocol ElevationSource: Sendable {
    /// The name of the data set, for example "Copernicus DEM GLO-90".
    var name: String { get }
    /// The attribution that the license of the data requires. The settings show it.
    var attribution: String { get }
    /// True if the heights include buildings and forests (a surface model). Then the profile
    /// ignores the terrain nearer than `nearClearanceM`, because a city gives a false wall around
    /// the observer.
    var isSurfaceModel: Bool { get }
    /// The overview level of the coarse check, for all tiles of the profile.
    var coarseLevel: Int { get }
    /// The level of the near tiles at full resolution.
    var fullLevel: Int { get }
    /// The tiles nearer than this are read at `fullLevel` if the terrain is high. Farther away,
    /// `coarseLevel` is sufficient.
    var fullResolutionM: Double { get }
    /// The URL of the file of a tile.
    func url(_ tile: ElevationTile) -> URL
}

/// The clearance of the near path for a surface model. Buildings smeared over cells of 90 m add at
/// most about 0.4° at this distance, and real mountains are usually farther away.
let nearClearanceM = 2_000.0

extension ElevationSource {
    /// The options of the profile for the source: the clearance of the near path for a surface
    /// model. The coarse check and the full profile use the same options.
    var horizonOptions: HorizonOptions {
        HorizonOptions(minDistanceM: isSurfaceModel ? nearClearanceM : 0)
    }

    /// True if the tile is near enough for full resolution.
    func isNear(_ tile: ElevationTile) -> Bool { tile.distanceM < fullResolutionM }
}

/// Copernicus GLO-90 on AWS Open Data: tiles of 1° with samples of 90 m. It is a surface model, so
/// its heights include buildings and forests.
struct CopernicusSource: ElevationSource {
    let name = "Copernicus DEM GLO-90"
    let attribution =
        "Copernicus DEM GLO-90, © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018, "
        + "provided under COPERNICUS by the European Union and ESA; all rights reserved."
    let isSurfaceModel = true
    /// Level 2 of a Copernicus tile has 300 x 300 samples (about 370 m). Its prefix is about
    /// 300 KB of the 2–5 MB of a tile.
    let coarseLevel = 2
    let fullLevel = 0
    /// At 25 km, a sample of 370 m is a small angle.
    let fullResolutionM = 25_000.0

    func url(_ tile: ElevationTile) -> URL {
        let file = "Copernicus_DSM_COG_30_\(DemCache.name(tile))_DEM"
        return URL(string: "https://copernicus-dem-90m.s3.amazonaws.com/\(file)/\(file).tif")!
    }
}
