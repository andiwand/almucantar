#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <limits>
#include <stdexcept>

#include "reference_data.hpp"
#include "sunradar/search.hpp"
#include "sunradar/sun.hpp"

using Catch::Matchers::WithinAbs;
using namespace sunradar;

namespace {

constexpr double kDay = 86400;
constexpr double kYearStart = 1767222000;  // 2026-01-01T00:00 CET

double day_of_year(const double t) { return (t - kYearStart) / kDay; }

}  // namespace

TEST_CASE("find_time finds the time of a direction on the path", "[search]") {
  const Observer vienna = test::site("vienna");
  for (const double hour : {5.0, 8.5, 12.0, 15.25, 19.0}) {
    const double t = kYearStart + 120 * kDay + hour * 3600;  // 2026-05-01
    INFO("hour " << hour);
    const TimeMatch m = find_time(vienna, sun_position(t, vienna), kYearStart + 120 * kDay,
                                  kYearStart + 121 * kDay);
    CHECK_THAT(m.unix_utc, WithinAbs(t, 1));
    CHECK(m.separation_deg < 1e-3);
    CHECK_THAT(m.sun.azimuth_deg, WithinAbs(sun_position(t, vienna).azimuth_deg, 1e-2));
  }
}

TEST_CASE("find_time gives the nearest point for a direction off the path", "[search]") {
  const Observer vienna = test::site("vienna");
  const double t = kYearStart + 120 * kDay + 11 * 3600;
  Horizontal d = sun_position(t, vienna);
  d.altitude_deg += 3;
  const TimeMatch m = find_time(vienna, d, kYearStart + 120 * kDay, kYearStart + 121 * kDay);
  // The path is not perpendicular to the altitude, so the nearest point is nearer than 3°.
  CHECK(m.separation_deg > 1);
  CHECK(m.separation_deg < 3);
  CHECK(std::abs(m.unix_utc - t) < 2 * 3600);
}

TEST_CASE("find_time works in the polar day and the polar night", "[search]") {
  const Observer tromso = test::site("tromso");
  // 2026-06-21: the midnight sun in the north at about 3° altitude.
  const double midnight = 1782000000 - 1.26 * 3600;  // local solar midnight in UTC
  const Horizontal north = sun_position(midnight, tromso);
  CHECK(north.altitude_deg > 0);
  const TimeMatch m = find_time(tromso, north, midnight - 12 * 3600, midnight + 12 * 3600);
  CHECK_THAT(m.unix_utc, WithinAbs(midnight, 2));
  CHECK(m.separation_deg < 1e-3);

  // 2026-12-21: the sun stays below the horizon, but the search still gives the nearest
  // point, and the caller sees the negative altitude.
  const TimeMatch night =
      find_time(tromso, {.azimuth_deg = 180, .altitude_deg = 10}, 1797800000, 1797800000 + kDay);
  CHECK(night.sun.altitude_deg < 0);
  CHECK(night.separation_deg > 10);
}

TEST_CASE("find_dates finds the two dates of a direction", "[search]") {
  const Observer vienna = test::site("vienna");
  // 2026-05-01T10:00 CET. The other date is 2026-08-11 (day 222), symmetric about the
  // solstice. The equation of time moves the time on that day by about 5 minutes.
  const double t = kYearStart + 120 * kDay + 10 * 3600;
  const auto matches =
      find_dates(vienna, sun_position(t, vienna), kYearStart, kYearStart + 365 * kDay);
  REQUIRE(matches.size() == 2);
  CHECK_THAT(matches[0].unix_utc, WithinAbs(t, 1));
  CHECK(matches[0].separation_deg < 1e-3);
  CHECK_THAT(day_of_year(matches[1].unix_utc), WithinAbs(222.42, 0.05));
  CHECK(matches[1].separation_deg < 0.2);
}

TEST_CASE("find_dates gives one date at the solstice", "[search]") {
  const Observer vienna = test::site("vienna");
  const auto matches =
      find_dates(vienna, sun_position(1782039420, vienna), kYearStart, kYearStart + 365 * kDay);
  REQUIRE(matches.size() == 1);
  CHECK_THAT(day_of_year(matches[0].unix_utc), WithinAbs(171.5, 0.05));
}

TEST_CASE("find_dates gives no date for a direction that the sun never reaches", "[search]") {
  const Observer vienna = test::site("vienna");
  CHECK(find_dates(vienna, {.azimuth_deg = 0, .altitude_deg = 30}, kYearStart,
                   kYearStart + 365 * kDay)
            .empty());
  // With a large threshold, the nearest days are returned (around the summer solstice).
  const auto near = find_dates(vienna, {.azimuth_deg = 0, .altitude_deg = 30}, kYearStart,
                               kYearStart + 365 * kDay, {.max_separation_deg = 90});
  REQUIRE_FALSE(near.empty());
  for (const TimeMatch& m : near) {
    CHECK(m.separation_deg > 20);
  }
}

TEST_CASE("the search functions reject invalid arguments", "[search]") {
  const Observer vienna = test::site("vienna");
  const double nan = std::numeric_limits<double>::quiet_NaN();
  const Horizontal d{.azimuth_deg = 180, .altitude_deg = 30};
  const double t = kYearStart;
  CHECK_THROWS_AS(find_time(vienna, d, t, t), std::invalid_argument);
  CHECK_THROWS_AS(find_time(vienna, {.azimuth_deg = nan}, t, t + kDay), std::invalid_argument);
  CHECK_THROWS_AS(find_time(vienna, {.altitude_deg = 91}, t, t + kDay), std::invalid_argument);
  CHECK_THROWS_AS(find_time(vienna, d, t, t + kDay, {.step_s = 0}), std::invalid_argument);
  CHECK_THROWS_AS(find_time(vienna, d, t, t + kDay, {.tolerance_s = 0}), std::invalid_argument);
  CHECK_THROWS_AS(find_time(vienna, d, t, t + kDay, {.step_s = 2 * kDay}), std::invalid_argument);
  CHECK_THROWS_AS(find_time({.latitude_deg = 95}, d, t, t + kDay), std::invalid_argument);
  CHECK_THROWS_AS(find_dates(vienna, d, t, t + kDay / 2), std::invalid_argument);
  CHECK_THROWS_AS(find_dates(vienna, d, t, t + 4000 * kDay), std::invalid_argument);
  CHECK_THROWS_AS(find_dates(vienna, d, t, t + 10 * kDay, {.max_separation_deg = nan}),
                  std::invalid_argument);
}
