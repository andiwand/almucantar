#include "sunradar/heading.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

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

namespace {

constexpr std::size_t kN = kHeadingFilterSize;
using State = std::array<double, kN>;
using Matrix = std::array<double, kN * kN>;
/// The 3 x kN Jacobian of the magnetometer, row by row.
using Jacobian = std::array<double, 3 * kN>;
using Matrix3 = std::array<double, 9>;

/// The index of the offset, of the first bias component, and of the first disturbance component.
constexpr std::size_t kOffset = 0;
constexpr std::size_t kBias = 1;
constexpr std::size_t kDisturbance = 4;

/// The variance of the offset above which the update first takes the offset from the sample, so
/// the linearization starts near the solution.
constexpr double kRecenterVarianceDeg2 = 30.0 * 30.0;
/// The number of iterations of the iterated EKF.
constexpr int kIterations = 5;
/// The normalized innovation squared (3 degrees of freedom, 99.9 %) above which a magnetometer
/// sample gets less weight.
constexpr double kGate = 16.27;

State to_state(const HeadingFilter& f) {
  return {f.offset_deg,       f.bias_ut.x,        f.bias_ut.y,       f.bias_ut.z,
          f.disturbance_ut.x, f.disturbance_ut.y, f.disturbance_ut.z};
}

HeadingFilter to_filter(const State& x, const Matrix& p) {
  HeadingFilter f{
      .offset_deg = internal::wrap_360(x[kOffset]),
      .bias_ut = {.x = x[kBias], .y = x[kBias + 1], .z = x[kBias + 2]},
      .disturbance_ut = {.x = x[kDisturbance], .y = x[kDisturbance + 1], .z = x[kDisturbance + 2]},
      .covariance = p};
  // Keep the covariance exactly symmetric.
  for (std::size_t r = 0; r < kN; ++r) {
    for (std::size_t c = r + 1; c < kN; ++c) {
      const double mean = (p[r * kN + c] + p[c * kN + r]) / 2;
      f.covariance[r * kN + c] = mean;
      f.covariance[c * kN + r] = mean;
    }
  }
  return f;
}

void validate(const HeadingFilter& f) {
  require_finite(f.offset_deg, "offset_deg");
  for (const State x = to_state(f); const double v : x) {
    require_finite(v, "filter state");
  }
  for (const double v : f.covariance) {
    require_finite(v, "covariance");
  }
  for (std::size_t i = 0; i < kN; ++i) {
    require(f.covariance[i * kN + i] > 0, "a variance of the covariance is not positive");
  }
}

void validate(const HeadingFilterOptions& o) {
  require_finite(o.drift_deg2_per_s, "drift_deg2_per_s");
  require(o.drift_deg2_per_s >= 0, "drift_deg2_per_s is negative");
  require_finite(o.bias_drift_ut2_per_s, "bias_drift_ut2_per_s");
  require(o.bias_drift_ut2_per_s >= 0, "bias_drift_ut2_per_s is negative");
  internal::require_positive(o.disturbance_sigma_ut, "disturbance_sigma_ut");
  internal::require_positive(o.disturbance_time_s, "disturbance_time_s");
  internal::require_positive(o.noise_sigma_ut, "noise_sigma_ut");
}

/// Returns the inverse of the 3 x 3 matrix `m`, row by row.
Matrix3 inverse(const Matrix3& m) {
  const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7],
               i = m[8];
  const double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
  require(det != 0 && std::isfinite(det), "the innovation covariance is singular");
  return {(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
          (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
          (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det};
}

/// The Joseph form of the covariance update: (I - K H) P (I - K H)ᵀ + K R Kᵀ. `k` is kN x m and
/// `h` is m x kN, row by row. `r` is the variance of each of the m independent measurements.
template <std::size_t M>
Matrix joseph(const Matrix& p, const std::array<double, kN * M>& k,
              const std::array<double, M * kN>& h, const double r) {
  Matrix a{};  // I - K H
  for (std::size_t i = 0; i < kN; ++i) {
    for (std::size_t j = 0; j < kN; ++j) {
      double s = i == j ? 1 : 0;
      for (std::size_t l = 0; l < M; ++l) {
        s -= k[i * M + l] * h[l * kN + j];
      }
      a[i * kN + j] = s;
    }
  }
  Matrix ap{};
  for (std::size_t i = 0; i < kN; ++i) {
    for (std::size_t j = 0; j < kN; ++j) {
      for (std::size_t l = 0; l < kN; ++l) {
        ap[i * kN + j] += a[i * kN + l] * p[l * kN + j];
      }
    }
  }
  Matrix out{};
  for (std::size_t i = 0; i < kN; ++i) {
    for (std::size_t j = 0; j < kN; ++j) {
      double s = 0;
      for (std::size_t l = 0; l < kN; ++l) {
        s += ap[i * kN + l] * a[j * kN + l];
      }
      for (std::size_t l = 0; l < M; ++l) {
        s += r * k[i * M + l] * k[j * M + l];
      }
      out[i * kN + j] = s;
    }
  }
  return out;
}

/// The expected magnetometer sample for the state `x`, and its Jacobian.
struct Prediction {
  internal::Vec3 field_ut;
  Jacobian h;
};

Prediction predict_field(const State& x, const Quaternion& frame_to_device,
                         const internal::Vec3 earth_enu_ut) {
  const internal::Vec3 world =
      earth_enu_ut + internal::Vec3{x[kDisturbance], x[kDisturbance + 1], x[kDisturbance + 2]};
  // A true azimuth a is the azimuth a - offset in the frame.
  const auto to_device = [&](const internal::Vec3 enu) {
    return internal::rotate(frame_to_device, internal::rotate_azimuth(enu, -x[kOffset]));
  };
  const internal::Vec3 bias{x[kBias], x[kBias + 1], x[kBias + 2]};
  Prediction out{.field_ut = to_device(world) + bias, .h = {}};

  // The derivative by the offset in degrees: rotate_azimuth(v, -t) has the derivative
  // (-v.x sin t - v.y cos t, v.x cos t - v.y sin t, 0) by t in radians.
  const double s = internal::sin_deg(x[kOffset]);
  const double c = internal::cos_deg(x[kOffset]);
  const internal::Vec3 d_frame =
      kDegToRad * internal::Vec3{-world.x * s - world.y * c, world.x * c - world.y * s, 0};
  const internal::Vec3 d_offset = internal::rotate(frame_to_device, d_frame);
  const std::array<internal::Vec3, 3> d_disturbance{to_device({1, 0, 0}), to_device({0, 1, 0}),
                                                    to_device({0, 0, 1})};
  const auto set_row = [&](const std::size_t row, const double d_off, const double d0,
                           const double d1, const double d2) {
    out.h[row * kN + kOffset] = d_off;
    out.h[row * kN + kBias + row] = 1;
    out.h[row * kN + kDisturbance] = d0;
    out.h[row * kN + kDisturbance + 1] = d1;
    out.h[row * kN + kDisturbance + 2] = d2;
  };
  set_row(0, d_offset.x, d_disturbance[0].x, d_disturbance[1].x, d_disturbance[2].x);
  set_row(1, d_offset.y, d_disturbance[0].y, d_disturbance[1].y, d_disturbance[2].y);
  set_row(2, d_offset.z, d_disturbance[0].z, d_disturbance[1].z, d_disturbance[2].z);
  return out;
}

}  // namespace

HeadingFilter heading_filter_start(const Vector3 bias_ut, const double bias_sigma_ut,
                                   const HeadingFilterOptions& options) {
  require_finite(bias_ut.x, "bias_ut.x");
  require_finite(bias_ut.y, "bias_ut.y");
  require_finite(bias_ut.z, "bias_ut.z");
  internal::require_positive(bias_sigma_ut, "bias_sigma_ut");
  validate(options);
  HeadingFilter f{.offset_deg = 0, .bias_ut = bias_ut, .disturbance_ut = {}, .covariance = {}};
  const double dist = options.disturbance_sigma_ut * options.disturbance_sigma_ut;
  const std::array<double, kN> variances{kUnknownHeadingVarianceDeg2,
                                         bias_sigma_ut * bias_sigma_ut,
                                         bias_sigma_ut * bias_sigma_ut,
                                         bias_sigma_ut * bias_sigma_ut,
                                         dist,
                                         dist,
                                         dist};
  for (std::size_t i = 0; i < kN; ++i) {
    f.covariance[i * kN + i] = variances[i];
  }
  return f;
}

HeadingFilter heading_filter_predict(const HeadingFilter& filter, const double dt_s,
                                     const HeadingFilterOptions& options) {
  validate(filter);
  validate(options);
  require_finite(dt_s, "dt_s");
  require(dt_s >= 0, "dt_s is negative");

  State x = to_state(filter);
  Matrix p = filter.covariance;
  // The disturbance is a first-order Gauss-Markov process: it decays to 0, and its variance
  // goes to disturbance_sigma_ut².
  const double decay = std::exp(-dt_s / options.disturbance_time_s);
  std::array<double, kN> f{};
  f.fill(1);
  for (std::size_t i = kDisturbance; i < kN; ++i) {
    f[i] = decay;
    x[i] *= decay;
  }
  for (std::size_t r = 0; r < kN; ++r) {
    for (std::size_t c = 0; c < kN; ++c) {
      p[r * kN + c] *= f[r] * f[c];
    }
  }

  // The cap applies to the growth only, so a larger variance does not get smaller.
  const double offset_growth =
      std::clamp(kUnknownHeadingVarianceDeg2 - p[0], 0.0, options.drift_deg2_per_s * dt_s);
  p[kOffset * kN + kOffset] += offset_growth;
  for (std::size_t i = kBias; i < kDisturbance; ++i) {
    p[i * kN + i] += options.bias_drift_ut2_per_s * dt_s;
  }
  const double dist = options.disturbance_sigma_ut * options.disturbance_sigma_ut;
  for (std::size_t i = kDisturbance; i < kN; ++i) {
    p[i * kN + i] += dist * (1 - decay * decay);
  }
  return to_filter(x, p);
}

HeadingFilter heading_filter_update(const HeadingFilter& filter,
                                    const HeadingMeasurement& measurement) {
  validate(filter);
  require_finite(measurement.offset_deg, "offset_deg");
  require_finite(measurement.variance_deg2, "variance_deg2");
  require(measurement.variance_deg2 > 0, "variance_deg2 is not positive");

  State x = to_state(filter);
  const Matrix& p = filter.covariance;
  // H = (1, 0, ..., 0), so P Hᵀ is the first column and H P Hᵀ is the variance of the offset.
  const double s = p[0] + measurement.variance_deg2;
  const double innovation = internal::wrap_180(measurement.offset_deg - x[kOffset]);
  std::array<double, kN> k{};
  std::array<double, kN> h{};
  h[kOffset] = 1;
  for (std::size_t i = 0; i < kN; ++i) {
    k[i] = p[i * kN] / s;
    x[i] += k[i] * innovation;
  }
  return to_filter(x, joseph<1>(p, k, h, measurement.variance_deg2));
}

HeadingFilter heading_filter_update_magnetometer(const HeadingFilter& filter,
                                                 const Quaternion& device_to_frame,
                                                 const Vector3 field_ut, const Observer& observer,
                                                 const double unix_utc,
                                                 const HeadingFilterOptions& options) {
  validate(filter);
  validate(options);
  require_finite(field_ut.x, "field_ut.x");
  require_finite(field_ut.y, "field_ut.y");
  require_finite(field_ut.z, "field_ut.z");
  require_finite(unix_utc, "unix_utc");
  internal::validate(device_to_frame, "device_to_frame");
  internal::validate(observer);

  const internal::MagneticField wmm =
      internal::wmm_field(internal::wmm_coefficients(), observer.latitude_deg,
                          observer.longitude_deg, 0.0, internal::decimal_year(unix_utc));
  const internal::Vec3 earth{wmm.east_nt / 1000, wmm.north_nt / 1000, -wmm.down_nt / 1000};
  const Quaternion q = internal::normalized(device_to_frame);
  const Quaternion frame_to_device{q.w, -q.x, -q.y, -q.z};
  const internal::Vec3 z{field_ut.x, field_ut.y, field_ut.z};

  const State x0 = to_state(filter);
  const Matrix& p = filter.covariance;
  State xi = x0;
  if (p[0] > kRecenterVarianceDeg2) {
    // The offset is not known well, so the linearization starts at the offset of this sample:
    // the azimuth of the horizontal field without the bias, in the frame and in ENU.
    const internal::Vec3 bias{x0[kBias], x0[kBias + 1], x0[kBias + 2]};
    const internal::Vec3 m = internal::rotate(q, z - bias);
    const internal::Vec3 world =
        earth + internal::Vec3{x0[kDisturbance], x0[kDisturbance + 1], x0[kDisturbance + 2]};
    if (std::hypot(m.x, m.y) > 0) {
      xi[kOffset] = std::atan2(world.x, world.y) * kRadToDeg - std::atan2(m.x, m.y) * kRadToDeg;
    }
  }

  double r = options.noise_sigma_ut * options.noise_sigma_ut;
  std::array<double, kN * 3> k{};
  Jacobian h{};
  for (int iteration = 0; iteration < kIterations; ++iteration) {
    const Prediction pred = predict_field(xi, frame_to_device, earth);
    h = pred.h;
    // The innovation of the iterated EKF: z - h(xi) - H (x0 - xi).
    State dx{};
    for (std::size_t i = 0; i < kN; ++i) {
      dx[i] = x0[i] - xi[i];
    }
    dx[kOffset] = internal::wrap_180(dx[kOffset]);
    std::array<double, 3> y{z.x - pred.field_ut.x, z.y - pred.field_ut.y, z.z - pred.field_ut.z};
    for (std::size_t row = 0; row < 3; ++row) {
      for (std::size_t i = 0; i < kN; ++i) {
        y[row] -= h[row * kN + i] * dx[i];
      }
    }

    // P Hᵀ (kN x 3) and S = H P Hᵀ + R (3 x 3).
    std::array<double, kN * 3> pht{};
    for (std::size_t i = 0; i < kN; ++i) {
      for (std::size_t row = 0; row < 3; ++row) {
        for (std::size_t l = 0; l < kN; ++l) {
          pht[i * 3 + row] += p[i * kN + l] * h[row * kN + l];
        }
      }
    }
    const auto innovation_covariance = [&](const double noise) {
      Matrix3 s{};
      for (std::size_t a = 0; a < 3; ++a) {
        for (std::size_t b = 0; b < 3; ++b) {
          for (std::size_t l = 0; l < kN; ++l) {
            s[a * 3 + b] += h[a * kN + l] * pht[l * 3 + b];
          }
        }
        s[a * 3 + a] += noise;
      }
      return s;
    };
    Matrix3 s_inv = inverse(innovation_covariance(r));
    if (iteration == 0) {
      // A sample far from the expected field (for example near a magnet) gets less weight.
      double nis = 0;
      for (std::size_t a = 0; a < 3; ++a) {
        for (std::size_t b = 0; b < 3; ++b) {
          nis += y[a] * s_inv[a * 3 + b] * y[b];
        }
      }
      if (nis > kGate) {
        r *= nis / kGate;
        s_inv = inverse(innovation_covariance(r));
      }
    }
    k = {};
    for (std::size_t i = 0; i < kN; ++i) {
      for (std::size_t b = 0; b < 3; ++b) {
        for (std::size_t a = 0; a < 3; ++a) {
          k[i * 3 + b] += pht[i * 3 + a] * s_inv[a * 3 + b];
        }
      }
    }
    for (std::size_t i = 0; i < kN; ++i) {
      double step = 0;
      for (std::size_t row = 0; row < 3; ++row) {
        step += k[i * 3 + row] * y[row];
      }
      xi[i] = x0[i] + step;
    }
    // Keep the offset near the prior, so that dx stays small on the next iteration.
    xi[kOffset] = x0[kOffset] + internal::wrap_180(xi[kOffset] - x0[kOffset]);
  }
  return to_filter(xi, joseph<3>(p, k, h, r));
}

}  // namespace sunradar
