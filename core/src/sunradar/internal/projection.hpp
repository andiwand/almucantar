#pragma once

#include <vector>

#include "sunradar/internal/vector.hpp"
#include "sunradar/view.hpp"

namespace sunradar::internal {

/// A view prepared for projection: the camera axes in the true East-North-Up frame and
/// the limit of the valid area. The caller validates the view first.
class Projector {
 public:
  explicit Projector(const View& view) noexcept;

  [[nodiscard]] Projection project(Vec3 direction) const noexcept;
  [[nodiscard]] Vec3 unproject(ScreenPoint point) const noexcept;
  [[nodiscard]] std::vector<Polyline> project_line(const std::vector<Vec3>& line,
                                                   bool closed) const;

  /// True if the unit vector `direction` is inside the valid cone.
  [[nodiscard]] bool inside(Vec3 direction) const noexcept;

 private:
  [[nodiscard]] ScreenPoint distort_to_pixel(double x, double y) const noexcept;
  /// Projects a unit vector in front of the camera without the check of the cone. For points
  /// on the border of the cone, where rounding can put them a little outside.
  [[nodiscard]] ScreenPoint project_unchecked(Vec3 direction) const noexcept;
  [[nodiscard]] Vec3 boundary(Vec3 inside, Vec3 outside) const noexcept;

  Camera camera_;
  Vec3 right_;
  Vec3 down_;
  Vec3 forward_;
  double cos_max_angle_ = 0;
};

}  // namespace sunradar::internal
