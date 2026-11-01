#include "sunradar/internal/astronomy_engine.hpp"

#include <astronomy.h>

#include <stdexcept>

#include "sunradar/internal/math.hpp"
#include "sunradar/internal/time.hpp"

namespace sunradar::internal {

namespace {

/// Astronomy Engine counts the time in days since J2000.0 (2000-01-01T12:00 UT).
astro_time_t to_astro_time(const double unix_utc) {
  return Astronomy_TimeFromDays(julian_date(unix_utc) - 2451545.0);
}

Horizontal topocentric(const astro_body_t body, const double unix_utc, const Observer& observer) {
  astro_time_t time = to_astro_time(unix_utc);
  const astro_observer_t where =
      Astronomy_MakeObserver(observer.latitude_deg, observer.longitude_deg, 0.0);
  const astro_equatorial_t equator =
      Astronomy_Equator(body, &time, where, EQUATOR_OF_DATE, ABERRATION);
  if (equator.status != ASTRO_SUCCESS) {
    throw std::runtime_error("Astronomy Engine failed to calculate the position");
  }
  const astro_horizon_t horizon =
      Astronomy_Horizon(&time, where, equator.ra, equator.dec, REFRACTION_NONE);
  return {.azimuth_deg = wrap_360(horizon.azimuth), .altitude_deg = horizon.altitude};
}

}  // namespace

Horizontal AstronomyEngineModel::sun(const double unix_utc, const Observer& observer) const {
  return topocentric(BODY_SUN, unix_utc, observer);
}

Horizontal moon_airless(const double unix_utc, const Observer& observer) {
  return topocentric(BODY_MOON, unix_utc, observer);
}

double moon_fraction(const double unix_utc) {
  const astro_illum_t illumination = Astronomy_Illumination(BODY_MOON, to_astro_time(unix_utc));
  if (illumination.status != ASTRO_SUCCESS) {
    throw std::runtime_error("Astronomy Engine failed to calculate the illumination");
  }
  return illumination.phase_fraction;
}

}  // namespace sunradar::internal
