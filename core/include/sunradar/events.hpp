#pragma once

#include <vector>

#include "sunradar/solar_model.hpp"
#include "sunradar/types.hpp"

namespace sunradar {

/// The airless altitude of the center of the sun at the standard sunrise and
/// sunset: 34 arcminutes of refraction plus 16 arcminutes of semi-diameter.
inline constexpr double kSunriseAltitudeDeg = -0.8333;

struct EventOptions {
  /// The airless altitude of the center of the sun that defines a crossing. Use
  /// -6, -12, or -18 for the civil, nautical, or astronomical twilight.
  double altitude_deg = kSunriseAltitudeDeg;

  /// The interval of the first search. A crossing is found only if the sun stays
  /// on one side of the threshold for longer than this interval. Near the poles,
  /// the sun can stay above the threshold for a shorter time.
  double search_step_s = 600;

  /// The time accuracy of the results.
  double tolerance_s = 0.5;
};

/// The sun crosses the threshold altitude.
struct Crossing {
  double unix_utc = 0;
  bool rising = false;  ///< true for a sunrise, false for a sunset
};

enum class DayKind {
  normal,       ///< the sun crosses the threshold at least once
  polar_day,    ///< the sun stays above the threshold for the full interval
  polar_night,  ///< the sun stays below the threshold for the full interval
};

struct DayEvents {
  DayKind kind = DayKind::normal;

  /// All crossings in the interval, in time order. Usually one sunrise and one
  /// sunset, but there can be more or fewer near the poles.
  std::vector<Crossing> crossings;

  /// The time of the highest altitude in the interval. If the highest altitude is
  /// at one end of the interval, this is that end.
  double culmination_utc = 0;
  double culmination_altitude_deg = 0;
};

/// Finds the sunrise, the sunset, and the culmination of the sun between
/// `start_utc` and `end_utc`. For one local day, give the start and the end of
/// that day in UTC. The platform calculates them, because it knows the time zone.
[[nodiscard]] DayEvents sun_events(const SolarModel& model, const Observer& observer,
                                   double start_utc, double end_utc,
                                   const EventOptions& options = {});

}  // namespace sunradar
