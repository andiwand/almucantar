#include "almucantar/internal/validate.hpp"

#include <cmath>
#include <stdexcept>
#include <string>
#include <utility>

namespace almucantar::internal {

void require(const bool condition, const char* const message) {
  if (!condition) {
    throw std::invalid_argument(message);
  }
}

void require_finite(const double value, const char* const name) {
  if (!std::isfinite(value)) {
    throw std::invalid_argument(std::string(name) + " is not finite");
  }
}

void require_positive(const double value, const char* const name) {
  require_finite(value, name);
  if (value <= 0) {
    throw std::invalid_argument(std::string(name) + " is not positive");
  }
}

void validate(const Quaternion& q, const char* const name) {
  const std::string prefix(name);
  for (const auto& [value, component] :
       {std::pair{q.w, ".w"}, std::pair{q.x, ".x"}, std::pair{q.y, ".y"}, std::pair{q.z, ".z"}}) {
    require_finite(value, (prefix + component).c_str());
  }
  if (q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z <= 1e-12) {
    throw std::invalid_argument(prefix + " has length 0");
  }
}

void validate(const Observer& observer) {
  require_finite(observer.latitude_deg, "latitude_deg");
  require_finite(observer.longitude_deg, "longitude_deg");
  require(observer.latitude_deg >= -90 && observer.latitude_deg <= 90,
          "latitude_deg is not in the range [-90, 90]");
}

void validate(const Atmosphere& atmosphere) {
  require_finite(atmosphere.pressure_hpa, "pressure_hpa");
  require_finite(atmosphere.temperature_c, "temperature_c");
  require(atmosphere.pressure_hpa >= 0, "pressure_hpa is negative");
  require(atmosphere.temperature_c > -273.15, "temperature_c is below absolute zero");
}

}  // namespace almucantar::internal
