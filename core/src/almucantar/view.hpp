#pragma once

#include <cstdint>
#include <vector>

#include "almucantar/sun.hpp"
#include "almucantar/types.hpp"

namespace almucantar {

/// The rotation of the screen content relative to the natural orientation of the device,
/// with the meaning of Android `Display.getRotation()` and `Surface.ROTATION_*`: the content
/// turns opposite to the device, so it stays upright.
enum class ScreenRotation : std::uint8_t {
  rotation_0,    ///< natural orientation (portrait on a phone)
  rotation_90,   ///< device turned 90° counterclockwise: screen right is device -y
  rotation_180,  ///< upside down: screen right is device -x
  rotation_270,  ///< device turned 90° clockwise: screen right is device +y
};

/// A pinhole camera with lens distortion, in the pixels of the screen. The default is a
/// virtual camera of 1000 × 1000 px with a horizontal field of view of 60°.
///
/// The distortion uses the Brown–Conrady model of OpenCV: it maps the ideal normalized
/// point (x, y) to the distorted point
///   x' = x (1 + k1 r² + k2 r⁴ + k3 r⁶) + 2 p1 x y + p2 (r² + 2 x²)
///   y' = y (1 + k1 r² + k2 r⁴ + k3 r⁶) + p1 (r² + 2 y²) + 2 p2 x y
/// and then the pixel is (fx x' + cx, fy y' + cy).
struct Camera {
  double width = 1000;   ///< the width of the screen area in pixels
  double height = 1000;  ///< the height of the screen area in pixels
  double fx = 866.0254037844386;
  double fy = 866.0254037844386;
  double cx = 500;  ///< the principal point
  double cy = 500;
  double k1 = 0;
  double k2 = 0;
  double k3 = 0;
  double p1 = 0;
  double p2 = 0;
};

/// Everything that defines what the screen shows.
struct View {
  /// The orientation of the device: device frame to East-North-Up. North is the north
  /// of the orientation sensor, usually magnetic north.
  Quaternion device_to_enu;
  ScreenRotation screen_rotation = ScreenRotation::rotation_0;
  /// The angle that the view adds to the azimuth of the sensor to get the true azimuth.
  /// Usually the magnetic declination plus the offset of the calibration.
  double heading_offset_deg = 0;
  Camera camera;
};

/// The result of `project()`.
struct Projection {
  /// True if the direction is in front of the camera and inside the area where the
  /// camera model is valid (a cone a little larger than the screen). Otherwise `point`
  /// has no meaning.
  bool valid = false;
  ScreenPoint point;
};

/// A line on the screen: a sequence of connected points.
using Polyline = std::vector<ScreenPoint>;

/// Returns a camera without distortion, with the principal point at the center and a
/// horizontal field of view of `horizontal_fov_deg`. Throws `std::invalid_argument` if
/// a size is not positive or if the field of view is not in (0, 180).
[[nodiscard]] Camera camera_from_fov(double width, double height, double horizontal_fov_deg);

/// Projects a direction to the screen. Throws `std::invalid_argument` if the view is
/// not valid: a value that is not finite, a quaternion of length 0, a size or a focal
/// length that is not positive, or an unknown screen rotation.
[[nodiscard]] Projection project(const View& view, Horizontal direction);

/// Projects many directions, for example all stars of a catalogue, in one call. The result has
/// the order of `directions`. Throws `std::invalid_argument` if the view is not valid or if a
/// direction is not finite.
[[nodiscard]] std::vector<Projection> project_points(const View& view,
                                                     const std::vector<Horizontal>& directions);

/// Returns the direction that the screen point shows. This is the inverse of
/// `project()`. Throws `std::invalid_argument` if the view is not valid.
[[nodiscard]] Horizontal unproject(const View& view, ScreenPoint point);

/// Projects a line of directions to the screen. The function clips the line at the
/// border of the valid area, so the result can have 0, 1, or more polylines. The points
/// of the line must be dense (a few degrees apart at most), because the function clips
/// each segment separately. Throws `std::invalid_argument` if the view is not valid.
[[nodiscard]] std::vector<Polyline> project_line(const View& view,
                                                 const std::vector<Horizontal>& line);

/// Projects a path of the sun (see `sun_path()`). The same as `project_line()` with the
/// positions of the path.
[[nodiscard]] std::vector<Polyline> project_path(const View& view,
                                                 const std::vector<PathPoint>& path);

/// Projects the astronomical horizon (altitude 0°) as a closed line in steps of 1°.
[[nodiscard]] std::vector<Polyline> horizon_line(const View& view);

}  // namespace almucantar
