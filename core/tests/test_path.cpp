#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>

#include "reference_data.hpp"
#include "sunradar/noaa_model.hpp"
#include "sunradar/path.hpp"
#include "sunradar/refraction.hpp"

using Catch::Matchers::WithinAbs;
using namespace sunradar;

TEST_CASE("sun_path samples at the step", "[path]") {
  const NoaaModel model;
  const Observer vienna = test::site("vienna");
  const double start = 1782000000;

  const auto path = sun_path(model, vienna, start, start + 86400, {.step_s = 300});
  REQUIRE(path.size() == 289);  // both ends are included
  for (std::size_t k = 0; k < path.size(); ++k) {
    CHECK(path[k].unix_utc == start + static_cast<double>(k) * 300);
  }
  CHECK(path.back().unix_utc == start + 86400);
}

TEST_CASE("sun_path does not include a point after the end", "[path]") {
  const NoaaModel model;
  const auto path = sun_path(model, test::site("vienna"), 0, 1000, {.step_s = 300});
  REQUIRE(path.size() == 4);
  CHECK(path.back().unix_utc == 900);
}

TEST_CASE("sun_path applies the refraction only with an atmosphere", "[path]") {
  const NoaaModel model;
  const Observer vienna = test::site("vienna");
  const double t = 1782039420;

  const auto airless = sun_path(model, vienna, t, t, {.atmosphere = std::nullopt});
  const auto seen = sun_path(model, vienna, t, t);
  REQUIRE(airless.size() == 1);
  REQUIRE(seen.size() == 1);

  const Horizontal direct = model.sun(t, vienna);
  CHECK(airless[0].position.altitude_deg == direct.altitude_deg);
  CHECK_THAT(seen[0].position.altitude_deg, WithinAbs(refracted(direct).altitude_deg, 1e-12));
}

TEST_CASE("sun_path with invalid arguments is empty", "[path]") {
  const NoaaModel model;
  const Observer vienna = test::site("vienna");
  CHECK(sun_path(model, vienna, 1000, 0).empty());
  CHECK(sun_path(model, vienna, 0, 1000, {.step_s = 0}).empty());
  CHECK(sun_path(model, vienna, 0, 1000, {.step_s = -5}).empty());
}
