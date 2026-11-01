#include "sunradar/heading.hpp"

#include <algorithm>
#include <cmath>

#include "sunradar/internal/math.hpp"
#include "sunradar/internal/time.hpp"
#include "sunradar/internal/validate.hpp"
#include "sunradar/internal/vector.hpp"
#include "sunradar/internal/wmm.hpp"

namespace sunradar {

namespace {

using internal::kDegToRad;
using internal::kRadToDeg;
using internal::require;
using internal::require_finite;

void validate(const HeadingEstimate& e) {
  require_finite(e.offset_deg, "offset_deg");
  require_finite(e.variance_deg2, "variance_deg2");
  require(e.variance_deg2 > 0, "variance_deg2 is not positive");
}

}  // namespace

HeadingEstimate heading_predict(const HeadingEstimate& estimate, const double dt_s,
                                const double drift_deg2_per_s) {
  validate(estimate);
  require_finite(dt_s, "dt_s");
  require_finite(drift_deg2_per_s, "drift_deg2_per_s");
  require(dt_s >= 0, "dt_s is negative");
  require(drift_deg2_per_s >= 0, "drift_deg2_per_s is negative");
  return {.offset_deg = estimate.offset_deg,
          // The cap applies to the growth only, so a larger variance does not get smaller.
          .variance_deg2 = std::max(estimate.variance_deg2,
                                    std::min(estimate.variance_deg2 + drift_deg2_per_s * dt_s,
                                             kUnknownHeadingVarianceDeg2))};
}

HeadingEstimate heading_update(const HeadingEstimate& estimate,
                               const HeadingMeasurement& measurement) {
  validate(estimate);
  require_finite(measurement.offset_deg, "offset_deg");
  require_finite(measurement.variance_deg2, "variance_deg2");
  require(measurement.variance_deg2 > 0, "variance_deg2 is not positive");
  // The innovation on the circle: the shortest angle from the estimate to the measurement.
  const double innovation = internal::wrap_180(measurement.offset_deg - estimate.offset_deg);
  const double gain = estimate.variance_deg2 / (estimate.variance_deg2 + measurement.variance_deg2);
  return {.offset_deg = internal::wrap_360(estimate.offset_deg + gain * innovation),
          .variance_deg2 = (1 - gain) * estimate.variance_deg2};
}

HeadingMeasurement heading_from_magnetometer(const Quaternion& device_to_frame,
                                             const Vector3 field_ut, const Observer& observer,
                                             const double unix_utc,
                                             const MagnetometerOptions& options) {
  require_finite(field_ut.x, "field_ut.x");
  require_finite(field_ut.y, "field_ut.y");
  require_finite(field_ut.z, "field_ut.z");
  require_finite(unix_utc, "unix_utc");
  internal::require_positive(options.sensor_sigma_deg, "sensor_sigma_deg");
  internal::validate(device_to_frame, "device_to_frame");
  internal::validate(observer);

  // The measured field in the orientation frame (x east, y north of the frame, z up).
  const internal::Vec3 m =
      internal::rotate(internal::normalized(device_to_frame), {field_ut.x, field_ut.y, field_ut.z});
  const double measured_f = internal::norm(m);
  require(measured_f > 0, "the magnetic field is 0");
  const double measured_h = std::hypot(m.x, m.y);
  const double measured_inclination = std::atan2(-m.z, measured_h);

  // The expected field from the WMM, in µT.
  const internal::MagneticField expected =
      internal::wmm_field(internal::wmm_coefficients(), observer.latitude_deg,
                          observer.longitude_deg, 0.0, internal::decimal_year(unix_utc));
  const double north = expected.north_nt / 1000;
  const double east = expected.east_nt / 1000;
  const double down = expected.down_nt / 1000;
  const double expected_h = std::hypot(north, east);
  const double expected_f = std::hypot(expected_h, down);
  const double expected_inclination = std::atan2(down, expected_h);
  const double declination = std::atan2(east, north) * kRadToDeg;

  // The horizontal field points to magnetic north. Its azimuth in the frame is atan2(x, y), and
  // its true azimuth is the declination.
  const double frame_azimuth = std::atan2(m.x, m.y) * kRadToDeg;

  // A disturbance of size d changes the heading by up to atan(d / H). The differences of the
  // strength and of the inclination are lower bounds of d.
  const double d = std::hypot(measured_f - expected_f,
                              expected_f * (measured_inclination - expected_inclination));
  const double disturbance_deg = std::atan2(d, expected_h) * kRadToDeg;
  const double sigma = options.sensor_sigma_deg;

  return {.offset_deg = internal::wrap_360(declination - frame_azimuth),
          .variance_deg2 = sigma * sigma + disturbance_deg * disturbance_deg};
}

HeadingMeasurement heading_from_direction(const View& view, const ScreenPoint point,
                                          const double true_azimuth_deg, const double sigma_deg) {
  require_finite(true_azimuth_deg, "true_azimuth_deg");
  require_finite(sigma_deg, "sigma_deg");
  require(sigma_deg > 0, "sigma_deg is not positive");
  View frame_view = view;
  frame_view.heading_offset_deg = 0;
  const Horizontal shown = unproject(frame_view, point);
  require(std::abs(shown.altitude_deg) < 89, "the point is too near the zenith or the nadir");
  // An angular error sigma moves the azimuth by sigma / cos(altitude).
  const double sigma_azimuth = sigma_deg / std::cos(shown.altitude_deg * kDegToRad);
  return {.offset_deg = internal::wrap_360(true_azimuth_deg - shown.azimuth_deg),
          .variance_deg2 = sigma_azimuth * sigma_azimuth};
}

}  // namespace sunradar
