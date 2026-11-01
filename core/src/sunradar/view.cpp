#include "sunradar/view.hpp"

#include <cmath>
#include <cstdint>
#include <utility>

#include "sunradar/internal/math.hpp"
#include "sunradar/internal/projection.hpp"
#include "sunradar/internal/validate.hpp"
#include "sunradar/internal/vector.hpp"

namespace sunradar {

namespace {

using internal::require;
using internal::require_finite;

void validate(const View& view) {
  internal::validate(view.device_to_enu, "device_to_enu");
  require(static_cast<std::uint8_t>(view.screen_rotation) <=
              static_cast<std::uint8_t>(ScreenRotation::rotation_270),
          "screen_rotation is unknown");
  require_finite(view.heading_offset_deg, "heading_offset_deg");

  const Camera& c = view.camera;
  for (const auto& [value, name] :
       {std::pair{c.width, "width"}, std::pair{c.height, "height"}, std::pair{c.fx, "fx"},
        std::pair{c.fy, "fy"}, std::pair{c.cx, "cx"}, std::pair{c.cy, "cy"}, std::pair{c.k1, "k1"},
        std::pair{c.k2, "k2"}, std::pair{c.k3, "k3"}, std::pair{c.p1, "p1"},
        std::pair{c.p2, "p2"}}) {
    require_finite(value, name);
  }
  require(c.width > 0 && c.height > 0, "the camera size is not positive");
  require(c.fx > 0 && c.fy > 0, "the focal length is not positive");
}

internal::Projector projector(const View& view) {
  validate(view);
  return internal::Projector(view);
}

}  // namespace

Camera camera_from_fov(const double width, const double height, const double horizontal_fov_deg) {
  require_finite(width, "width");
  require_finite(height, "height");
  require_finite(horizontal_fov_deg, "horizontal_fov_deg");
  require(width > 0 && height > 0, "the camera size is not positive");
  require(horizontal_fov_deg > 0 && horizontal_fov_deg < 180,
          "horizontal_fov_deg is not in the range (0, 180)");
  const double f = 0.5 * width / internal::tan_deg(0.5 * horizontal_fov_deg);
  return Camera{
      .width = width, .height = height, .fx = f, .fy = f, .cx = 0.5 * width, .cy = 0.5 * height};
}

Projection project(const View& view, const Horizontal direction) {
  require_finite(direction.azimuth_deg, "azimuth_deg");
  require_finite(direction.altitude_deg, "altitude_deg");
  return projector(view).project(internal::to_enu(direction));
}

Horizontal unproject(const View& view, const ScreenPoint point) {
  require_finite(point.x, "x");
  require_finite(point.y, "y");
  return internal::to_horizontal(projector(view).unproject(point));
}

std::vector<Polyline> project_line(const View& view, const std::vector<Horizontal>& line) {
  const internal::Projector p = projector(view);
  std::vector<internal::Vec3> directions;
  directions.reserve(line.size());
  for (const Horizontal& h : line) {
    require_finite(h.azimuth_deg, "azimuth_deg");
    require_finite(h.altitude_deg, "altitude_deg");
    directions.push_back(internal::to_enu(h));
  }
  return p.project_line(directions, false);
}

std::vector<Polyline> project_path(const View& view, const std::vector<PathPoint>& path) {
  std::vector<Horizontal> line;
  line.reserve(path.size());
  for (const PathPoint& point : path) {
    line.push_back(point.position);
  }
  return project_line(view, line);
}

std::vector<Polyline> horizon_line(const View& view) {
  const internal::Projector p = projector(view);
  std::vector<internal::Vec3> directions;
  directions.reserve(360);
  for (int azimuth = 0; azimuth < 360; ++azimuth) {
    directions.push_back(
        internal::to_enu({.azimuth_deg = static_cast<double>(azimuth), .altitude_deg = 0}));
  }
  return p.project_line(directions, true);
}

}  // namespace sunradar
