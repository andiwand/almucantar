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

/// The state of the air near the observer. The refraction depends on it.
struct Atmosphere {
  double pressure_hpa = 1010;
  double temperature_c = 10;
};

}  // namespace sunradar
