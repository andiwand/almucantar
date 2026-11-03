#include "almucantar/internal/astronomy_engine.hpp"

#include <astronomy.h>

#include <stdexcept>

#include "almucantar/internal/math.hpp"
#include "almucantar/internal/time.hpp"

namespace almucantar::internal {

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

astro_body_t to_body(const Planet planet) {
  switch (planet) {
    case Planet::mercury:
      return BODY_MERCURY;
    case Planet::venus:
      return BODY_VENUS;
    case Planet::mars:
      return BODY_MARS;
    case Planet::jupiter:
      return BODY_JUPITER;
    case Planet::saturn:
      return BODY_SATURN;
    case Planet::uranus:
      return BODY_URANUS;
    case Planet::neptune:
      return BODY_NEPTUNE;
  }
  throw std::invalid_argument("planet is not a Planet");
}

}  // namespace

Horizontal AstronomyEngineModel::sun(const double unix_utc, const Observer& observer) const {
  return topocentric(BODY_SUN, unix_utc, observer);
}

Horizontal moon_airless(const double unix_utc, const Observer& observer) {
  return topocentric(BODY_MOON, unix_utc, observer);
}

Horizontal planet_airless(const Planet planet, const double unix_utc, const Observer& observer) {
  return topocentric(to_body(planet), unix_utc, observer);
}

double planet_visual_magnitude(const Planet planet, const double unix_utc) {
  const astro_illum_t illumination =
      Astronomy_Illumination(to_body(planet), to_astro_time(unix_utc));
  if (illumination.status != ASTRO_SUCCESS) {
    throw std::runtime_error("Astronomy Engine failed to calculate the illumination");
  }
  return illumination.mag;
}

std::vector<Horizontal> galactic_airless(const std::vector<Galactic>& directions,
                                         const double unix_utc, const Observer& observer) {
  astro_time_t time = to_astro_time(unix_utc);
  const astro_observer_t where =
      Astronomy_MakeObserver(observer.latitude_deg, observer.longitude_deg, 0.0);
  // One rotation for all directions: galactic to J2000, then J2000 to the horizon of date.
  const astro_rotation_t rotation = Astronomy_CombineRotation(
      Astronomy_Rotation_GAL_EQJ(), Astronomy_Rotation_EQJ_HOR(&time, where));
  if (rotation.status != ASTRO_SUCCESS) {
    throw std::runtime_error("Astronomy Engine failed to calculate the rotation");
  }
  std::vector<Horizontal> result;
  result.reserve(directions.size());
  for (const Galactic& direction : directions) {
    const astro_spherical_t sphere{.status = ASTRO_SUCCESS,
                                   .lat = direction.latitude_deg,
                                   .lon = direction.longitude_deg,
                                   .dist = 1.0};
    const astro_vector_t horizon =
        Astronomy_RotateVector(rotation, Astronomy_VectorFromSphere(sphere, time));
    const astro_spherical_t position = Astronomy_HorizonFromVector(horizon, REFRACTION_NONE);
    if (position.status != ASTRO_SUCCESS) {
      throw std::runtime_error("Astronomy Engine failed to calculate the position");
    }
    result.push_back({.azimuth_deg = wrap_360(position.lon), .altitude_deg = position.lat});
  }
  return result;
}

double moon_fraction(const double unix_utc) {
  const astro_illum_t illumination = Astronomy_Illumination(BODY_MOON, to_astro_time(unix_utc));
  if (illumination.status != ASTRO_SUCCESS) {
    throw std::runtime_error("Astronomy Engine failed to calculate the illumination");
  }
  return illumination.phase_fraction;
}

}  // namespace almucantar::internal
