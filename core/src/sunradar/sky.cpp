#include "sunradar/sky.hpp"

#include <cmath>
#include <cstddef>

#include "sunradar/internal/astronomy_engine.hpp"
#include "sunradar/internal/refraction.hpp"
#include "sunradar/internal/validate.hpp"

namespace sunradar {

namespace {

using internal::require;
using internal::require_finite;

void validate(const double unix_utc, const Observer& observer, const SkyOptions& options) {
  require_finite(unix_utc, "unix_utc");
  internal::validate(observer);
  if (options.refraction) {
    internal::validate(options.atmosphere);
  }
}

Horizontal seen(const Horizontal position, const SkyOptions& options) {
  return options.refraction ? internal::refracted(position, options.atmosphere) : position;
}

}  // namespace

Horizontal planet_position(const Planet planet, const double unix_utc, const Observer& observer,
                           const SkyOptions& options) {
  validate(unix_utc, observer, options);
  return seen(internal::planet_airless(planet, unix_utc, observer), options);
}

double planet_magnitude(const Planet planet, const double unix_utc) {
  require_finite(unix_utc, "unix_utc");
  return internal::planet_visual_magnitude(planet, unix_utc);
}

Horizontal galactic_position(const double galactic_longitude_deg,
                             const double galactic_latitude_deg, const double unix_utc,
                             const Observer& observer, const SkyOptions& options) {
  validate(unix_utc, observer, options);
  require_finite(galactic_longitude_deg, "galactic_longitude_deg");
  require_finite(galactic_latitude_deg, "galactic_latitude_deg");
  require(galactic_latitude_deg >= -90 && galactic_latitude_deg <= 90,
          "galactic_latitude_deg is not in the range [-90, 90]");
  const std::vector<internal::Galactic> direction{
      {.longitude_deg = galactic_longitude_deg, .latitude_deg = galactic_latitude_deg}};
  return seen(internal::galactic_airless(direction, unix_utc, observer).front(), options);
}

std::vector<Horizontal> galactic_plane(const double unix_utc, const Observer& observer,
                                       const double step_deg, const SkyOptions& options) {
  validate(unix_utc, observer, options);
  require_finite(step_deg, "step_deg");
  require(step_deg >= 0.01 && step_deg <= 90, "step_deg is not in the range [0.01, 90]");
  const auto count = static_cast<std::size_t>(std::ceil(360 / step_deg));
  std::vector<internal::Galactic> directions;
  directions.reserve(count);
  for (std::size_t i = 0; i < count; ++i) {
    const double longitude = static_cast<double>(i) * step_deg;
    if (longitude < 360) {
      directions.push_back({.longitude_deg = longitude, .latitude_deg = 0});
    }
  }
  std::vector<Horizontal> plane = internal::galactic_airless(directions, unix_utc, observer);
  for (Horizontal& point : plane) {
    point = seen(point, options);
  }
  return plane;
}

}  // namespace sunradar
