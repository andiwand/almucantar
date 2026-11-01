#pragma once

#include "sunradar/types.hpp"

namespace sunradar::internal {

/// Returns the atmospheric refraction in degrees for an object at the airless
/// altitude `altitude_deg`. Add the result to the altitude to get the altitude
/// that the observer sees.
///
/// The function uses the formula of Saemundsson (Sky and Telescope, 1986), with a
/// correction for the pressure and the temperature. JPL Horizons uses the same
/// formula. The formula is not valid far below the horizon, so the function keeps
/// the value of -1 degree for all lower altitudes. The function is continuous, and
/// the apparent altitude increases monotonically with the airless altitude.
[[nodiscard]] double refraction_deg(double altitude_deg,
                                    const Atmosphere& atmosphere = {}) noexcept;

/// Returns `position` with the refraction added to the altitude.
[[nodiscard]] Horizontal refracted(Horizontal position, const Atmosphere& atmosphere = {}) noexcept;

}  // namespace sunradar::internal
