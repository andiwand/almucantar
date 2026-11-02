#include "sunradar/internal/geotiff.hpp"

#include <miniz.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <map>
#include <set>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace sunradar::internal {

namespace {

/// The file is not trusted, so each limit stops a large allocation from a bad header.
constexpr std::uint64_t kMaxPixels = 100'000'000;
constexpr std::uint64_t kMaxChunkBytes = 256ULL * 1024 * 1024;
constexpr std::size_t kMaxDirectories = 64;

enum Tag : std::uint16_t {
  kSubfileType = 254,
  kImageWidth = 256,
  kImageLength = 257,
  kBitsPerSample = 258,
  kCompression = 259,
  kStripOffsets = 273,
  kSamplesPerPixel = 277,
  kRowsPerStrip = 278,
  kStripByteCounts = 279,
  kPlanarConfiguration = 284,
  kPredictor = 317,
  kTileWidth = 322,
  kTileLength = 323,
  kTileOffsets = 324,
  kTileByteCounts = 325,
  kSampleFormat = 339,
  kModelPixelScale = 33550,
  kModelTiepoint = 33922,
  kGeoKeyDirectory = 34735,
  kGdalNoData = 42113,
};

enum class SampleType : std::uint8_t { float32, int16, uint16 };

[[noreturn]] void fail(const std::string& message) {
  throw std::invalid_argument("the GeoTIFF " + message);
}

/// Reads the numbers of the file in its byte order, with a check of each range.
class Reader {
 public:
  explicit Reader(const std::vector<std::uint8_t>& data) : data_(data) {
    if (data.size() < 8) {
      fail("is too short");
    }
    if (data[0] == 'I' && data[1] == 'I') {
      little_ = true;
    } else if (data[0] == 'M' && data[1] == 'M') {
      little_ = false;
    } else {
      fail("has no byte order mark");
    }
    const std::uint16_t magic = u16(2);
    if (magic == 43) {
      fail("is a BigTIFF, which is not supported");
    }
    if (magic != 42) {
      fail("is not a TIFF file");
    }
  }

  [[nodiscard]] bool little() const { return little_; }
  [[nodiscard]] std::size_t size() const { return data_.size(); }
  [[nodiscard]] const std::uint8_t* at(const std::uint64_t offset,
                                       const std::uint64_t length) const {
    if (offset > data_.size() || length > data_.size() - offset) {
      fail("points outside the file");
    }
    return data_.data() + offset;
  }

  [[nodiscard]] std::uint64_t unsigned_value(const std::uint64_t offset, const int bytes) const {
    const std::uint8_t* p = at(offset, static_cast<std::uint64_t>(bytes));
    std::uint64_t value = 0;
    for (int i = 0; i < bytes; ++i) {
      const auto shift = static_cast<unsigned>(little_ ? 8 * i : 8 * (bytes - 1 - i));
      value |= static_cast<std::uint64_t>(p[i]) << shift;
    }
    return value;
  }
  [[nodiscard]] std::uint16_t u16(const std::uint64_t offset) const {
    return static_cast<std::uint16_t>(unsigned_value(offset, 2));
  }
  [[nodiscard]] std::uint32_t u32(const std::uint64_t offset) const {
    return static_cast<std::uint32_t>(unsigned_value(offset, 4));
  }
  [[nodiscard]] double f64(const std::uint64_t offset) const {
    const std::uint64_t bits = unsigned_value(offset, 8);
    double value = 0;
    std::memcpy(&value, &bits, sizeof value);
    return value;
  }

 private:
  const std::vector<std::uint8_t>& data_;
  bool little_ = true;
};

/// One entry of an image file directory (IFD).
struct Entry {
  std::uint16_t type = 0;
  std::uint32_t count = 0;
  std::uint64_t value_offset = 0;  ///< where the values are: in the entry or elsewhere
};

int type_size(const std::uint16_t type) {
  switch (type) {
    case 1:  // BYTE
    case 2:  // ASCII
    case 6:  // SBYTE
    case 7:  // UNDEFINED
      return 1;
    case 3:  // SHORT
    case 8:  // SSHORT
      return 2;
    case 4:   // LONG
    case 9:   // SLONG
    case 11:  // FLOAT
      return 4;
    case 5:   // RATIONAL
    case 10:  // SRATIONAL
    case 12:  // DOUBLE
      return 8;
    default:
      return 0;
  }
}

using Directory = std::map<std::uint16_t, Entry>;

/// Reads the IFD at `offset` and returns it with the offset of the next IFD.
std::pair<Directory, std::uint32_t> read_directory(const Reader& reader,
                                                   const std::uint64_t offset) {
  const std::uint16_t count = reader.u16(offset);
  Directory directory;
  for (std::uint16_t i = 0; i < count; ++i) {
    const std::uint64_t position = offset + 2 + (12ULL * i);
    Entry entry{.type = reader.u16(position + 2), .count = reader.u32(position + 4)};
    const auto bytes = static_cast<std::uint64_t>(type_size(entry.type)) * entry.count;
    entry.value_offset = bytes <= 4 ? position + 8 : reader.u32(position + 8);
    if (type_size(entry.type) > 0) {
      static_cast<void>(reader.at(entry.value_offset, bytes));  // check the range
    }
    directory[reader.u16(position)] = entry;
  }
  return {directory, reader.u32(offset + 2 + (12ULL * count))};
}

std::vector<std::uint64_t> integers(const Reader& reader, const Directory& directory,
                                    const std::uint16_t tag) {
  const auto it = directory.find(tag);
  if (it == directory.end()) {
    return {};
  }
  const Entry& entry = it->second;
  if (entry.type != 3 && entry.type != 4) {
    fail("has a tag " + std::to_string(tag) + " that is not an integer");
  }
  const int size = type_size(entry.type);
  std::vector<std::uint64_t> values;
  values.reserve(entry.count);
  for (std::uint32_t i = 0; i < entry.count; ++i) {
    values.push_back(
        reader.unsigned_value(entry.value_offset + (static_cast<std::uint64_t>(size) * i), size));
  }
  return values;
}

std::uint64_t integer(const Reader& reader, const Directory& directory, const std::uint16_t tag,
                      const std::uint64_t fallback) {
  const std::vector<std::uint64_t> values = integers(reader, directory, tag);
  return values.empty() ? fallback : values.front();
}

std::vector<double> doubles(const Reader& reader, const Directory& directory,
                            const std::uint16_t tag) {
  const auto it = directory.find(tag);
  if (it == directory.end()) {
    return {};
  }
  const Entry& entry = it->second;
  if (entry.type != 12) {
    fail("has a tag " + std::to_string(tag) + " that is not a double");
  }
  std::vector<double> values;
  values.reserve(entry.count);
  for (std::uint32_t i = 0; i < entry.count; ++i) {
    values.push_back(reader.f64(entry.value_offset + (8ULL * i)));
  }
  return values;
}

/// The value of the no-data tag of GDAL (an ASCII number), or NaN.
double no_data(const Reader& reader, const Directory& directory) {
  const auto it = directory.find(kGdalNoData);
  if (it == directory.end() || it->second.type != 2 || it->second.count == 0) {
    return std::numeric_limits<double>::quiet_NaN();
  }
  const auto* p =
      reinterpret_cast<const char*>(reader.at(it->second.value_offset, it->second.count));
  const std::string text(p, std::find(p, p + it->second.count, '\0'));
  char* end = nullptr;
  const double value = std::strtod(text.c_str(), &end);
  return end == text.c_str() ? std::numeric_limits<double>::quiet_NaN() : value;
}

/// The georeferencing of the full image, from the GeoTIFF tags of the first IFD.
struct Georeference {
  double scale_x = 0;
  double scale_y = 0;
  double corner_x = 0;  ///< the longitude of the left edge of column 0
  double corner_y = 0;  ///< the latitude of the top edge of row 0
};

Georeference georeference(const Reader& reader, const Directory& directory) {
  const std::vector<double> scale = doubles(reader, directory, kModelPixelScale);
  const std::vector<double> tiepoint = doubles(reader, directory, kModelTiepoint);
  if (scale.size() < 2 || tiepoint.size() < 6) {
    fail("has no pixel scale or tie point");
  }
  if (!(scale[0] > 0 && scale[1] > 0 && std::isfinite(scale[0]) && std::isfinite(scale[1]))) {
    fail("has a pixel scale that is not positive");
  }
  bool pixel_is_point = false;
  const std::vector<std::uint64_t> keys = integers(reader, directory, kGeoKeyDirectory);
  if (keys.size() >= 4) {
    // The size of the tag limits the count, so the count fits into a size_t.
    const auto count = static_cast<std::size_t>(std::min<std::uint64_t>(keys[3], keys.size()));
    for (std::size_t k = 0; k < count && (4 * k) + 7 < keys.size(); ++k) {
      const std::uint64_t key = keys[(4 * k) + 4];
      const std::uint64_t location = keys[(4 * k) + 5];
      const std::uint64_t value = keys[(4 * k) + 7];
      if (location != 0) {
        continue;  // The value is in another tag; the two keys below are always inline.
      }
      if (key == 1024 && value != 2) {  // GTModelTypeGeoKey: 2 is geographic
        fail("is not in a geographic system (latitude and longitude)");
      }
      if (key == 1025) {  // GTRasterTypeGeoKey: 1 is PixelIsArea, 2 is PixelIsPoint
        pixel_is_point = value == 2;
      }
    }
  }
  Georeference g{.scale_x = scale[0], .scale_y = scale[1]};
  // The tie point maps the raster point (i, j) to the model point (x, y).
  g.corner_x = tiepoint[3] - (tiepoint[0] * scale[0]);
  g.corner_y = tiepoint[4] + (tiepoint[1] * scale[1]);
  if (pixel_is_point) {
    g.corner_x -= 0.5 * scale[0];
    g.corner_y += 0.5 * scale[1];
  }
  return g;
}

/// The layout and the format of one image.
struct Image {
  std::uint64_t width = 0;
  std::uint64_t height = 0;
  SampleType type = SampleType::float32;
  int bytes_per_sample = 4;
  std::uint64_t compression = 1;
  std::uint64_t predictor = 1;
  bool tiled = false;
  std::uint64_t chunk_width = 0;   ///< the tile width, or the image width for strips
  std::uint64_t chunk_height = 0;  ///< the tile length, or the rows per strip
  std::vector<std::uint64_t> offsets;
  std::vector<std::uint64_t> byte_counts;
};

Image image_layout(const Reader& reader, const Directory& directory) {
  Image image;
  image.width = integer(reader, directory, kImageWidth, 0);
  image.height = integer(reader, directory, kImageLength, 0);
  if (image.width == 0 || image.height == 0 || image.width * image.height > kMaxPixels) {
    fail("has a size that is zero or too large");
  }
  if (integer(reader, directory, kSamplesPerPixel, 1) != 1) {
    fail("has more than one sample per pixel");
  }
  if (integer(reader, directory, kPlanarConfiguration, 1) != 1) {
    fail("has a planar configuration that is not supported");
  }
  const std::uint64_t bits = integer(reader, directory, kBitsPerSample, 1);
  const std::uint64_t format = integer(reader, directory, kSampleFormat, 1);
  if (format == 3 && bits == 32) {
    image.type = SampleType::float32;
    image.bytes_per_sample = 4;
  } else if (format == 2 && bits == 16) {
    image.type = SampleType::int16;
    image.bytes_per_sample = 2;
  } else if (format == 1 && bits == 16) {
    image.type = SampleType::uint16;
    image.bytes_per_sample = 2;
  } else {
    fail("has a sample type that is not supported");
  }
  image.compression = integer(reader, directory, kCompression, 1);
  if (image.compression != 1 && image.compression != 8 && image.compression != 32946) {
    fail("has a compression that is not supported");
  }
  image.predictor = integer(reader, directory, kPredictor, 1);
  if (image.predictor != 1 && image.predictor != 2 && image.predictor != 3) {
    fail("has a predictor that is not supported");
  }
  const bool is_float = image.type == SampleType::float32;
  if ((image.predictor == 3 && !is_float) || (image.predictor == 2 && is_float)) {
    fail("has a predictor that does not match the sample type");
  }
  image.tiled = directory.contains(kTileWidth);
  if (image.tiled) {
    image.chunk_width = integer(reader, directory, kTileWidth, 0);
    image.chunk_height = integer(reader, directory, kTileLength, 0);
    image.offsets = integers(reader, directory, kTileOffsets);
    image.byte_counts = integers(reader, directory, kTileByteCounts);
  } else {
    image.chunk_width = image.width;
    image.chunk_height =
        std::min(integer(reader, directory, kRowsPerStrip, image.height), image.height);
    image.offsets = integers(reader, directory, kStripOffsets);
    image.byte_counts = integers(reader, directory, kStripByteCounts);
  }
  if (image.chunk_width == 0 || image.chunk_height == 0 ||
      image.chunk_width * image.chunk_height * static_cast<std::uint64_t>(image.bytes_per_sample) >
          kMaxChunkBytes) {
    fail("has a tile or strip size that is zero or too large");
  }
  const std::uint64_t across = (image.width + image.chunk_width - 1) / image.chunk_width;
  const std::uint64_t down = (image.height + image.chunk_height - 1) / image.chunk_height;
  if (image.offsets.size() != across * down || image.byte_counts.size() != across * down) {
    fail("has a wrong number of tiles or strips");
  }
  return image;
}

/// Decompresses one tile or strip and undoes the predictor. Returns the samples, row by row.
std::vector<float> decode_chunk(const Reader& reader, const Image& image, const std::size_t index,
                                const std::uint64_t rows) {
  const std::uint64_t width = image.chunk_width;
  const auto row_bytes =
      static_cast<std::size_t>(width) * static_cast<std::size_t>(image.bytes_per_sample);
  const std::size_t expected = row_bytes * static_cast<std::size_t>(rows);
  const std::uint8_t* raw = reader.at(image.offsets[index], image.byte_counts[index]);
  std::vector<std::uint8_t> bytes(expected);
  if (image.compression == 1) {
    if (image.byte_counts[index] < expected) {
      fail("has a strip or tile that is too short");
    }
    std::memcpy(bytes.data(), raw, expected);
  } else {
    const std::size_t written = tinfl_decompress_mem_to_mem(
        bytes.data(), expected, raw, static_cast<std::size_t>(image.byte_counts[index]),
        TINFL_FLAG_PARSE_ZLIB_HEADER);
    if (written != expected) {
      fail("has data that does not decompress to the size of a tile or strip");
    }
  }

  std::vector<float> samples(static_cast<std::size_t>(width) * static_cast<std::size_t>(rows));
  for (std::size_t r = 0; r < rows; ++r) {
    std::uint8_t* row = bytes.data() + (r * row_bytes);
    float* out = samples.data() + (r * static_cast<std::size_t>(width));
    if (image.predictor == 3) {
      // The floating point predictor: a difference of the bytes, and then the bytes of each
      // sample in planes, the most significant byte first. The order does not depend on the
      // byte order of the file.
      for (std::size_t i = 1; i < row_bytes; ++i) {
        row[i] = static_cast<std::uint8_t>(row[i] + row[i - 1]);
      }
      for (std::size_t i = 0; i < width; ++i) {
        std::uint32_t bits = 0;
        for (std::size_t b = 0; b < 4; ++b) {
          bits = (bits << 8U) | row[(b * width) + i];
        }
        std::memcpy(&out[i], &bits, sizeof bits);
      }
      continue;
    }
    std::vector<std::uint32_t> values(static_cast<std::size_t>(width));
    for (std::size_t i = 0; i < width; ++i) {
      std::uint32_t v = 0;
      for (int b = 0; b < image.bytes_per_sample; ++b) {
        const int shift = reader.little() ? 8 * b : 8 * (image.bytes_per_sample - 1 - b);
        v |= static_cast<std::uint32_t>(row[(i * static_cast<std::size_t>(image.bytes_per_sample)) +
                                            static_cast<std::size_t>(b)])
             << static_cast<unsigned>(shift);
      }
      values[i] = v;
    }
    if (image.predictor == 2) {
      const std::uint32_t mask = image.bytes_per_sample == 2 ? 0xFFFFU : 0xFFFFFFFFU;
      for (std::size_t i = 1; i < width; ++i) {
        values[i] = (values[i] + values[i - 1]) & mask;
      }
    }
    for (std::size_t i = 0; i < width; ++i) {
      switch (image.type) {
        case SampleType::float32:
          std::memcpy(&out[i], &values[i], sizeof(float));
          break;
        case SampleType::int16:
          out[i] = static_cast<float>(static_cast<std::int16_t>(values[i]));
          break;
        case SampleType::uint16:
          out[i] = static_cast<float>(values[i]);
          break;
      }
    }
  }
  return samples;
}

}  // namespace

ElevationGrid read_geotiff(const std::vector<std::uint8_t>& data, const int level) {
  if (level < 0) {
    throw std::invalid_argument("level is negative");
  }
  const Reader reader(data);

  // Find the full image and its overviews in the chain of IFDs. Skip the masks.
  std::vector<Directory> images;
  std::set<std::uint32_t> seen;
  std::uint32_t offset = reader.u32(4);
  const auto wanted = static_cast<std::size_t>(level);
  while (offset != 0 && images.size() <= wanted) {
    if (!seen.insert(offset).second || seen.size() > kMaxDirectories) {
      fail("has a loop or too many directories");
    }
    auto [directory, next] = read_directory(reader, offset);
    const std::uint64_t subfile = integer(reader, directory, kSubfileType, 0);
    if ((subfile & 4U) == 0) {
      images.push_back(std::move(directory));
    }
    offset = next;
  }
  if (images.empty()) {
    fail("has no image");
  }
  if (images.size() <= wanted) {
    throw std::invalid_argument("level is greater than the number of overviews");
  }

  const Georeference geo = georeference(reader, images.front());
  const Directory& directory = images[static_cast<std::size_t>(level)];
  const Image full = image_layout(reader, images.front());
  const Image image = level == 0 ? full : image_layout(reader, directory);
  const double missing = no_data(reader, images.front());

  // An overview covers the same area with fewer, larger pixels.
  const double scale_x =
      geo.scale_x * static_cast<double>(full.width) / static_cast<double>(image.width);
  const double scale_y =
      geo.scale_y * static_cast<double>(full.height) / static_cast<double>(image.height);
  ElevationGrid grid;
  grid.latitude_step_deg = scale_y;
  grid.longitude_step_deg = scale_x;
  grid.north_latitude_deg = geo.corner_y - (0.5 * scale_y);
  grid.west_longitude_deg = geo.corner_x + (0.5 * scale_x);
  grid.rows = static_cast<int>(image.height);
  grid.columns = static_cast<int>(image.width);
  if (grid.north_latitude_deg > 90 + scale_y ||
      grid.north_latitude_deg - (static_cast<double>(grid.rows - 1) * scale_y) < -90 - scale_y) {
    fail("is not in the range of the latitude");
  }
  grid.north_latitude_deg = std::min(grid.north_latitude_deg, 90.0);
  grid.heights_m.assign(static_cast<std::size_t>(image.width * image.height),
                        std::numeric_limits<float>::quiet_NaN());

  const std::uint64_t across = (image.width + image.chunk_width - 1) / image.chunk_width;
  for (std::size_t index = 0; index < image.offsets.size(); ++index) {
    const std::uint64_t top = (index / across) * image.chunk_height;
    const std::uint64_t left = (index % across) * image.chunk_width;
    // A tile has its full size also at the edge, a strip only the remaining rows.
    const std::uint64_t rows =
        image.tiled ? image.chunk_height : std::min(image.chunk_height, image.height - top);
    const std::vector<float> samples = decode_chunk(reader, image, index, rows);
    for (std::uint64_t r = 0; r < rows && top + r < image.height; ++r) {
      for (std::uint64_t c = 0; c < image.chunk_width && left + c < image.width; ++c) {
        const float value = samples[static_cast<std::size_t>((r * image.chunk_width) + c)];
        const bool no_value = std::isnan(value) || static_cast<double>(value) == missing;
        grid.heights_m[static_cast<std::size_t>(((top + r) * image.width) + left + c)] =
            no_value ? std::numeric_limits<float>::quiet_NaN() : value;
      }
    }
  }
  return grid;
}

}  // namespace sunradar::internal
