#include "sunradar/internal/search.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>

#include "sunradar/internal/math.hpp"
#include "sunradar/internal/numeric.hpp"
#include "sunradar/internal/refraction.hpp"

namespace sunradar::internal {

TimeMatch nearest_time(const SolarModelImpl& model, const Observer& observer, const Vec3 direction,
                       const double start_utc, const double end_utc, const double step_s,
                       const double tolerance_s, const Atmosphere* const atmosphere) {
  const auto position = [&](const double t) {
    const Horizontal h = model.sun(t, observer);
    return atmosphere != nullptr ? refracted(h, *atmosphere) : h;
  };
  // The angle in radians; atan2 keeps the precision near 0.
  const auto separation = [&](const double t) {
    const Vec3 s = to_enu(position(t));
    return std::atan2(norm(cross(s, direction)), dot(s, direction));
  };

  // Sample with a fixed step. The last sample is at the end.
  const double span = end_utc - start_utc;
  const auto steps = static_cast<std::size_t>(std::max(1.0, std::ceil(span / step_s)));
  const double step = span / static_cast<double>(steps);
  double best_t = start_utc;
  double best_s = separation(start_utc);
  for (std::size_t i = 1; i <= steps; ++i) {
    const double t = i == steps ? end_utc : start_utc + static_cast<double>(i) * step;
    const double s = separation(t);
    if (s < best_s) {
      best_s = s;
      best_t = t;
    }
  }

  // Refine between the neighbors of the best sample, and keep the better result.
  const double refined = golden_min(separation, std::max(start_utc, best_t - step),
                                    std::min(end_utc, best_t + step), tolerance_s);
  const double refined_s = separation(refined);
  if (refined_s < best_s) {
    best_s = refined_s;
    best_t = refined;
  }
  return TimeMatch{
      .unix_utc = best_t, .separation_deg = best_s * kRadToDeg, .sun = position(best_t)};
}

}  // namespace sunradar::internal
