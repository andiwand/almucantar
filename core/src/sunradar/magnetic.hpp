#pragma once

#include "sunradar/types.hpp"

namespace sunradar {

/// Returns the magnetic declination at `observer` and `unix_utc`: the angle from true
/// north to magnetic north, positive to the east. Add it to a magnetic azimuth to get
/// the true azimuth.
///
/// The model is the World Magnetic Model 2025 (WMM2025) at the height of the
/// ellipsoid. It is valid from 2025-01-01 to 2029-12-31. Outside that period the
/// function extrapolates, and the error increases. The model does not know local
/// anomalies, for example the steel in a building. Throws `std::invalid_argument` if
/// an argument is not finite or not in its range.
[[nodiscard]] double magnetic_declination_deg(const Observer& observer, double unix_utc);

}  // namespace sunradar
