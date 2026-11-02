#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

#include "sunradar/terrain.hpp"

using Catch::Matchers::WithinAbs;
using namespace sunradar;

namespace {

/// The files of test/tools/make_geotiff.py: 7 rows and 5 columns with a step of 0.5°, the corner
/// at 48° north and 11° east, and h(row, column) = 1000 + 10 * row + column + 0.25.
std::vector<std::uint8_t> read_file(const std::string& name) {
  std::ifstream file(std::string(SUNRADAR_TEST_DATA_DIR) + "/geotiff/" + name, std::ios::binary);
  REQUIRE(file);
  return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}

double expected(const int row, const int column) { return 1000 + (10 * row) + column + 0.25; }

double at(const ElevationGrid& grid, const int row, const int column) {
  return static_cast<double>(
      grid.heights_m[(static_cast<std::size_t>(row) * static_cast<std::size_t>(grid.columns)) +
                     static_cast<std::size_t>(column)]);
}

void require_layout(const ElevationGrid& grid) {
  REQUIRE(grid.rows == 7);
  REQUIRE(grid.columns == 5);
  REQUIRE(grid.latitude_step_deg == 0.5);
  REQUIRE(grid.longitude_step_deg == 0.5);
  // The center of the first pixel is half a step inside the corner.
  REQUIRE_THAT(grid.north_latitude_deg, WithinAbs(47.75, 1e-12));
  REQUIRE_THAT(grid.west_longitude_deg, WithinAbs(11.25, 1e-12));
}

}  // namespace

TEST_CASE("read_elevation_geotiff reads tiles with DEFLATE and the float predictor", "[geotiff]") {
  const std::vector<std::uint8_t> data = read_file("float_tiles_deflate_p3.tif");
  const ElevationGrid grid = read_elevation_geotiff(data);
  require_layout(grid);
  for (int r = 0; r < grid.rows; ++r) {
    for (int c = 0; c < grid.columns; ++c) {
      REQUIRE(at(grid, r, c) == expected(r, c));
    }
  }

  // The overview has every second row and column, so it has half the resolution.
  const ElevationGrid overview = read_elevation_geotiff(data, 1);
  REQUIRE(overview.rows == 4);
  REQUIRE(overview.columns == 3);
  REQUIRE_THAT(overview.latitude_step_deg, WithinAbs(0.875, 1e-12));
  REQUIRE_THAT(overview.longitude_step_deg, WithinAbs(2.5 / 3, 1e-12));
  REQUIRE(at(overview, 1, 1) == expected(2, 2));
  REQUIRE_THROWS_AS(read_elevation_geotiff(data, 2), std::invalid_argument);
  REQUIRE_THROWS_AS(read_elevation_geotiff(data, -1), std::invalid_argument);
}

TEST_CASE("read_elevation_geotiff reads big-endian int16 strips with no data", "[geotiff]") {
  const ElevationGrid grid = read_elevation_geotiff(read_file("int16_strips_deflate_p2_be.tif"));
  // PixelIsPoint: the tie point is the center of the first pixel, so the layout is the same.
  require_layout(grid);
  for (int r = 0; r < grid.rows; ++r) {
    for (int c = 0; c < grid.columns; ++c) {
      if (r == 2 && c == 3) {
        REQUIRE(std::isnan(at(grid, r, c)));
      } else {
        REQUIRE(at(grid, r, c) == std::floor(expected(r, c)));
      }
    }
  }
}

TEST_CASE("read_elevation_geotiff reads an uncompressed strip", "[geotiff]") {
  const ElevationGrid grid = read_elevation_geotiff(read_file("float_strips_none.tif"));
  require_layout(grid);
  REQUIRE(at(grid, 6, 4) == expected(6, 4));
}

TEST_CASE("read_elevation_geotiff refuses files that are not valid", "[geotiff]") {
  REQUIRE_THROWS_AS(read_elevation_geotiff({}), std::invalid_argument);
  REQUIRE_THROWS_AS(read_elevation_geotiff({'I', 'I', 43, 0, 8, 0, 0, 0}), std::invalid_argument);
  REQUIRE_THROWS_AS(read_elevation_geotiff({'X', 'X', 42, 0, 8, 0, 0, 0}), std::invalid_argument);

  // A truncated file must throw or give the full image, not crash. The full image needs only the
  // part up to the end of the first directory, because its data comes before the directory.
  const std::vector<std::uint8_t> data = read_file("float_tiles_deflate_p3.tif");
  const std::size_t first = data[4] | (static_cast<std::size_t>(data[5]) << 8U);
  const std::size_t entries = data[first] | (static_cast<std::size_t>(data[first + 1]) << 8U);
  const std::size_t first_end = first + 2 + (12 * entries) + 4;
  for (std::size_t size = 0; size < data.size(); size += 7) {
    INFO("size " << size);
    const std::vector<std::uint8_t> prefix(data.begin(), data.begin() + static_cast<long>(size));
    if (size < first_end) {
      REQUIRE_THROWS_AS(read_elevation_geotiff(prefix), std::invalid_argument);
    } else {
      REQUIRE(read_elevation_geotiff(prefix).rows == 7);
    }
  }
  // A changed byte in the compressed data must throw or give a grid, not crash.
  for (std::size_t i = 0; i < data.size(); i += 3) {
    std::vector<std::uint8_t> changed = data;
    changed[i] = static_cast<std::uint8_t>(changed[i] ^ 0x5AU);
    try {
      static_cast<void>(read_elevation_geotiff(changed));
    } catch (const std::invalid_argument&) {
      // expected for most bytes
    }
  }
}

TEST_CASE("elevation_geotiff_prefix_size gives the bytes that a level needs", "[geotiff]") {
  for (const std::string name :
       {"float_tiles_deflate_p3.tif", "int16_strips_deflate_p2_be.tif", "float_strips_none.tif"}) {
    const std::vector<std::uint8_t> data = read_file(name);
    for (const int level : {0, 1}) {
      if (name != "float_tiles_deflate_p3.tif" && level == 1) {
        continue;  // only this file has an overview
      }
      INFO(name << " level " << level);
      const std::uint32_t size = elevation_geotiff_prefix_size(data, level);
      REQUIRE(size > 0);
      REQUIRE(size <= data.size());
      // The prefix reads the level exactly as the whole file.
      const std::vector<std::uint8_t> prefix(data.begin(), data.begin() + size);
      const ElevationGrid from_prefix = read_elevation_geotiff(prefix, level);
      const ElevationGrid from_file = read_elevation_geotiff(data, level);
      REQUIRE(from_prefix.rows == from_file.rows);
      for (std::size_t i = 0; i < from_file.heights_m.size(); ++i) {
        const auto a = static_cast<double>(from_prefix.heights_m[i]);
        const auto b = static_cast<double>(from_file.heights_m[i]);
        REQUIRE(((std::isnan(a) && std::isnan(b)) || a == b));
      }
      // One byte less is not enough, and a header of that size cannot give the answer.
      const std::vector<std::uint8_t> shorter(data.begin(), data.begin() + size - 1);
      REQUIRE_THROWS_AS(read_elevation_geotiff(shorter, level), std::invalid_argument);
      // The answer does not change with more bytes than the prefix.
      REQUIRE(elevation_geotiff_prefix_size(prefix, level) == size);
    }
  }
  // A header that is too short to tell gives 0, also with fewer than 8 bytes.
  const std::vector<std::uint8_t> data = read_file("float_tiles_deflate_p3.tif");
  REQUIRE(elevation_geotiff_prefix_size({data.begin(), data.begin() + 4}, 0) == 0);
  REQUIRE(elevation_geotiff_prefix_size({data.begin(), data.begin() + 16}, 0) == 0);
  REQUIRE_THROWS_AS(elevation_geotiff_prefix_size({'X', 'X', 42, 0, 8, 0, 0, 0}, 0),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(elevation_geotiff_prefix_size(data, 5), std::invalid_argument);
}
