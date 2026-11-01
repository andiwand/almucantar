#pragma once

#include <cmath>

#include "sunradar/internal/math.hpp"
#include "sunradar/types.hpp"

namespace sunradar::internal {

struct Vec3 {
  double x = 0;
  double y = 0;
  double z = 0;
};

constexpr Vec3 operator+(const Vec3 a, const Vec3 b) noexcept {
  return {a.x + b.x, a.y + b.y, a.z + b.z};
}
constexpr Vec3 operator-(const Vec3 a, const Vec3 b) noexcept {
  return {a.x - b.x, a.y - b.y, a.z - b.z};
}
constexpr Vec3 operator*(const double s, const Vec3 v) noexcept {
  return {s * v.x, s * v.y, s * v.z};
}
constexpr double dot(const Vec3 a, const Vec3 b) noexcept {
  return a.x * b.x + a.y * b.y + a.z * b.z;
}
constexpr Vec3 cross(const Vec3 a, const Vec3 b) noexcept {
  return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
inline double norm(const Vec3 v) noexcept { return std::sqrt(dot(v, v)); }
inline Vec3 normalized(const Vec3 v) noexcept { return (1.0 / norm(v)) * v; }

/// Returns the unit vector of a direction in the East-North-Up frame.
inline Vec3 to_enu(const Horizontal h) noexcept {
  const double ca = cos_deg(h.altitude_deg);
  return {ca * sin_deg(h.azimuth_deg), ca * cos_deg(h.azimuth_deg), sin_deg(h.altitude_deg)};
}

/// Returns the direction of a vector in the East-North-Up frame. The vector need not
/// have unit length.
inline Horizontal to_horizontal(const Vec3 v) noexcept {
  return {.azimuth_deg = wrap_360(std::atan2(v.x, v.y) * kRadToDeg),
          .altitude_deg = std::atan2(v.z, std::hypot(v.x, v.y)) * kRadToDeg};
}

/// Rotates `v` with the unit quaternion `q`.
inline Vec3 rotate(const Quaternion& q, const Vec3 v) noexcept {
  // v' = v + 2 u × (u × v + w v), with u the vector part of q.
  const Vec3 u{q.x, q.y, q.z};
  const Vec3 t = cross(u, v) + q.w * v;
  return v + 2.0 * cross(u, t);
}

/// Rotates `v` about the up axis so that its azimuth increases by `delta_deg`.
inline Vec3 rotate_azimuth(const Vec3 v, const double delta_deg) noexcept {
  const double c = cos_deg(delta_deg);
  const double s = sin_deg(delta_deg);
  return {v.x * c + v.y * s, -v.x * s + v.y * c, v.z};
}

}  // namespace sunradar::internal
