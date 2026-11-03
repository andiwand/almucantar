#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <limits>
#include <stdexcept>

#include "almucantar/almucantar.hpp"
#include "reference_data.hpp"

using Catch::Matchers::WithinAbs;
using namespace almucantar;

TEST_CASE("sun_position agrees with JPL Horizons, with and without refraction", "[sun]") {
  // The internal tests check every point. This test checks that the public
  // function selects the model and applies the refraction correctly.
  const auto& airless = test::airless_positions();
  const auto& refracted = test::refracted_positions();
  for (std::size_t i = 0; i < airless.size(); i += 97) {
    const Observer observer = test::site(airless[i].site);
    const double t = airless[i].unix_utc;
    INFO(airless[i].site << " " << t);
    CHECK(test::angular_separation_deg(sun_position(t, observer, {.refraction = false}),
                                       airless[i].position) < 0.02);
    if (refracted[i].position.altitude_deg > 0) {
      CHECK(test::angular_separation_deg(sun_position(t, observer), refracted[i].position) < 0.02);
    }
  }
}

TEST_CASE("sun_position uses the atmosphere", "[sun]") {
  const Observer vienna = test::site("vienna");
  const double t = 1782014400;  // 2026-06-21T04:00Z, the sun is about 9° high in Vienna
  const double standard = sun_position(t, vienna).altitude_deg;
  const double dense =
      sun_position(t, vienna, {.atmosphere = {.pressure_hpa = 1040, .temperature_c = -20}})
          .altitude_deg;
  const double vacuum = sun_position(t, vienna, {.atmosphere = {.pressure_hpa = 0}}).altitude_deg;
  CHECK(dense > standard);
  CHECK(vacuum < standard);
  CHECK_THAT(vacuum, WithinAbs(sun_position(t, vienna, {.refraction = false}).altitude_deg, 1e-12));
}

TEST_CASE("sun_position rejects invalid arguments", "[sun]") {
  const double nan = std::numeric_limits<double>::quiet_NaN();
  const double inf = std::numeric_limits<double>::infinity();
  CHECK_THROWS_AS(sun_position(nan, {}), std::invalid_argument);
  CHECK_THROWS_AS(sun_position(inf, {}), std::invalid_argument);
  CHECK_THROWS_AS(sun_position(0, {.latitude_deg = 90.5}), std::invalid_argument);
  CHECK_THROWS_AS(sun_position(0, {.latitude_deg = -90.5}), std::invalid_argument);
  CHECK_THROWS_AS(sun_position(0, {.longitude_deg = nan}), std::invalid_argument);
  CHECK_THROWS_AS(sun_position(0, {}, {.atmosphere = {.pressure_hpa = -1}}), std::invalid_argument);
  CHECK_THROWS_AS(sun_position(0, {}, {.atmosphere = {.temperature_c = -300}}),
                  std::invalid_argument);
  CHECK_THROWS_AS(sun_position(0, {}, {.model = static_cast<SolarModel>(200)}),
                  std::invalid_argument);
  CHECK_NOTHROW(sun_position(0, {.latitude_deg = 90}));
  CHECK_NOTHROW(sun_position(0, {.latitude_deg = -90}));
}
