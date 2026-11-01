#pragma once

#include <optional>
#include <vector>

#include "sunradar/solar_model.hpp"
#include "sunradar/types.hpp"

namespace sunradar {

struct PathOptions {
  /// The time between two points of the path.
  double step_s = 300;

  /// If set, the path contains the refracted altitude, which is what the observer
  /// sees. If not set, the path contains the airless altitude.
  std::optional<Atmosphere> atmosphere = Atmosphere{};
};

struct PathPoint {
  double unix_utc = 0;
  Horizontal position;
};

/// Calculates the path of the sun at the times `start_utc + k * step_s`, for all
/// k >= 0 with a time not after `end_utc`. The path includes the points below the
/// horizon, so the caller can draw them differently.
///
/// If `start_utc` is the start of a local day, the points fall on the full hours
/// of local time when 3600 is a multiple of `step_s`.
[[nodiscard]] std::vector<PathPoint> sun_path(const SolarModel& model, const Observer& observer,
                                              double start_utc, double end_utc,
                                              const PathOptions& options = {});

}  // namespace sunradar
