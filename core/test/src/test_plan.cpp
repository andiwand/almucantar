#include <algorithm>
#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <cstddef>
#include <stdexcept>
#include <vector>

#include "almucantar/moon.hpp"
#include "almucantar/plan.hpp"
#include "almucantar/sky.hpp"
#include "almucantar/sun.hpp"

using Catch::Matchers::WithinAbs;
using namespace almucantar;

namespace {

constexpr double kEarthRadiusM = 6'371'008.8;
constexpr double kRadToDeg = 180 / 3.14159265358979323846;
constexpr double kDegToRad = 3.14159265358979323846 / 180;
constexpr double kDayS = 86'400;
/// 2026-03-20 12:00 UTC, near the March equinox: the declination of the sun changes fast.
constexpr double kEquinoxUtc = 1'774'008'000;
/// 2026-12-15 12:00 UTC: long nights in Vienna.
constexpr double kWinterUtc = 1'797'336'000;

const Observer kVienna{.latitude_deg = 48.2082, .longitude_deg = 16.3738};
const Observer kAlps{.latitude_deg = 47, .longitude_deg = 11};

/// The angle between two directions, with the haversine formula, which is precise for small
/// angles.
double angle_deg(const Horizontal& a, const Horizontal& b) {
  const double h1 = a.altitude_deg * kDegToRad;
  const double h2 = b.altitude_deg * kDegToRad;
  const double dz = (b.azimuth_deg - a.azimuth_deg) * kDegToRad;
  const double s = (std::sin((h2 - h1) / 2) * std::sin((h2 - h1) / 2)) +
                   (std::cos(h1) * std::cos(h2) * std::sin(dz / 2) * std::sin(dz / 2));
  return 2 * std::asin(std::min(1.0, std::sqrt(s))) * kRadToDeg;
}

/// The times of the local minima of the separation below `max_deg`, with a scan every `step_s`.
/// This is the brute force that `find_alignments()` must agree with.
std::vector<double> scanned_minima(const Body body, const Observer& observer,
                                   const Horizontal& direction, const double start,
                                   const double end, const double max_deg, const double step_s) {
  std::vector<double> times;
  const auto sep = [&](const double t) {
    return angle_deg(body_position(body, t, observer), direction);
  };
  double a = sep(start);
  double b = sep(start + step_s);
  for (double t = start + step_s; t + step_s <= end; t += step_s) {
    const double c = sep(t + step_s);
    if (b <= a && b <= c && b < max_deg) {
      times.push_back(t);
    }
    a = b;
    b = c;
  }
  return times;
}

/// A grid of 0 m around `center`, with a step of 1/240° (about 460 m by 310 m).
ElevationGrid flat_grid(const Observer& center) {
  ElevationGrid grid;
  grid.latitude_step_deg = 1.0 / 240;
  grid.longitude_step_deg = 1.0 / 240;
  grid.rows = 241;
  grid.columns = 361;
  grid.north_latitude_deg = center.latitude_deg + 0.5;
  grid.west_longitude_deg = center.longitude_deg - 0.75;
  grid.heights_m.assign(
      static_cast<std::size_t>(grid.rows) * static_cast<std::size_t>(grid.columns), 0.0F);
  return grid;
}

}  // namespace

TEST_CASE("body_position is the position of each body", "[plan]") {
  const double t = kEquinoxUtc;
  const auto same = [](const Horizontal& a, const Horizontal& b) {
    CHECK_THAT(a.azimuth_deg, WithinAbs(b.azimuth_deg, 1e-12));
    CHECK_THAT(a.altitude_deg, WithinAbs(b.altitude_deg, 1e-12));
  };
  same(body_position(Body::sun, t, kVienna), sun_position(t, kVienna));
  same(body_position(Body::sun, t, kVienna, {.model = SolarModel::astronomy_engine}),
       sun_position(t, kVienna, {.model = SolarModel::astronomy_engine}));
  same(body_position(Body::moon, t, kVienna), moon_position(t, kVienna));
  same(body_position(Body::galactic_center, t, kVienna, {.refraction = false}),
       galactic_position(0, 0, t, kVienna, {.refraction = false}));
  same(body_position(Body::mercury, t, kVienna), planet_position(Planet::mercury, t, kVienna));
  same(body_position(Body::saturn, t, kVienna), planet_position(Planet::saturn, t, kVienna));
  same(body_position(Body::neptune, t, kVienna), planet_position(Planet::neptune, t, kVienna));
  REQUIRE_THROWS_AS(body_position(static_cast<Body>(10), t, kVienna), std::invalid_argument);
}

TEST_CASE("sight has the bearing and the altitude of the terrain model", "[plan]") {
  const Site eye{.latitude_deg = 47, .longitude_deg = 11, .elevation_m = 600};
  // 10 km to the north, 1500 m higher.
  const Site peak{.latitude_deg = 47 + (10'000 / kEarthRadiusM * kRadToDeg),
                  .longitude_deg = 11,
                  .elevation_m = 2100};
  const Sight s = sight(eye, peak);
  CHECK_THAT(s.distance_m, WithinAbs(10'000, 1e-6));
  CHECK_THAT(s.direction.azimuth_deg, WithinAbs(0, 1e-9));
  const double drop = 10'000.0 * 10'000 * (1 - 0.13) / (2 * kEarthRadiusM);
  CHECK_THAT(s.direction.altitude_deg,
             WithinAbs(std::atan((1500 - drop) / 10'000) * kRadToDeg, 1e-9));
  // Without refraction, the far point drops more.
  CHECK(sight(eye, peak, {.refraction_coefficient = 0}).direction.altitude_deg <
        s.direction.altitude_deg);
  // The way back looks south and down.
  const Sight back = sight(peak, eye);
  CHECK_THAT(back.direction.azimuth_deg, WithinAbs(180, 1e-9));
  CHECK(back.direction.altitude_deg < 0);
  REQUIRE_THROWS_AS(sight(eye, eye), std::invalid_argument);
  REQUIRE_THROWS_AS(sight(eye, {.latitude_deg = 52, .longitude_deg = 11}), std::invalid_argument);
}

TEST_CASE("find_alignments finds the sun in its direction of one time", "[plan]") {
  const double t0 = kEquinoxUtc + 3600;
  const Horizontal direction = sun_position(t0, kVienna);
  const std::vector<Alignment> found =
      find_alignments(Body::sun, kVienna, direction, kEquinoxUtc - (3 * kDayS),
                      kEquinoxUtc + (3 * kDayS), {.max_separation_deg = 0.25});
  // Near the equinox, the declination changes by 0.4° per day, so only the day itself matches.
  REQUIRE(found.size() == 1);
  CHECK_THAT(found[0].unix_utc, WithinAbs(t0, 2));
  CHECK(found[0].separation_deg < 0.001);
}

TEST_CASE("find_alignments agrees with a scan of the separation", "[plan]") {
  const double start = kEquinoxUtc;
  const double end = kEquinoxUtc + (40 * kDayS);
  for (const Body body : {Body::moon, Body::galactic_center, Body::venus}) {
    // The direction of the body on day 10, so the search has one sure match and maybe more.
    const Horizontal direction = body_position(body, start + (10.3 * kDayS), kVienna);
    const std::vector<Alignment> found =
        find_alignments(body, kVienna, direction, start, end, {.max_separation_deg = 1});
    const std::vector<double> scanned =
        scanned_minima(body, kVienna, direction, start, end, 1, 120);
    INFO("body " << static_cast<int>(body));
    REQUIRE(found.size() == scanned.size());
    for (std::size_t i = 0; i < found.size(); ++i) {
      CHECK_THAT(found[i].unix_utc, WithinAbs(scanned[i], 150));
      CHECK(found[i].separation_deg <= 1);
      CHECK_THAT(found[i].separation_deg, WithinAbs(angle_deg(found[i].body, direction), 1e-9));
      if (i > 0) {
        CHECK(found[i].unix_utc > found[i - 1].unix_utc);
      }
    }
    CHECK(std::ranges::any_of(found, [&](const Alignment& a) {
      return std::abs(a.unix_utc - (start + (10.3 * kDayS))) < 5;
    }));
  }
}

TEST_CASE("find_alignments finds the moon below the horizon too", "[plan]") {
  const double t0 = kEquinoxUtc + (2 * kDayS);
  const Horizontal direction = moon_position(t0, kVienna);
  const std::vector<Alignment> found = find_alignments(Body::moon, kVienna, direction, t0 - kDayS,
                                                       t0 + kDayS, {.max_separation_deg = 0.1});
  REQUIRE(found.size() == 1);
  CHECK_THAT(found[0].unix_utc, WithinAbs(t0, 2));
}

TEST_CASE("find_alignments validates the arguments", "[plan]") {
  const Horizontal d{.azimuth_deg = 100, .altitude_deg = 10};
  REQUIRE_THROWS_AS(find_alignments(Body::moon, kVienna, d, 10, 10), std::invalid_argument);
  REQUIRE_THROWS_AS(find_alignments(Body::moon, kVienna, d, 0, 3661 * kDayS),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(
      find_alignments(Body::moon, kVienna, {.azimuth_deg = 0, .altitude_deg = 91}, 0, kDayS),
      std::invalid_argument);
  REQUIRE_THROWS_AS(find_alignments(Body::moon, kVienna, d, 0, kDayS, {.max_separation_deg = 0}),
                    std::invalid_argument);
}

TEST_CASE("alignment_line puts the landmark on the body from each place", "[plan]") {
  const Site tower{
      .latitude_deg = kAlps.latitude_deg, .longitude_deg = kAlps.longitude_deg, .elevation_m = 300};
  for (const Body body : {Body::sun, Body::moon}) {
    // A time when the body is low in the sky of the Alps.
    double t = kEquinoxUtc;
    while (body_position(body, t, kAlps).altitude_deg < 2 ||
           body_position(body, t, kAlps).altitude_deg > 6) {
      t += 300;
    }
    const AlignmentLine line =
        alignment_line(body, tower, t, {}, {.min_distance_m = 500, .max_distance_m = 40'000});
    REQUIRE(line.points.size() == 396);
    CHECK(line.spots.empty());
    for (const LinePoint& p : line.points) {
      CHECK(std::isnan(p.ground_elevation_m));
      // The defining property, checked with sight(): from the eye, the landmark is on the body.
      const Site eye{.latitude_deg = p.place.latitude_deg,
                     .longitude_deg = p.place.longitude_deg,
                     .elevation_m = p.eye_elevation_m};
      const Sight s = sight(eye, tower);
      const Horizontal b = body_position(body, t, p.place);
      CHECK_THAT(s.distance_m, WithinAbs(p.distance_m, 1e-3));
      CHECK(angle_deg(s.direction, b) < 1e-6);
      CHECK(angle_deg(p.body, b) < 1e-9);
    }
    // The body is low, so a far place needs a low eye.
    CHECK(line.points.back().eye_elevation_m < line.points.front().eye_elevation_m);
  }
}

TEST_CASE("alignment_line finds the spots on the ground", "[plan]") {
  const Site tower{
      .latitude_deg = kAlps.latitude_deg, .longitude_deg = kAlps.longitude_deg, .elevation_m = 300};
  // A low sun in the afternoon: the spot is a few kilometers from the tower.
  double t = kEquinoxUtc;
  while (sun_position(t, kAlps).altitude_deg > 4) {
    t += 300;
  }
  const AlignmentLine line = alignment_line(Body::sun, tower, t, {flat_grid(kAlps)},
                                            {.max_distance_m = 20'000, .eye_height_m = 1.6});
  for (const LinePoint& p : line.points) {
    CHECK_THAT(p.ground_elevation_m, WithinAbs(0, 1e-9));
  }
  // On flat ground, the needed eye falls with the distance, so there is one spot, where it is
  // 1.6 m. From there, the eye on the ground sees the tower on the sun.
  REQUIRE(line.spots.size() == 1);
  const LinePoint& spot = line.spots[0];
  CHECK_THAT(spot.eye_elevation_m, WithinAbs(1.6, 1e-6));
  const Sight s = sight({.latitude_deg = spot.place.latitude_deg,
                         .longitude_deg = spot.place.longitude_deg,
                         .elevation_m = 1.6},
                        tower);
  CHECK(angle_deg(s.direction, sun_position(t, spot.place)) < 0.01);
  CHECK(spot.distance_m > 3000);
}

TEST_CASE("alignment_line validates the arguments", "[plan]") {
  const Site tower{.latitude_deg = 47, .longitude_deg = 11, .elevation_m = 300};
  REQUIRE_THROWS_AS(alignment_line(Body::sun, tower, kEquinoxUtc, {}, {.min_distance_m = 0}),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(alignment_line(Body::sun, tower, kEquinoxUtc, {}, {.max_distance_m = 600'000}),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(alignment_line(Body::sun, tower, kEquinoxUtc, {}, {.step_m = 0.1}),
                    std::invalid_argument);
}

TEST_CASE("line_of_sight sees over flat ground and not through a ridge", "[plan]") {
  ElevationGrid grid = flat_grid(kAlps);
  const Site eye{
      .latitude_deg = kAlps.latitude_deg, .longitude_deg = kAlps.longitude_deg, .elevation_m = 1.6};
  // A tower of 100 m, 10 km to the east.
  const double lon =
      kAlps.longitude_deg +
      (10'000 / (kEarthRadiusM * std::cos(kAlps.latitude_deg * kDegToRad)) * kRadToDeg);
  const Site tower{.latitude_deg = kAlps.latitude_deg, .longitude_deg = lon, .elevation_m = 100};
  const LineOfSight open = line_of_sight(eye, tower, {grid});
  CHECK(open.visible);
  CHECK(open.clearance_deg > 0);

  // A ridge of 200 m from north to south, half way.
  const double ridge_lon = (kAlps.longitude_deg + lon) / 2;
  const auto column =
      static_cast<int>(std::round((ridge_lon - grid.west_longitude_deg) / grid.longitude_step_deg));
  for (int r = 0; r < grid.rows; ++r) {
    for (int c = column - 1; c <= column + 1; ++c) {
      grid.heights_m[(static_cast<std::size_t>(r) * static_cast<std::size_t>(grid.columns)) +
                     static_cast<std::size_t>(c)] = 200;
    }
  }
  const LineOfSight blocked = line_of_sight(eye, tower, {grid});
  CHECK_FALSE(blocked.visible);
  CHECK(blocked.clearance_deg < 0);
  CHECK_THAT(blocked.blocking_distance_m, WithinAbs(5000, 600));
  CHECK_THAT(blocked.blocking.longitude_deg, WithinAbs(ridge_lon, 0.01));
  // A higher tower looks over the ridge again.
  CHECK(line_of_sight(
            eye, {.latitude_deg = tower.latitude_deg, .longitude_deg = lon, .elevation_m = 600},
            {grid})
            .visible);
  // The ridge half way is the target itself if the margin covers it.
  const LineOfSight margin = line_of_sight(eye, tower, {grid}, {.target_margin_m = 6000});
  CHECK(margin.visible);
  CHECK(margin.blocking_distance_m < 4000);
  REQUIRE_THROWS_AS(line_of_sight(eye, tower, {}), std::invalid_argument);
  REQUIRE_THROWS_AS(line_of_sight(eye, tower, {grid}, {.target_margin_m = -1}),
                    std::invalid_argument);
}

TEST_CASE("dark_windows agrees with a scan of the conditions", "[plan]") {
  const double start = kWinterUtc;
  const double end = kWinterUtc + (10 * kDayS);
  const DarkOptions options;
  const auto dark = [&](const double t) {
    if (body_position(Body::sun, t, kVienna, {.refraction = false}).altitude_deg >= -18) {
      return false;
    }
    return moon_illuminated_fraction(t) <= 0.1 || moon_position(t, kVienna).altitude_deg < 0;
  };
  const std::vector<TimeWindow> windows = dark_windows(kVienna, start, end, options);
  REQUIRE(!windows.empty());
  // Each window is dark inside, and light just outside.
  for (const TimeWindow& w : windows) {
    CHECK(w.end_utc > w.start_utc);
    CHECK(dark((w.start_utc + w.end_utc) / 2));
    if (w.start_utc > start) {
      CHECK_FALSE(dark(w.start_utc - 60));
      CHECK(dark(w.start_utc + 60));
    }
    if (w.end_utc < end) {
      CHECK(dark(w.end_utc - 60));
      CHECK_FALSE(dark(w.end_utc + 60));
    }
  }
  // The scan finds the same dark time.
  double scanned = 0;
  for (double t = start; t < end; t += 60) {
    scanned += dark(t + 30) ? 60 : 0;
  }
  double total = 0;
  for (const TimeWindow& w : windows) {
    total += w.end_utc - w.start_utc;
  }
  CHECK_THAT(total, WithinAbs(scanned, 600));

  // The Milky Way needs its center up too, so its windows are inside the dark windows. In
  // December, the center is near the sun, so there are none.
  const std::vector<TimeWindow> milky = dark_windows(
      kVienna, start, end, {.galactic_center = true, .min_galactic_center_altitude_deg = 10});
  CHECK(milky.empty());
  // A wall of 80° hides the moon, so all nights are dark.
  HorizonProfile wall;
  wall.azimuth_step_deg = 1;
  wall.altitude_deg.assign(360, 80);
  wall.distance_m.assign(360, 1000);
  double walled = 0;
  for (const TimeWindow& w : dark_windows(kVienna, start, end, options, wall)) {
    walled += w.end_utc - w.start_utc;
  }
  CHECK(walled >= total);
  REQUIRE_THROWS_AS(dark_windows(kVienna, 10, 10), std::invalid_argument);
}
