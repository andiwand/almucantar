#pragma once

#include <cstdint>
#include <vector>

#include "almucantar/types.hpp"

/// The solar and the lunar eclipses (Astronomy Engine), for the planner of Alidade: when an
/// eclipse happens, how much of the sun or the moon it covers, the times of its phases, and the
/// central line of a total or annular solar eclipse.

namespace almucantar {

enum class EclipseKind : std::uint8_t {
  penumbral,  ///< a lunar eclipse in the penumbra of the Earth only (never a solar eclipse)
  partial,
  annular,  ///< a solar eclipse with a ring of the sun around the moon (never a lunar eclipse)
  total,
};

/// A lunar eclipse. It looks the same from every place where the moon is up.
struct LunarEclipse {
  EclipseKind kind = EclipseKind::penumbral;
  double peak_utc = 0;
  /// The part of the disc of the moon in the umbra of the Earth at the peak: 0 for a penumbral
  /// eclipse, 1 for a total one.
  double obscuration = 0;
  /// The times of the phases. A phase that does not happen has NaN.
  double penumbral_begin_utc = 0;
  double partial_begin_utc = 0;
  double total_begin_utc = 0;
  double total_end_utc = 0;
  double partial_end_utc = 0;
  double penumbral_end_utc = 0;
};

/// Returns the lunar eclipses with the peak between `start_utc` and `end_utc`, in time order.
/// Throws `std::invalid_argument` if `end_utc <= start_utc` or if the interval is longer than
/// 100 years.
[[nodiscard]] std::vector<LunarEclipse> lunar_eclipses(double start_utc, double end_utc);

/// A solar eclipse for the whole Earth.
struct SolarEclipse {
  /// Partial if no place sees the sun fully covered or as a ring.
  EclipseKind kind = EclipseKind::partial;
  /// The time when the axis of the shadow of the moon is nearest to the center of the Earth.
  double peak_utc = 0;
  /// The center of the shadow at the peak, for a total or annular eclipse; else NaN.
  Observer peak_place;
  /// The part of the area of the sun that the moon covers at `peak_place`; NaN for a partial one.
  double obscuration = 0;
};

/// Returns the solar eclipses with the peak between `start_utc` and `end_utc`, in time order.
/// Throws `std::invalid_argument` if `end_utc <= start_utc` or if the interval is longer than
/// 100 years.
[[nodiscard]] std::vector<SolarEclipse> solar_eclipses(double start_utc, double end_utc);

/// A solar eclipse seen from one place.
struct LocalSolarEclipse {
  EclipseKind kind = EclipseKind::partial;
  /// The part of the area of the sun that the moon covers at the peak, 0 to 1.
  double obscuration = 0;
  double partial_begin_utc = 0;
  /// The second and the third contact of a total or annular eclipse; else NaN.
  double total_begin_utc = 0;
  double peak_utc = 0;
  double total_end_utc = 0;
  double partial_end_utc = 0;
  /// The altitude of the center of the sun with refraction at the peak. Below 0, the place does
  /// not see the peak.
  double peak_sun_altitude_deg = 0;
};

/// Returns the solar eclipses that `observer` can see if the sun is up, with the peak between
/// `start_utc` and `end_utc`, in time order. The list also has the eclipses at night; check
/// `peak_sun_altitude_deg`, or the altitude of the sun at the phases. Throws
/// `std::invalid_argument` if `end_utc <= start_utc`, if the interval is longer than 100 years,
/// or if an argument is not valid.
[[nodiscard]] std::vector<LocalSolarEclipse> local_solar_eclipses(const Observer& observer,
                                                                  double start_utc, double end_utc);

/// A point of the central line of a solar eclipse.
struct ShadowPoint {
  double unix_utc = 0;
  /// Where the axis of the shadow of the moon meets the surface of the Earth.
  Observer place;
  /// The radius of the umbra in the plane across the axis there, in km: positive for a total
  /// eclipse, negative for an annular one (the antumbra). On the ground, the shadow is wider where
  /// the sun is low, because the axis meets the ground at a slant.
  double umbra_radius_km = 0;
};

/// Returns the central line of the solar eclipse with the peak at `peak_utc` (see
/// `solar_eclipses()`): the place where the axis of the shadow of the moon meets the Earth, every
/// `step_s` seconds, while it meets the Earth (within 4 hours of the peak). A partial eclipse has
/// an empty line. Throws `std::invalid_argument` if `step_s` is not in the range [1, 3600] or if
/// the time is not finite.
[[nodiscard]] std::vector<ShadowPoint> eclipse_central_line(double peak_utc, double step_s = 60);

}  // namespace almucantar
