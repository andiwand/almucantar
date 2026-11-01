#include "sunradar/internal/wmm.hpp"

#include <algorithm>
#include <cmath>

#include "sunradar/internal/math.hpp"

namespace sunradar::internal {

namespace {

// WGS 84 ellipsoid, and the reference radius of the WMM.
constexpr double kSemiMajorAxisM = 6378137.0;
constexpr double kFlattening = 1.0 / 298.257223563;
constexpr double kEccentricitySquared = kFlattening * (2.0 - kFlattening);
constexpr double kReferenceRadiusM = 6371200.0;

constexpr std::size_t kSize = kWmmDegree + 1;
using Table = std::array<std::array<double, kSize>, kSize>;

}  // namespace

MagneticField wmm_field(const WmmCoefficients& coefficients, const double latitude_deg,
                        const double longitude_deg, const double height_m,
                        const double decimal_year) noexcept {
  // 1. Geodetic to geocentric spherical coordinates (WMM2025 report, section 1.2).
  const double lat = std::clamp(latitude_deg, -90.0 + 1e-6, 90.0 - 1e-6) * kDegToRad;
  const double lon = longitude_deg * kDegToRad;
  const double sin_lat = std::sin(lat);
  const double cos_lat = std::cos(lat);
  const double rc = kSemiMajorAxisM / std::sqrt(1.0 - kEccentricitySquared * sin_lat * sin_lat);
  const double p = (rc + height_m) * cos_lat;
  const double z = (rc * (1.0 - kEccentricitySquared) + height_m) * sin_lat;
  const double r = std::hypot(p, z);
  const double lat_c = std::asin(z / r);

  // 2. Schmidt semi-normalized associated Legendre functions of the colatitude θ,
  // and their derivatives by θ.
  const double cos_t = std::sin(lat_c);
  const double sin_t = std::cos(lat_c);
  Table pnm{};
  Table dpnm{};
  pnm[0][0] = 1.0;
  for (std::size_t n = 1; n < kSize; ++n) {
    const auto nd = static_cast<double>(n);
    // The diagonal: P(n, n) from P(n-1, n-1).
    const double k = n == 1 ? 1.0 : std::sqrt((2.0 * nd - 1.0) / (2.0 * nd));
    pnm[n][n] = k * sin_t * pnm[n - 1][n - 1];
    dpnm[n][n] = k * (cos_t * pnm[n - 1][n - 1] + sin_t * dpnm[n - 1][n - 1]);
    // The other orders: P(n, m) from P(n-1, m) and P(n-2, m).
    for (std::size_t m = 0; m < n; ++m) {
      const auto md = static_cast<double>(m);
      const double a = 2.0 * nd - 1.0;
      const double b = std::sqrt((nd - 1.0) * (nd - 1.0) - md * md);
      const double c = std::sqrt(nd * nd - md * md);
      const double p2 = n >= 2 && m <= n - 2 ? pnm[n - 2][m] : 0.0;
      const double dp2 = n >= 2 && m <= n - 2 ? dpnm[n - 2][m] : 0.0;
      pnm[n][m] = (a * cos_t * pnm[n - 1][m] - b * p2) / c;
      dpnm[n][m] = (a * (cos_t * dpnm[n - 1][m] - sin_t * pnm[n - 1][m]) - b * dp2) / c;
    }
  }

  // 3. The field in geocentric spherical coordinates (section 1.2, equation 10).
  const double dt = decimal_year - coefficients.epoch;
  double x_c = 0;
  double y_c = 0;
  double z_c = 0;
  for (const WmmTerm& term : coefficients.terms) {
    const auto n = static_cast<std::size_t>(term.n);
    const auto m = static_cast<std::size_t>(term.m);
    const double g = term.g + dt * term.g_dot;
    const double h = term.h + dt * term.h_dot;
    const double ratio = std::pow(kReferenceRadiusM / r, static_cast<double>(term.n + 2));
    const double cos_ml = std::cos(static_cast<double>(term.m) * lon);
    const double sin_ml = std::sin(static_cast<double>(term.m) * lon);
    // d/dφ' = -d/dθ, so X' = -Σ (...) dP/dφ' = Σ (...) dP/dθ.
    x_c += ratio * (g * cos_ml + h * sin_ml) * dpnm[n][m];
    y_c += ratio * static_cast<double>(term.m) * (g * sin_ml - h * cos_ml) * pnm[n][m];
    z_c -= ratio * static_cast<double>(term.n + 1) * (g * cos_ml + h * sin_ml) * pnm[n][m];
  }
  y_c /= std::cos(lat_c);

  // 4. Rotate from the geocentric to the geodetic frame (equation 17).
  const double psi = lat_c - lat;
  return MagneticField{
      .north_nt = x_c * std::cos(psi) - z_c * std::sin(psi),
      .east_nt = y_c,
      .down_nt = x_c * std::sin(psi) + z_c * std::cos(psi),
  };
}

}  // namespace sunradar::internal
