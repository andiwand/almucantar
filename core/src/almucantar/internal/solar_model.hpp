#pragma once

#include "almucantar/sun.hpp"
#include "almucantar/types.hpp"

namespace almucantar::internal {

/// A model that calculates the position of the sun.
///
/// The position is the apparent position of the center of the sun, without
/// refraction ("airless"). It includes aberration and nutation.
class SolarModelImpl {
 public:
  SolarModelImpl() = default;
  SolarModelImpl(const SolarModelImpl&) = delete;
  SolarModelImpl& operator=(const SolarModelImpl&) = delete;
  SolarModelImpl(SolarModelImpl&&) = delete;
  SolarModelImpl& operator=(SolarModelImpl&&) = delete;
  virtual ~SolarModelImpl() = default;

  /// Returns the position of the sun at the time `unix_utc` for `observer`.
  [[nodiscard]] virtual Horizontal sun(double unix_utc, const Observer& observer) const = 0;
};

/// Returns the implementation of `model`. The object lives until the program ends.
/// Throws `std::invalid_argument` for a value that is not an enumerator.
[[nodiscard]] const SolarModelImpl& solar_model(SolarModel model);

}  // namespace almucantar::internal
