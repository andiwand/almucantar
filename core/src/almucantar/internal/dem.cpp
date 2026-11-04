#include "almucantar/internal/dem.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <optional>
#include <tuple>
#include <vector>

#include "almucantar/internal/math.hpp"
#include "almucantar/internal/validate.hpp"

namespace almucantar::internal {

namespace {

constexpr double kMaxGridSamples = 200'000'000;

}  // namespace

void validate(const ElevationGrid& grid) {
  require_finite(grid.north_latitude_deg, "north_latitude_deg");
  require_finite(grid.west_longitude_deg, "west_longitude_deg");
  require_positive(grid.latitude_step_deg, "latitude_step_deg");
  require_positive(grid.longitude_step_deg, "longitude_step_deg");
  require(grid.rows >= 2, "rows is less than 2");
  require(grid.columns >= 2, "columns is less than 2");
  require(static_cast<double>(grid.rows) * grid.columns <= kMaxGridSamples,
          "the grid has more than 200,000,000 samples");
  require(grid.heights_m.size() ==
              static_cast<std::size_t>(grid.rows) * static_cast<std::size_t>(grid.columns),
          "heights_m does not have rows * columns values");
  require(grid.north_latitude_deg <= 90, "north_latitude_deg is greater than 90");
  require(grid.north_latitude_deg - (grid.rows - 1) * grid.latitude_step_deg >= -90,
          "the grid goes south of -90");
  require((grid.columns - 1) * grid.longitude_step_deg < 360, "the grid is wider than 360°");
}

SortedTiles sort_tiles(const std::vector<ElevationGrid>& tiles, const double latitude_deg) {
  require(tiles.size() <= 1000, "tiles has more than 1000 grids");
  for (const ElevationGrid& grid : tiles) {
    validate(grid);
  }
  const double cos_latitude = std::max(std::cos(latitude_deg * kDegToRad), 0.01);
  SortedTiles sorted;
  sorted.reserve(tiles.size());
  for (const ElevationGrid& grid : tiles) {
    sorted.push_back(&grid);
  }
  const auto key = [cos_latitude](const ElevationGrid* g) {
    return std::tuple{std::min(g->latitude_step_deg, g->longitude_step_deg * cos_latitude),
                      -g->north_latitude_deg, g->west_longitude_deg};
  };
  std::ranges::sort(sorted, {}, key);
  return sorted;
}

double smallest_cell_m(const std::vector<ElevationGrid>& tiles, const double latitude_deg) {
  // Near a pole the columns get narrow, so the cosine has a lower limit.
  const double meters_per_degree = kEarthRadiusM * kDegToRad;
  const double cos_latitude = std::max(std::cos(latitude_deg * kDegToRad), 0.01);
  double cell_m = std::numeric_limits<double>::infinity();
  for (const ElevationGrid& grid : tiles) {
    cell_m = std::min({cell_m, grid.latitude_step_deg * meters_per_degree,
                       grid.longitude_step_deg * meters_per_degree * cos_latitude});
  }
  return cell_m;
}

std::optional<double> height_at(const ElevationGrid& grid, const double latitude_deg,
                                const double longitude_deg, const double margin) {
  double row = (grid.north_latitude_deg - latitude_deg) / grid.latitude_step_deg;
  double column = wrap_360(longitude_deg - grid.west_longitude_deg) / grid.longitude_step_deg;
  const double full_turn = 360 / grid.longitude_step_deg;
  if (column > full_turn - margin) {
    column -= full_turn;  // a place just west of the grid
  }
  const double last_row = grid.rows - 1;
  const double last_column = grid.columns - 1;
  if (row < -margin || row > last_row + margin || column < -margin ||
      column > last_column + margin) {
    return std::nullopt;
  }
  row = std::clamp(row, 0.0, last_row);
  column = std::clamp(column, 0.0, last_column);
  const int r = std::min(static_cast<int>(row), grid.rows - 2);
  const int c = std::min(static_cast<int>(column), grid.columns - 2);
  const double u = row - r;
  const double v = column - c;
  const auto at = [&grid](const int rr, const int cc) {
    const std::size_t index =
        (static_cast<std::size_t>(rr) * static_cast<std::size_t>(grid.columns)) +
        static_cast<std::size_t>(cc);
    return static_cast<double>(grid.heights_m[index]);
  };
  const double h = ((1 - u) * ((1 - v) * at(r, c) + v * at(r, c + 1))) +
                   (u * ((1 - v) * at(r + 1, c) + v * at(r + 1, c + 1)));
  if (std::isnan(h)) {
    return std::nullopt;
  }
  return h;
}

std::optional<double> height_in_tiles(const SortedTiles& tiles, const double latitude_deg,
                                      const double longitude_deg) {
  // A margin of 1.5 cells, because the gap between two Web Mercator tiles in a grid with equal
  // steps of latitude (see terrarium_grid()) can be a little larger than one step.
  for (const double margin : {0.0, 1.5}) {
    for (const ElevationGrid* tile : tiles) {
      if (const auto h = height_at(*tile, latitude_deg, longitude_deg, margin)) {
        return h;
      }
    }
  }
  return std::nullopt;
}

Observer destination(const Observer& from, const double azimuth_deg, const double distance_m) {
  const double phi1 = from.latitude_deg * kDegToRad;
  const double theta = azimuth_deg * kDegToRad;
  const double delta = distance_m / kEarthRadiusM;
  const double sin_phi2 =
      (std::sin(phi1) * std::cos(delta)) + (std::cos(phi1) * std::sin(delta) * std::cos(theta));
  const double phi2 = std::asin(std::clamp(sin_phi2, -1.0, 1.0));
  const double lambda2 = (from.longitude_deg * kDegToRad) +
                         std::atan2(std::sin(theta) * std::sin(delta) * std::cos(phi1),
                                    std::cos(delta) - (std::sin(phi1) * sin_phi2));
  return {.latitude_deg = phi2 * kRadToDeg, .longitude_deg = wrap_180(lambda2 * kRadToDeg)};
}

double bearing_deg(const Observer& from, const Observer& to) {
  const double phi1 = from.latitude_deg * kDegToRad;
  const double phi2 = to.latitude_deg * kDegToRad;
  const double dlambda = (to.longitude_deg - from.longitude_deg) * kDegToRad;
  const double y = std::sin(dlambda) * std::cos(phi2);
  const double x =
      (std::cos(phi1) * std::sin(phi2)) - (std::sin(phi1) * std::cos(phi2) * std::cos(dlambda));
  return wrap_360(std::atan2(y, x) * kRadToDeg);
}

double distance_m(const Observer& from, const Observer& to) {
  const double phi1 = from.latitude_deg * kDegToRad;
  const double phi2 = to.latitude_deg * kDegToRad;
  const double dphi = phi2 - phi1;
  const double dlambda = (to.longitude_deg - from.longitude_deg) * kDegToRad;
  const double a =
      (std::sin(dphi / 2) * std::sin(dphi / 2)) +
      (std::cos(phi1) * std::cos(phi2) * std::sin(dlambda / 2) * std::sin(dlambda / 2));
  return 2 * kEarthRadiusM * std::asin(std::min(1.0, std::sqrt(a)));
}

double sight_tangent(const double rise_m, const double distance_m,
                     const double refraction_coefficient) {
  const double drop_m =
      distance_m * distance_m * (1 - refraction_coefficient) / (2 * kEarthRadiusM);
  return (rise_m - drop_m) / distance_m;
}

}  // namespace almucantar::internal
