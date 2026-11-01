#include "sunradar/path.hpp"

#include <cmath>
#include <cstddef>

#include "sunradar/refraction.hpp"

namespace sunradar {

std::vector<PathPoint> sun_path(const SolarModel& model, const Observer& observer,
                                double start_utc, double end_utc, const PathOptions& options) {
  std::vector<PathPoint> path;
  if (!(options.step_s > 0) || end_utc < start_utc) {
    return path;
  }
  // Count the points first, so that the times do not collect rounding errors.
  const auto count = static_cast<std::size_t>(std::floor((end_utc - start_utc) / options.step_s)) + 1;
  path.reserve(count);
  for (std::size_t k = 0; k < count; ++k) {
    const double t = start_utc + static_cast<double>(k) * options.step_s;
    Horizontal position = model.sun(t, observer);
    if (options.atmosphere) {
      position = refracted(position, *options.atmosphere);
    }
    path.push_back(PathPoint{.unix_utc = t, .position = position});
  }
  return path;
}

}  // namespace sunradar
