#pragma once

#include <vector>

#include "almucantar/internal/solar_model.hpp"
#include "almucantar/sun.hpp"

namespace almucantar::internal {

/// Implements `almucantar::sun_path()`. The caller validates the arguments:
/// `end_utc >= start_utc` and `step_s > 0`. If `atmosphere` is null, the path has
/// the airless altitude.
[[nodiscard]] std::vector<PathPoint> compute_path(const SolarModelImpl& model,
                                                  const Observer& observer, double start_utc,
                                                  double end_utc, double step_s,
                                                  const Atmosphere* atmosphere);

}  // namespace almucantar::internal
