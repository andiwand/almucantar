#pragma once

// Device poses for the tests of the view and the heading.

#include <array>

#include "almucantar/types.hpp"
#include "almucantar/view.hpp"

namespace almucantar::test {

using Axis = std::array<double, 3>;  // East, North, Up

/// A device pose: its x, y, and z axes in East-North-Up, and the quaternion device to ENU.
struct Pose {
  Axis x;
  Axis y;
  Axis z;
  Quaternion q;
};

/// The quaternion of a rotation matrix with the columns x, y, and z.
Quaternion from_axes(const Axis& x, const Axis& y, const Axis& z);

/// A device held upright with the back camera to azimuth `azimuth_deg`, pitched up by
/// `pitch_deg`, and rolled by `roll_deg` (clockwise as the user sees the screen). The device is
/// rotated in its own plane so that the screen content is upright for `rotation`, with the
/// meaning of Android Display.getRotation().
Pose upright_pose(double azimuth_deg, double pitch_deg = 0, double roll_deg = 0,
                  ScreenRotation rotation = ScreenRotation::rotation_0);

/// The quaternion of `upright_pose()`.
Quaternion upright(double azimuth_deg, double pitch_deg = 0, double roll_deg = 0,
                   ScreenRotation rotation = ScreenRotation::rotation_0);

}  // namespace almucantar::test
