#include <algorithm>
#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <cstddef>
#include <map>
#include <numbers>
#include <stdexcept>
#include <string>
#include <vector>

#include "almucantar/sky.hpp"
#include "reference_data.hpp"

using Catch::Matchers::WithinAbs;
using namespace almucantar;

namespace {

constexpr double kDegToRad = std::numbers::pi / 180;
constexpr double kRadToDeg = 180 / std::numbers::pi;

const std::map<std::string, Planet>& planets() {
  static const std::map<std::string, Planet> kPlanets = {
      {"mercury", Planet::mercury}, {"venus", Planet::venus},   {"mars", Planet::mars},
      {"jupiter", Planet::jupiter}, {"saturn", Planet::saturn}, {"uranus", Planet::uranus},
      {"neptune", Planet::neptune},
  };
  return kPlanets;
}

/// An independent calculation for a fixed J2000 direction: the precession of IAU 1976 (Lieske)
/// and the mean sidereal time. It has no nutation and no aberration, so it agrees to about 0.01°.
Horizontal j2000_to_horizontal(const double ra_deg, const double dec_deg, const double unix_utc,
                               const Observer& observer) {
  const double days = (unix_utc / 86400) + 2440587.5 - 2451545.0;
  const double t = days / 36525;
  const double arcsec = kDegToRad / 3600;
  const double zeta = ((2306.2181 * t) + (0.30188 * t * t)) * arcsec;
  const double z = ((2306.2181 * t) + (1.09468 * t * t)) * arcsec;
  const double theta = ((2004.3109 * t) - (0.42665 * t * t)) * arcsec;
  const double a0 = ra_deg * kDegToRad;
  const double d0 = dec_deg * kDegToRad;
  const double a = std::cos(d0) * std::sin(a0 + zeta);
  const double b =
      (std::cos(theta) * std::cos(d0) * std::cos(a0 + zeta)) - (std::sin(theta) * std::sin(d0));
  const double c =
      (std::sin(theta) * std::cos(d0) * std::cos(a0 + zeta)) + (std::cos(theta) * std::sin(d0));
  const double ra = std::atan2(a, b) + z;
  const double dec = std::asin(c);
  const double gmst_deg = 280.46061837 + (360.98564736629 * days);
  const double h = (gmst_deg + observer.longitude_deg) * kDegToRad - ra;
  const double phi = observer.latitude_deg * kDegToRad;
  const double altitude =
      std::asin((std::sin(phi) * std::sin(dec)) + (std::cos(phi) * std::cos(dec) * std::cos(h)));
  const double azimuth =
      std::atan2(-std::cos(dec) * std::sin(h),
                 (std::sin(dec) * std::cos(phi)) - (std::cos(dec) * std::cos(h) * std::sin(phi)));
  const double azimuth_deg = std::fmod((azimuth * kRadToDeg) + 360, 360);
  return {.azimuth_deg = azimuth_deg, .altitude_deg = altitude * kRadToDeg};
}

}  // namespace

TEST_CASE("planet_position agrees with JPL Horizons", "[sky]") {
  std::map<std::string, std::size_t> counts;
  double worst = 0;
  for (const auto& ref : test::planet_positions()) {
    INFO(ref.site << " " << ref.planet << " " << ref.unix_utc);
    const Horizontal position = planet_position(planets().at(ref.planet), ref.unix_utc,
                                                test::site(ref.site), {.refraction = false});
    const double error = test::angular_separation_deg(position, ref.position);
    worst = std::max(worst, error);
    ++counts[ref.planet];
  }
  INFO("worst " << worst);
  // Measured maximum: 0.0060° (Venus, high in the sky at Quito), over 3850 points in 2026 at
  // five sites. The other planets agree to 0.0046° or better.
  REQUIRE(worst < 0.008);
  REQUIRE(counts.size() == 7);
  for (const auto& [planet, count] : counts) {
    REQUIRE(count == 550);
  }
}

TEST_CASE("planet_position adds the refraction", "[sky]") {
  const Observer vienna = test::site("vienna");
  const double t = 1'782'000'000;
  for (const auto& [name, planet] : planets()) {
    const Horizontal airless = planet_position(planet, t, vienna, {.refraction = false});
    const Horizontal seen = planet_position(planet, t, vienna);
    REQUIRE(seen.azimuth_deg == airless.azimuth_deg);
    REQUIRE(seen.altitude_deg > airless.altitude_deg);
  }
}

TEST_CASE("planet_magnitude is in the known range", "[sky]") {
  for (double t = 1'767'225'600; t < 1'798'761'600; t += 86400 * 7) {
    INFO("unix_utc " << t);
    REQUIRE(planet_magnitude(Planet::venus, t) < -3.5);
    REQUIRE(planet_magnitude(Planet::venus, t) > -5);
    REQUIRE(planet_magnitude(Planet::jupiter, t) < -1.5);
    REQUIRE(planet_magnitude(Planet::jupiter, t) > -3);
    REQUIRE(planet_magnitude(Planet::neptune, t) > 7.5);
    REQUIRE(planet_magnitude(Planet::neptune, t) < 8.1);
  }
  REQUIRE_THROWS_AS(planet_magnitude(Planet::mars, std::nan("")), std::invalid_argument);
}

TEST_CASE("galactic_position agrees with an independent calculation", "[sky]") {
  // The galactic center and the north galactic pole in J2000 (IAU 1958, in the FK5 frame).
  struct Case {
    double l_deg;
    double b_deg;
    double ra_deg;
    double dec_deg;
  };
  const std::vector<Case> cases = {
      {.l_deg = 0, .b_deg = 0, .ra_deg = 266.404996, .dec_deg = -28.936172},
      {.l_deg = 123, .b_deg = 90, .ra_deg = 192.859481, .dec_deg = 27.128251},
  };
  for (const std::string& name : test::site_names()) {
    const Observer observer = test::site(name);
    for (double t = 1'767'225'600; t < 1'798'761'600; t += 86400 * 29.3) {
      for (const Case& c : cases) {
        INFO(name << " " << t << " l " << c.l_deg);
        const Horizontal position =
            galactic_position(c.l_deg, c.b_deg, t, observer, {.refraction = false});
        const Horizontal expected = j2000_to_horizontal(c.ra_deg, c.dec_deg, t, observer);
        // The reference has no nutation (up to 0.005°) and no aberration (up to 0.006°).
        REQUIRE(test::angular_separation_deg(position, expected) < 0.02);
      }
    }
  }
}

TEST_CASE("galactic_plane is 90° from the galactic pole", "[sky]") {
  const Observer sydney = test::site("sydney");
  const double t = 1'782'000'000;
  const std::vector<Horizontal> plane = galactic_plane(t, sydney, 1, {.refraction = false});
  REQUIRE(plane.size() == 360);
  const Horizontal center = galactic_position(
      kGalacticCenterLongitudeDeg, kGalacticCenterLatitudeDeg, t, sydney, {.refraction = false});
  REQUIRE(test::angular_separation_deg(plane.front(), center) < 1e-9);
  const Horizontal pole = galactic_position(0, 90, t, sydney, {.refraction = false});
  for (const Horizontal& point : plane) {
    REQUIRE_THAT(test::angular_separation_deg(point, pole), WithinAbs(90, 1e-6));
  }
  // The neighbors are 1° apart.
  REQUIRE_THAT(test::angular_separation_deg(plane[10], plane[11]), WithinAbs(1, 1e-6));
  REQUIRE(galactic_plane(t, sydney, 90).size() == 4);
  REQUIRE(galactic_plane(t, sydney, 7).size() == 52);
}

TEST_CASE("star_positions agrees with an independent calculation", "[sky]") {
  // Sirius, Vega, Polaris, and Canopus in J2000 (Bright Star Catalogue), without proper motion.
  const StarCatalog catalog{.ra_deg = {101.2871, 279.2346, 37.9529, 95.9879},
                            .dec_deg = {-16.7161, 38.7837, 89.2642, -52.6957}};
  for (const std::string& name : test::site_names()) {
    const Observer observer = test::site(name);
    for (double t = 1'767'225'600; t < 1'798'761'600; t += 86400 * 29.3) {
      const std::vector<Horizontal> positions =
          star_positions(catalog, t, observer, {.refraction = false});
      REQUIRE(positions.size() == 4);
      for (std::size_t i = 0; i < positions.size(); ++i) {
        INFO(name << " " << t << " star " << i);
        const Horizontal expected =
            j2000_to_horizontal(catalog.ra_deg[i], catalog.dec_deg[i], t, observer);
        // The reference has no nutation (up to 0.005°) and no aberration (up to 0.006°).
        REQUIRE(test::angular_separation_deg(positions[i], expected) < 0.02);
      }
    }
  }
}

TEST_CASE("star_positions adds the annual aberration", "[sky]") {
  // The north galactic pole as a star, and as a galactic direction, which has no aberration.
  // The pole has an ecliptic latitude of 29.8°, so the aberration there goes from
  // 20.5" * sin(29.8°) = 0.0028° to 20.5" = 0.0057° in a year.
  const StarCatalog pole{.ra_deg = {192.859481}, .dec_deg = {27.128251}};
  const Observer vienna = test::site("vienna");
  double smallest = 1;
  double largest = 0;
  for (double t = 1'767'225'600; t < 1'798'761'600; t += 86400) {
    const Horizontal star = star_positions(pole, t, vienna, {.refraction = false}).front();
    const Horizontal galactic = galactic_position(0, 90, t, vienna, {.refraction = false});
    const double shift = test::angular_separation_deg(star, galactic);
    smallest = std::min(smallest, shift);
    largest = std::max(largest, shift);
  }
  INFO("smallest " << smallest << " largest " << largest);
  // Measured in 2026: 0.00277° to 0.00622°. The largest value includes about 0.0005° of the
  // difference between the J2000 position of the pole above and the galactic frame of Astronomy
  // Engine.
  REQUIRE_THAT(smallest, WithinAbs(0.0028, 0.0003));
  REQUIRE(largest < 0.0065);
  REQUIRE(largest > 0.0055);
}

TEST_CASE("star_positions applies the proper motion", "[sky]") {
  // Arcturus: -1.093 and -1.999 arcseconds per year (Bright Star Catalogue).
  const StarCatalog fixed{.ra_deg = {213.9154}, .dec_deg = {19.1824}};
  StarCatalog moving = fixed;
  moving.pm_ra_arcsec_per_year = {-1.093};
  moving.pm_dec_arcsec_per_year = {-1.999};
  const Observer vienna = test::site("vienna");
  const double t = 1'782'000'000;  // 2026-06-20, 26.47 years after J2000.0
  const Horizontal a = star_positions(fixed, t, vienna, {.refraction = false}).front();
  const Horizontal b = star_positions(moving, t, vienna, {.refraction = false}).front();
  const double years = ((t / 86400) + 2440587.5 - 2451545.0) / 365.25;
  const double expected = std::hypot(1.093, 1.999) * years / 3600;
  REQUIRE_THAT(test::angular_separation_deg(a, b), WithinAbs(expected, expected * 0.01));
}

TEST_CASE("star_positions adds the refraction", "[sky]") {
  const StarCatalog catalog{.ra_deg = {0, 90, 180, 270}, .dec_deg = {0, 20, -20, 40}};
  const Observer vienna = test::site("vienna");
  const double t = 1'782'000'000;
  const std::vector<Horizontal> airless = star_positions(catalog, t, vienna, {.refraction = false});
  const std::vector<Horizontal> seen = star_positions(catalog, t, vienna);
  for (std::size_t i = 0; i < seen.size(); ++i) {
    REQUIRE(seen[i].azimuth_deg == airless[i].azimuth_deg);
    REQUIRE(seen[i].altitude_deg >= airless[i].altitude_deg);
  }
}

TEST_CASE("star_positions validates the catalogue", "[sky]") {
  const Observer vienna = test::site("vienna");
  REQUIRE(star_positions({}, 0, vienna).empty());
  REQUIRE_THROWS_AS(star_positions({.ra_deg = {1, 2}, .dec_deg = {1}}, 0, vienna),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(star_positions({.ra_deg = {1}, .dec_deg = {91}}, 0, vienna),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(star_positions({.ra_deg = {std::nan("")}, .dec_deg = {1}}, 0, vienna),
                    std::invalid_argument);
  REQUIRE_THROWS_AS(star_positions({.ra_deg = {1},
                                    .dec_deg = {1},
                                    .pm_ra_arcsec_per_year = {0.1},
                                    .pm_dec_arcsec_per_year = {}},
                                   0, vienna),
                    std::invalid_argument);
}

TEST_CASE("the sky functions validate their arguments", "[sky]") {
  const Observer vienna = test::site("vienna");
  REQUIRE_THROWS_AS(galactic_plane(0, vienna, 0), std::invalid_argument);
  REQUIRE_THROWS_AS(galactic_plane(0, vienna, 91), std::invalid_argument);
  REQUIRE_THROWS_AS(galactic_position(0, 91, 0, vienna), std::invalid_argument);
  REQUIRE_THROWS_AS(planet_position(Planet::mars, 0, {.latitude_deg = 91}), std::invalid_argument);
  REQUIRE_THROWS_AS(planet_position(static_cast<Planet>(42), 0, vienna), std::invalid_argument);
}
