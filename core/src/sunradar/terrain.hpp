#pragma once

#include <cstdint>
#include <vector>

#include "sunradar/sun.hpp"
#include "sunradar/types.hpp"

namespace sunradar {

/// A digital elevation model (DEM) as a plain grid of heights, for example a tile of
/// Copernicus GLO-30. The sample in row `r` and column `c` is at the latitude
/// `north_latitude_deg - r * latitude_step_deg` and the longitude
/// `west_longitude_deg + c * longitude_step_deg`. So the rows go from north to south, as in a
/// GeoTIFF that is north up. The grid can cross the antimeridian.
struct ElevationGrid {
  double north_latitude_deg = 0;  ///< the latitude of row 0
  double west_longitude_deg = 0;  ///< the longitude of column 0
  double latitude_step_deg = 0;   ///< the distance between two rows, positive
  double longitude_step_deg = 0;  ///< the distance between two columns, positive
  int rows = 0;                   ///< at least 2
  int columns = 0;                ///< at least 2
  /// The heights above sea level in meters, row by row (`rows * columns` values). NaN is a
  /// sample without data.
  std::vector<float> heights_m;
};

struct HorizonOptions {
  /// The distance between two azimuths of the profile. 360 must be a multiple of it.
  double azimuth_step_deg = 0.25;
  double max_distance_m = 100'000;  ///< the terrain farther away is not used, at most 500 km
  /// The height of the eye above the ground. For a flat, add the height of the floor.
  double eye_height_m = 1.6;
  /// If true, the elevation of the ground at the observer comes from the tiles. If false, it is
  /// `observer_elevation_m`.
  bool use_grid_elevation = true;
  double observer_elevation_m = 0;  ///< used only if `use_grid_elevation` is false
  /// The coefficient of the terrestrial refraction: the curvature of the line of sight divided
  /// by the curvature of the Earth. 0.13 is the standard value; 0 gives no refraction.
  double refraction_coefficient = 0.13;
  /// The terrain nearer than this distance is not used. A surface model (for example Copernicus,
  /// which has buildings and forests in its heights) needs it: in a city, the buildings, smeared
  /// over cells of 90 m, give a false wall of several degrees around the observer. Real mountains
  /// are usually farther away. 0 uses all terrain, which is right for a bare-earth model.
  double min_distance_m = 0;
};

/// The altitude of the terrain horizon for each azimuth. The entry `i` is for the azimuth
/// `i * azimuth_step_deg`.
struct HorizonProfile {
  double azimuth_step_deg = 0;
  /// The altitude of the highest terrain that the observer sees, with the curvature of the Earth
  /// and the terrestrial refraction. NaN if the tiles have no data in this direction.
  std::vector<double> altitude_deg;
  /// The distance to the terrain point of `altitude_deg`. NaN if the tiles have no data in this
  /// direction.
  std::vector<double> distance_m;
  double observer_elevation_m = 0;  ///< the elevation of the ground at the observer
};

/// Reads one image of a GeoTIFF file with heights in latitude and longitude, for example a tile
/// of Copernicus GLO-90. `level` 0 is the full image, and 1, 2, ... are the overviews (each has
/// half the size of the one before). The reader supports classic TIFF (not BigTIFF), 16-bit
/// integers and 32-bit floats, tiles and strips, no compression and DEFLATE, and the predictors.
/// Throws `std::invalid_argument` if the file is not valid or not supported.
[[nodiscard]] ElevationGrid read_elevation_geotiff(const std::vector<std::uint8_t>& data,
                                                   int level = 0);

/// Returns the number of bytes from the start of a GeoTIFF file that `read_elevation_geotiff()`
/// needs for `level`, from the first part of the file in `header`. A Cloud Optimized GeoTIFF has
/// its directories and its smallest overviews first, so an app can download only this part with
/// an HTTP Range request. For a Copernicus GLO-90 tile, 16 KB of header are enough, and level 2
/// needs about 300 KB of the 2–5 MB of the file. Returns 0 if `header` is too short to tell; then
/// give a longer header. Throws `std::invalid_argument` if the file is not valid or not supported.
[[nodiscard]] std::uint32_t elevation_geotiff_prefix_size(const std::vector<std::uint8_t>& header,
                                                          int level);

/// A tile of 1° by 1° of a global DEM, named by its south-west corner. Copernicus names the tile
/// with `latitude_deg` 47 and `longitude_deg` 11 `N47_00_E011_00`; it covers 47° to 48° north and
/// 11° to 12° east.
struct ElevationTile {
  int latitude_deg = 0;   ///< -90 to 89
  int longitude_deg = 0;  ///< -180 to 179
  /// The distance from the observer to the nearest point of the tile, 0 for the tile of the
  /// observer. A far tile can use an overview (see `read_elevation_geotiff()`) to save memory.
  double distance_m = 0;
};

/// Returns the tiles of 1° by 1° that a horizon profile with `max_distance_m` needs: all tiles
/// that have a point nearer than `max_distance_m` to the observer, the nearest first.
/// Throws `std::invalid_argument` if an argument is not valid.
[[nodiscard]] std::vector<ElevationTile> elevation_tiles(const Observer& observer,
                                                         double max_distance_m);

/// A tile of the Web Mercator tile scheme of web maps (the scheme of OpenStreetMap): `x` from west
/// to east and `y` from north to south, both from 0 to `2^zoom - 1`.
struct MapTile {
  int zoom = 0;
  int x = 0;
  int y = 0;
  double distance_m = 0;  ///< the distance from the observer to the nearest point of the tile
};

/// Returns the Web Mercator tiles of `zoom` that a horizon profile with `max_distance_m` needs,
/// the nearest first. For example, zoom 10 has tiles of about 25 km at 50° latitude and pixels of
/// about 100 m. Throws `std::invalid_argument` if `zoom` is not in the range [0, 12], or if an
/// argument is not valid.
[[nodiscard]] std::vector<MapTile> map_tiles(const Observer& observer, double max_distance_m,
                                             int zoom);

/// Converts the pixels of a Terrarium elevation tile (the format of the AWS Terrain Tiles,
/// height = red * 256 + green + blue / 256 - 32768 meters) to a grid. `rgba` has 4 bytes for each
/// pixel, row by row from the north, as a canvas gives them. The grid has the same size as the
/// image and equal steps in latitude. Throws `std::invalid_argument` if an argument is not valid.
[[nodiscard]] ElevationGrid terrarium_grid(const std::vector<std::uint8_t>& rgba, int width,
                                           int height, const MapTile& tile);

/// Calculates the terrain horizon of `observer` from the grids in `tiles`. The function follows a
/// line from the observer for each azimuth, until `max_distance_m`. Tiles can overlap: at a point,
/// the tile with the smallest cells that has data wins, so fine tiles near the observer can lie on
/// top of coarse tiles for the far terrain. A point between two tiles uses the nearest sample at
/// their edges, and a point outside all tiles has no data (for example
/// the sea, which has no tile in Copernicus). Buildings and trees are not in a DEM, so they are not
/// in the profile. Throws `std::invalid_argument` if an argument is not valid, or if
/// `use_grid_elevation` is true and the tiles have no data at the observer.
[[nodiscard]] HorizonProfile horizon_profile(const Observer& observer,
                                             const std::vector<ElevationGrid>& tiles,
                                             const HorizonOptions& options = {});

/// Returns the altitude of the terrain horizon at `azimuth_deg`, with a linear interpolation
/// between the azimuths of `profile`. Returns NaN if a neighbor has no data. Throws
/// `std::invalid_argument` if the profile is empty or the azimuth is not finite.
[[nodiscard]] double terrain_altitude_deg(const HorizonProfile& profile, double azimuth_deg);

/// A body that can rise and set behind the terrain.
enum class Body : std::uint8_t {
  sun,
  moon,
};

struct TerrainEventOptions {
  Body body = Body::sun;
  SolarModel model = SolarModel::noaa;  ///< used only for the sun
  bool refraction = true;               ///< add the refraction to the position of the body
  Atmosphere atmosphere;                ///< used only if `refraction` is true
  /// The body is visible if its center plus this angle is above the terrain. The default is the
  /// semi-diameter of the sun, so the first light of the upper limb counts. The semi-diameter of
  /// the moon is 0.25° to 0.28°, so the default is also good for the moon. Use 0 for the center.
  double limb_deg = 0.2667;
  /// The interval of the first search. A terrain feature that hides the body for less than this
  /// interval can be missed.
  double search_step_s = 60;
  double tolerance_s = 0.5;  ///< the time accuracy of the results
};

/// The times when the body appears or goes away behind the terrain.
struct TerrainEvents {
  /// normal: the body crosses the terrain at least once. polar_day: the body stays above the
  /// terrain. polar_night: the body stays behind the terrain or below the horizon.
  DayKind kind = DayKind::normal;
  std::vector<Crossing> crossings;  ///< all crossings, in time order; `rising` is an appearance
};

/// Finds the times between `start_utc` and `end_utc` when the body of `options` (the sun or the
/// moon) crosses the terrain horizon `profile`: the visible rise and set, and the times when a peak
/// hides the body. For example, the moon rises behind a mountain at the first crossing with
/// `rising` true. An azimuth without data uses the altitude 0. Throws `std::invalid_argument` if
/// `end_utc <= start_utc`, if the interval has more than 1,000,000 search steps, or if an
/// argument is not valid.
[[nodiscard]] TerrainEvents terrain_events(const Observer& observer, const HorizonProfile& profile,
                                           double start_utc, double end_utc,
                                           const TerrainEventOptions& options = {});

}  // namespace sunradar
