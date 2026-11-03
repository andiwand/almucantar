#include "almucantar/magnetic.hpp"

#include <cmath>

#include "almucantar/internal/math.hpp"
#include "almucantar/internal/time.hpp"
#include "almucantar/internal/validate.hpp"
#include "almucantar/internal/wmm.hpp"

namespace almucantar {

double magnetic_declination_deg(const Observer& observer, const double unix_utc) {
  internal::require_finite(unix_utc, "unix_utc");
  internal::validate(observer);
  const internal::MagneticField field =
      internal::wmm_field(internal::wmm_coefficients(), observer.latitude_deg,
                          observer.longitude_deg, 0.0, internal::decimal_year(unix_utc));
  return std::atan2(field.east_nt, field.north_nt) * internal::kRadToDeg;
}

}  // namespace almucantar
