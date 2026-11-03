#include "almucantar/internal/refraction.hpp"

#include <algorithm>

#include "almucantar/internal/math.hpp"

namespace almucantar::internal {

double refraction_deg(const double altitude_deg, const Atmosphere& atmosphere) noexcept {
  // Below -1 degree the formula is not valid, and at -5.11 degrees it has a pole.
  const double h = std::max(altitude_deg, -1.0);
  const double r_arcmin = 1.02 / tan_deg(h + 10.3 / (h + 5.11));
  const double scale =
      (atmosphere.pressure_hpa / 1010.0) * (283.0 / (273.0 + atmosphere.temperature_c));
  // Near the zenith the formula gives a very small negative value.
  return std::max(r_arcmin * scale / 60.0, 0.0);
}

Horizontal refracted(Horizontal position, const Atmosphere& atmosphere) noexcept {
  position.altitude_deg += refraction_deg(position.altitude_deg, atmosphere);
  return position;
}

}  // namespace almucantar::internal
