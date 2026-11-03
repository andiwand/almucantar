#include "almucantar/internal/time.hpp"

#include <cmath>
#include <cstdint>

namespace almucantar::internal {

namespace {

/// Days since 1970-01-01 of a civil date (H. Hinnant, "chrono-compatible
/// low-level date algorithms", days_from_civil).
constexpr std::int64_t days_from_civil(std::int64_t y, const std::int64_t m,
                                       const std::int64_t d) noexcept {
  y -= m <= 2 ? 1 : 0;
  const std::int64_t era = (y >= 0 ? y : y - 399) / 400;
  const std::int64_t yoe = y - era * 400;
  const std::int64_t doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
  const std::int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return era * 146097 + doe - 719468;
}

/// The civil year of a day count since 1970-01-01 (civil_from_days).
constexpr std::int64_t year_from_days(std::int64_t z) noexcept {
  z += 719468;
  const std::int64_t era = (z >= 0 ? z : z - 146096) / 146097;
  const std::int64_t doe = z - era * 146097;
  const std::int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  const std::int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
  const std::int64_t mp = (5 * doy + 2) / 153;
  const std::int64_t m = mp < 10 ? mp + 3 : mp - 9;
  return yoe + era * 400 + (m <= 2 ? 1 : 0);
}

static_assert(days_from_civil(1970, 1, 1) == 0);
static_assert(days_from_civil(2000, 3, 1) == 11017);
static_assert(year_from_days(0) == 1970);
static_assert(year_from_days(-1) == 1969);
static_assert(year_from_days(days_from_civil(2024, 12, 31)) == 2024);

}  // namespace

std::int64_t civil_year(const double unix_utc) noexcept {
  return year_from_days(static_cast<std::int64_t>(std::floor(unix_utc / 86400.0)));
}

double year_start_utc(const std::int64_t year) noexcept {
  return static_cast<double>(days_from_civil(year, 1, 1)) * 86400.0;
}

double decimal_year(const double unix_utc) noexcept {
  const std::int64_t year = civil_year(unix_utc);
  const double start = year_start_utc(year);
  const double length = year_start_utc(year + 1) - start;
  return static_cast<double>(year) + (unix_utc - start) / length;
}

}  // namespace almucantar::internal
