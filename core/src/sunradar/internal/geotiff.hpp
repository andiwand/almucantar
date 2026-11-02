#pragma once

#include <cstdint>
#include <vector>

#include "sunradar/terrain.hpp"

namespace sunradar::internal {

/// Reads one image of a GeoTIFF file with heights in a geographic system (latitude and
/// longitude). `level` 0 is the full image, 1 is the first overview, and so on. The reader
/// supports classic TIFF (not BigTIFF), one sample per pixel of 16-bit integers or 32-bit floats,
/// tiles or strips, no compression or DEFLATE, and the predictors 1, 2, and 3. This is the format
/// of Copernicus GLO-30 and GLO-90. Throws `std::invalid_argument` if the file is not valid or
/// not supported.
[[nodiscard]] ElevationGrid read_geotiff(const std::vector<std::uint8_t>& data, int level);

}  // namespace sunradar::internal
