#pragma once

#include <cmath>
#include <cstdint>

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

/// Returns the civil year (proleptic Gregorian, UTC) that contains `unix_utc`.
[[nodiscard]] std::int64_t civil_year(double unix_utc) noexcept;

/// Returns the Unix time of the start of January 1 of `year` (UTC).
[[nodiscard]] double year_start_utc(std::int64_t year) noexcept;

/// Converts Unix time to a decimal year, for example 2025.5 near July 2, 2025. The
/// fraction is the part of the year in its own length (365 or 366 days), the same as
/// in the WMM software.
[[nodiscard]] double decimal_year(double unix_utc) noexcept;

}  // namespace sunradar::internal
