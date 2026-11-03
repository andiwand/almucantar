#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <fstream>
#include <numbers>
#include <sstream>
#include <string>
#include <vector>

#include "almucantar/internal/wmm.hpp"

using Catch::Matchers::WithinAbs;
using namespace almucantar::internal;

namespace {

struct TestValue {
  double year = 0;
  double height_km = 0;
  double latitude_deg = 0;
  double longitude_deg = 0;
  double declination_deg = 0;
  double x_nt = 0;
  double y_nt = 0;
  double z_nt = 0;
};

std::vector<TestValue> read_test_values() {
  std::ifstream file(std::string(ALMUCANTAR_TEST_DATA_DIR) + "/WMM2025_TestValues.txt");
  REQUIRE(file);
  std::vector<TestValue> values;
  std::string line;
  while (std::getline(file, line)) {
    if (line.empty() || line[0] == '#') {
      continue;
    }
    std::istringstream s(line);
    TestValue v;
    double inclination = 0;
    double h = 0;
    s >> v.year >> v.height_km >> v.latitude_deg >> v.longitude_deg >> v.declination_deg >>
        inclination >> h >> v.x_nt >> v.y_nt >> v.z_nt;
    REQUIRE(s);
    values.push_back(v);
  }
  return values;
}

}  // namespace

TEST_CASE("wmm_field agrees with the WMM2025 test values", "[wmm]") {
  // The test values come with the coefficients from NOAA NCEI. Measured maximum
  // errors: 7.2e-4 nT for a component (2e-8 relative), and 0.00497° for the
  // declination, which is the rounding of the test values to 2 decimals.
  const auto values = read_test_values();
  REQUIRE(values.size() == 100);
  for (const TestValue& v : values) {
    INFO("year " << v.year << " height " << v.height_km << " km, lat " << v.latitude_deg << " lon "
                 << v.longitude_deg);
    const MagneticField f = wmm_field(wmm_coefficients(), v.latitude_deg, v.longitude_deg,
                                      v.height_km * 1000.0, v.year);
    CHECK_THAT(f.north_nt, WithinAbs(v.x_nt, 1e-3));
    CHECK_THAT(f.east_nt, WithinAbs(v.y_nt, 1e-3));
    CHECK_THAT(f.down_nt, WithinAbs(v.z_nt, 1e-3));
    const double declination = std::atan2(f.east_nt, f.north_nt) * 180.0 / std::numbers::pi;
    CHECK_THAT(declination, WithinAbs(v.declination_deg, 0.0051));
  }
}

TEST_CASE("wmm_field is finite at the poles", "[wmm]") {
  for (const double lat : {-90.0, 90.0}) {
    const MagneticField f = wmm_field(wmm_coefficients(), lat, 0, 0, 2026);
    CHECK(std::isfinite(f.north_nt));
    CHECK(std::isfinite(f.east_nt));
    CHECK(std::isfinite(f.down_nt));
  }
}
