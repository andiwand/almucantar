#pragma once

#include "sunradar/types.hpp"
#include "sunradar/view.hpp"

namespace sunradar {

/// The variance of a heading that is not known at all, in square degrees.
inline constexpr double kUnknownHeadingVarianceDeg2 = 180.0 * 180.0;

/// The estimate of a heading filter: the angle that the app adds to the azimuth of the
/// orientation frame to get the true azimuth (true = frame + offset). The frame is usually the
/// frame of the Android game rotation vector, which has no fixed north. Use `offset_deg` as
/// `View::heading_offset_deg`.
struct HeadingEstimate {
  double offset_deg = 0;
  double variance_deg2 = kUnknownHeadingVarianceDeg2;
};

/// One measurement of the offset.
struct HeadingMeasurement {
  double offset_deg = 0;
  double variance_deg2 = 0;
};

struct MagnetometerOptions {
  /// The error of the heading from a magnetometer without disturbance: noise, the residual
  /// hard-iron error, and the tilt error.
  double sensor_sigma_deg = 2;
};

/// Increases the variance for the time `dt_s`, because the yaw of the orientation frame drifts.
/// `drift_deg2_per_s` is the growth of the variance (a random walk). The default of 1/60 is
/// 1 square degree per minute. Throws `std::invalid_argument` if a value is negative or not
/// finite.
[[nodiscard]] HeadingEstimate heading_predict(const HeadingEstimate& estimate, double dt_s,
                                              double drift_deg2_per_s = 1.0 / 60.0);

/// Combines the estimate with a measurement (a Kalman update on the circle). Throws
/// `std::invalid_argument` if a value is not finite or a variance is not positive.
[[nodiscard]] HeadingEstimate heading_update(const HeadingEstimate& estimate,
                                             const HeadingMeasurement& measurement);

/// The offset from a magnetometer. `device_to_frame` is the orientation of the device in the
/// orientation frame, and `field_ut` is the calibrated field in the device frame, in µT (Android
/// `TYPE_MAGNETIC_FIELD`). The function uses the WMM for the declination and for the expected
/// field. The variance grows with the difference between the measured and the expected field
/// strength and inclination, because a local disturbance (for example steel) changes them.
///
/// The errors of a magnetometer are correlated in time, so give the filter at most about one
/// measurement per second. Throws `std::invalid_argument` if an argument is not valid or the
/// field is 0.
[[nodiscard]] HeadingMeasurement heading_from_magnetometer(const Quaternion& device_to_frame,
                                                           Vector3 field_ut,
                                                           const Observer& observer,
                                                           double unix_utc,
                                                           const MagnetometerOptions& options = {});

/// The offset from a screen point that shows a known true azimuth, for example the sun that the
/// user taps, or a landmark with a known bearing. `view` uses the orientation frame; its
/// `heading_offset_deg` is ignored. `sigma_deg` is the angular error of the point. The variance
/// grows near the zenith, where the azimuth of a point is badly defined. Throws
/// `std::invalid_argument` if an argument is not valid, or if the point is within 1° of the
/// zenith or the nadir.
[[nodiscard]] HeadingMeasurement heading_from_direction(const View& view, ScreenPoint point,
                                                        double true_azimuth_deg, double sigma_deg);

}  // namespace sunradar
