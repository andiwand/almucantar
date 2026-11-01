#include "sunradar/magnetic.hpp"

#include <cmath>

#include "sunradar/internal/math.hpp"
#include "sunradar/internal/time.hpp"
#include "sunradar/internal/validate.hpp"
#include "sunradar/internal/wmm.hpp"

namespace sunradar {

double magnetic_declination_deg(const Observer& observer, const double unix_utc) {
  internal::require_finite(unix_utc, "unix_utc");
  internal::validate(observer);
  const internal::MagneticField field =
      internal::wmm_field(internal::wmm_coefficients(), observer.latitude_deg,
                          observer.longitude_deg, 0.0, internal::decimal_year(unix_utc));
  return std::atan2(field.east_nt, field.north_nt) * internal::kRadToDeg;
}

}  // namespace sunradar
