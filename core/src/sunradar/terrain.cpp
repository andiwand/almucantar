#include "sunradar/terrain.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <numbers>
#include <optional>
#include <stdexcept>
#include <utility>
#include <vector>

#include "sunradar/internal/astronomy_engine.hpp"
#include "sunradar/internal/geotiff.hpp"
#include "sunradar/internal/math.hpp"
#include "sunradar/internal/numeric.hpp"
#include "sunradar/internal/refraction.hpp"
#include "sunradar/internal/solar_model.hpp"
#include "sunradar/internal/validate.hpp"

namespace sunradar {

namespace {

using internal::kDegToRad;
using internal::kRadToDeg;
using internal::require;
using internal::require_finite;
using internal::require_positive;

/// The mean radius of the Earth (IUGG). The sphere is sufficient for the angles of the terrain:
/// the ellipsoid changes a distance of 100 km by less than 0.5%.
constexpr double kEarthRadiusM = 6'371'008.8;
constexpr double kMaxDistanceM = 500'000;
constexpr double kMaxGridSamples = 200'000'000;
constexpr double kMaxSearchSteps = 1'000'000;
constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
/// The limit of the Web Mercator projection: the map is a square.
constexpr double kMaxMercatorLatitudeDeg = 85.0511287798;

void validate(const ElevationGrid& grid) {
  require_finite(grid.north_latitude_deg, "north_latitude_deg");
  require_finite(grid.west_longitude_deg, "west_longitude_deg");
  require_positive(grid.latitude_step_deg, "latitude_step_deg");
  require_positive(grid.longitude_step_deg, "longitude_step_deg");
  require(grid.rows >= 2, "rows is less than 2");
  require(grid.columns >= 2, "columns is less than 2");
  require(static_cast<double>(grid.rows) * grid.columns <= kMaxGridSamples,
          "the grid has more than 200,000,000 samples");
  require(grid.heights_m.size() ==
              static_cast<std::size_t>(grid.rows) * static_cast<std::size_t>(grid.columns),
          "heights_m does not have rows * columns values");
  require(grid.north_latitude_deg <= 90, "north_latitude_deg is greater than 90");
  require(grid.north_latitude_deg - (grid.rows - 1) * grid.latitude_step_deg >= -90,
          "the grid goes south of -90");
  require((grid.columns - 1) * grid.longitude_step_deg < 360, "the grid is wider than 360°");
}

/// The number of azimuths of a profile with `step_deg`, or 0 if 360 is not a multiple of it.
std::size_t azimuth_count(const double step_deg) {
  const double count = std::round(360 / step_deg);
  if (count < 1 || std::abs(count * step_deg - 360) > 1e-9 * 360) {
    return 0;
  }
  return static_cast<std::size_t>(count);
}

/// Returns the height at a place with a bilinear interpolation, or nothing if the place is
/// outside the grid or a neighbor has no data. A place up to `margin` cells outside the edge uses
/// the samples at the edge, so the gap between two tiles has data.
std::optional<double> height_at(const ElevationGrid& grid, const double latitude_deg,
                                const double longitude_deg, const double margin) {
  double row = (grid.north_latitude_deg - latitude_deg) / grid.latitude_step_deg;
  double column =
      internal::wrap_360(longitude_deg - grid.west_longitude_deg) / grid.longitude_step_deg;
  const double full_turn = 360 / grid.longitude_step_deg;
  if (column > full_turn - margin) {
    column -= full_turn;  // a place just west of the grid
  }
  const double last_row = grid.rows - 1;
  const double last_column = grid.columns - 1;
  if (row < -margin || row > last_row + margin || column < -margin ||
      column > last_column + margin) {
    return std::nullopt;
  }
  row = std::clamp(row, 0.0, last_row);
  column = std::clamp(column, 0.0, last_column);
  const int r = std::min(static_cast<int>(row), grid.rows - 2);
  const int c = std::min(static_cast<int>(column), grid.columns - 2);
  const double u = row - r;
  const double v = column - c;
  const auto at = [&grid](const int rr, const int cc) {
    const std::size_t index =
        (static_cast<std::size_t>(rr) * static_cast<std::size_t>(grid.columns)) +
        static_cast<std::size_t>(cc);
    return static_cast<double>(grid.heights_m[index]);
  };
  const double h = ((1 - u) * ((1 - v) * at(r, c) + v * at(r, c + 1))) +
                   (u * ((1 - v) * at(r + 1, c) + v * at(r + 1, c + 1)));
  if (std::isnan(h)) {
    return std::nullopt;
  }
  return h;
}

/// The tiles of a profile, from the smallest cells to the largest.
using SortedTiles = std::vector<const ElevationGrid*>;

/// Returns the height at a place from the tile with the smallest cells that has data there. Tiles
/// can overlap, for example a fine tile near the observer on top of a coarse one.
std::optional<double> height_in_tiles(const SortedTiles& tiles, const double latitude_deg,
                                      const double longitude_deg) {
  // A margin of 1.5 cells, because the gap between two Web Mercator tiles in a grid with equal
  // steps of latitude (see terrarium_grid()) can be a little larger than one step.
  for (const double margin : {0.0, 1.5}) {
    for (const ElevationGrid* tile : tiles) {
      if (const auto h = height_at(*tile, latitude_deg, longitude_deg, margin)) {
        return h;
      }
    }
  }
  return std::nullopt;
}

/// The distance from the observer to the nearest point of a box in latitude and longitude. The
/// nearest point of the box is a good approximation of the nearest point for short distances.
double box_distance_m(const Observer& observer, const double south_deg, const double north_deg,
                      const double west_deg, const double width_deg) {
  const double lat = std::clamp(observer.latitude_deg, south_deg, north_deg);
  const double half = width_deg / 2;
  const double offset = internal::wrap_180(observer.longitude_deg - west_deg - half);
  const double lon = observer.longitude_deg - offset + std::clamp(offset, -half, half);
  const double phi1 = observer.latitude_deg * kDegToRad;
  const double phi2 = lat * kDegToRad;
  const double dphi = phi2 - phi1;
  const double dlambda = (lon - observer.longitude_deg) * kDegToRad;
  const double a =
      (std::sin(dphi / 2) * std::sin(dphi / 2)) +
      (std::cos(phi1) * std::cos(phi2) * std::sin(dlambda / 2) * std::sin(dlambda / 2));
  return 2 * kEarthRadiusM * std::asin(std::min(1.0, std::sqrt(a)));
}

/// The latitude of the Web Mercator row coordinate `y` (0 at the north edge, `n` at the south).
double mercator_latitude_deg(const double y, const double n) {
  return std::atan(std::sinh(std::numbers::pi * (1 - (2 * y / n)))) * kRadToDeg;
}

/// The highest terrain along one azimuth.
struct RayResult {
  double altitude_deg = kNaN;
  double distance_m = kNaN;
};

RayResult trace_ray(const SortedTiles& tiles, const Observer& observer, const double azimuth_deg,
                    const double eye_m, const HorizonOptions& options, const double cell_m) {
  const double phi1 = observer.latitude_deg * kDegToRad;
  const double sin_phi1 = std::sin(phi1);
  const double cos_phi1 = std::cos(phi1);
  const double sin_theta = std::sin(azimuth_deg * kDegToRad);
  const double cos_theta = std::cos(azimuth_deg * kDegToRad);
  // The terrestrial refraction makes the Earth look flatter: its effective radius is larger.
  const double two_effective_radius = 2 * kEarthRadiusM / (1 - options.refraction_coefficient);
  // The width of the ray grows with the distance, so a far sample can be farther from the last.
  const double beam = options.azimuth_step_deg * kDegToRad * 0.5;
  const double near_step = 0.5 * cell_m;

  RayResult result;
  double best_tangent = -std::numeric_limits<double>::infinity();
  // The step grows with the distance, so a counter of the steps does not give d.
  double d = near_step;
  while (d <= options.max_distance_m) {
    const double here = d;
    d += std::max(near_step, d * beam);
    // The destination on the sphere at the distance `here` along the azimuth.
    const double delta = here / kEarthRadiusM;
    const double sin_delta = std::sin(delta);
    const double cos_delta = std::cos(delta);
    const double sin_phi2 = (sin_phi1 * cos_delta) + (cos_phi1 * sin_delta * cos_theta);
    const double phi2 = std::asin(std::clamp(sin_phi2, -1.0, 1.0));
    const double lambda2 =
        (observer.longitude_deg * kDegToRad) +
        std::atan2(sin_theta * sin_delta * cos_phi1, cos_delta - (sin_phi1 * sin_phi2));
    const std::optional<double> h = height_in_tiles(tiles, phi2 * kRadToDeg, lambda2 * kRadToDeg);
    if (!h) {
      continue;  // A gap, for example the sea between two coasts.
    }
    const double drop_m = here * here / two_effective_radius;
    const double tangent = (*h - eye_m - drop_m) / here;
    if (tangent > best_tangent) {
      best_tangent = tangent;
      result.distance_m = here;
    }
  }
  if (!std::isnan(result.distance_m)) {
    result.altitude_deg = std::atan(best_tangent) * kRadToDeg;
  }
  return result;
}

/// The apparent altitude of the sun plus the limb, minus the terrain. Positive if the sun is
/// visible.
double clearance_deg(const internal::SolarModelImpl& model, const Observer& observer,
                     const HorizonProfile& profile, const TerrainEventOptions& options,
                     const double unix_utc) {
  Horizontal body = options.body == Body::moon ? internal::moon_airless(unix_utc, observer)
                                               : model.sun(unix_utc, observer);
  if (options.refraction) {
    body = internal::refracted(body, options.atmosphere);
  }
  const double terrain = terrain_altitude_deg(profile, body.azimuth_deg);
  return body.altitude_deg + options.limb_deg - (std::isnan(terrain) ? 0.0 : terrain);
}

}  // namespace

ElevationGrid read_elevation_geotiff(const std::vector<std::uint8_t>& data, const int level) {
  return internal::read_geotiff(data, level);
}

std::uint32_t elevation_geotiff_prefix_size(const std::vector<std::uint8_t>& header,
                                            const int level) {
  return internal::geotiff_prefix_size(header, level);
}

std::vector<ElevationTile> elevation_tiles(const Observer& observer, const double max_distance_m) {
  internal::validate(observer);
  require_positive(max_distance_m, "max_distance_m");
  require(max_distance_m <= kMaxDistanceM, "max_distance_m is greater than 500 km");
  const double reach_deg = max_distance_m / kEarthRadiusM * kRadToDeg;
  const auto south =
      static_cast<int>(std::floor(std::max(observer.latitude_deg - reach_deg, -90.0)));
  const auto north = std::min(static_cast<int>(std::floor(observer.latitude_deg + reach_deg)), 89);

  std::vector<std::pair<double, ElevationTile>> found;
  for (int latitude = south; latitude <= north; ++latitude) {
    for (int longitude = -180; longitude < 180; ++longitude) {
      const double d = box_distance_m(observer, latitude, latitude + 1, longitude, 1);
      // A small margin, because the box is an approximation of the nearest point.
      if (d <= max_distance_m * 1.01) {
        found.emplace_back(
            d,
            ElevationTile{.latitude_deg = latitude, .longitude_deg = longitude, .distance_m = d});
      }
    }
  }
  std::ranges::stable_sort(found, {}, &std::pair<double, ElevationTile>::first);
  std::vector<ElevationTile> tiles;
  tiles.reserve(found.size());
  for (const auto& entry : found) {
    tiles.push_back(entry.second);
  }
  return tiles;
}

std::vector<MapTile> map_tiles(const Observer& observer, const double max_distance_m,
                               const int zoom) {
  internal::validate(observer);
  require_positive(max_distance_m, "max_distance_m");
  require(max_distance_m <= kMaxDistanceM, "max_distance_m is greater than 500 km");
  require(zoom >= 0 && zoom <= 12, "zoom is not in the range [0, 12]");
  // zoom is in [0, 12] here, so the shift of an unsigned value is safe.
  const auto n = static_cast<int>(1U << static_cast<unsigned>(zoom));
  const double reach_deg = max_distance_m / kEarthRadiusM * kRadToDeg;
  const double north = std::min(observer.latitude_deg + reach_deg, kMaxMercatorLatitudeDeg);
  const double south = std::max(observer.latitude_deg - reach_deg, -kMaxMercatorLatitudeDeg);
  // The row of a latitude, from the inverse of mercator_latitude_deg().
  const auto row = [n](const double latitude_deg) {
    const double phi = latitude_deg * kDegToRad;
    const double y = (1 - (std::asinh(std::tan(phi)) / std::numbers::pi)) / 2 * n;
    return std::clamp(static_cast<int>(std::floor(y)), 0, n - 1);
  };
  const double width_deg = 360.0 / n;
  std::vector<std::pair<double, MapTile>> found;
  for (int y = row(north); y <= row(south); ++y) {
    const double tile_north = mercator_latitude_deg(y, n);
    const double tile_south = mercator_latitude_deg(y + 1, n);
    for (int x = 0; x < n; ++x) {
      const double d =
          box_distance_m(observer, tile_south, tile_north, -180 + (x * width_deg), width_deg);
      if (d <= max_distance_m * 1.01) {
        found.emplace_back(d, MapTile{.zoom = zoom, .x = x, .y = y, .distance_m = d});
      }
    }
  }
  std::ranges::stable_sort(found, {}, &std::pair<double, MapTile>::first);
  std::vector<MapTile> tiles;
  tiles.reserve(found.size());
  for (const auto& entry : found) {
    tiles.push_back(entry.second);
  }
  return tiles;
}

ElevationGrid terrarium_grid(const std::vector<std::uint8_t>& rgba, const int width,
                             const int height, const MapTile& tile) {
  require(width >= 2 && width <= 4096, "width is not in the range [2, 4096]");
  require(height >= 2 && height <= 4096, "height is not in the range [2, 4096]");
  require(rgba.size() == static_cast<std::size_t>(width) * static_cast<std::size_t>(height) * 4,
          "rgba does not have width * height * 4 values");
  require(tile.zoom >= 0 && tile.zoom <= 24, "zoom is not in the range [0, 24]");
  const double n = std::ldexp(1.0, tile.zoom);
  require(tile.x >= 0 && tile.x < n && tile.y >= 0 && tile.y < n,
          "x or y is not in the tile range");

  // The centers of the first and the last row. The rows of the image have equal steps in the
  // Mercator projection, so the grid gets equal steps in latitude, with a linear interpolation
  // between the two nearest rows of the image.
  const auto row_y = [&](const double r) { return tile.y + ((r + 0.5) / height); };
  const double north = mercator_latitude_deg(row_y(0), n);
  const double south = mercator_latitude_deg(row_y(height - 1), n);
  ElevationGrid grid;
  grid.rows = height;
  grid.columns = width;
  grid.north_latitude_deg = north;
  grid.latitude_step_deg = (north - south) / (height - 1);
  grid.longitude_step_deg = 360.0 / n / width;
  grid.west_longitude_deg = -180 + (360.0 * tile.x / n) + (0.5 * grid.longitude_step_deg);

  const auto pixel_height = [&](const int r, const int c) {
    const std::size_t i = ((static_cast<std::size_t>(r) * static_cast<std::size_t>(width)) +
                           static_cast<std::size_t>(c)) *
                          4;
    // Terrarium: (red * 256 + green + blue / 256) - 32768 meters.
    return (rgba[i] * 256.0) + rgba[i + 1] + (rgba[i + 2] / 256.0) - 32768.0;
  };
  grid.heights_m.resize(static_cast<std::size_t>(width) * static_cast<std::size_t>(height));
  for (int r = 0; r < height; ++r) {
    const double latitude = north - (r * grid.latitude_step_deg);
    const double phi = latitude * kDegToRad;
    const double y = ((1 - (std::asinh(std::tan(phi)) / std::numbers::pi)) / 2 * n);
    const double source = std::clamp(((y - tile.y) * height) - 0.5, 0.0, height - 1.0);
    const int r0 = std::min(static_cast<int>(source), height - 2);
    const double t = source - r0;
    for (int c = 0; c < width; ++c) {
      const double h = ((1 - t) * pixel_height(r0, c)) + (t * pixel_height(r0 + 1, c));
      grid.heights_m[(static_cast<std::size_t>(r) * static_cast<std::size_t>(width)) +
                     static_cast<std::size_t>(c)] = static_cast<float>(h);
    }
  }
  return grid;
}

HorizonProfile horizon_profile(const Observer& observer, const std::vector<ElevationGrid>& tiles,
                               const HorizonOptions& options) {
  internal::validate(observer);
  require(!tiles.empty(), "tiles is empty");
  require(tiles.size() <= 1000, "tiles has more than 1000 grids");
  for (const ElevationGrid& grid : tiles) {
    validate(grid);
  }
  // The tiles from the smallest cells to the largest, so a fine tile wins where tiles overlap.
  // The cell size uses the latitude of the observer, which is good enough for an order.
  const double cos_observer = std::max(std::cos(observer.latitude_deg * kDegToRad), 0.01);
  SortedTiles sorted;
  sorted.reserve(tiles.size());
  for (const ElevationGrid& grid : tiles) {
    sorted.push_back(&grid);
  }
  const auto cell_deg = [cos_observer](const ElevationGrid* g) {
    return std::min(g->latitude_step_deg, g->longitude_step_deg * cos_observer);
  };
  std::ranges::stable_sort(sorted, {}, cell_deg);
  require_positive(options.azimuth_step_deg, "azimuth_step_deg");
  const std::size_t count = azimuth_count(options.azimuth_step_deg);
  require(count > 0, "360 is not a multiple of azimuth_step_deg");
  require(count <= std::size_t{36'000}, "azimuth_step_deg is less than 0.01");
  require_positive(options.max_distance_m, "max_distance_m");
  require(options.max_distance_m <= kMaxDistanceM, "max_distance_m is greater than 500 km");
  require_finite(options.eye_height_m, "eye_height_m");
  require_finite(options.observer_elevation_m, "observer_elevation_m");
  require_finite(options.refraction_coefficient, "refraction_coefficient");
  require(options.refraction_coefficient > -1 && options.refraction_coefficient < 1,
          "refraction_coefficient is not in the range (-1, 1)");

  HorizonProfile profile;
  profile.azimuth_step_deg = options.azimuth_step_deg;
  if (options.use_grid_elevation) {
    const std::optional<double> ground =
        height_in_tiles(sorted, observer.latitude_deg, observer.longitude_deg);
    if (!ground) {
      throw std::invalid_argument("the tiles have no data at the observer");
    }
    profile.observer_elevation_m = *ground;
  } else {
    profile.observer_elevation_m = options.observer_elevation_m;
  }
  const double eye_m = profile.observer_elevation_m + options.eye_height_m;

  // The size of a cell in meters, the smaller of the two directions. Near a pole the columns get
  // narrow, so the cosine has a lower limit.
  const double meters_per_degree = kEarthRadiusM * kDegToRad;
  const double cos_lat = std::max(std::cos(observer.latitude_deg * kDegToRad), 0.01);
  double cell_m = std::numeric_limits<double>::infinity();
  for (const ElevationGrid& grid : tiles) {
    cell_m = std::min({cell_m, grid.latitude_step_deg * meters_per_degree,
                       grid.longitude_step_deg * meters_per_degree * cos_lat});
  }

  profile.altitude_deg.resize(count);
  profile.distance_m.resize(count);
  for (std::size_t i = 0; i < count; ++i) {
    const RayResult ray =
        trace_ray(sorted, observer, static_cast<double>(i) * options.azimuth_step_deg, eye_m,
                  options, cell_m);
    profile.altitude_deg[i] = ray.altitude_deg;
    profile.distance_m[i] = ray.distance_m;
  }
  return profile;
}

double terrain_altitude_deg(const HorizonProfile& profile, const double azimuth_deg) {
  require_finite(azimuth_deg, "azimuth_deg");
  require(!profile.altitude_deg.empty(), "the profile is empty");
  require_positive(profile.azimuth_step_deg, "azimuth_step_deg");
  const std::size_t count = profile.altitude_deg.size();
  const double position = internal::wrap_360(azimuth_deg) / profile.azimuth_step_deg;
  const auto i = std::min(static_cast<std::size_t>(position), count - 1);
  const double t = position - static_cast<double>(i);
  const double a = profile.altitude_deg[i];
  const double b = profile.altitude_deg[(i + 1) % count];
  return a + (t * (b - a));
}

TerrainEvents terrain_events(const Observer& observer, const HorizonProfile& profile,
                             const double start_utc, const double end_utc,
                             const TerrainEventOptions& options) {
  internal::validate(observer);
  require_finite(start_utc, "start_utc");
  require_finite(end_utc, "end_utc");
  require(end_utc > start_utc, "end_utc is not after start_utc");
  require(!profile.altitude_deg.empty(), "the profile is empty");
  require_positive(profile.azimuth_step_deg, "azimuth_step_deg");
  require(azimuth_count(profile.azimuth_step_deg) == profile.altitude_deg.size(),
          "the profile does not have 360 / azimuth_step_deg values");
  require(options.body == Body::sun || options.body == Body::moon, "body is not a Body");
  require_finite(options.limb_deg, "limb_deg");
  require_positive(options.search_step_s, "search_step_s");
  require_positive(options.tolerance_s, "tolerance_s");
  require((end_utc - start_utc) / options.search_step_s <= kMaxSearchSteps,
          "the interval has too many search steps");
  if (options.refraction) {
    internal::validate(options.atmosphere);
  }

  const internal::SolarModelImpl& model = internal::solar_model(options.model);
  const auto f = [&](const double t) {
    return clearance_deg(model, observer, profile, options, t);
  };

  TerrainEvents events;
  double t0 = start_utc;
  double f0 = f(t0);
  const bool visible_at_start = f0 > 0;
  while (t0 < end_utc) {
    const double t1 = std::min(t0 + options.search_step_s, end_utc);
    const double f1 = f(t1);
    if ((f0 > 0) != (f1 > 0)) {
      const double t = internal::bisect(f, t0, t1, f0, options.tolerance_s);
      events.crossings.push_back({.unix_utc = t, .rising = f1 > 0});
    }
    t0 = t1;
    f0 = f1;
  }
  if (events.crossings.empty()) {
    events.kind = visible_at_start ? DayKind::polar_day : DayKind::polar_night;
  }
  return events;
}

}  // namespace sunradar
