#pragma once

// Reference data from JPL Horizons (see tools/fetch_horizons.py).

#include <string>
#include <vector>

#include "sunradar/types.hpp"

namespace sunradar::test {

/// The sites in the reference data.
Observer site(const std::string& name);

/// The names of all sites.
const std::vector<std::string>& site_names();

struct ReferencePosition {
  std::string site;
  double unix_utc = 0;
  Horizontal position;
};

struct ReferenceEvent {
  std::string site;
  std::string kind;  // "rise", "transit", or "set"
  double unix_utc = 0;
};

/// The airless apparent positions (sun_airless.csv).
const std::vector<ReferencePosition>& airless_positions();

/// The refracted positions at the same times (sun_refracted.csv).
const std::vector<ReferencePosition>& refracted_positions();

/// Rise, transit, and set times for 2026 (sun_events.csv).
const std::vector<ReferenceEvent>& events();

/// The start and the end of the time span of events().
inline constexpr double kEventsStartUtc = 1767225600;  // 2026-01-01T00:00:00Z
inline constexpr double kEventsEndUtc = 1798761600;    // 2027-01-01T00:00:00Z

/// Returns the angle in degrees between two directions.
double angular_separation_deg(Horizontal a, Horizontal b);

}  // namespace sunradar::test
