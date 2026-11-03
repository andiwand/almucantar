#pragma once

#include "almucantar/internal/solar_model.hpp"
#include "almucantar/sun.hpp"

namespace almucantar::internal {

/// Implements `almucantar::sun_events()`. The caller validates the arguments:
/// `end_utc > start_utc`, `search_step_s > 0`, and `tolerance_s > 0`.
[[nodiscard]] DayEvents find_events(const SolarModelImpl& model, const Observer& observer,
                                    double start_utc, double end_utc, double altitude_deg,
                                    double search_step_s, double tolerance_s);

}  // namespace almucantar::internal
