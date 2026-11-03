#pragma once

#include "almucantar/sun.hpp"
#include "almucantar/types.hpp"

namespace almucantar {

struct MoonOptions {
  bool refraction = true;  ///< add the refraction, to get the position that the observer sees
  Atmosphere atmosphere;   ///< used only if `refraction` is true
};

/// Returns the apparent topocentric position of the center of the moon at `unix_utc` for
/// `observer` (Astronomy Engine). The moon is near, so the place of the observer changes its
/// position by up to 1°. Throws `std::invalid_argument` if an argument is not valid.
[[nodiscard]] Horizontal moon_position(double unix_utc, const Observer& observer,
                                       const MoonOptions& options = {});

/// Returns the illuminated fraction of the disc of the moon at `unix_utc`: 0 at new moon, 1 at
/// full moon. Throws `std::invalid_argument` if the time is not finite.
[[nodiscard]] double moon_illuminated_fraction(double unix_utc);

}  // namespace almucantar
