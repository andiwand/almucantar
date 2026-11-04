#include "almucantar/eclipse.hpp"

#include <astronomy.h>

#include <cmath>
#include <limits>
#include <stdexcept>
#include <vector>

#include "almucantar/internal/time.hpp"
#include "almucantar/internal/validate.hpp"

namespace almucantar {

namespace {

using internal::require;
using internal::require_finite;

constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
constexpr double kDayS = 86'400;
/// The Unix time of J2000.0 (2000-01-01T12:00 UTC), the zero of the time of Astronomy Engine.
constexpr double kJ2000Unix = 946'728'000;
constexpr double kMaxIntervalS = 100 * 366 * kDayS;
/// The mean radius of the moon, the value of Astronomy Engine for the eclipses.
constexpr double kMoonRadiusKm = 1737.4;

astro_time_t to_astro_time(const double unix_utc) {
  return Astronomy_TimeFromDays(internal::julian_date(unix_utc) - 2451545.0);
}

double to_unix(const astro_time_t& time) { return (time.ut * kDayS) + kJ2000Unix; }

EclipseKind to_kind(const astro_eclipse_kind_t kind) {
  switch (kind) {
    case ECLIPSE_PENUMBRAL:
      return EclipseKind::penumbral;
    case ECLIPSE_ANNULAR:
      return EclipseKind::annular;
    case ECLIPSE_TOTAL:
      return EclipseKind::total;
    default:
      return EclipseKind::partial;
  }
}

void validate_interval(const double start_utc, const double end_utc) {
  require_finite(start_utc, "start_utc");
  require_finite(end_utc, "end_utc");
  require(end_utc > start_utc, "end_utc is not after start_utc");
  require(end_utc - start_utc <= kMaxIntervalS, "the interval is longer than 100 years");
}

/// Throws if Astronomy Engine failed. The searches of eclipses do not fail for valid times.
void check(const astro_status_t status) {
  if (status != ASTRO_SUCCESS) {
    throw std::runtime_error("Astronomy Engine failed to search the eclipses");
  }
}

}  // namespace

std::vector<LunarEclipse> lunar_eclipses(const double start_utc, const double end_utc) {
  validate_interval(start_utc, end_utc);
  std::vector<LunarEclipse> result;
  astro_lunar_eclipse_t e = Astronomy_SearchLunarEclipse(to_astro_time(start_utc));
  for (;;) {
    check(e.status);
    const double peak = to_unix(e.peak);
    if (peak > end_utc) {
      break;
    }
    // A semi-duration of 0 means that the phase does not happen.
    const auto phase = [peak](const double minutes, const double sign) {
      return minutes > 0 ? peak + (sign * minutes * 60) : kNaN;
    };
    if (peak >= start_utc) {
      result.push_back({.kind = to_kind(e.kind),
                        .peak_utc = peak,
                        .obscuration = e.obscuration,
                        .penumbral_begin_utc = phase(e.sd_penum, -1),
                        .partial_begin_utc = phase(e.sd_partial, -1),
                        .total_begin_utc = phase(e.sd_total, -1),
                        .total_end_utc = phase(e.sd_total, 1),
                        .partial_end_utc = phase(e.sd_partial, 1),
                        .penumbral_end_utc = phase(e.sd_penum, 1)});
    }
    e = Astronomy_NextLunarEclipse(e.peak);
  }
  return result;
}

std::vector<SolarEclipse> solar_eclipses(const double start_utc, const double end_utc) {
  validate_interval(start_utc, end_utc);
  std::vector<SolarEclipse> result;
  astro_global_solar_eclipse_t e = Astronomy_SearchGlobalSolarEclipse(to_astro_time(start_utc));
  for (;;) {
    check(e.status);
    const double peak = to_unix(e.peak);
    if (peak > end_utc) {
      break;
    }
    const bool central = e.kind == ECLIPSE_TOTAL || e.kind == ECLIPSE_ANNULAR;
    if (peak >= start_utc) {
      result.push_back(
          {.kind = to_kind(e.kind),
           .peak_utc = peak,
           .peak_place = central
                             ? Observer{.latitude_deg = e.latitude, .longitude_deg = e.longitude}
                             : Observer{.latitude_deg = kNaN, .longitude_deg = kNaN},
           .obscuration = central ? e.obscuration : kNaN});
    }
    e = Astronomy_NextGlobalSolarEclipse(e.peak);
  }
  return result;
}

std::vector<LocalSolarEclipse> local_solar_eclipses(const Observer& observer,
                                                    const double start_utc, const double end_utc) {
  internal::validate(observer);
  validate_interval(start_utc, end_utc);
  const astro_observer_t where =
      Astronomy_MakeObserver(observer.latitude_deg, observer.longitude_deg, 0.0);
  std::vector<LocalSolarEclipse> result;
  astro_local_solar_eclipse_t e =
      Astronomy_SearchLocalSolarEclipse(to_astro_time(start_utc), where);
  for (;;) {
    check(e.status);
    const double peak = to_unix(e.peak.time);
    if (peak > end_utc) {
      break;
    }
    const bool central = e.kind == ECLIPSE_TOTAL || e.kind == ECLIPSE_ANNULAR;
    if (peak >= start_utc) {
      result.push_back({.kind = to_kind(e.kind),
                        .obscuration = e.obscuration,
                        .partial_begin_utc = to_unix(e.partial_begin.time),
                        .total_begin_utc = central ? to_unix(e.total_begin.time) : kNaN,
                        .peak_utc = peak,
                        .total_end_utc = central ? to_unix(e.total_end.time) : kNaN,
                        .partial_end_utc = to_unix(e.partial_end.time),
                        .peak_sun_altitude_deg = e.peak.altitude});
    }
    e = Astronomy_NextLocalSolarEclipse(e.peak.time, where);
  }
  return result;
}

std::vector<ShadowPoint> eclipse_central_line(const double peak_utc, const double step_s) {
  require_finite(peak_utc, "peak_utc");
  require_finite(step_s, "step_s");
  require(step_s >= 1 && step_s <= 3600, "step_s is not in the range [1, 3600]");
  std::vector<ShadowPoint> line;
  const double span = 4 * 3600.0;
  const auto steps = static_cast<int>(std::floor(2 * span / step_s));
  for (int i = 0; i <= steps; ++i) {
    const double t = peak_utc - span + (i * step_s);
    astro_time_t time = to_astro_time(t);
    // The axis of the shadow: from the moon away from the sun, in the equator of date, in km.
    const astro_rotation_t rotation = Astronomy_Rotation_EQJ_EQD(&time);
    const astro_vector_t moon_j2000 = Astronomy_GeoMoon(time);
    const astro_vector_t sun_j2000 = Astronomy_GeoVector(BODY_SUN, time, NO_ABERRATION);
    check(moon_j2000.status);
    check(sun_j2000.status);
    const astro_vector_t moon = Astronomy_RotateVector(rotation, moon_j2000);
    const astro_vector_t sun = Astronomy_RotateVector(rotation, sun_j2000);
    const double mx = moon.x * KM_PER_AU;
    const double my = moon.y * KM_PER_AU;
    const double mz = moon.z * KM_PER_AU;
    double dx = mx - (sun.x * KM_PER_AU);
    double dy = my - (sun.y * KM_PER_AU);
    double dz = mz - (sun.z * KM_PER_AU);
    const double sun_moon_km = std::sqrt((dx * dx) + (dy * dy) + (dz * dz));
    dx /= sun_moon_km;
    dy /= sun_moon_km;
    dz /= sun_moon_km;
    // The axis meets the ellipsoid x² + y² + (z / f)² = R²: a quadratic in the distance s from the
    // moon. The smaller root is the side of the Earth that faces the moon.
    const double f = EARTH_FLATTENING;
    const double r = EARTH_EQUATORIAL_RADIUS_KM;
    const double a = (dx * dx) + (dy * dy) + ((dz * dz) / (f * f));
    const double b = 2 * ((mx * dx) + (my * dy) + ((mz * dz) / (f * f)));
    const double c = (mx * mx) + (my * my) + ((mz * mz) / (f * f)) - (r * r);
    const double discriminant = (b * b) - (4 * a * c);
    if (discriminant < 0) {
      continue;  // the axis passes by the Earth
    }
    const double s = (-b - std::sqrt(discriminant)) / (2 * a);
    if (s < 0) {
      continue;
    }
    astro_vector_t ground{.status = ASTRO_SUCCESS,
                          .x = (mx + (s * dx)) / KM_PER_AU,
                          .y = (my + (s * dy)) / KM_PER_AU,
                          .z = (mz + (s * dz)) / KM_PER_AU,
                          .t = time};
    const astro_observer_t place = Astronomy_VectorObserver(&ground, EQUATOR_OF_DATE);
    // The cone of the umbra: the radius of the moon, less the part that the sun takes away
    // over the distance from the moon.
    const double umbra = kMoonRadiusKm - (s * (SUN_RADIUS_KM - kMoonRadiusKm) / sun_moon_km);
    line.push_back({.unix_utc = t,
                    .place = {.latitude_deg = place.latitude, .longitude_deg = place.longitude},
                    .umbra_radius_km = umbra});
  }
  return line;
}

}  // namespace almucantar
