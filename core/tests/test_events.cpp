#include <catch2/catch_test_macros.hpp>
#include <catch2/generators/catch_generators.hpp>
#include <catch2/generators/catch_generators_range.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <limits>
#include <string>

#include "reference_data.hpp"
#include "sunradar/events.hpp"
#include "sunradar/noaa_model.hpp"

using Catch::Matchers::WithinAbs;
using namespace sunradar;

namespace {

/// The start of the solar day `day` of 2026 (local mean midnight) in UTC.
double solar_day_start(int day, const Observer& observer) {
  return test::kEventsStartUtc + day * 86400.0 - observer.longitude_deg / 360.0 * 86400.0;
}

double nearest_reference(const std::string& site, const std::string& kind, double t) {
  double best = std::numeric_limits<double>::infinity();
  for (const auto& e : test::events()) {
    if (e.site == site && e.kind == kind) {
      best = std::min(best, std::fabs(e.unix_utc - t));
    }
  }
  return best;
}

}  // namespace

TEST_CASE("sun_events agrees with JPL Horizons for sunrise and sunset", "[events]") {
  // Horizons gives the times rounded to the minute, and it defines a sunrise with
  // the actual semi-diameter and refraction, not with the standard -0.8333
  // degrees. The measured maximum is 71 s, but 135 s in Tromso, where the sun
  // crosses the horizon at a low angle near the polar day and the polar night.
  const NoaaModel model;
  const auto site = GENERATE(from_range(test::site_names()));
  const double tolerance_s = site == "tromso" ? 180 : 90;
  INFO("site " << site);
  const Observer observer = test::site(site);

  int ours = 0;
  int reference = 0;
  for (const auto& e : test::events()) {
    if (e.site == site && e.kind != "transit") {
      ++reference;
    }
  }
  for (int day = -1; day <= 365; ++day) {
    const double start = solar_day_start(day, observer);
    const auto result = sun_events(model, observer, start, start + 86400);
    for (const auto& c : result.crossings) {
      // Compare only the crossings in the time span of the reference data.
      if (c.unix_utc < test::kEventsStartUtc || c.unix_utc >= test::kEventsEndUtc) {
        continue;
      }
      ++ours;
      INFO("day " << day << " " << (c.rising ? "rise" : "set") << " " << c.unix_utc);
      CHECK(nearest_reference(site, c.rising ? "rise" : "set", c.unix_utc) < tolerance_s);
    }
  }
  CHECK(ours == reference);
}

TEST_CASE("sun_events finds the culmination", "[events]") {
  // Horizons gives the meridian transit, rounded to the minute. The culmination
  // (the highest altitude) differs from the transit by a few seconds, because the
  // declination changes. Measured maximum: 79 s. Horizons has no transits during
  // the polar day and the polar night, so the test uses the other sites only.
  const NoaaModel model;
  const auto site = GENERATE(as<std::string>{}, "quito", "vienna", "sydney");
  INFO("site " << site);
  const Observer observer = test::site(site);
  for (int day = 0; day < 365; ++day) {
    const double start = solar_day_start(day, observer);
    const auto result = sun_events(model, observer, start, start + 86400);
    INFO("day " << day);
    CHECK(nearest_reference(site, "transit", result.culmination_utc) < 120);
    CHECK_THAT(result.culmination_altitude_deg,
               WithinAbs(model.sun(result.culmination_utc, observer).altitude_deg, 1e-9));
  }
}

TEST_CASE("sun_events gives the crossings at the threshold altitude", "[events]") {
  const NoaaModel model;
  const Observer vienna = test::site("vienna");
  for (const double threshold : {kSunriseAltitudeDeg, -6.0, -18.0, 30.0}) {
    INFO("threshold " << threshold);
    const auto result = sun_events(model, vienna, 1782000000, 1782000000 + 86400,
                                   {.altitude_deg = threshold});
    REQUIRE(result.kind == DayKind::normal);
    REQUIRE(result.crossings.size() == 2);
    for (const auto& c : result.crossings) {
      // 0.5 s of time is at most about 0.002 degrees of altitude.
      CHECK_THAT(model.sun(c.unix_utc, vienna).altitude_deg, WithinAbs(threshold, 0.003));
    }
  }
}

TEST_CASE("sun_events detects the polar day and the polar night", "[events]") {
  const NoaaModel model;
  const Observer tromso = test::site("tromso");

  // 2026-06-21: midnight sun.
  const auto summer = sun_events(model, tromso, 1782000000, 1782000000 + 86400);
  CHECK(summer.kind == DayKind::polar_day);
  CHECK(summer.crossings.empty());

  // 2026-12-21: polar night.
  const auto winter = sun_events(model, tromso, 1797800000, 1797800000 + 86400);
  CHECK(winter.kind == DayKind::polar_night);
  CHECK(winter.crossings.empty());
  CHECK(winter.culmination_altitude_deg < kSunriseAltitudeDeg);
}

TEST_CASE("sun_events with an empty interval", "[events]") {
  const NoaaModel model;
  const auto result = sun_events(model, test::site("vienna"), 1782000000, 1782000000);
  CHECK(result.crossings.empty());
  CHECK(result.culmination_utc == 1782000000);
}
