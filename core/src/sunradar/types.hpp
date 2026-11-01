#pragma once

namespace sunradar {

/// A place on the Earth, in geodetic coordinates (WGS 84).
struct Observer {
  double latitude_deg = 0;   ///< north is positive, -90 to 90
  double longitude_deg = 0;  ///< east is positive, -180 to 180
};

/// A direction in the local horizontal system of an observer.
struct Horizontal {
  double azimuth_deg = 0;   ///< from true north, clockwise (east is 90), 0 to 360
  double altitude_deg = 0;  ///< above the horizon, -90 to 90
};

/// A rotation as a unit quaternion. The view uses it for the orientation of the device:
/// it rotates a vector from the frame of the device to the East-North-Up frame. This is
/// the convention of the Android rotation vector (`SensorManager.getQuaternionFromVector`).
struct Quaternion {
  double w = 1;
  double x = 0;
  double y = 0;
  double z = 0;
};

/// A vector in three dimensions, for sensor values. The frame and the unit depend on the use.
struct Vector3 {
  double x = 0;
  double y = 0;
  double z = 0;
};

/// A point on the screen, in pixels. The origin is the top left corner, x goes right,
/// and y goes down.
struct ScreenPoint {
  double x = 0;
  double y = 0;
};

/// The state of the air near the observer. The refraction depends on it.
struct Atmosphere {
  double pressure_hpa = 1010;
  double temperature_c = 10;
};

}  // namespace sunradar
