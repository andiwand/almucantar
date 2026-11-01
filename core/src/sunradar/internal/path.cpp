#include "sunradar/internal/path.hpp"

#include <cmath>
#include <cstddef>

#include "sunradar/internal/refraction.hpp"

namespace sunradar::internal {

std::vector<PathPoint> compute_path(const SolarModelImpl& model, const Observer& observer,
                                    const double start_utc, const double end_utc,
                                    const double step_s, const Atmosphere* const atmosphere) {
  std::vector<PathPoint> path;
  // Count the points first, so that the times do not collect rounding errors.
  const auto count = static_cast<std::size_t>(std::floor((end_utc - start_utc) / step_s)) + 1;
  path.reserve(count);
  for (std::size_t k = 0; k < count; ++k) {
    const double t = start_utc + static_cast<double>(k) * step_s;
    Horizontal position = model.sun(t, observer);
    if (atmosphere != nullptr) {
      position = refracted(position, *atmosphere);
    }
    path.push_back(PathPoint{.unix_utc = t, .position = position});
  }
  return path;
}

}  // namespace sunradar::internal
