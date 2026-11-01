#include "sunradar/internal/noaa_model.hpp"

#include <cmath>

#include "sunradar/internal/math.hpp"
#include "sunradar/internal/time.hpp"

namespace sunradar::internal {

Horizontal NoaaModel::sun(const double unix_utc, const Observer& observer) const {
  // Julian centuries since J2000.0.
  const double t = (julian_date(unix_utc) - 2451545.0) / 36525.0;

  // Geometric mean longitude, mean anomaly, and eccentricity of the orbit of the Earth.
  const double l0 = wrap_360(280.46646 + t * (36000.76983 + t * 0.0003032));
  const double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
  const double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);

  // Equation of the center, true longitude, and apparent longitude (with
  // aberration and the main term of the nutation).
  const double c = sin_deg(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                   sin_deg(2 * m) * (0.019993 - 0.000101 * t) + sin_deg(3 * m) * 0.000289;
  const double omega = 125.04 - 1934.136 * t;
  const double lambda = l0 + c - 0.00569 - 0.00478 * sin_deg(omega);

  // Obliquity of the ecliptic, with the correction for nutation.
  const double eps0 =
      23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0;
  const double eps = eps0 + 0.00256 * cos_deg(omega);

  const double declination = std::asin(sin_deg(eps) * sin_deg(lambda)) * kRadToDeg;

  // Equation of time, in degrees of hour angle.
  const double y = std::pow(std::tan(eps / 2 * kDegToRad), 2);
  const double eot = kRadToDeg * (y * sin_deg(2 * l0) - 2 * e * sin_deg(m) +
                                  4 * e * y * sin_deg(m) * cos_deg(2 * l0) -
                                  0.5 * y * y * sin_deg(4 * l0) - 1.25 * e * e * sin_deg(2 * m));

  // Hour angle: the angle of the Earth rotation since UTC midnight, minus 180
  // degrees, plus the equation of time and the longitude.
  const double seconds_of_day = unix_utc - 86400.0 * std::floor(unix_utc / 86400.0);
  const double hour_angle = wrap_180(seconds_of_day / 240.0 - 180.0 + eot + observer.longitude_deg);

  // Rotate into the East-North-Up frame of the observer. This form has no
  // singularity at the zenith or at the poles.
  const double sd = sin_deg(declination), cd = cos_deg(declination);
  const double sh = sin_deg(hour_angle), ch = cos_deg(hour_angle);
  const double sp = sin_deg(observer.latitude_deg), cp = cos_deg(observer.latitude_deg);
  const double east = -cd * sh;
  const double north = sd * cp - cd * ch * sp;
  const double up = sd * sp + cd * ch * cp;

  return Horizontal{
      .azimuth_deg = wrap_360(std::atan2(east, north) * kRadToDeg),
      .altitude_deg = std::atan2(up, std::hypot(east, north)) * kRadToDeg,
  };
}

}  // namespace sunradar::internal
