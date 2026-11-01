#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>

#include "sunradar/time.hpp"

using Catch::Matchers::WithinAbs;

TEST_CASE("julian_date of known epochs", "[time]") {
  CHECK_THAT(sunradar::julian_date(0), WithinAbs(2440587.5, 1e-9));
  // J2000.0 is 2000-01-01T12:00:00 TT. In UTC, that is 946728000 s (ignoring 64 s of TT-UTC).
  CHECK_THAT(sunradar::julian_date(946728000), WithinAbs(2451545.0, 1e-9));
}

TEST_CASE("unix_time is the inverse of julian_date", "[time]") {
  for (const double t : {-631152000.0, 0.0, 1767225600.5, 4102444800.0}) {
    CHECK_THAT(sunradar::unix_time(sunradar::julian_date(t)), WithinAbs(t, 1e-4));
  }
}
