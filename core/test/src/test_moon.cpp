#include <catch2/catch_test_macros.hpp>
#include <catch2/generators/catch_generators.hpp>
#include <catch2/generators/catch_generators_range.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cstddef>
#include <limits>
#include <stdexcept>

#include "almucantar/moon.hpp"
#include "almucantar/sun.hpp"
#include "reference_data.hpp"

using Catch::Matchers::WithinAbs;
using namespace almucantar;

TEST_CASE("moon_position agrees with JPL Horizons", "[moon]") {
  // Measured maximum: 0.0019° over 4260 points in 2026 at five sites.
  constexpr double kToleranceDeg = 0.003;
  const auto site = GENERATE(from_range(test::site_names()));
  INFO("site " << site);
  const Observer observer = test::site(site);
  std::size_t count = 0;
  for (const auto& ref : test::moon_positions()) {
    if (ref.site != site) {
      continue;
    }
    INFO("unix_utc " << ref.unix_utc);
    const Horizontal moon = moon_position(ref.unix_utc, observer, {.refraction = false});
    REQUIRE(test::angular_separation_deg(moon, ref.position) < kToleranceDeg);
    ++count;
  }
  REQUIRE(count > 800);
}

TEST_CASE("SolarModel::astronomy_engine agrees with JPL Horizons", "[moon][sun]") {
  // Measured maximum: 0.0018° (NoaaModel: 0.0154°), over 1950, 2026, and 2099–2100.
  constexpr double kToleranceDeg = 0.003;
  for (const auto& ref : test::airless_positions()) {
    INFO(ref.site << " " << ref.unix_utc);
    const Horizontal sun =
        sun_position(ref.unix_utc, test::site(ref.site),
                     {.model = SolarModel::astronomy_engine, .refraction = false});
    REQUIRE(test::angular_separation_deg(sun, ref.position) < kToleranceDeg);
  }
}

TEST_CASE("moon_position applies the refraction", "[moon]") {
  const Observer vienna = test::site("vienna");
  const double t = 1767225600;
  const Horizontal airless = moon_position(t, vienna, {.refraction = false});
  const Horizontal seen = moon_position(t, vienna);
  CHECK(seen.azimuth_deg == airless.azimuth_deg);
  // The refraction is positive at each altitude, so the moon is always higher.
  CHECK(seen.altitude_deg > airless.altitude_deg);
}

TEST_CASE("moon_illuminated_fraction follows the phases", "[moon]") {
  // January 2026: full moon on the 3rd, new moon on the 18th.
  CHECK(moon_illuminated_fraction(1767441600) > 0.99);  // 2026-01-03T12:00Z
  CHECK(moon_illuminated_fraction(1768737600) < 0.02);  // 2026-01-18T12:00Z
  for (double t = 1767225600; t < 1767225600 + 60 * 86400.0; t += 86400) {
    const double f = moon_illuminated_fraction(t);
    CHECK(f >= 0);
    CHECK(f <= 1);
  }
}

TEST_CASE("the moon functions reject invalid arguments", "[moon]") {
  const double nan = std::numeric_limits<double>::quiet_NaN();
  CHECK_THROWS_AS(moon_position(nan, {}), std::invalid_argument);
  CHECK_THROWS_AS(moon_position(0, {.latitude_deg = 91}), std::invalid_argument);
  CHECK_THROWS_AS(moon_position(0, {}, {.atmosphere = {.pressure_hpa = -1}}),
                  std::invalid_argument);
  CHECK_THROWS_AS(moon_illuminated_fraction(nan), std::invalid_argument);
}
