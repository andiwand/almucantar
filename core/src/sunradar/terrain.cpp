#include "sunradar/terrain.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <optional>
#include <stdexcept>

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
/// outside the grid or a neighbor has no data.
std::optional<double> height_at(const ElevationGrid& grid, const double latitude_deg,
                                const double longitude_deg) {
  const double row = (grid.north_latitude_deg - latitude_deg) / grid.latitude_step_deg;
  const double column =
      internal::wrap_360(longitude_deg - grid.west_longitude_deg) / grid.longitude_step_deg;
  if (row < 0 || row > grid.rows - 1 || column < 0 || column > grid.columns - 1) {
    return std::nullopt;
  }
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

/// The highest terrain along one azimuth.
struct RayResult {
  double altitude_deg = kNaN;
  double distance_m = kNaN;
};

RayResult trace_ray(const ElevationGrid& grid, const Observer& observer, const double azimuth_deg,
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
    const std::optional<double> h = height_at(grid, phi2 * kRadToDeg, lambda2 * kRadToDeg);
    if (!h) {
      if (std::isnan(result.distance_m)) {
        continue;  // The data can start after a gap near the observer, for example at a coast.
      }
      break;  // The ray leaves the grid.
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
  Horizontal sun = model.sun(unix_utc, observer);
  if (options.refraction) {
    sun = internal::refracted(sun, options.atmosphere);
  }
  const double terrain = terrain_altitude_deg(profile, sun.azimuth_deg);
  return sun.altitude_deg + options.limb_deg - (std::isnan(terrain) ? 0.0 : terrain);
}

}  // namespace

HorizonProfile horizon_profile(const Observer& observer, const ElevationGrid& grid,
                               const HorizonOptions& options) {
  internal::validate(observer);
  validate(grid);
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
        height_at(grid, observer.latitude_deg, observer.longitude_deg);
    if (!ground) {
      throw std::invalid_argument("the grid has no data at the observer");
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
  const double cell_m = std::min(grid.latitude_step_deg * meters_per_degree,
                                 grid.longitude_step_deg * meters_per_degree * cos_lat);

  profile.altitude_deg.resize(count);
  profile.distance_m.resize(count);
  for (std::size_t i = 0; i < count; ++i) {
    const RayResult ray = trace_ray(
        grid, observer, static_cast<double>(i) * options.azimuth_step_deg, eye_m, options, cell_m);
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
