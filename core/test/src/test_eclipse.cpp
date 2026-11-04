#include <algorithm>
#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <vector>

#include "almucantar/eclipse.hpp"
#include "almucantar/sun.hpp"

using Catch::Matchers::WithinAbs;
using namespace almucantar;

namespace {

constexpr double kDegToRad = 3.14159265358979323846 / 180;
/// 2026-01-01 00:00 UTC and 2028-01-01 00:00 UTC.
constexpr double k2026 = 1'767'225'600;
constexpr double k2028 = 1'830'297'600;

/// The great-circle distance in km.
double distance_km(const Observer& a, const Observer& b) {
  const double p1 = a.latitude_deg * kDegToRad;
  const double p2 = b.latitude_deg * kDegToRad;
  const double dl = (b.longitude_deg - a.longitude_deg) * kDegToRad;
  const double h = (std::sin((p2 - p1) / 2) * std::sin((p2 - p1) / 2)) +
                   (std::cos(p1) * std::cos(p2) * std::sin(dl / 2) * std::sin(dl / 2));
  return 2 * 6371 * std::asin(std::min(1.0, std::sqrt(h)));
}

// The times and places of NASA (eclipse.gsfc.nasa.gov, Five Millennium Canon), rounded:
// 2026-08-12 total, greatest eclipse 17:46 UT at 65.2° N, 25.2° W.
// 2027-02-06 annular, greatest eclipse 16:00 UT at 31.3° S, 48.5° W.
// 2027-08-02 total, greatest eclipse 10:07 UT at 25.5° N, 33.2° E (near Luxor), 6 min 23 s.
constexpr double k20260812 = 1'786'556'760;  // 17:46 UT
constexpr double k20270206 = 1'801'929'600;  // 16:00 UT
constexpr double k20270802 = 1'817'201'220;  // 10:07 UT

const SolarEclipse& near(const std::vector<SolarEclipse>& list, const double t) {
  return *std::ranges::min_element(list, {},
                                   [t](const SolarEclipse& e) { return std::abs(e.peak_utc - t); });
}

}  // namespace

TEST_CASE("solar_eclipses agrees with the canon of NASA", "[eclipse]") {
  const std::vector<SolarEclipse> list = solar_eclipses(k2026, k2028);
  // 2026: February (annular), August (total); 2027: February (annular), August (total).
  REQUIRE(list.size() == 4);
  for (std::size_t i = 1; i < list.size(); ++i) {
    CHECK(list[i].peak_utc > list[i - 1].peak_utc);
  }
  const SolarEclipse& spain = near(list, k20260812);
  CHECK(spain.kind == EclipseKind::total);
  CHECK_THAT(spain.peak_utc, WithinAbs(k20260812, 120));
  CHECK(distance_km(spain.peak_place, {.latitude_deg = 65.2, .longitude_deg = -25.2}) < 60);
  CHECK_THAT(spain.obscuration, WithinAbs(1, 1e-9));
  const SolarEclipse& annular = near(list, k20270206);
  CHECK(annular.kind == EclipseKind::annular);
  CHECK_THAT(annular.peak_utc, WithinAbs(k20270206, 300));
  CHECK(annular.obscuration < 1);
  const SolarEclipse& egypt = near(list, k20270802);
  CHECK(egypt.kind == EclipseKind::total);
  CHECK_THAT(egypt.peak_utc, WithinAbs(k20270802, 120));
  CHECK(distance_km(egypt.peak_place, {.latitude_deg = 25.5, .longitude_deg = 33.2}) < 60);
  REQUIRE_THROWS_AS(solar_eclipses(10, 10), std::invalid_argument);
  REQUIRE_THROWS_AS(solar_eclipses(0, 101 * 366 * 86'400.0), std::invalid_argument);
}

TEST_CASE("eclipse_central_line goes through the place of the peak", "[eclipse]") {
  const SolarEclipse egypt = near(solar_eclipses(k2026, k2028), k20270802);
  const std::vector<ShadowPoint> line = eclipse_central_line(egypt.peak_utc, 60);
  REQUIRE(line.size() > 100);  // the shadow crosses the Earth in about three hours
  for (std::size_t i = 1; i < line.size(); ++i) {
    CHECK_THAT(line[i].unix_utc - line[i - 1].unix_utc, WithinAbs(60, 1e-6));
    CHECK(line[i].umbra_radius_km > 0);  // total
  }
  const ShadowPoint& at_peak = *std::ranges::min_element(
      line, {}, [&](const ShadowPoint& p) { return std::abs(p.unix_utc - egypt.peak_utc); });
  CHECK(distance_km(at_peak.place, egypt.peak_place) < 40);
  // The line passes Luxor. At the greatest eclipse the path is 258 km wide (NASA), and the sun is
  // high, so the radius across the axis is about half of that.
  const Observer luxor{.latitude_deg = 25.69, .longitude_deg = 32.64};
  double nearest = std::numeric_limits<double>::infinity();
  for (const ShadowPoint& p : line) nearest = std::min(nearest, distance_km(p.place, luxor));
  CHECK(nearest < 60);
  CHECK_THAT(at_peak.umbra_radius_km, WithinAbs(129, 10));

  // The antumbra of an annular eclipse has a negative radius; a partial eclipse has no line.
  const SolarEclipse annular = near(solar_eclipses(k2026, k2028), k20270206);
  const std::vector<ShadowPoint> ring = eclipse_central_line(annular.peak_utc, 120);
  REQUIRE(!ring.empty());
  CHECK(ring.front().umbra_radius_km < 0);
  REQUIRE_THROWS_AS(eclipse_central_line(egypt.peak_utc, 0.5), std::invalid_argument);
}

TEST_CASE("local_solar_eclipses gives the phases at a place", "[eclipse]") {
  // Luxor sees the total eclipse of 2027 for more than 6 minutes.
  const Observer luxor{.latitude_deg = 25.69, .longitude_deg = 32.64};
  const std::vector<LocalSolarEclipse> egypt =
      local_solar_eclipses(luxor, k20270802 - 86'400, k20270802 + 86'400);
  REQUIRE(egypt.size() == 1);
  const LocalSolarEclipse& e = egypt[0];
  CHECK(e.kind == EclipseKind::total);
  CHECK_THAT(e.obscuration, WithinAbs(1, 1e-9));
  CHECK(e.partial_begin_utc < e.total_begin_utc);
  CHECK(e.total_begin_utc < e.peak_utc);
  CHECK(e.peak_utc < e.total_end_utc);
  CHECK(e.total_end_utc < e.partial_end_utc);
  CHECK_THAT(e.total_end_utc - e.total_begin_utc, WithinAbs(6 * 60 + 20, 30));
  CHECK_THAT(e.peak_sun_altitude_deg, WithinAbs(sun_position(e.peak_utc, luxor).altitude_deg, 0.1));
  // Vienna sees the eclipse of 2026 as a partial one, late in the afternoon.
  const Observer vienna{.latitude_deg = 48.2082, .longitude_deg = 16.3738};
  const std::vector<LocalSolarEclipse> austria =
      local_solar_eclipses(vienna, k20260812 - 86'400, k20260812 + 86'400);
  REQUIRE(austria.size() == 1);
  CHECK(austria[0].kind == EclipseKind::partial);
  CHECK(std::isnan(austria[0].total_begin_utc));
  CHECK(austria[0].obscuration > 0.5);
  CHECK(austria[0].obscuration < 1);
  REQUIRE_THROWS_AS(local_solar_eclipses({.latitude_deg = 91, .longitude_deg = 0}, 0, 86'400),
                    std::invalid_argument);
}

TEST_CASE("lunar_eclipses agrees with the canon of NASA", "[eclipse]") {
  // 2026-03-03 total, greatest eclipse 11:33 UT; 2026-08-28 partial, greatest eclipse 04:13 UT.
  const std::vector<LunarEclipse> list = lunar_eclipses(k2026, 1'790'000'000);
  REQUIRE(list.size() == 2);
  const LunarEclipse& march = list[0];
  CHECK(march.kind == EclipseKind::total);
  CHECK_THAT(march.peak_utc, WithinAbs(1'772'537'580, 180));
  CHECK_THAT(march.obscuration, WithinAbs(1, 1e-9));
  CHECK(march.penumbral_begin_utc < march.partial_begin_utc);
  CHECK(march.partial_begin_utc < march.total_begin_utc);
  CHECK(march.total_end_utc < march.partial_end_utc);
  CHECK(march.partial_end_utc < march.penumbral_end_utc);
  const LunarEclipse& august = list[1];
  CHECK(august.kind == EclipseKind::partial);
  CHECK_THAT(august.peak_utc, WithinAbs(1'787'890'380, 180));
  CHECK(std::isnan(august.total_begin_utc));
  CHECK(august.obscuration > 0.8);
  CHECK(august.obscuration < 1);
}
