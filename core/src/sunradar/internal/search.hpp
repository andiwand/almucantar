#pragma once

#include "sunradar/internal/solar_model.hpp"
#include "sunradar/internal/vector.hpp"
#include "sunradar/search.hpp"

namespace sunradar::internal {

/// Implements `sunradar::find_time()` for the unit vector `direction`. The caller
/// validates the arguments. If `atmosphere` is null, the search uses the airless
/// position.
[[nodiscard]] TimeMatch nearest_time(const SolarModelImpl& model, const Observer& observer,
                                     Vec3 direction, double start_utc, double end_utc,
                                     double step_s, double tolerance_s,
                                     const Atmosphere* atmosphere);

}  // namespace sunradar::internal
