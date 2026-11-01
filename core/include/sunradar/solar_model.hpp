#pragma once

#include "sunradar/types.hpp"

namespace sunradar {

/// A model that calculates the position of the sun.
///
/// The position is the apparent position of the center of the sun, without
/// refraction ("airless"). It includes aberration and nutation. To get the
/// position that an observer sees, apply refraction() to the altitude.
class SolarModel {
 public:
  virtual ~SolarModel() = default;

  /// Returns the position of the sun at the time `unix_utc` for `observer`.
  [[nodiscard]] virtual Horizontal sun(double unix_utc, const Observer& observer) const = 0;
};

}  // namespace sunradar
