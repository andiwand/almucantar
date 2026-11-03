#pragma once

#include <cstdint>
#include <vector>

#include "almucantar/types.hpp"

namespace almucantar {

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

/// A catalogue of stars as columns of the same length, so a binding passes it as arrays. The
/// positions are J2000 at the epoch 2000.0. An empty proper motion column means 0 for all stars.
struct StarCatalog {
  std::vector<double> ra_deg;   ///< right ascension, J2000, in degrees
  std::vector<double> dec_deg;  ///< declination, J2000, in degrees
  /// The proper motion in right ascension, d(ra)/dt * cos(dec), in arcseconds per year (the
  /// convention of the Bright Star Catalogue and of Hipparcos).
  std::vector<double> pm_ra_arcsec_per_year;
  std::vector<double> pm_dec_arcsec_per_year;  ///< the proper motion in declination
};

/// Returns the apparent positions of all stars of `catalog` at `unix_utc` for `observer`, in the
/// order of the catalogue. The function applies the proper motion, the precession, the nutation,
/// and the annual aberration (Astronomy Engine), and the refraction of `options`. It ignores the
/// parallax (at most 0.0002° for a bright star). One call for all stars is fast: a frame projects
/// the result with `project_points()`. Throws `std::invalid_argument` if the columns do not have
/// the same length, if a value is not finite, or if an argument is not valid.
[[nodiscard]] std::vector<Horizontal> star_positions(const StarCatalog& catalog, double unix_utc,
                                                     const Observer& observer,
                                                     const SkyOptions& options = {});

}  // namespace almucantar
