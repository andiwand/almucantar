#include "sunradar/internal/projection.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace sunradar::internal {

namespace {

/// The valid cone is this much wider than the angle of the farthest screen corner.
constexpr double kConeMargin = 1.5;
/// The valid cone is never wider than this half angle.
constexpr double kMaxConeAngleDeg = 85;

/// Removes the lens distortion from a normalized point with a fixed-point iteration.
/// The iteration converges for the moderate distortion of phone cameras.
std::array<double, 2> undistort(const Camera& c, const double xd, const double yd) noexcept {
  double x = xd;
  double y = yd;
  for (int i = 0; i < 50; ++i) {
    const double r2 = x * x + y * y;
    const double radial = 1 + r2 * (c.k1 + r2 * (c.k2 + r2 * c.k3));
    const double dx = 2 * c.p1 * x * y + c.p2 * (r2 + 2 * x * x);
    const double dy = c.p1 * (r2 + 2 * y * y) + 2 * c.p2 * x * y;
    const double nx = (xd - dx) / radial;
    const double ny = (yd - dy) / radial;
    if (std::abs(nx - x) < 1e-14 && std::abs(ny - y) < 1e-14) {
      return {nx, ny};
    }
    x = nx;
    y = ny;
  }
  return {x, y};
}

/// The screen axes (right, down) in the device frame, for a screen rotation. The meaning is
/// the one of Android `Display.getRotation()`: `rotation_90` means that the device is turned
/// 90° counterclockwise, so its top points to the left of the user.
std::array<Vec3, 2> screen_axes(const ScreenRotation rotation) noexcept {
  switch (rotation) {
    case ScreenRotation::rotation_0:  // right = +x, up = +y
      return {Vec3{1, 0, 0}, Vec3{0, -1, 0}};
    case ScreenRotation::rotation_90:  // right = -y, up = +x
      return {Vec3{0, -1, 0}, Vec3{-1, 0, 0}};
    case ScreenRotation::rotation_180:  // right = -x, up = -y
      return {Vec3{-1, 0, 0}, Vec3{0, 1, 0}};
    case ScreenRotation::rotation_270:  // right = +y, up = -x
      return {Vec3{0, 1, 0}, Vec3{1, 0, 0}};
  }
  return {Vec3{1, 0, 0}, Vec3{0, -1, 0}};
}

}  // namespace

Projector::Projector(const View& view) noexcept : camera_(view.camera) {
  const Quaternion q = normalized(view.device_to_enu);
  const auto [right_device, down_device] = screen_axes(view.screen_rotation);
  // The back camera looks along the -z axis of the device.
  const Vec3 forward_device{0, 0, -1};
  const auto to_true = [&](const Vec3 v) {
    return rotate_azimuth(rotate(q, v), view.heading_offset_deg);
  };
  right_ = to_true(right_device);
  down_ = to_true(down_device);
  forward_ = to_true(forward_device);

  // The valid cone: wider than the farthest corner of the screen.
  double max_tan = 0;
  for (const ScreenPoint corner :
       {ScreenPoint{0, 0}, ScreenPoint{camera_.width, 0}, ScreenPoint{0, camera_.height},
        ScreenPoint{camera_.width, camera_.height}}) {
    const auto [x, y] = undistort(camera_, (corner.x - camera_.cx) / camera_.fx,
                                  (corner.y - camera_.cy) / camera_.fy);
    max_tan = std::max(max_tan, std::hypot(x, y));
  }
  const double angle = std::min(std::atan(max_tan * kConeMargin), kMaxConeAngleDeg * kDegToRad);
  cos_max_angle_ = std::cos(angle);
}

bool Projector::inside(const Vec3 direction) const noexcept {
  return dot(direction, forward_) >= cos_max_angle_;
}

ScreenPoint Projector::distort_to_pixel(const double x, const double y) const noexcept {
  const Camera& c = camera_;
  const double r2 = x * x + y * y;
  const double radial = 1 + r2 * (c.k1 + r2 * (c.k2 + r2 * c.k3));
  const double xd = x * radial + 2 * c.p1 * x * y + c.p2 * (r2 + 2 * x * x);
  const double yd = y * radial + c.p1 * (r2 + 2 * y * y) + 2 * c.p2 * x * y;
  return {c.fx * xd + c.cx, c.fy * yd + c.cy};
}

ScreenPoint Projector::project_unchecked(const Vec3 direction) const noexcept {
  const double z = dot(direction, forward_);
  return distort_to_pixel(dot(direction, right_) / z, dot(direction, down_) / z);
}

Projection Projector::project(const Vec3 direction) const noexcept {
  const Vec3 d = normalized(direction);
  if (!inside(d)) {
    return {};
  }
  return {.valid = true, .point = project_unchecked(d)};
}

Vec3 Projector::unproject(const ScreenPoint point) const noexcept {
  const auto [x, y] =
      undistort(camera_, (point.x - camera_.cx) / camera_.fx, (point.y - camera_.cy) / camera_.fy);
  return normalized(x * right_ + y * down_ + forward_);
}

Vec3 Projector::boundary(Vec3 inside_point, Vec3 outside_point) const noexcept {
  // Bisection along the chord. The chord is close to the great circle, because the
  // points are dense.
  for (int i = 0; i < 40; ++i) {
    const Vec3 mid = normalized(inside_point + outside_point);
    if (inside(mid)) {
      inside_point = mid;
    } else {
      outside_point = mid;
    }
  }
  return inside_point;
}

std::vector<Polyline> Projector::project_line(const std::vector<Vec3>& line,
                                              const bool closed) const {
  std::vector<Polyline> result;
  if (line.empty()) {
    return result;
  }
  // The points of the line are inside the cone, and the boundary points are on its border.
  // Both are in front of the camera, because the cone is at most 85° wide. A second check of
  // the cone could reject a boundary point because of rounding, so the projection skips it.
  const auto point_at = [&](const Vec3 d) { return project_unchecked(d); };

  Polyline current;
  const std::size_t count = closed ? line.size() + 1 : line.size();
  Vec3 previous = normalized(line[0]);
  bool previous_inside = inside(previous);
  if (previous_inside) {
    current.push_back(point_at(previous));
  }
  for (std::size_t i = 1; i < count; ++i) {
    const Vec3 d = normalized(line[i % line.size()]);
    const bool d_inside = inside(d);
    if (previous_inside && d_inside) {
      current.push_back(point_at(d));
    } else if (previous_inside) {
      current.push_back(point_at(boundary(previous, d)));
      result.push_back(std::move(current));
      current = {};
    } else if (d_inside) {
      current.push_back(point_at(boundary(d, previous)));
      current.push_back(point_at(d));
    }
    previous = d;
    previous_inside = d_inside;
  }
  if (current.size() >= 2) {
    result.push_back(std::move(current));
  }
  // A closed line that is inside at its start and its end is one polyline.
  if (closed && result.size() >= 2 && inside(normalized(line[0]))) {
    Polyline& first = result.front();
    Polyline& last = result.back();
    last.insert(last.end(), first.begin() + 1, first.end());
    first = std::move(last);
    result.pop_back();
  }
  return result;
}

}  // namespace sunradar::internal
