#pragma once

namespace sunradar::internal {

/// The Julian date of the Unix epoch (1970-01-01T00:00:00Z).
inline constexpr double kJulianDateUnixEpoch = 2440587.5;

/// Converts Unix time in seconds (UTC) to a Julian date (UT).
/// The core ignores leap seconds and the difference between UTC and UT1. Both are
/// smaller than 1 s, which is about 0.004 degrees of sun movement.
constexpr double julian_date(double unix_utc) noexcept {
  return unix_utc / 86400.0 + kJulianDateUnixEpoch;
}

/// Converts a Julian date (UT) to Unix time in seconds (UTC).
constexpr double unix_time(double julian_date) noexcept {
  return (julian_date - kJulianDateUnixEpoch) * 86400.0;
}

}  // namespace sunradar::internal
