#include <algorithm>
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

#include "sunradar/moon.hpp"
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
  // The profile must cover 360°, else the interpolation goes past its end.
  profile.altitude_deg = {0, 10, 20};
  REQUIRE_THROWS_AS(terrain_altitude_deg(profile, 300), std::invalid_argument);
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
  const HorizonProfile two = horizon_profile(kAlps, {east, west}, options);
  for (std::size_t i = 0; i < one.altitude_deg.size(); ++i) {
    INFO("azimuth " << static_cast<double>(i) * 5);
    REQUIRE_THAT(two.altitude_deg[i], WithinAbs(one.altitude_deg[i], 0.01));
  }
  // The order of the tiles does not change the result, also not in the gap between them.
  const HorizonProfile swapped = horizon_profile(kAlps, {west, east}, options);
  REQUIRE(swapped.altitude_deg == two.altitude_deg);
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

  // At a high latitude, the nearest point of a tile is not at the latitude of the observer: the
  // great circle to the east edge goes toward the pole. The tile 80° N, 26° E has its nearest
  // point at 81° N, 494.1 km away (a brute-force search over its edges gives the same).
  const auto north = elevation_tiles({.latitude_deg = 80, .longitude_deg = -0.5}, 500'000);
  const auto tile = std::ranges::find_if(
      north, [](const ElevationTile& t) { return t.latitude_deg == 80 && t.longitude_deg == 26; });
  REQUIRE(tile != north.end());
  REQUIRE_THAT(tile->distance_m, WithinAbs(494'143, 10));
  REQUIRE_THROWS_AS(elevation_tiles({.latitude_deg = 47, .longitude_deg = 11}, 0),
                    std::invalid_argument);
}

namespace {

/// The standard formula of the tile scheme of OpenStreetMap.
std::pair<int, int> osm_tile(const Observer& o, const int zoom) {
  const double n = std::ldexp(1.0, zoom);
  const double phi = o.latitude_deg * kDegToRad;
  return {static_cast<int>(std::floor((o.longitude_deg + 180) / 360 * n)),
          static_cast<int>(
              std::floor((1 - (std::asinh(std::tan(phi)) / 3.14159265358979323846)) / 2 * n))};
}

double mercator_latitude(const double y, const double n) {
  return std::atan(std::sinh(3.14159265358979323846 * (1 - (2 * y / n)))) * kRadToDeg;
}

}  // namespace

TEST_CASE("map_tiles lists the Web Mercator tiles near the observer", "[terrain]") {
  const Observer vienna{.latitude_deg = 48.2082, .longitude_deg = 16.3738};
  const std::vector<MapTile> tiles = map_tiles(vienna, 100'000, 10);
  const auto [x, y] = osm_tile(vienna, 10);
  REQUIRE(tiles.front().x == x);
  REQUIRE(tiles.front().y == y);
  REQUIRE(tiles.front().zoom == 10);
  REQUIRE(tiles.front().distance_m == 0);
  // A tile at zoom 10 is about 26 km wide and high here, so 200 km need about 8 by 8 tiles,
  // without the corners.
  REQUIRE(tiles.size() > 40);
  REQUIRE(tiles.size() < 80);
  for (const MapTile& t : tiles) {
    REQUIRE(t.distance_m <= 101'000);
    REQUIRE(std::abs(t.x - x) <= 5);
    REQUIRE(std::abs(t.y - y) <= 5);
  }
  REQUIRE(map_tiles(vienna, 1000, 0).size() == 1);
  REQUIRE_THROWS_AS(map_tiles(vienna, 1000, 13), std::invalid_argument);
}

TEST_CASE("terrarium_grid converts a tile to equal steps of latitude", "[terrain]") {
  const MapTile tile{.zoom = 10, .x = 558, .y = 355};
  const int size = 256;
  const double n = 1024;
  // Each pixel has the height 1000 * (latitude of its center - 48), so the height of the grid at
  // a row must be 1000 * (latitude of the row - 48).
  std::vector<std::uint8_t> rgba(static_cast<std::size_t>(size) * size * 4);
  for (int r = 0; r < size; ++r) {
    const double latitude = mercator_latitude(tile.y + ((r + 0.5) / size), n);
    const double value = (1000 * (latitude - 48)) + 32768;
    for (int c = 0; c < size; ++c) {
      const std::size_t i =
          ((static_cast<std::size_t>(r) * size) + static_cast<std::size_t>(c)) * 4;
      rgba[i] = static_cast<std::uint8_t>(std::floor(value / 256));
      rgba[i + 1] = static_cast<std::uint8_t>(static_cast<int>(std::floor(value)) % 256);
      rgba[i + 2] = static_cast<std::uint8_t>(std::floor((value - std::floor(value)) * 256));
      rgba[i + 3] = 255;
    }
  }
  const ElevationGrid grid = terrarium_grid(rgba, size, size, tile);
  REQUIRE(grid.rows == size);
  REQUIRE(grid.columns == size);
  REQUIRE_THAT(grid.north_latitude_deg, WithinAbs(mercator_latitude(355 + (0.5 / size), n), 1e-12));
  REQUIRE_THAT(grid.west_longitude_deg,
               WithinAbs(-180 + (360.0 * 558 / n) + (180.0 / n / size), 1e-12));
  REQUIRE_THAT(grid.longitude_step_deg, WithinAbs(360.0 / n / size, 1e-15));
  for (int r = 0; r < size; r += 15) {
    const double latitude = grid.north_latitude_deg - (r * grid.latitude_step_deg);
    INFO("row " << r);
    // The linear interpolation between two rows of the image gives an error below 1 mm here.
    REQUIRE_THAT(grid.heights_m[index(grid, r, 7)], WithinAbs(1000 * (latitude - 48), 0.01));
  }
  REQUIRE_THROWS_AS(terrarium_grid(rgba, size, size - 1, tile), std::invalid_argument);
  REQUIRE_THROWS_AS(terrarium_grid(rgba, size, size, {.zoom = 1, .x = 2}), std::invalid_argument);
}

TEST_CASE("terrain_events finds the moon behind the terrain", "[terrain]") {
  const Observer vienna{.latitude_deg = 48.21, .longitude_deg = 16.37};
  const double start = kSolsticeUtc;
  const double end = start + (3 * 86400);
  const TerrainEventOptions moon{.body = Body::moon};
  const TerrainEvents flat = terrain_events(vienna, constant_profile(0), start, end, moon);
  REQUIRE(flat.kind == DayKind::normal);
  // Three days have about three moonrises and three moonsets.
  REQUIRE(flat.crossings.size() >= 5);
  REQUIRE(flat.crossings.size() <= 7);
  for (std::size_t i = 0; i < flat.crossings.size(); ++i) {
    const Crossing& crossing = flat.crossings[i];
    if (i > 0) {
      REQUIRE(crossing.rising != flat.crossings[i - 1].rising);
    }
    // The upper limb of the moon is on the horizon within the tolerance of 0.5 s.
    const auto clearance = [&](const double t) {
      return moon_position(t, vienna).altitude_deg + 0.2667;
    };
    INFO("crossing " << i);
    REQUIRE((clearance(crossing.unix_utc - 1) > 0) != crossing.rising);
    REQUIRE((clearance(crossing.unix_utc + 1) > 0) == crossing.rising);
  }

  // A terrain of 10° makes each moonrise later and each moonset earlier.
  const TerrainEvents high = terrain_events(vienna, constant_profile(10), start, end, moon);
  const auto first = [](const TerrainEvents& e, const bool rising) {
    for (const Crossing& c : e.crossings) {
      if (c.rising == rising) {
        return c.unix_utc;
      }
    }
    return std::numeric_limits<double>::quiet_NaN();
  };
  REQUIRE(first(high, true) > first(flat, true));
  REQUIRE(first(high, true) - first(flat, true) < 7200);

  // The sun is the default, so the moon gives other times.
  REQUIRE(first(terrain_events(vienna, constant_profile(0), start, end), true) !=
          first(flat, true));
  REQUIRE_THROWS_AS(
      terrain_events(vienna, constant_profile(0), start, end, {.body = static_cast<Body>(7)}),
      std::invalid_argument);
}

TEST_CASE("horizon_profile prefers the finer tile where tiles overlap", "[terrain]") {
  // A fine grid with a ridge at 10 km to the east, on top of a coarse flat grid of the same area.
  ElevationGrid fine = flat_grid();
  for (int r = 0; r < fine.rows; ++r) {
    for (int c = 230; c < 240; ++c) {
      fine.heights_m[index(fine, r, c)] = 1500;
    }
  }
  ElevationGrid coarse = flat_grid();
  coarse.latitude_step_deg *= 4;
  coarse.longitude_step_deg *= 4;
  coarse.rows = 73;
  coarse.columns = 109;
  coarse.heights_m.assign(index(coarse, coarse.rows, 0), 0.0F);
  const HorizonOptions options{.azimuth_step_deg = 10};
  const HorizonProfile alone = horizon_profile(kAlps, {fine}, options);
  for (const auto& tiles :
       {std::vector<ElevationGrid>{coarse, fine}, std::vector<ElevationGrid>{fine, coarse}}) {
    const HorizonProfile both = horizon_profile(kAlps, tiles, options);
    for (std::size_t i = 0; i < alone.altitude_deg.size(); ++i) {
      REQUIRE(both.altitude_deg[i] == alone.altitude_deg[i]);
    }
  }
  REQUIRE(alone.altitude_deg[9] > 5);  // the ridge in the east
}

TEST_CASE("horizon_profile ignores the terrain nearer than min_distance_m", "[terrain]") {
  // A wall of 30 m at 300 m to the east (a smeared city block), and a ridge at 10 km.
  ElevationGrid grid = flat_grid();
  const double meters_per_degree_east = kEarthRadiusM * kDegToRad * std::cos(47 * kDegToRad);
  for (int r = 0; r < grid.rows; ++r) {
    for (int c = 0; c < grid.columns; ++c) {
      const double east_m =
          (grid.west_longitude_deg + (c * grid.longitude_step_deg) - kAlps.longitude_deg) *
          meters_per_degree_east;
      if (east_m > 300 && east_m < 1000) {
        grid.heights_m[index(grid, r, c)] = 30;
      }
      if (east_m > 10'000 && east_m < 11'000) {
        grid.heights_m[index(grid, r, c)] = 300;
      }
    }
  }
  // A step of 1°: the ray takes samples at about half the width of the beam, so a wide step
  // would jump over the ridge.
  const HorizonProfile all = horizon_profile(kAlps, {grid}, {.azimuth_step_deg = 1});
  const HorizonProfile cleared =
      horizon_profile(kAlps, {grid}, {.azimuth_step_deg = 1, .min_distance_m = 2000});
  // With all terrain, the near wall is the horizon in the east (2.6°, far above the ridge).
  REQUIRE(all.altitude_deg[90] > 2);
  REQUIRE(all.distance_m[90] < 1500);
  // With the clearance, the ridge at 10 km is the horizon (about 1.6°).
  REQUIRE_THAT(cleared.altitude_deg[90], WithinAbs(expected_altitude_deg(300 - 1.6, 10'000), 0.2));
  REQUIRE(cleared.distance_m[90] >= 10'000);
  REQUIRE_THROWS_AS(
      horizon_profile(kAlps, {grid}, {.max_distance_m = 1000, .min_distance_m = 1000}),
      std::invalid_argument);
  REQUIRE_THROWS_AS(horizon_profile(kAlps, {grid}, {.min_distance_m = -1}), std::invalid_argument);
}
