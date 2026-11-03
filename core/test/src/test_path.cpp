#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <limits>
#include <stdexcept>

#include "almucantar/sun.hpp"
#include "reference_data.hpp"

using Catch::Matchers::WithinAbs;
using namespace almucantar;

TEST_CASE("sun_path samples at the step", "[path]") {
  const Observer vienna = test::site("vienna");
  const double start = 1782000000;

  const auto path = sun_path(vienna, start, start + 86400, {.step_s = 300});
  REQUIRE(path.size() == 289);  // both ends are included
  for (std::size_t k = 0; k < path.size(); ++k) {
    CHECK(path[k].unix_utc == start + static_cast<double>(k) * 300);
  }
  CHECK(path.back().unix_utc == start + 86400);
}

TEST_CASE("sun_path does not include a point after the end", "[path]") {
  const auto path = sun_path(test::site("vienna"), 0, 1000, {.step_s = 300});
  REQUIRE(path.size() == 4);
  CHECK(path.back().unix_utc == 900);
}

TEST_CASE("sun_path applies the refraction only if requested", "[path]") {
  const Observer vienna = test::site("vienna");
  const double t = 1782039420;

  const auto airless = sun_path(vienna, t, t, {.refraction = false});
  const auto seen = sun_path(vienna, t, t);
  REQUIRE(airless.size() == 1);
  REQUIRE(seen.size() == 1);

  CHECK(airless[0].position.altitude_deg ==
        sun_position(t, vienna, {.refraction = false}).altitude_deg);
  CHECK(seen[0].position.altitude_deg == sun_position(t, vienna).altitude_deg);
  CHECK(seen[0].position.altitude_deg > airless[0].position.altitude_deg);
}

TEST_CASE("sun_path rejects invalid arguments", "[path]") {
  const Observer vienna = test::site("vienna");
  const double inf = std::numeric_limits<double>::infinity();
  CHECK_THROWS_AS(sun_path(vienna, 1000, 0), std::invalid_argument);
  CHECK_THROWS_AS(sun_path(vienna, 0, 1000, {.step_s = 0}), std::invalid_argument);
  CHECK_THROWS_AS(sun_path(vienna, 0, 1000, {.step_s = -5}), std::invalid_argument);
  CHECK_THROWS_AS(sun_path(vienna, 0, inf), std::invalid_argument);
  CHECK_THROWS_AS(sun_path(vienna, 0, 1e12, {.step_s = 1}), std::invalid_argument);
  CHECK_THROWS_AS(sun_path(vienna, 0, 1000, {.atmosphere = {.pressure_hpa = -1}}),
                  std::invalid_argument);
  // The atmosphere is not used, so it is not validated.
  CHECK_NOTHROW(
      sun_path(vienna, 0, 1000, {.refraction = false, .atmosphere = {.pressure_hpa = -1}}));
}
