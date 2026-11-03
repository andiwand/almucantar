#pragma once

#include <cstdint>
#include <vector>

#include "almucantar/types.hpp"

namespace almucantar {

/// The model that calculates the position of the sun.
enum class SolarModel : std::uint8_t {
  noaa,              ///< NOAA solar calculator (Meeus); error ≤ 0.02° for 1950–2100
  astronomy_engine,  ///< Astronomy Engine (VSOP87); more precise and slower than noaa
};

/// The airless altitude of the center of the sun at the standard sunrise and
/// sunset: 34′ of refraction plus 16′ of semi-diameter.
inline constexpr double kSunriseAltitudeDeg = -0.8333;

struct SunOptions {
  SolarModel model = SolarModel::noaa;
  bool refraction = true;  ///< add the refraction, to get the position that the observer sees
  Atmosphere atmosphere;   ///< used only if `refraction` is true
};

/// Returns the position of the center of the sun at `unix_utc` for `observer`.
/// Throws `std::invalid_argument` if an argument is not finite or not in its range.
[[nodiscard]] Horizontal sun_position(double unix_utc, const Observer& observer,
                                      const SunOptions& options = {});

struct EventOptions {
  SolarModel model = SolarModel::noaa;

  /// The airless altitude of the center of the sun that defines a crossing. Use
  /// -6, -12, or -18 for the civil, nautical, or astronomical twilight.
  double altitude_deg = kSunriseAltitudeDeg;

  /// The interval of the first search. The search finds a crossing only if the sun
  /// stays on one side of the threshold for longer than this interval.
  double search_step_s = 600;

  double tolerance_s = 0.5;  ///< the time accuracy of the results
};

/// The sun crosses the threshold altitude.
struct Crossing {
  double unix_utc = 0;
  bool rising = false;  ///< true for a sunrise, false for a sunset
};

enum class DayKind : std::uint8_t {
  normal,       ///< the sun crosses the threshold at least once
  polar_day,    ///< the sun stays above the threshold for the full interval
  polar_night,  ///< the sun stays below the threshold for the full interval
};

struct DayEvents {
  DayKind kind = DayKind::normal;
  std::vector<Crossing> crossings;  ///< all crossings, in time order
  double culmination_utc = 0;       ///< the time of the highest airless altitude
  double culmination_altitude_deg = 0;
};

/// Finds the crossings of the threshold altitude and the culmination of the sun
/// between `start_utc` and `end_utc`. For one local day, give the start and the end
/// of that day in UTC. Throws `std::invalid_argument` if `end_utc <= start_utc`, or
/// if an argument is not finite or not in its range.
[[nodiscard]] DayEvents sun_events(const Observer& observer, double start_utc, double end_utc,
                                   const EventOptions& options = {});

struct PathOptions {
  SolarModel model = SolarModel::noaa;
  double step_s = 300;     ///< the time between two points
  bool refraction = true;  ///< add the refraction, to get the path that the observer sees
  Atmosphere atmosphere;   ///< used only if `refraction` is true
};

struct PathPoint {
  double unix_utc = 0;
  Horizontal position;
};

/// Returns the path of the sun at the times `start_utc + k * step_s` that are not
/// after `end_utc`. The path includes the points below the horizon. If `start_utc`
/// is a local midnight and 3600 is a multiple of `step_s`, the points fall on the
/// full hours. Throws `std::invalid_argument` if `end_utc < start_utc`, if the path
/// has more than 1,000,000 points, or if an argument is not finite or not in its range.
[[nodiscard]] std::vector<PathPoint> sun_path(const Observer& observer, double start_utc,
                                              double end_utc, const PathOptions& options = {});

}  // namespace almucantar
