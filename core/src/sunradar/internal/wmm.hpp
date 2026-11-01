#pragma once

#include <array>
#include <cstdint>
#include <string_view>

namespace sunradar::internal {

/// The maximum degree and order of the World Magnetic Model.
inline constexpr std::int32_t kWmmDegree = 12;

/// One Gauss coefficient pair of degree `n` and order `m`, in nT and nT per year.
struct WmmTerm {
  std::int32_t n = 0;
  std::int32_t m = 0;
  double g = 0;
  double h = 0;
  double g_dot = 0;
  double h_dot = 0;
};

struct WmmCoefficients {
  std::string_view name;          ///< for example "WMM-2025"
  double epoch = 0;               ///< decimal year of the coefficients
  std::array<WmmTerm, 90> terms;  ///< n = 1..12, m = 0..n, in this order
};

/// The coefficients compiled into the library (wmm_coefficients.cpp, generated).
[[nodiscard]] const WmmCoefficients& wmm_coefficients();

/// The magnetic field in the geodetic frame of the observer, in nT.
struct MagneticField {
  double north_nt = 0;  ///< X
  double east_nt = 0;   ///< Y
  double down_nt = 0;   ///< Z
};

/// Calculates the main field of the WMM at a geodetic position (WGS 84) and a
/// decimal year. Near the geographic poles, the function moves the latitude to
/// ±(90° − 1e-6°), because the east component is not defined at the pole.
[[nodiscard]] MagneticField wmm_field(const WmmCoefficients& coefficients, double latitude_deg,
                                      double longitude_deg, double height_m,
                                      double decimal_year) noexcept;

}  // namespace sunradar::internal
