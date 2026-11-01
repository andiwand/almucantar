#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cstddef>

#include "reference_data.hpp"
#include "sunradar/refraction.hpp"

using Catch::Matchers::WithinAbs;
using namespace sunradar;

TEST_CASE("refraction agrees with JPL Horizons", "[refraction]") {
  // Horizons uses the same formula and the same standard atmosphere, so the
  // measured error is less than 0.0001 degrees.
  constexpr double kToleranceDeg = 0.001;

  const auto& airless = test::airless_positions();
  const auto& refracted_ref = test::refracted_positions();
  REQUIRE(airless.size() == refracted_ref.size());

  std::size_t count = 0;
  for (std::size_t i = 0; i < airless.size(); ++i) {
    if (airless[i].position.altitude_deg < -1.0) {
      continue;
    }
    INFO(airless[i].site << " " << airless[i].unix_utc);
    const Horizontal ours = refracted(airless[i].position);
    CHECK_THAT(ours.altitude_deg, WithinAbs(refracted_ref[i].position.altitude_deg, kToleranceDeg));
    CHECK(ours.azimuth_deg == airless[i].position.azimuth_deg);
    ++count;
  }
  REQUIRE(count > 4000);
}

TEST_CASE("refraction has the expected shape", "[refraction]") {
  CHECK(refraction_deg(90) == 0);
  CHECK_THAT(refraction_deg(45), WithinAbs(1.0 / 60.0, 0.1 / 60.0));  // about 1 arcminute
  CHECK_THAT(refraction_deg(0), WithinAbs(29.0 / 60.0, 1.0 / 60.0));  // airless altitude 0

  SECTION("the apparent altitude increases with the airless altitude") {
    double previous = -1e9;
    for (double h = -10; h <= 90; h += 0.01) {
      const double apparent = h + refraction_deg(h);
      REQUIRE(apparent > previous);
      previous = apparent;
    }
  }

  SECTION("the value stays constant below -1 degree") {
    CHECK(refraction_deg(-1) == refraction_deg(-5.11));
    CHECK(refraction_deg(-1) == refraction_deg(-90));
  }

  SECTION("higher pressure and lower temperature give more refraction") {
    const double standard = refraction_deg(2);
    CHECK(refraction_deg(2, {.pressure_hpa = 1030, .temperature_c = 10}) > standard);
    CHECK(refraction_deg(2, {.pressure_hpa = 1010, .temperature_c = -10}) > standard);
    CHECK(refraction_deg(2, {.pressure_hpa = 0, .temperature_c = 10}) == 0);
  }
}
