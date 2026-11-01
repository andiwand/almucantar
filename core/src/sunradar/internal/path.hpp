#pragma once

#include <vector>

#include "sunradar/internal/solar_model.hpp"
#include "sunradar/sun.hpp"

namespace sunradar::internal {

/// Implements `sunradar::sun_path()`. The caller validates the arguments:
/// `end_utc >= start_utc` and `step_s > 0`. If `atmosphere` is null, the path has
/// the airless altitude.
[[nodiscard]] std::vector<PathPoint> compute_path(const SolarModelImpl& model,
                                                  const Observer& observer, double start_utc,
                                                  double end_utc, double step_s,
                                                  const Atmosphere* atmosphere);

}  // namespace sunradar::internal
