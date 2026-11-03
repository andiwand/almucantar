#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>

#include "almucantar/internal/time.hpp"

using Catch::Matchers::WithinAbs;

TEST_CASE("julian_date of known epochs", "[time]") {
  CHECK_THAT(almucantar::internal::julian_date(0), WithinAbs(2440587.5, 1e-9));
  // J2000.0 is 2000-01-01T12:00:00 TT. In UTC, that is 946728000 s (ignoring 64 s of TT-UTC).
  CHECK_THAT(almucantar::internal::julian_date(946728000), WithinAbs(2451545.0, 1e-9));
}

TEST_CASE("unix_time is the inverse of julian_date", "[time]") {
  for (const double t : {-631152000.0, 0.0, 1767225600.5, 4102444800.0}) {
    CHECK_THAT(almucantar::internal::unix_time(almucantar::internal::julian_date(t)),
               WithinAbs(t, 1e-4));
  }
}

TEST_CASE("decimal_year", "[time]") {
  using almucantar::internal::civil_year;
  using almucantar::internal::decimal_year;
  using almucantar::internal::year_start_utc;
  const double y2025 = 1735689600;  // 2025-01-01T00:00:00Z
  const double y2028 = 1830297600;  // 2028-01-01T00:00:00Z, a leap year
  CHECK(year_start_utc(1970) == 0);
  CHECK(year_start_utc(2025) == y2025);
  CHECK(year_start_utc(2028) == y2028);
  CHECK(civil_year(y2025) == 2025);
  CHECK(civil_year(y2025 - 1) == 2024);
  CHECK(civil_year(-1) == 1969);
  CHECK_THAT(decimal_year(y2025), WithinAbs(2025.0, 1e-12));
  CHECK_THAT(decimal_year(y2025 + 182.5 * 86400), WithinAbs(2025.5, 1e-12));
  CHECK_THAT(decimal_year(y2028 + 183 * 86400), WithinAbs(2028.5, 1e-12));
  CHECK(decimal_year(y2028 - 0.001) < 2028.0);
}
