#pragma once

#include <optional>
#include <vector>

#include "almucantar/terrain.hpp"
#include "almucantar/types.hpp"

namespace almucantar::internal {

/// The mean radius of the Earth (IUGG). The sphere is sufficient for the angles of the terrain:
/// the ellipsoid changes a distance of 100 km by less than 0.5%.
inline constexpr double kEarthRadiusM = 6'371'008.8;

/// Throws `std::invalid_argument` if the grid is not valid.
void validate(const ElevationGrid& grid);

/// The tiles of a calculation, from the smallest cells to the largest.
using SortedTiles = std::vector<const ElevationGrid*>;

/// Validates the tiles and sorts them from the smallest cells to the largest, so a fine tile wins
/// where tiles overlap. The cell size uses `latitude_deg`, which is good enough for an order.
/// Equal cells are ordered by their north-west corner, so the result does not depend on the order
/// of the tiles. Throws `std::invalid_argument` if there are more than 1000 tiles or a tile is not
/// valid.
[[nodiscard]] SortedTiles sort_tiles(const std::vector<ElevationGrid>& tiles, double latitude_deg);

/// The size of the smallest cell of the tiles in meters at `latitude_deg`. Infinity if there are no
/// tiles.
[[nodiscard]] double smallest_cell_m(const std::vector<ElevationGrid>& tiles, double latitude_deg);

/// Returns the height at a place with a bilinear interpolation, or nothing if the place is
/// outside the grid or a neighbor has no data. A place up to `margin` cells outside the edge uses
/// the samples at the edge, so the gap between two tiles has data.
[[nodiscard]] std::optional<double> height_at(const ElevationGrid& grid, double latitude_deg,
                                              double longitude_deg, double margin);

/// Returns the height at a place from the tile with the smallest cells that has data there. Tiles
/// can overlap, for example a fine tile near the observer on top of a coarse one.
[[nodiscard]] std::optional<double> height_in_tiles(const SortedTiles& tiles, double latitude_deg,
                                                    double longitude_deg);

/// The point at `distance_m` from `from` along the great circle with the initial bearing
/// `azimuth_deg`, on the sphere.
[[nodiscard]] Observer destination(const Observer& from, double azimuth_deg, double distance_m);

/// The initial bearing of the great circle from `from` to `to`, in [0, 360).
[[nodiscard]] double bearing_deg(const Observer& from, const Observer& to);

/// The great-circle distance between two places on the sphere (haversine).
[[nodiscard]] double distance_m(const Observer& from, const Observer& to);

/// The tangent of the altitude of a point `distance_m` away and `rise_m` higher than the eye, with
/// the curvature of the Earth and the terrestrial refraction (`refraction_coefficient`). This is
/// the model of the terrain horizon: the drop of the far point is d² / (2 R / (1 - k)).
[[nodiscard]] double sight_tangent(double rise_m, double distance_m, double refraction_coefficient);

}  // namespace almucantar::internal
