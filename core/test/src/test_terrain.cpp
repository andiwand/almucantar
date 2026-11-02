#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <cstddef>
#include <limits>
#include <set>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "sunradar/sun.hpp"
#include "sunradar/terrain.hpp"

using Catch::Matchers::WithinAbs;
using namespace sunradar;

namespace {

constexpr double kEarthRadiusM = 6'371'008.8;
constexpr double kDegToRad = 3.14159265358979323846 / 180;
constexpr double kRadToDeg = 180 / 3.14159265358979323846;
/// 2026-06-21 00:00 UTC.
constexpr double kSolsticeUtc = 1'782'000'000;

const Observer kAlps{.latitude_deg = 47, .longitude_deg = 11};

std::size_t index(const ElevationGrid& grid, const int row, const int column) {
  return (static_cast<std::size_t>(row) * static_cast<std::size_t>(grid.columns)) +
         static_cast<std::size_t>(column);
}

/// A grid of 0 m around `kAlps`, with a step of 1/120° (about 0.9 km by 0.6 km).
ElevationGrid flat_grid() {
  ElevationGrid grid;
  grid.latitude_step_deg = 1.0 / 120;
  grid.longitude_step_deg = 1.0 / 120;
  grid.rows = 289;     // 2.4° from north to south
  grid.columns = 433;  // 3.6° from west to east
  grid.north_latitude_deg = kAlps.latitude_deg + 1.2;
  grid.west_longitude_deg = kAlps.longitude_deg - 1.8;
  grid.heights_m.assign(index(grid, grid.rows, 0), 0.0F);
  return grid;
}

/// The altitude of a point at the distance `d` and the height `h` above the eye, with the
/// standard terrestrial refraction.
double expected_altitude_deg(const double h, const double d) {
  const double effective_radius = kEarthRadiusM / (1 - 0.13);
  return std::atan((h - (d * d / (2 * effective_radius))) / d) * kRadToDeg;
}

HorizonProfile constant_profile(const double altitude_deg) {
  HorizonProfile profile;
  profile.azimuth_step_deg = 1;
  profile.altitude_deg.assign(360, altitude_deg);
  profile.distance_m.assign(360, 1000);
  return profile;
}

}  // namespace

TEST_CASE("horizon_profile gives the dip of the horizon over flat ground", "[terrain]") {
  // From 100 m above a flat Earth with refraction, the horizon is at -sqrt(2 h / R').
  const HorizonProfile profile =
      horizon_profile(kAlps, {flat_grid()}, {.eye_height_m = 100, .use_grid_elevation = true});
  const double effective_radius = kEarthRadiusM / (1 - 0.13);
  const double dip_deg = -std::sqrt(2 * 100 / effective_radius) * kRadToDeg;
  const double distance_m = std::sqrt(2 * effective_radius * 100);
  REQUIRE(profile.altitude_deg.size() == 1440);
  REQUIRE(profile.observer_elevation_m == 0);
  for (std::size_t i = 0; i < profile.altitude_deg.size(); i += 40) {
    INFO("azimuth index " << i);
    REQUIRE_THAT(profile.altitude_deg[i], WithinAbs(dip_deg, 0.001));
    REQUIRE_THAT(profile.distance_m[i], WithinAbs(distance_m, 2000));
  }
}

TEST_CASE("horizon_profile finds a ridge in the east", "[terrain]") {
  // A ridge of 2000 m from 20 km to 25 km east of the observer, along all rows.
  ElevationGrid grid = flat_grid();
  const double meters_per_degree_east = kEarthRadiusM * kDegToRad * std::cos(47 * kDegToRad);
  const double west_edge_deg = kAlps.longitude_deg + (20'000 / meters_per_degree_east);
  const double east_edge_deg = kAlps.longitude_deg + (25'000 / meters_per_degree_east);
  double first_column_deg = 0;
  for (int c = grid.columns - 1; c >= 0; --c) {
    const double longitude = grid.west_longitude_deg + (c * grid.longitude_step_deg);
    if (longitude >= west_edge_deg && longitude <= east_edge_deg) {
      first_column_deg = longitude;
      for (int r = 0; r < grid.rows; ++r) {
        grid.heights_m[index(grid, r, c)] = 2000;
      }
    }
  }
  const double ridge_m = (first_column_deg - kAlps.longitude_deg) * meters_per_degree_east;
  const HorizonProfile profile = horizon_profile(kAlps, {grid}, {.azimuth_step_deg = 1});
  REQUIRE(profile.altitude_deg.size() == 360);

  // The first sample on the ridge can be up to one step (316 m) behind its edge.
  const double expected_deg = expected_altitude_deg(2000 - 1.6, ridge_m);
  REQUIRE_THAT(profile.altitude_deg[90], WithinAbs(expected_deg, 0.1));
  REQUIRE_THAT(profile.distance_m[90], WithinAbs(ridge_m, 700));
  REQUIRE_THAT(terrain_altitude_deg(profile, 90.5),
               WithinAbs((profile.altitude_deg[90] + profile.altitude_deg[91]) / 2, 1e-12));
  // The west has only the flat ground.
  REQUIRE(profile.altitude_deg[270] < 0);
  REQUIRE(profile.altitude_deg[270] > -0.1);
}

TEST_CASE("horizon_profile uses the given elevation of the observer", "[terrain]") {
  ElevationGrid grid = flat_grid();
  const std::size_t center = index(grid, 144, 216);
  grid.heights_m[center] = std::numeric_limits<float>::quiet_NaN();
  REQUIRE_THROWS_AS(horizon_profile(kAlps, {grid}), std::invalid_argument);

  const HorizonProfile profile = horizon_profile(kAlps, {grid},
                                                 {.azimuth_step_deg = 10,
                                                  .eye_height_m = 0,
                                                  .use_grid_elevation = false,
                                                  .observer_elevation_m = 500});
  REQUIRE(profile.observer_elevation_m == 500);
  // From 500 m above the plain, the terrain is below the horizon in all directions.
  for (const double altitude : profile.altitude_deg) {
    REQUIRE(altitude < -0.5);
  }
}

TEST_CASE("horizon_profile has no data where the tiles have none", "[terrain]") {
  ElevationGrid grid = flat_grid();
  grid.heights_m.assign(grid.heights_m.size(), std::numeric_limits<float>::quiet_NaN());
  // Only the observer and its neighbors have data.
  for (const int r : {144, 145}) {
    for (const int c : {216, 217}) {
      grid.heights_m[index(grid, r, c)] = 0;
    }
  }
  const HorizonProfile profile = horizon_profile(kAlps, {grid}, {.azimuth_step_deg = 90});
  // To the north and the west, the ray finds no data.
  REQUIRE(std::isnan(profile.altitude_deg[0]));
  REQUIRE(std::isnan(profile.distance_m[3]));
  REQUIRE(std::isnan(terrain_altitude_deg(profile, 10)));
}

TEST_CASE("horizon_profile validates its arguments", "[terrain]") {
  ElevationGrid grid = flat_grid();
  REQUIRE_THROWS_AS(horizon_profile(kAlps, {grid}, {.azimuth_step_deg = 0.7}),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(horizon_profile(kAlps, {grid}, {.max_distance_m = 600'000}),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(horizon_profile({.latitude_deg = 10, .longitude_deg = 11}, {grid}),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(horizon_profile(kAlps, {}), std::invalid_argument);
  grid.heights_m.pop_back();
  REQUIRE_THROWS_AS(horizon_profile(kAlps, {grid}), std::invalid_argument);
}

TEST_CASE("terrain_altitude_deg interpolates across north", "[terrain]") {
  HorizonProfile profile;
  profile.azimuth_step_deg = 90;
  profile.altitude_deg = {0, 10, 20, 30};
  profile.distance_m = {1, 1, 1, 1};
  REQUIRE_THAT(terrain_altitude_deg(profile, 45), WithinAbs(5, 1e-12));
  REQUIRE_THAT(terrain_altitude_deg(profile, 315), WithinAbs(15, 1e-12));
  REQUIRE_THAT(terrain_altitude_deg(profile, -45), WithinAbs(15, 1e-12));
  REQUIRE_THAT(terrain_altitude_deg(profile, 360), WithinAbs(0, 1e-12));
  REQUIRE_THROWS_AS(terrain_altitude_deg(HorizonProfile{}, 0), std::invalid_argument);
}

TEST_CASE("terrain_events over a flat horizon agrees with sun_events", "[terrain]") {
  const Observer vienna{.latitude_deg = 48.21, .longitude_deg = 16.37};
  const double start = kSolsticeUtc - 7200;
  const double end = start + 86400;
  const TerrainEvents terrain = terrain_events(vienna, constant_profile(0), start, end);
  const DayEvents sun = sun_events(vienna, start, end);
  REQUIRE(terrain.kind == DayKind::normal);
  REQUIRE(terrain.crossings.size() == 2);
  REQUIRE(sun.crossings.size() == 2);
  for (std::size_t i = 0; i < 2; ++i) {
    // The upper limb at the apparent altitude 0 is about the standard -0.8333° (airless).
    REQUIRE(terrain.crossings[i].rising == sun.crossings[i].rising);
    REQUIRE_THAT(terrain.crossings[i].unix_utc, WithinAbs(sun.crossings[i].unix_utc, 60));
  }
}

TEST_CASE("terrain_events finds a peak that hides the sun", "[terrain]") {
  const Observer vienna{.latitude_deg = 48.21, .longitude_deg = 16.37};
  const double start = kSolsticeUtc - 7200;
  const double end = start + 86400;
  // A steep mountain of 70° in the south-east, from the azimuth 120° to 140°.
  HorizonProfile profile = constant_profile(0);
  for (int a = 120; a <= 140; ++a) {
    profile.altitude_deg[static_cast<std::size_t>(a)] = 70;
  }
  const TerrainEvents events = terrain_events(vienna, profile, start, end);
  REQUIRE(events.kind == DayKind::normal);
  REQUIRE(events.crossings.size() == 4);
  for (std::size_t i = 0; i < events.crossings.size(); ++i) {
    const Crossing& crossing = events.crossings[i];
    REQUIRE(crossing.rising == (i % 2 == 0));
    // The upper limb crosses the terrain within the tolerance of 0.5 s. The edges of the
    // mountain are steep, so check the sign 1 s before and after, not the angle.
    const auto clearance = [&](const double t) {
      const Horizontal sun = sun_position(t, vienna);
      return sun.altitude_deg + 0.2667 - terrain_altitude_deg(profile, sun.azimuth_deg);
    };
    INFO("crossing " << i);
    REQUIRE((clearance(crossing.unix_utc - 1) > 0) != crossing.rising);
    REQUIRE((clearance(crossing.unix_utc + 1) > 0) == crossing.rising);
  }
  // The sun goes behind the mountain and comes back, both in the south-east.
  REQUIRE(sun_position(events.crossings[1].unix_utc, vienna).azimuth_deg > 119);
  REQUIRE(sun_position(events.crossings[2].unix_utc, vienna).azimuth_deg < 141);
}

TEST_CASE("terrain_events gives polar_day and polar_night", "[terrain]") {
  const Observer vienna{.latitude_deg = 48.21, .longitude_deg = 16.37};
  const double start = kSolsticeUtc;
  const double end = start + 86400;
  REQUIRE(terrain_events(vienna, constant_profile(80), start, end).kind == DayKind::polar_night);
  // The sun in Vienna is never 80° below the horizon, so it stays above this terrain.
  REQUIRE(terrain_events(vienna, constant_profile(-80), start, end).kind == DayKind::polar_day);
  const Observer svalbard{.latitude_deg = 78.2, .longitude_deg = 15.6};
  REQUIRE(terrain_events(svalbard, constant_profile(0), start, end).kind == DayKind::polar_day);
  REQUIRE_THROWS_AS(terrain_events(vienna, constant_profile(0), end, start), std::invalid_argument);
}

namespace {

/// Splits `grid` at column `split` into two tiles with a gap of one cell between them, as the
/// tiles of Copernicus have: the last sample of the west tile and the first of the east tile are
/// one step apart.
std::pair<ElevationGrid, ElevationGrid> split_tiles(const ElevationGrid& grid, const int split) {
  ElevationGrid west = grid;
  ElevationGrid east = grid;
  west.columns = split;
  east.columns = grid.columns - split;
  east.west_longitude_deg = grid.west_longitude_deg + (split * grid.longitude_step_deg);
  west.heights_m.clear();
  east.heights_m.clear();
  for (int r = 0; r < grid.rows; ++r) {
    for (int c = 0; c < grid.columns; ++c) {
      (c < split ? west : east).heights_m.push_back(grid.heights_m[index(grid, r, c)]);
    }
  }
  return {west, east};
}

}  // namespace

TEST_CASE("horizon_profile joins tiles across the gap at their edges", "[terrain]") {
  // A ridge of 1500 m at 10 km to the east, and a tile edge on top of it.
  ElevationGrid grid = flat_grid();
  for (int r = 0; r < grid.rows; ++r) {
    for (int c = 230; c < 240; ++c) {
      grid.heights_m[index(grid, r, c)] = 1500;
    }
  }
  const HorizonOptions options{.azimuth_step_deg = 5};
  const HorizonProfile one = horizon_profile(kAlps, {grid}, options);
  const auto [west, east] = split_tiles(grid, 235);
  // The order of the tiles does not matter.
  const HorizonProfile two = horizon_profile(kAlps, {east, west}, options);
  for (std::size_t i = 0; i < one.altitude_deg.size(); ++i) {
    INFO("azimuth " << static_cast<double>(i) * 5);
    REQUIRE_THAT(two.altitude_deg[i], WithinAbs(one.altitude_deg[i], 0.01));
  }
  REQUIRE(one.altitude_deg[18] > 5);  // the ridge in the east

  // Without the tile of the ridge, the rays to the east find only the flat ground.
  const auto [near_tile, ridge_tile] = split_tiles(grid, 225);
  REQUIRE(horizon_profile(kAlps, {near_tile}, options).altitude_deg[18] < 0);
}

TEST_CASE("elevation_tiles lists the tiles near the observer", "[terrain]") {
  const auto as_set = [](const std::vector<ElevationTile>& tiles) {
    std::set<std::pair<int, int>> set;
    for (const ElevationTile& t : tiles) {
      set.emplace(t.latitude_deg, t.longitude_deg);
    }
    return set;
  };
  // 47.1° N, 11.1° E: the tiles to the south (11 km), the west (7.6 km), and the south-west
  // (13.5 km) are nearer than 20 km, the others are not.
  const std::vector<ElevationTile> near =
      elevation_tiles({.latitude_deg = 47.1, .longitude_deg = 11.1}, 20'000);
  REQUIRE(near.front().latitude_deg == 47);
  REQUIRE(near.front().longitude_deg == 11);
  REQUIRE(near.front().distance_m == 0);
  REQUIRE_THAT(near[1].distance_m, WithinAbs(7'600, 100));  // the tile to the west
  REQUIRE(as_set(near) == std::set<std::pair<int, int>>{{47, 11}, {46, 11}, {47, 10}, {46, 10}});
  REQUIRE(elevation_tiles({.latitude_deg = 47.5, .longitude_deg = 11.5}, 20'000).size() == 1);

  // 100 km covers about 0.9° north and south, and 1.3° east and west.
  const std::vector<ElevationTile> far =
      elevation_tiles({.latitude_deg = 47.27, .longitude_deg = 11.39}, 100'000);
  REQUIRE(far.size() >= 9);
  REQUIRE(far.size() <= 12);
  REQUIRE(as_set(far).contains({48, 12}));
  REQUIRE_FALSE(as_set(far).contains({49, 11}));

  // Across the antimeridian.
  const auto fiji =
      as_set(elevation_tiles({.latitude_deg = -17.5, .longitude_deg = 179.9}, 50'000));
  REQUIRE(fiji.contains({-18, 179}));
  REQUIRE(fiji.contains({-18, -180}));

  // Near the pole, all longitudes.
  const auto pole = elevation_tiles({.latitude_deg = 89.9, .longitude_deg = 0}, 50'000);
  REQUIRE(pole.size() == 360);
  REQUIRE_THROWS_AS(elevation_tiles({.latitude_deg = 47, .longitude_deg = 11}, 0),
                    std::invalid_argument);
}
