#pragma once

#include <cstdint>
#include <vector>

#include "sunradar/types.hpp"

namespace sunradar {

/// The planets that Astronomy Engine calculates.
enum class Planet : std::uint8_t {
  mercury,
  venus,
  mars,
  jupiter,
  saturn,
  uranus,
  neptune,
};

struct SkyOptions {
  bool refraction = true;  ///< add the refraction, to get the position that the observer sees
  Atmosphere atmosphere;   ///< used only if `refraction` is true
};

/// Returns the apparent topocentric position of the center of `planet` at `unix_utc` for
/// `observer` (Astronomy Engine). Throws `std::invalid_argument` if an argument is not valid.
[[nodiscard]] Horizontal planet_position(Planet planet, double unix_utc, const Observer& observer,
                                         const SkyOptions& options = {});

/// Returns the visual magnitude of `planet` at `unix_utc`, as seen from the center of the Earth.
/// A smaller value is brighter: Venus is about -4, Saturn about 0 to 1, and the eye sees about 6
/// in a dark sky. Throws `std::invalid_argument` if an argument is not valid.
[[nodiscard]] double planet_magnitude(Planet planet, double unix_utc);

/// The center of the Milky Way (Sagittarius A*) in galactic coordinates.
inline constexpr double kGalacticCenterLongitudeDeg = 0;
inline constexpr double kGalacticCenterLatitudeDeg = 0;

/// Returns the position of a fixed direction in the galactic system (IAU 1958), for example a
/// point of the Milky Way, at `unix_utc` for `observer`. The function includes the precession and
/// the nutation, but not the aberration (at most 0.006°). Throws `std::invalid_argument` if an
/// argument is not valid.
[[nodiscard]] Horizontal galactic_position(double galactic_longitude_deg,
                                           double galactic_latitude_deg, double unix_utc,
                                           const Observer& observer,
                                           const SkyOptions& options = {});

/// Returns the galactic plane, the center line of the Milky Way, at `unix_utc` for `observer`.
/// The point `i` is at the galactic longitude `i * step_deg`, from 0 (the galactic center) to less
/// than 360. The line includes the points below the horizon. Throws `std::invalid_argument` if
/// `step_deg` is not in the range [0.01, 90], or if an argument is not valid.
[[nodiscard]] std::vector<Horizontal> galactic_plane(double unix_utc, const Observer& observer,
                                                     double step_deg = 1,
                                                     const SkyOptions& options = {});

}  // namespace sunradar
