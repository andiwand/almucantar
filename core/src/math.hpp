#pragma once

// Internal helpers of the core. Not part of the public API.

#include <cmath>
#include <numbers>

namespace sunradar::detail {

inline constexpr double kDegToRad = std::numbers::pi / 180.0;
inline constexpr double kRadToDeg = 180.0 / std::numbers::pi;

inline double sin_deg(double x) noexcept { return std::sin(x * kDegToRad); }
inline double cos_deg(double x) noexcept { return std::cos(x * kDegToRad); }
inline double tan_deg(double x) noexcept { return std::tan(x * kDegToRad); }

/// Returns `x` in the range [0, 360).
inline double wrap_360(double x) noexcept {
  const double r = std::fmod(x, 360.0);
  return r < 0 ? r + 360.0 : r;
}

/// Returns `x` in the range [-180, 180).
inline double wrap_180(double x) noexcept { return wrap_360(x + 180.0) - 180.0; }

}  // namespace sunradar::detail
