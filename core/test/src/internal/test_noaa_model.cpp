#include <algorithm>
#include <catch2/catch_test_macros.hpp>
#include <catch2/generators/catch_generators.hpp>
#include <catch2/generators/catch_generators_range.hpp>
#include <map>
#include <string>

#include "reference_data.hpp"
#include "sunradar/internal/noaa_model.hpp"

using namespace sunradar;
using sunradar::internal::NoaaModel;

TEST_CASE("NoaaModel agrees with JPL Horizons", "[noaa]") {
  // Measured maximum: 0.0154 degrees (2026, quito). The target of the plan is 0.05.
  constexpr double kToleranceDeg = 0.02;

  const NoaaModel model;
  const auto site = GENERATE(from_range(test::site_names()));
  INFO("site " << site);
  const Observer observer = test::site(site);

  double max_error = 0;
  std::size_t count = 0;
  for (const auto& ref : test::airless_positions()) {
    if (ref.site != site) {
      continue;
    }
    const Horizontal ours = model.sun(ref.unix_utc, observer);
    const double error = test::angular_separation_deg(ours, ref.position);
    max_error = std::max(max_error, error);
    ++count;
    INFO("unix_utc " << ref.unix_utc << " azimuth " << ours.azimuth_deg << " altitude "
                     << ours.altitude_deg);
    REQUIRE(error < kToleranceDeg);
  }
  REQUIRE(count > 1500);
}

TEST_CASE("NoaaModel gives values in the documented ranges", "[noaa]") {
  const NoaaModel model;
  for (double lat = -90; lat <= 90; lat += 15) {
    for (double t = 1767225600; t < 1767225600 + 366 * 86400.0; t += 86400.0 * 7.3) {
      const Horizontal h = model.sun(t, Observer{.latitude_deg = lat, .longitude_deg = 10});
      CHECK(h.azimuth_deg >= 0);
      CHECK(h.azimuth_deg < 360);
      CHECK(h.altitude_deg >= -90);
      CHECK(h.altitude_deg <= 90);
    }
  }
}

TEST_CASE("NoaaModel has the sun in the south at noon in Vienna in summer", "[noaa]") {
  const NoaaModel model;
  // 2026-06-21, the transit in Vienna is at about 10:57 UTC.
  const Horizontal h = model.sun(1782039420, test::site("vienna"));
  CHECK(h.azimuth_deg > 179);
  CHECK(h.azimuth_deg < 181);
  CHECK(h.altitude_deg > 65);
  CHECK(h.altitude_deg < 65.5);
}
