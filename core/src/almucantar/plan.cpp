#include "almucantar/plan.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <optional>
#include <vector>

#include "almucantar/internal/dem.hpp"
#include "almucantar/internal/math.hpp"
#include "almucantar/internal/numeric.hpp"
#include "almucantar/internal/validate.hpp"
#include "almucantar/moon.hpp"
#include "almucantar/sky.hpp"

namespace almucantar {

namespace {

using internal::kDegToRad;
using internal::kEarthRadiusM;
using internal::kRadToDeg;
using internal::require;
using internal::require_finite;
using internal::require_positive;

constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
constexpr double kDayS = 86'400;
constexpr double kMaxDistanceM = 500'000;
constexpr double kMaxSteps = 1'000'000;

void validate(const Body body) {
  require(static_cast<int>(body) <= static_cast<int>(Body::neptune), "body is not a Body");
}

void validate(const BodyOptions& options) {
  if (options.refraction) {
    internal::validate(options.atmosphere);
  }
}

void validate(const Site& site) {
  internal::validate(
      Observer{.latitude_deg = site.latitude_deg, .longitude_deg = site.longitude_deg});
  require_finite(site.elevation_m, "elevation_m");
}

void validate_refraction(const double coefficient) {
  require_finite(coefficient, "refraction_coefficient");
  require(coefficient > -1 && coefficient < 1,
          "refraction_coefficient is not in the range (-1, 1)");
}

Observer place(const Site& site) {
  return {.latitude_deg = site.latitude_deg, .longitude_deg = site.longitude_deg};
}

/// The angle between two directions, with the haversine formula, which is precise also for small
/// angles.
double separation_deg(const Horizontal& a, const Horizontal& b) {
  const double h1 = a.altitude_deg * kDegToRad;
  const double h2 = b.altitude_deg * kDegToRad;
  const double dh = h2 - h1;
  const double dz = (b.azimuth_deg - a.azimuth_deg) * kDegToRad;
  const double s = (std::sin(dh / 2) * std::sin(dh / 2)) +
                   (std::cos(h1) * std::cos(h2) * std::sin(dz / 2) * std::sin(dz / 2));
  return 2 * std::asin(std::min(1.0, std::sqrt(s))) * kRadToDeg;
}

/// The hour angle of a direction at the latitude `latitude_deg`, in degrees, positive to the west.
/// The hour angle of the sky turns once per day, so a body passes each hour angle once per day.
double hour_angle_deg(const Horizontal& h, const double latitude_deg) {
  const double a = h.altitude_deg * kDegToRad;
  const double z = h.azimuth_deg * kDegToRad;
  const double phi = latitude_deg * kDegToRad;
  return std::atan2(-std::sin(z) * std::cos(a),
                    (std::sin(a) * std::cos(phi)) - (std::cos(a) * std::cos(z) * std::sin(phi))) *
         kRadToDeg;
}

/// The mean rate of the hour angle of the body, in degrees per second. The moon moves east by
/// about 13° per day, so its hour angle is slower.
double hour_angle_rate(const Body body) {
  return body == Body::moon ? 360.0 / (24.8412 * 3600) : 360.0 / 86'164.1;
}

/// The planet of a body, or nothing for the sun, the moon, and the galactic center.
std::optional<Planet> planet_of(const Body body) {
  switch (body) {
    case Body::mercury:
      return Planet::mercury;
    case Body::venus:
      return Planet::venus;
    case Body::mars:
      return Planet::mars;
    case Body::jupiter:
      return Planet::jupiter;
    case Body::saturn:
      return Planet::saturn;
    case Body::uranus:
      return Planet::uranus;
    case Body::neptune:
      return Planet::neptune;
    default:
      return std::nullopt;
  }
}

}  // namespace

Horizontal body_position(const Body body, const double unix_utc, const Observer& observer,
                         const BodyOptions& options) {
  validate(body);
  const SkyOptions sky{.refraction = options.refraction, .atmosphere = options.atmosphere};
  if (const std::optional<Planet> planet = planet_of(body)) {
    return planet_position(*planet, unix_utc, observer, sky);
  }
  if (body == Body::moon) {
    return moon_position(unix_utc, observer,
                         {.refraction = options.refraction, .atmosphere = options.atmosphere});
  }
  if (body == Body::galactic_center) {
    return galactic_position(kGalacticCenterLongitudeDeg, kGalacticCenterLatitudeDeg, unix_utc,
                             observer, sky);
  }
  return sun_position(
      unix_utc, observer,
      {.model = options.model, .refraction = options.refraction, .atmosphere = options.atmosphere});
}

Sight sight(const Site& from, const Site& to, const SightOptions& options) {
  validate(from);
  validate(to);
  validate_refraction(options.refraction_coefficient);
  const double d = internal::distance_m(place(from), place(to));
  require(d > 1e-3, "the two sites are at the same place");
  require(d <= kMaxDistanceM, "the two sites are more than 500 km apart");
  const double tangent =
      internal::sight_tangent(to.elevation_m - from.elevation_m, d, options.refraction_coefficient);
  return {.direction = {.azimuth_deg = internal::bearing_deg(place(from), place(to)),
                        .altitude_deg = std::atan(tangent) * kRadToDeg},
          .distance_m = d};
}

std::vector<Alignment> find_alignments(const Body body, const Observer& observer,
                                       const Horizontal direction, const double start_utc,
                                       const double end_utc, const AlignmentOptions& options) {
  validate(body);
  internal::validate(observer);
  validate(options.body);
  require_finite(start_utc, "start_utc");
  require_finite(end_utc, "end_utc");
  require(end_utc > start_utc, "end_utc is not after start_utc");
  require(end_utc - start_utc <= 3660 * kDayS, "the interval is longer than 3660 days");
  require_finite(direction.azimuth_deg, "azimuth_deg");
  require_finite(direction.altitude_deg, "altitude_deg");
  require(direction.altitude_deg >= -90 && direction.altitude_deg <= 90,
          "altitude_deg is not in the range [-90, 90]");
  require_positive(options.max_separation_deg, "max_separation_deg");
  require_positive(options.tolerance_s, "tolerance_s");

  const auto position = [&](const double t) {
    return body_position(body, t, observer, options.body);
  };
  const auto separation = [&](const double t) { return separation_deg(position(t), direction); };
  const double target = hour_angle_deg(direction, observer.latitude_deg);
  const double rate = hour_angle_rate(body);
  const auto behind = [&](const double t) {
    return target - hour_angle_deg(position(t), observer.latitude_deg);
  };

  std::vector<Alignment> found;
  double t = start_utc;
  while (t < end_utc) {
    // Forward to the next time at the hour angle of the direction, then Newton steps with the mean
    // rate. The rate of the moon changes by a few percent, so a few steps are needed.
    t += internal::wrap_360(behind(t)) / rate;
    for (int i = 0; i < 20; ++i) {
      const double step = internal::wrap_180(behind(t)) / rate;
      t += step;
      if (std::abs(step) < 0.1 * options.tolerance_s) {
        break;
      }
    }
    if (t >= end_utc) {
      break;
    }
    // The separation is smallest near this time: the declination of the moon changes by at most
    // 0.3° per hour, so the minimum is within minutes. A far pass needs no search.
    if (t >= start_utc && separation(t) < options.max_separation_deg + 2) {
      const double best =
          std::clamp(internal::golden_min(separation, t - 3600, t + 3600, options.tolerance_s),
                     start_utc, end_utc);
      const Horizontal p = position(best);
      const double s = separation_deg(p, direction);
      if (s <= options.max_separation_deg) {
        found.push_back({.unix_utc = best, .separation_deg = s, .body = p});
      }
    }
    // Half a day on, so the next search finds the next pass and not this one again.
    t += 0.5 * kDayS;
  }
  return found;
}

AlignmentLine alignment_line(const Body body, const Site& target, const double unix_utc,
                             const std::vector<ElevationGrid>& tiles,
                             const AlignmentLineOptions& options) {
  validate(body);
  validate(target);
  validate(options.body);
  require_finite(unix_utc, "unix_utc");
  require_positive(options.step_m, "step_m");
  require_finite(options.min_distance_m, "min_distance_m");
  require_finite(options.max_distance_m, "max_distance_m");
  require(options.min_distance_m > 0 && options.min_distance_m <= options.max_distance_m,
          "min_distance_m is not in the range (0, max_distance_m]");
  require(options.max_distance_m <= kMaxDistanceM, "max_distance_m is greater than 500 km");
  require((options.max_distance_m - options.min_distance_m) / options.step_m <= 100'000,
          "the line has more than 100,000 points");
  require_finite(options.eye_height_m, "eye_height_m");
  validate_refraction(options.refraction_coefficient);
  const internal::SortedTiles sorted = internal::sort_tiles(tiles, target.latitude_deg);

  const Observer landmark = place(target);
  const auto count = static_cast<std::size_t>(
      std::floor(((options.max_distance_m - options.min_distance_m) / options.step_m) + 1e-9));
  AlignmentLine line;
  line.points.reserve(count + 1);
  Horizontal b = body_position(body, unix_utc, landmark, options.body);
  require(std::abs(b.altitude_deg) < 89, "the body is within 1° of the zenith or the nadir");
  // The bearing from the landmark to the place: opposite to the body, then corrected so the place
  // sees the landmark in the azimuth of the body. The correction comes from the convergence of the
  // meridians and from the parallax of the moon.
  double away = internal::wrap_360(b.azimuth_deg + 180);
  for (std::size_t i = 0; i <= count; ++i) {
    const double d = options.min_distance_m + (static_cast<double>(i) * options.step_m);
    Observer at = internal::destination(landmark, away, d);
    for (int k = 0; k < 8; ++k) {
      b = body_position(body, unix_utc, at, options.body);
      const double error = internal::wrap_180(b.azimuth_deg - internal::bearing_deg(at, landmark));
      if (std::abs(error) < 1e-9) {
        break;
      }
      away = internal::wrap_360(away + error);
      at = internal::destination(landmark, away, d);
    }
    require(std::abs(b.altitude_deg) < 89, "the body is within 1° of the zenith or the nadir");
    // The eye that sees the landmark at the altitude of the body: the inverse of sight_tangent().
    const double drop_m = d * d * (1 - options.refraction_coefficient) / (2 * kEarthRadiusM);
    const double eye = target.elevation_m - drop_m - (d * std::tan(b.altitude_deg * kDegToRad));
    const std::optional<double> ground =
        internal::height_in_tiles(sorted, at.latitude_deg, at.longitude_deg);
    line.points.push_back({.place = at,
                           .distance_m = d,
                           .eye_elevation_m = eye,
                           .ground_elevation_m = ground.value_or(kNaN),
                           .body = b});
  }

  // The spots: the ground plus the eye height crosses the eye that the alignment needs.
  for (std::size_t i = 1; i < line.points.size(); ++i) {
    const LinePoint& p = line.points[i - 1];
    const LinePoint& q = line.points[i];
    if (std::isnan(p.ground_elevation_m) || std::isnan(q.ground_elevation_m)) {
      continue;
    }
    const double gp = p.ground_elevation_m + options.eye_height_m - p.eye_elevation_m;
    const double gq = q.ground_elevation_m + options.eye_height_m - q.eye_elevation_m;
    if ((gp >= 0) == (gq >= 0)) {
      continue;
    }
    const double f = gp / (gp - gq);
    const auto lerp = [f](const double a, const double c) { return a + (f * (c - a)); };
    line.spots.push_back(
        {.place = {.latitude_deg = lerp(p.place.latitude_deg, q.place.latitude_deg),
                   .longitude_deg =
                       p.place.longitude_deg +
                       (f * internal::wrap_180(q.place.longitude_deg - p.place.longitude_deg))},
         .distance_m = lerp(p.distance_m, q.distance_m),
         .eye_elevation_m = lerp(p.eye_elevation_m, q.eye_elevation_m),
         .ground_elevation_m = lerp(p.ground_elevation_m, q.ground_elevation_m),
         .body = {.azimuth_deg = p.body.azimuth_deg +
                                 (f * internal::wrap_180(q.body.azimuth_deg - p.body.azimuth_deg)),
                  .altitude_deg = lerp(p.body.altitude_deg, q.body.altitude_deg)}});
  }
  return line;
}

LineOfSight line_of_sight(const Site& from, const Site& to, const std::vector<ElevationGrid>& tiles,
                          const SightOptions& options) {
  validate(from);
  validate(to);
  validate_refraction(options.refraction_coefficient);
  require_finite(options.target_margin_m, "target_margin_m");
  require(options.target_margin_m >= 0, "target_margin_m is negative");
  require(!tiles.empty(), "tiles is empty");
  const internal::SortedTiles sorted = internal::sort_tiles(tiles, from.latitude_deg);
  const double total = internal::distance_m(place(from), place(to));
  require(total > 1e-3, "the two sites are at the same place");
  require(total <= kMaxDistanceM, "the two sites are more than 500 km apart");

  const double cell_m = internal::smallest_cell_m(tiles, from.latitude_deg);
  const double step = std::max(0.5 * cell_m, total / kMaxSteps);
  const double azimuth = internal::bearing_deg(place(from), place(to));
  const double target = internal::sight_tangent(to.elevation_m - from.elevation_m, total,
                                                options.refraction_coefficient);
  double best = -std::numeric_limits<double>::infinity();
  LineOfSight result{.visible = true,
                     .clearance_deg = kNaN,
                     .blocking_distance_m = kNaN,
                     .blocking = {.latitude_deg = kNaN, .longitude_deg = kNaN}};
  const double last = total - std::max(1.5 * cell_m, options.target_margin_m);
  const auto count = last > step ? static_cast<std::size_t>(last / step) : std::size_t{0};
  for (std::size_t i = 1; i <= count; ++i) {
    const double d = static_cast<double>(i) * step;
    const Observer at = internal::destination(place(from), azimuth, d);
    const std::optional<double> h =
        internal::height_in_tiles(sorted, at.latitude_deg, at.longitude_deg);
    if (!h) {
      continue;
    }
    const double tangent =
        internal::sight_tangent(*h - from.elevation_m, d, options.refraction_coefficient);
    if (tangent > best) {
      best = tangent;
      result.blocking_distance_m = d;
      result.blocking = at;
    }
  }
  if (std::isfinite(best)) {
    result.clearance_deg = (std::atan(target) - std::atan(best)) * kRadToDeg;
    result.visible = result.clearance_deg > 0;
  }
  return result;
}

std::vector<TimeWindow> dark_windows(const Observer& observer, const double start_utc,
                                     const double end_utc, const DarkOptions& options,
                                     const HorizonProfile& profile) {
  internal::validate(observer);
  require_finite(start_utc, "start_utc");
  require_finite(end_utc, "end_utc");
  require(end_utc > start_utc, "end_utc is not after start_utc");
  require_finite(options.max_sun_altitude_deg, "max_sun_altitude_deg");
  require_finite(options.max_moon_altitude_deg, "max_moon_altitude_deg");
  require_finite(options.max_moon_fraction, "max_moon_fraction");
  require_finite(options.min_galactic_center_altitude_deg, "min_galactic_center_altitude_deg");
  require_positive(options.step_s, "step_s");
  require_positive(options.tolerance_s, "tolerance_s");
  require((end_utc - start_utc) / options.step_s <= kMaxSteps,
          "the interval has too many search steps");
  const bool terrain = !profile.altitude_deg.empty();
  if (terrain) {
    (void)terrain_altitude_deg(profile, 0);  // validates the profile
  }
  const auto over_terrain = [&](const Horizontal& h) {
    if (!terrain) {
      return h.altitude_deg;
    }
    const double t = terrain_altitude_deg(profile, h.azimuth_deg);
    return h.altitude_deg - (std::isnan(t) ? 0.0 : t);
  };

  // The margin of the darkness: positive if all conditions hold. The fraction of the moon changes
  // slowly, so a lit moon only adds a condition.
  const auto margin = [&](const double t) {
    BodyOptions airless;
    airless.refraction = false;
    double m =
        options.max_sun_altitude_deg - body_position(Body::sun, t, observer, airless).altitude_deg;
    if (m <= 0) {
      return m;  // the day: the other conditions do not matter, and the sign is right
    }
    if (moon_illuminated_fraction(t) > options.max_moon_fraction) {
      m = std::min(
          m, options.max_moon_altitude_deg - over_terrain(body_position(Body::moon, t, observer)));
    }
    if (options.galactic_center) {
      m = std::min(m, over_terrain(body_position(Body::galactic_center, t, observer)) -
                          options.min_galactic_center_altitude_deg);
    }
    return m;
  };

  std::vector<TimeWindow> windows;
  double t0 = start_utc;
  double m0 = margin(t0);
  std::optional<double> open;
  if (m0 > 0) {
    open = start_utc;
  }
  while (t0 < end_utc) {
    const double t1 = std::min(t0 + options.step_s, end_utc);
    const double m1 = margin(t1);
    if ((m0 > 0) != (m1 > 0)) {
      const double t = internal::bisect(margin, t0, t1, m0, options.tolerance_s);
      if (m1 > 0) {
        open = t;
      } else if (open) {
        windows.push_back({.start_utc = *open, .end_utc = t});
        open.reset();
      }
    }
    t0 = t1;
    m0 = m1;
  }
  if (open) {
    windows.push_back({.start_utc = *open, .end_utc = end_utc});
  }
  return windows;
}

}  // namespace almucantar
