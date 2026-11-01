#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <limits>
#include <numbers>
#include <stdexcept>

#include "poses.hpp"
#include "reference_data.hpp"
#include "sunradar/heading.hpp"
#include "sunradar/internal/time.hpp"
#include "sunradar/internal/wmm.hpp"
#include "sunradar/magnetic.hpp"
#include "sunradar/sun.hpp"

using Catch::Matchers::WithinAbs;
using namespace sunradar;
using test::Axis;
using test::upright_pose;

namespace {

constexpr double kDeg = std::numbers::pi / 180.0;
constexpr double kT = 1767225600;  // 2026-01-01T00:00Z

/// The WMM field at the observer in East-North-Up, in µT. This is the field that the function
/// expects, so a measurement of it has no disturbance.
Axis earth_field_enu(const Observer& observer) {
  const internal::MagneticField f =
      internal::wmm_field(internal::wmm_coefficients(), observer.latitude_deg,
                          observer.longitude_deg, 0, internal::decimal_year(kT));
  return {f.east_nt / 1000, f.north_nt / 1000, -f.down_nt / 1000};
}

/// The components of a vector (in ENU) in the device frame.
Vector3 in_device(const test::Pose& pose, const Axis& v) {
  const auto dot = [](const Axis& a, const Axis& b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  };
  return {dot(pose.x, v), dot(pose.y, v), dot(pose.z, v)};
}

}  // namespace

TEST_CASE("heading_predict grows the variance up to the unknown variance", "[heading]") {
  const HeadingEstimate e{.offset_deg = 10, .variance_deg2 = 4};
  const HeadingEstimate p = heading_predict(e, 60);
  CHECK(p.offset_deg == 10);
  CHECK_THAT(p.variance_deg2, WithinAbs(5, 1e-12));  // 1 square degree per minute
  CHECK(heading_predict(e, 1e9).variance_deg2 == kUnknownHeadingVarianceDeg2);
  CHECK(heading_predict(e, 0).variance_deg2 == 4);
  // A variance above the cap does not get smaller.
  CHECK(heading_predict({.variance_deg2 = 1e6}, 1).variance_deg2 == 1e6);
  CHECK_THROWS_AS(heading_predict(e, -1), std::invalid_argument);
  CHECK_THROWS_AS(heading_predict(e, 1, -1), std::invalid_argument);
  CHECK_THROWS_AS(heading_predict({.variance_deg2 = 0}, 1), std::invalid_argument);
}

TEST_CASE("heading_update is a Kalman update on the circle", "[heading]") {
  const HeadingEstimate a = heading_update({.offset_deg = 10, .variance_deg2 = 4},
                                           {.offset_deg = 20, .variance_deg2 = 4});
  CHECK_THAT(a.offset_deg, WithinAbs(15, 1e-12));
  CHECK_THAT(a.variance_deg2, WithinAbs(2, 1e-12));

  // 359° and 1° are 2° apart, so the result is 0°, not 180°.
  const HeadingEstimate b = heading_update({.offset_deg = 359, .variance_deg2 = 1},
                                           {.offset_deg = 1, .variance_deg2 = 1});
  CHECK(std::min(b.offset_deg, 360 - b.offset_deg) < 1e-9);

  // From the unknown estimate, the first measurement decides.
  const HeadingEstimate c = heading_update({}, {.offset_deg = 123, .variance_deg2 = 1});
  CHECK_THAT(c.offset_deg, WithinAbs(123, 0.01));
  CHECK(c.variance_deg2 < 1);

  CHECK_THROWS_AS(heading_update({}, {.offset_deg = 1, .variance_deg2 = 0}), std::invalid_argument);
  CHECK_THROWS_AS(heading_update({}, {.offset_deg = std::numeric_limits<double>::quiet_NaN(),
                                      .variance_deg2 = 1}),
                  std::invalid_argument);
}

TEST_CASE("heading_from_magnetometer gives the offset of the frame", "[heading]") {
  const Observer vienna = test::site("vienna");
  const Axis field = earth_field_enu(vienna);
  // The device in the true frame, and the same device in a frame whose azimuths are 70° less.
  const double offset = 70;
  for (const auto& [azimuth, pitch, roll] :
       {std::array{30.0, 10.0, 5.0}, std::array{200.0, -20.0, 0.0},
        std::array{300.0, 60.0, -30.0}}) {
    INFO("azimuth " << azimuth << " pitch " << pitch << " roll " << roll);
    const test::Pose pose = upright_pose(azimuth, pitch, roll);
    const Quaternion in_frame = upright_pose(azimuth - offset, pitch, roll).q;
    const HeadingMeasurement m =
        heading_from_magnetometer(in_frame, in_device(pose, field), vienna, kT);
    CHECK_THAT(m.offset_deg, WithinAbs(offset, 1e-6));
    // Without disturbance, the variance is the sensor variance (2°)².
    CHECK_THAT(m.variance_deg2, WithinAbs(4, 1e-9));
  }
}

TEST_CASE("heading_from_magnetometer gives a larger variance near a disturbance", "[heading]") {
  const Observer vienna = test::site("vienna");
  Axis field = earth_field_enu(vienna);
  field[0] += 15;  // 15 µT to the east, for example from steel
  const test::Pose pose = upright_pose(45, 0, 0);
  const Quaternion in_frame = upright_pose(45 - 70, 0, 0).q;
  const HeadingMeasurement m =
      heading_from_magnetometer(in_frame, in_device(pose, field), vienna, kT);
  const double error = std::abs(std::remainder(m.offset_deg - 70, 360.0));
  INFO("error " << error << " sigma " << std::sqrt(m.variance_deg2));
  CHECK(error > 10);  // the disturbance really moves the heading
  CHECK(m.variance_deg2 > 100);
  CHECK(error < 3 * std::sqrt(m.variance_deg2));
}

TEST_CASE("heading_from_direction gives the offset from a tapped point", "[heading]") {
  const Observer vienna = test::site("vienna");
  const double offset = 70;
  const double t = 1782039420;  // the sun is about 65° high in the south
  const Horizontal sun = sun_position(t, vienna);
  const View view{.device_to_enu = upright_pose(sun.azimuth_deg - offset, 50, 0).q};
  // The sun in the frame has the azimuth sun - offset.
  const Projection p =
      project(view, {.azimuth_deg = sun.azimuth_deg - offset, .altitude_deg = sun.altitude_deg});
  REQUIRE(p.valid);
  const HeadingMeasurement m = heading_from_direction(view, p.point, sun.azimuth_deg, 0.5);
  CHECK_THAT(m.offset_deg, WithinAbs(offset, 1e-6));
  const double sigma = 0.5 / std::cos(sun.altitude_deg * kDeg);
  CHECK_THAT(m.variance_deg2, WithinAbs(sigma * sigma, 1e-6));

  // The heading offset of the view is ignored.
  View shifted = view;
  shifted.heading_offset_deg = 33;
  CHECK_THAT(heading_from_direction(shifted, p.point, sun.azimuth_deg, 0.5).offset_deg,
             WithinAbs(offset, 1e-6));

  // At the zenith, the azimuth is not defined.
  const View up{.device_to_enu = upright_pose(0, 90, 0).q};
  CHECK_THROWS_AS(heading_from_direction(up, {500, 500}, 0, 0.5), std::invalid_argument);
  CHECK_THROWS_AS(heading_from_direction(view, p.point, sun.azimuth_deg, 0), std::invalid_argument);
}

TEST_CASE("the heading filter converges on magnetometer measurements", "[heading]") {
  const Observer vienna = test::site("vienna");
  const Axis field = earth_field_enu(vienna);
  const double offset = 250;
  HeadingEstimate e;
  for (int second = 0; second < 30; ++second) {
    const double azimuth = 10.0 * second;  // the user turns around
    const test::Pose pose = upright_pose(azimuth, 5, 0);
    e = heading_predict(e, 1);
    e = heading_update(e, heading_from_magnetometer(upright_pose(azimuth - offset, 5, 0).q,
                                                    in_device(pose, field), vienna, kT));
  }
  CHECK_THAT(e.offset_deg, WithinAbs(offset, 1e-3));
  CHECK(e.variance_deg2 < 1);
}

namespace {

/// The magnetometer sample of a device in `pose`, with a hard-iron bias in the device frame.
Vector3 sample(const test::Pose& pose, const Axis& field_enu, const Vector3 bias) {
  const Vector3 v = in_device(pose, field_enu);
  return {v.x + bias.x, v.y + bias.y, v.z + bias.z};
}

/// One second of the filter: predict, then one magnetometer sample. The frame has azimuths
/// that are `offset` less than the true azimuths.
HeadingFilter step(const HeadingFilter& f, const Observer& observer, const double offset,
                   const double azimuth, const double pitch, const double roll,
                   const Axis& field_enu, const Vector3 bias) {
  const test::Pose pose = upright_pose(azimuth, pitch, roll);
  return heading_filter_update_magnetometer(heading_filter_predict(f, 1),
                                            upright_pose(azimuth - offset, pitch, roll).q,
                                            sample(pose, field_enu, bias), observer, kT);
}

double angle_error(const double a, const double b) {
  return std::abs(std::remainder(a - b, 360.0));
}

}  // namespace

TEST_CASE("heading_filter_start has an unknown offset and the given bias", "[heading]") {
  const HeadingFilter f = heading_filter_start({.x = 1, .y = 2, .z = 3}, 10);
  CHECK(f.covariance[0] == kUnknownHeadingVarianceDeg2);
  CHECK(f.bias_ut.y == 2);
  CHECK(f.covariance[1 * kHeadingFilterSize + 1] == 100);
  CHECK(f.covariance[4 * kHeadingFilterSize + 4] == 9);  // disturbance (3 µT)²
  CHECK(f.covariance[1] == 0);
  CHECK_THROWS_AS(heading_filter_start({}, 0), std::invalid_argument);
  CHECK_THROWS_AS(heading_filter_start({}, 1, {.disturbance_time_s = 0}), std::invalid_argument);
}

TEST_CASE("heading_filter_predict grows the variances and decays the disturbance", "[heading]") {
  HeadingFilter f = heading_filter_start({}, 1);
  f.covariance[0] = 4;
  f.disturbance_ut = {.x = 2, .y = 0, .z = 0};
  const HeadingFilter p = heading_filter_predict(f, 60);
  CHECK_THAT(p.covariance[0], WithinAbs(5, 1e-12));  // 1 square degree per minute
  CHECK_THAT(p.covariance[1 * kHeadingFilterSize + 1], WithinAbs(1.1, 1e-12));
  CHECK_THAT(p.disturbance_ut.x, WithinAbs(2 * std::exp(-0.5), 1e-12));
  // The variance of the disturbance stays at (3 µT)².
  CHECK_THAT(p.covariance[4 * kHeadingFilterSize + 4], WithinAbs(9, 1e-9));
  CHECK(heading_filter_predict(f, 1e9).covariance[0] == kUnknownHeadingVarianceDeg2);
  CHECK_THROWS_AS(heading_filter_predict(f, -1), std::invalid_argument);
  HeadingFilter bad = f;
  bad.covariance[0] = 0;
  CHECK_THROWS_AS(heading_filter_predict(bad, 1), std::invalid_argument);
}

TEST_CASE("heading_filter_update with a direction is a Kalman update on the circle", "[heading]") {
  HeadingFilter f = heading_filter_start({}, 1);
  f.offset_deg = 359;
  f.covariance[0] = 1;
  const HeadingFilter u = heading_filter_update(f, {.offset_deg = 1, .variance_deg2 = 1});
  CHECK(angle_error(u.offset_deg, 0) < 1e-9);
  CHECK_THAT(u.covariance[0], WithinAbs(0.5, 1e-12));
  CHECK_THROWS_AS(heading_filter_update(f, {.offset_deg = 1, .variance_deg2 = 0}),
                  std::invalid_argument);
}

TEST_CASE("the magnetic heading filter converges without a bias", "[heading]") {
  const Observer vienna = test::site("vienna");
  const Axis field = earth_field_enu(vienna);
  const double offset = 250;
  HeadingFilter f = heading_filter_start({}, 2);
  // The first sample already gives the offset, also when it is far from the start value.
  f = step(f, vienna, offset, 0, 5, 0, field, {});
  CHECK(angle_error(f.offset_deg, offset) < 1);
  for (int second = 1; second < 60; ++second)
    f = step(f, vienna, offset, 10.0 * second, 5, 0, field, {});
  CHECK(angle_error(f.offset_deg, offset) < 0.5);
  // A disturbance of 3 µT is about 8° in Vienna (H = 21 µT), and it is correlated for 120 s. So
  // one minute of samples does not give much better than that.
  const double sigma = std::sqrt(f.covariance[0]);
  CHECK(sigma > 3);
  CHECK(sigma < 9);
}

TEST_CASE("the magnetic heading filter learns a hard-iron bias from rotations", "[heading]") {
  const Observer vienna = test::site("vienna");
  const Axis field = earth_field_enu(vienna);
  const double offset = 40;
  const Vector3 bias{.x = 25, .y = -15, .z = 40};
  // The OS guess of the bias is 10 µT wrong in each component.
  HeadingFilter f = heading_filter_start({.x = 35, .y = -5, .z = 30}, 20);
  for (int second = 0; second < 300; ++second) {
    // The user turns around and tilts the device up, down, and to the side.
    const double t = second;
    f = step(f, vienna, offset, 7.0 * t, 40 * std::sin(t / 9), 30 * std::sin(t / 13), field, bias);
  }
  INFO("offset " << f.offset_deg << " bias " << f.bias_ut.x << " " << f.bias_ut.y << " "
                 << f.bias_ut.z);
  CHECK(angle_error(f.offset_deg, offset) < 1);
  CHECK_THAT(f.bias_ut.x, WithinAbs(bias.x, 1));
  CHECK_THAT(f.bias_ut.y, WithinAbs(bias.y, 1));
  CHECK_THAT(f.bias_ut.z, WithinAbs(bias.z, 1));
}

TEST_CASE("a sun tap moves the bias, so the magnetometer does not pull the offset back",
          "[heading]") {
  const Observer vienna = test::site("vienna");
  const Axis field = earth_field_enu(vienna);
  const double offset = 40;
  // A bias that the filter does not know, and a device that the user holds still, so the
  // rotations cannot show the bias. The magnetometer gives a wrong offset.
  const Vector3 bias{.x = 12, .y = 0, .z = -8};
  HeadingFilter f = heading_filter_start({}, 5);
  for (int second = 0; second < 600; ++second) f = step(f, vienna, offset, 120, 10, 0, field, bias);
  const double error_before = angle_error(f.offset_deg, offset);
  INFO("error before the tap " << error_before);
  REQUIRE(error_before > 5);

  // The user taps the sun.
  f = heading_filter_update(f, {.offset_deg = offset, .variance_deg2 = 0.25});
  CHECK(angle_error(f.offset_deg, offset) < 0.5);
  // Ten more minutes in the same pose: the offset stays, because the bias explains the field.
  for (int second = 0; second < 600; ++second) f = step(f, vienna, offset, 120, 10, 0, field, bias);
  INFO("offset after 10 minutes " << f.offset_deg);
  CHECK(angle_error(f.offset_deg, offset) < 1);
  // The user then turns around: the offset stays correct.
  for (int second = 0; second < 120; ++second)
    f = step(f, vienna, offset, 120 + 3.0 * second, 10, 0, field, bias);
  CHECK(angle_error(f.offset_deg, offset) < 1);
}

TEST_CASE("a short disturbance moves the offset only a little", "[heading]") {
  const Observer vienna = test::site("vienna");
  Axis field = earth_field_enu(vienna);
  const double offset = 40;
  HeadingFilter f = heading_filter_start({}, 2);
  for (int second = 0; second < 300; ++second)
    f = step(f, vienna, offset, 2.0 * second, 10 * std::sin(second / 7.0), 0, field, {});
  REQUIRE(angle_error(f.offset_deg, offset) < 0.5);
  // 10 seconds next to steel: 10 µT to the east. Alone, this moves the heading by about 25°.
  field[0] += 10;
  for (int second = 0; second < 10; ++second) f = step(f, vienna, offset, 0, 0, 0, field, {});
  INFO("offset " << f.offset_deg);
  CHECK(angle_error(f.offset_deg, offset) < 5);
}
