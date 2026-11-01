#include "poses.hpp"

#include <cmath>
#include <cstddef>
#include <numbers>

namespace sunradar::test {

Quaternion from_axes(const Axis& x, const Axis& y, const Axis& z) {
  const double m00 = x[0], m01 = y[0], m02 = z[0];
  const double m10 = x[1], m11 = y[1], m12 = z[1];
  const double m20 = x[2], m21 = y[2], m22 = z[2];
  const double trace = m00 + m11 + m22;
  if (trace > 0) {
    const double s = 2 * std::sqrt(trace + 1);
    return {s / 4, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s};
  }
  if (m00 > m11 && m00 > m22) {
    const double s = 2 * std::sqrt(1 + m00 - m11 - m22);
    return {(m21 - m12) / s, s / 4, (m01 + m10) / s, (m02 + m20) / s};
  }
  if (m11 > m22) {
    const double s = 2 * std::sqrt(1 + m11 - m00 - m22);
    return {(m02 - m20) / s, (m01 + m10) / s, s / 4, (m12 + m21) / s};
  }
  const double s = 2 * std::sqrt(1 + m22 - m00 - m11);
  return {(m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, s / 4};
}

Pose upright_pose(const double azimuth_deg, const double pitch_deg, const double roll_deg,
                  const ScreenRotation rotation) {
  constexpr double kDeg = std::numbers::pi / 180.0;
  const double a = azimuth_deg * kDeg, p = pitch_deg * kDeg, r = roll_deg * kDeg;
  // The camera axes of the user: forward, right, and up.
  const Axis f{std::sin(a) * std::cos(p), std::cos(a) * std::cos(p), std::sin(p)};
  const Axis right0{std::cos(a), -std::sin(a), 0};
  const Axis up0{-std::sin(a) * std::sin(p), -std::cos(a) * std::sin(p), std::cos(p)};
  Axis right{}, up{};
  for (std::size_t i = 0; i < 3; ++i) {
    right[i] = std::cos(r) * right0[i] - std::sin(r) * up0[i];
    up[i] = std::sin(r) * right0[i] + std::cos(r) * up0[i];
  }
  const Axis back{-f[0], -f[1], -f[2]};  // device +z points out of the screen
  const auto neg = [](const Axis& v) { return Axis{-v[0], -v[1], -v[2]}; };
  Axis x{}, y{};
  switch (rotation) {
    case ScreenRotation::rotation_0:
      x = right, y = up;
      break;
    // rotation_90: the device is turned 90° counterclockwise, so the device top (+y) points to
    // the left of the user and the device right side (+x) points up.
    case ScreenRotation::rotation_90:
      x = up, y = neg(right);
      break;
    case ScreenRotation::rotation_180:
      x = neg(right), y = neg(up);
      break;
    case ScreenRotation::rotation_270:
      x = neg(up), y = right;
      break;
  }
  return {x, y, back, from_axes(x, y, back)};
}

Quaternion upright(const double azimuth_deg, const double pitch_deg, const double roll_deg,
                   const ScreenRotation rotation) {
  return upright_pose(azimuth_deg, pitch_deg, roll_deg, rotation).q;
}

}  // namespace sunradar::test
