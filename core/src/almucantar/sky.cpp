#include "almucantar/sky.hpp"

#include <cmath>
#include <cstddef>

#include "almucantar/internal/astronomy_engine.hpp"
#include "almucantar/internal/math.hpp"
#include "almucantar/internal/refraction.hpp"
#include "almucantar/internal/time.hpp"
#include "almucantar/internal/validate.hpp"

namespace almucantar {

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

std::vector<Horizontal> star_positions(const StarCatalog& catalog, const double unix_utc,
                                       const Observer& observer, const SkyOptions& options) {
  validate(unix_utc, observer, options);
  const std::size_t count = catalog.ra_deg.size();
  require(catalog.dec_deg.size() == count, "dec_deg does not have the length of ra_deg");
  const bool moves =
      !catalog.pm_ra_arcsec_per_year.empty() || !catalog.pm_dec_arcsec_per_year.empty();
  if (moves) {
    require(catalog.pm_ra_arcsec_per_year.size() == count,
            "pm_ra_arcsec_per_year does not have the length of ra_deg");
    require(catalog.pm_dec_arcsec_per_year.size() == count,
            "pm_dec_arcsec_per_year does not have the length of ra_deg");
  }
  // The proper motion is linear in the years since the epoch 2000.0 (Julian years).
  const double years = (internal::julian_date(unix_utc) - 2451545.0) / 365.25;
  std::vector<internal::EquatorialJ2000> directions;
  directions.reserve(count);
  for (std::size_t i = 0; i < count; ++i) {
    double ra = catalog.ra_deg[i];
    double dec = catalog.dec_deg[i];
    require_finite(ra, "ra_deg");
    require_finite(dec, "dec_deg");
    require(dec >= -90 && dec <= 90, "dec_deg is not in the range [-90, 90]");
    if (moves) {
      const double pm_ra = catalog.pm_ra_arcsec_per_year[i];
      const double pm_dec = catalog.pm_dec_arcsec_per_year[i];
      require_finite(pm_ra, "pm_ra_arcsec_per_year");
      require_finite(pm_dec, "pm_dec_arcsec_per_year");
      // pm_ra includes cos(dec). At a pole, the right ascension has no meaning, so it stays.
      const double cos_dec = internal::cos_deg(dec);
      if (cos_dec > 1e-9) {
        ra += pm_ra * years / 3600 / cos_dec;
      }
      dec += pm_dec * years / 3600;
    }
    directions.push_back({.ra_deg = ra, .dec_deg = dec});
  }
  std::vector<Horizontal> positions = internal::stars_airless(directions, unix_utc, observer);
  for (Horizontal& position : positions) {
    position = seen(position, options);
  }
  return positions;
}

}  // namespace almucantar
