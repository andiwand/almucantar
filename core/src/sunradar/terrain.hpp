#pragma once

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
  /// If true, the elevation of the ground at the observer comes from the grid. If false, it is
  /// `observer_elevation_m`.
  bool use_grid_elevation = true;
  double observer_elevation_m = 0;  ///< used only if `use_grid_elevation` is false
  /// The coefficient of the terrestrial refraction: the curvature of the line of sight divided
  /// by the curvature of the Earth. 0.13 is the standard value; 0 gives no refraction.
  double refraction_coefficient = 0.13;
};

/// The altitude of the terrain horizon for each azimuth. The entry `i` is for the azimuth
/// `i * azimuth_step_deg`.
struct HorizonProfile {
  double azimuth_step_deg = 0;
  /// The altitude of the highest terrain that the observer sees, with the curvature of the Earth
  /// and the terrestrial refraction. NaN if the grid has no data in this direction.
  std::vector<double> altitude_deg;
  /// The distance to the terrain point of `altitude_deg`. NaN if the grid has no data in this
  /// direction.
  std::vector<double> distance_m;
  double observer_elevation_m = 0;  ///< the elevation of the ground at the observer
};

/// Calculates the terrain horizon of `observer` from `grid`. The function follows a line from the
/// observer for each azimuth, until `max_distance_m` or the edge of the grid. Buildings and trees
/// are not in a DEM, so they are not in the profile. Throws `std::invalid_argument` if an argument
/// is not valid, or if `use_grid_elevation` is true and the grid has no data at the observer.
[[nodiscard]] HorizonProfile horizon_profile(const Observer& observer, const ElevationGrid& grid,
                                             const HorizonOptions& options = {});

/// Returns the altitude of the terrain horizon at `azimuth_deg`, with a linear interpolation
/// between the azimuths of `profile`. Returns NaN if a neighbor has no data. Throws
/// `std::invalid_argument` if the profile is empty or the azimuth is not finite.
[[nodiscard]] double terrain_altitude_deg(const HorizonProfile& profile, double azimuth_deg);

struct TerrainEventOptions {
  SolarModel model = SolarModel::noaa;
  bool refraction = true;  ///< add the refraction to the position of the sun
  Atmosphere atmosphere;   ///< used only if `refraction` is true
  /// The sun is visible if its center plus this angle is above the terrain. The default is the
  /// semi-diameter of the sun, so the first light of the upper limb counts. Use 0 for the center.
  double limb_deg = 0.2667;
  /// The interval of the first search. A terrain feature that hides the sun for less than this
  /// interval can be missed.
  double search_step_s = 60;
  double tolerance_s = 0.5;  ///< the time accuracy of the results
};

/// The times when the sun appears or goes away behind the terrain.
struct TerrainEvents {
  /// normal: the sun crosses the terrain at least once. polar_day: the sun stays above the
  /// terrain. polar_night: the sun stays behind the terrain or below the horizon.
  DayKind kind = DayKind::normal;
  std::vector<Crossing> crossings;  ///< all crossings, in time order; `rising` is an appearance
};

/// Finds the times between `start_utc` and `end_utc` when the sun crosses the terrain horizon
/// `profile`: the visible sunrise and sunset, and the times when a peak hides the sun during the
/// day. An azimuth without data uses the altitude 0. Throws `std::invalid_argument` if
/// `end_utc <= start_utc`, if the interval has more than 1,000,000 search steps, or if an
/// argument is not valid.
[[nodiscard]] TerrainEvents terrain_events(const Observer& observer, const HorizonProfile& profile,
                                           double start_utc, double end_utc,
                                           const TerrainEventOptions& options = {});

}  // namespace sunradar
