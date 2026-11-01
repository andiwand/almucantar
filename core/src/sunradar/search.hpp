#pragma once

#include <vector>

#include "sunradar/sun.hpp"
#include "sunradar/types.hpp"

namespace sunradar {

struct SearchOptions {
  SolarModel model = SolarModel::noaa;
  double step_s = 300;       ///< the interval of the first search
  double tolerance_s = 0.5;  ///< the time accuracy of the results
  bool refraction = true;    ///< compare with the position that the observer sees
  Atmosphere atmosphere;     ///< used only if `refraction` is true
  /// `find_dates()` returns only matches that are nearer than this angle.
  double max_separation_deg = 2;
};

/// The time when the sun is nearest to a direction.
struct TimeMatch {
  double unix_utc = 0;
  double separation_deg = 0;  ///< the angle between the sun and the direction
  Horizontal sun;             ///< the position of the sun at `unix_utc`
};

/// Finds the time between `start_utc` and `end_utc` when the sun is nearest to
/// `direction`. This is the auto mode: give the direction of the screen center (see
/// `unproject()`) and the interval of the local day. The result is the nearest point of
/// the path, also if it is far away or below the horizon, so check `separation_deg` and
/// `sun.altitude_deg`. Throws `std::invalid_argument` if `end_utc <= start_utc`, or if an
/// argument is not finite or not in its range.
[[nodiscard]] TimeMatch find_time(const Observer& observer, Horizontal direction, double start_utc,
                                  double end_utc, const SearchOptions& options = {});

/// Finds the dates when the sun goes through `direction`, for the date slider. The
/// function searches each 24-hour window from `start_utc` (give a local midnight) to
/// `end_utc` with `find_time()`, and returns the windows where the separation has a
/// local minimum below `max_separation_deg`. A direction between the paths of the two
/// solstices usually gives two dates per year, one on each side of a solstice. Throws
/// `std::invalid_argument` if `end_utc` is less than one day after `start_utc`, if the
/// interval is longer than 3660 days, or if an argument is not finite or not in its
/// range.
[[nodiscard]] std::vector<TimeMatch> find_dates(const Observer& observer, Horizontal direction,
                                                double start_utc, double end_utc,
                                                const SearchOptions& options = {});

}  // namespace sunradar
