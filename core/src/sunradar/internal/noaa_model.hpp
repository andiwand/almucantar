#pragma once

#include "sunradar/internal/solar_model.hpp"

namespace sunradar::internal {

/// The equations of the NOAA solar calculator, which come from Meeus,
/// "Astronomical Algorithms", chapter 25 (low accuracy).
///
/// The angular error against JPL Horizons is less than 0.02 degrees for the
/// years 1950 to 2100 (see the tests). The model does not use the difference
/// between TT and UT, because this changes the result by less than 0.001 degrees.
class NoaaModel final : public SolarModelImpl {
 public:
  [[nodiscard]] Horizontal sun(double unix_utc, const Observer& observer) const override;
};

}  // namespace sunradar::internal
