#include "almucantar/terrain.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <numbers>
#include <optional>
#include <stdexcept>
#include <tuple>
#include <utility>
#include <vector>

#include "almucantar/internal/dem.hpp"
#include "almucantar/internal/geotiff.hpp"
#include "almucantar/internal/math.hpp"
#include "almucantar/internal/numeric.hpp"
#include "almucantar/internal/validate.hpp"
#include "almucantar/plan.hpp"

namespace almucantar {

namespace {

using internal::kDegToRad;
using internal::kRadToDeg;
using internal::require;
using internal::require_finite;
using internal::require_positive;

using internal::height_in_tiles;
using internal::kEarthRadiusM;
using internal::SortedTiles;
constexpr double kMaxDistanceM = 500'000;
constexpr double kMaxSearchSteps = 1'000'000;
constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
/// The limit of the Web Mercator projection: the map is a square.
constexpr double kMaxMercatorLatitudeDeg = 85.0511287798;

/// The number of azimuths of a profile with `step_deg`, or 0 if 360 is not a multiple of it.
std::size_t azimuth_count(const double step_deg) {
  const double count = std::round(360 / step_deg);
  if (count < 1 || std::abs(count * step_deg - 360) > 1e-9 * 360) {
    return 0;
  }
  return static_cast<std::size_t>(count);
}

void validate(const HorizonProfile& profile) {
  require(!profile.altitude_deg.empty(), "the profile is empty");
  require_positive(profile.azimuth_step_deg, "azimuth_step_deg");
  require(azimuth_count(profile.azimuth_step_deg) == profile.altitude_deg.size(),
          "the profile does not have 360 / azimuth_step_deg values");
}

/// The distance from the observer to the nearest point of a box in latitude and longitude. The
/// nearest point is on the meridian of the box that is nearest to the observer. On that meridian,
/// it is the point where the great circle from the observer meets the meridian at a right angle.
double box_distance_m(const Observer& observer, const double south_deg, const double north_deg,
                      const double west_deg, const double width_deg) {
  const double half = width_deg / 2;
  const double offset = internal::wrap_180(observer.longitude_deg - west_deg - half);
  const double lon = observer.longitude_deg - offset + std::clamp(offset, -half, half);
  const double phi1 = observer.latitude_deg * kDegToRad;
  const double dlambda = (lon - observer.longitude_deg) * kDegToRad;
  const double nearest_deg =
      std::atan2(std::sin(phi1), std::cos(phi1) * std::cos(dlambda)) * kRadToDeg;
  const double phi2 = std::clamp(nearest_deg, south_deg, north_deg) * kDegToRad;
  const double dphi = phi2 - phi1;
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
  double d = std::max(near_step, options.min_distance_m);
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

/// The altitude of the body plus the limb, minus the terrain. Positive if the body is visible.
double clearance_deg(const Observer& observer, const HorizonProfile& profile,
                     const TerrainEventOptions& options, const double unix_utc) {
  const Horizontal body = body_position(
      options.body, unix_utc, observer,
      {.model = options.model, .refraction = options.refraction, .atmosphere = options.atmosphere});
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
      if (d <= max_distance_m) {
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
      if (d <= max_distance_m) {
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
  const SortedTiles sorted = internal::sort_tiles(tiles, observer.latitude_deg);
  require_positive(options.azimuth_step_deg, "azimuth_step_deg");
  const std::size_t count = azimuth_count(options.azimuth_step_deg);
  require(count > 0, "360 is not a multiple of azimuth_step_deg");
  require(count <= std::size_t{36'000}, "azimuth_step_deg is less than 0.01");
  require_positive(options.max_distance_m, "max_distance_m");
  require(options.max_distance_m <= kMaxDistanceM, "max_distance_m is greater than 500 km");
  require_finite(options.min_distance_m, "min_distance_m");
  require(options.min_distance_m >= 0 && options.min_distance_m < options.max_distance_m,
          "min_distance_m is not in the range [0, max_distance_m)");
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

  const double cell_m = internal::smallest_cell_m(tiles, observer.latitude_deg);

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
  validate(profile);
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
  validate(profile);
  require(static_cast<int>(options.body) <= static_cast<int>(Body::neptune), "body is not a Body");
  require_finite(options.limb_deg, "limb_deg");
  require_positive(options.search_step_s, "search_step_s");
  require_positive(options.tolerance_s, "tolerance_s");
  require((end_utc - start_utc) / options.search_step_s <= kMaxSearchSteps,
          "the interval has too many search steps");
  if (options.refraction) {
    internal::validate(options.atmosphere);
  }

  const auto f = [&](const double t) { return clearance_deg(observer, profile, options, t); };

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

}  // namespace almucantar
