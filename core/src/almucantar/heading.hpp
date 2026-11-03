#pragma once

#include <array>
#include <cstddef>

#include "almucantar/types.hpp"
#include "almucantar/view.hpp"

namespace almucantar {

/// The variance of a heading that is not known at all, in square degrees.
inline constexpr double kUnknownHeadingVarianceDeg2 = 180.0 * 180.0;

/// The default growth of the variance in heading_predict(), in square degrees per second.
inline constexpr double kDefaultHeadingDriftDeg2PerS = 1.0 / 60.0;

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
[[nodiscard]] HeadingEstimate heading_predict(
    const HeadingEstimate& estimate, double dt_s,
    double drift_deg2_per_s = kDefaultHeadingDriftDeg2PerS);

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

/// The number of values in the state of a HeadingFilter: the offset, the bias (3), and the
/// disturbance (3).
inline constexpr std::size_t kHeadingFilterSize = 7;

/// The noise model of a HeadingFilter.
struct HeadingFilterOptions {
  /// The growth of the variance of the offset, because the yaw of the frame drifts.
  double drift_deg2_per_s = kDefaultHeadingDriftDeg2PerS;
  /// The growth of the variance of each bias component, because the bias changes slowly (for
  /// example with the temperature). The default is 1 µT² in 10 minutes.
  double bias_drift_ut2_per_s = 1.0 / 600.0;
  /// The size of a local disturbance of the field (for example steel), per component.
  double disturbance_sigma_ut = 3;
  /// The correlation time of a local disturbance. A disturbance that stays longer than this
  /// time moves the offset.
  double disturbance_time_s = 120;
  /// The noise of one magnetometer sample, per component.
  double noise_sigma_ut = 1;
};

/// The state of the heading filter with the magnetometer (PLAN.md, section 5.4a). It estimates
/// the offset of the frame to true north (as HeadingEstimate), the hard-iron bias of the
/// magnetometer in the device frame, and a local disturbance of the field in East-North-Up. A
/// direction measurement (for example a sun tap) also corrects the bias, so the magnetometer
/// does not pull the offset back. Make it with heading_filter_start().
struct HeadingFilter {
  double offset_deg = 0;   ///< true azimuth = frame azimuth + offset
  Vector3 bias_ut;         ///< the hard-iron bias in the device frame, in µT
  Vector3 disturbance_ut;  ///< the local disturbance in East-North-Up, in µT
  /// The covariance, row by row, in the order offset (deg), bias x, y, z (µT), disturbance
  /// east, north, up (µT). The first value is the variance of the offset.
  std::array<double, kHeadingFilterSize * kHeadingFilterSize> covariance{};
};

/// A filter with an unknown offset. `bias_ut` is the first guess of the bias (for example the
/// bias that the OS estimates, or 0 for a field that the OS calibrated), and `bias_sigma_ut` is
/// its error. Throws `std::invalid_argument` if a value is not finite or the sigma is not
/// positive.
[[nodiscard]] HeadingFilter heading_filter_start(Vector3 bias_ut, double bias_sigma_ut,
                                                 const HeadingFilterOptions& options = {});

/// Moves the filter forward by the time `dt_s`: the variances grow, and the disturbance decays.
/// The variance of the offset grows to at most kUnknownHeadingVarianceDeg2. Throws
/// `std::invalid_argument` if an argument is not valid.
[[nodiscard]] HeadingFilter heading_filter_predict(const HeadingFilter& filter, double dt_s,
                                                   const HeadingFilterOptions& options = {});

/// Combines the filter with a measurement of the offset, for example from
/// heading_from_direction(). Throws `std::invalid_argument` if an argument is not valid.
[[nodiscard]] HeadingFilter heading_filter_update(const HeadingFilter& filter,
                                                  const HeadingMeasurement& measurement);

/// Combines the filter with one magnetometer sample. `device_to_frame` is the orientation of the
/// device in the orientation frame. `field_ut` is the field in the device frame, in µT, with
/// the hard-iron bias in it (Android `TYPE_MAGNETIC_FIELD_UNCALIBRATED`) or without it (a field
/// that the OS calibrated). The expected field comes from the WMM. A sample that is far from
/// the expected field gets less weight. Give the filter about one sample per second. Throws
/// `std::invalid_argument` if an argument is not valid.
[[nodiscard]] HeadingFilter heading_filter_update_magnetometer(
    const HeadingFilter& filter, const Quaternion& device_to_frame, Vector3 field_ut,
    const Observer& observer, double unix_utc, const HeadingFilterOptions& options = {});

}  // namespace almucantar
