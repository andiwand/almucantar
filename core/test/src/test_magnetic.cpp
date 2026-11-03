#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <limits>
#include <stdexcept>

#include "almucantar/magnetic.hpp"
#include "reference_data.hpp"

using Catch::Matchers::WithinAbs;
using namespace almucantar;

TEST_CASE("magnetic_declination_deg gives known values", "[magnetic]") {
  // The internal test checks the model against all official test values. This test
  // checks the public function: the conversion of the time and the height 0.
  // The only WMM2025 test value at height 0 km: 2027.5, 13° S, 59° W: -17.49°.
  const double t2027_5 = 1798761600 + 0.5 * 365 * 86400;  // 2027-07-02T12:00Z
  CHECK_THAT(magnetic_declination_deg({.latitude_deg = -13, .longitude_deg = -59}, t2027_5),
             WithinAbs(-17.49, 0.006));

  // The sign: east of true north in central Europe, west in Iceland.
  const double t = 1767225600;  // 2026-01-01T00:00Z
  CHECK(magnetic_declination_deg(test::site("vienna"), t) > 0);
  CHECK(magnetic_declination_deg({.latitude_deg = 64.1466, .longitude_deg = -21.9426}, t) < 0);
}

TEST_CASE("magnetic_declination_deg rejects invalid arguments", "[magnetic]") {
  const double nan = std::numeric_limits<double>::quiet_NaN();
  CHECK_THROWS_AS(magnetic_declination_deg({}, nan), std::invalid_argument);
  CHECK_THROWS_AS(magnetic_declination_deg({.latitude_deg = 91}, 0), std::invalid_argument);
  CHECK_THROWS_AS(magnetic_declination_deg({.longitude_deg = nan}, 0), std::invalid_argument);
}
