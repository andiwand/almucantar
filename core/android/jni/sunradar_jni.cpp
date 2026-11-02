// The JNI binding of the public API (src/sunradar/*.hpp) for the Kotlin class
// at.stefl.sunradar.core.SunradarNative. It must not include an internal header.
//
// Every value crosses JNI as a flat double array, so the binding needs no field access
// and a frame allocates only a few arrays. The layouts are the same as in Pack.kt:
//
//   Observer       [2]  latitude_deg, longitude_deg
//   Horizontal     [2]  azimuth_deg, altitude_deg
//   ScreenPoint    [2]  x, y
//   SunOptions     [4]  model, refraction, pressure_hpa, temperature_c
//   EventOptions   [4]  model, altitude_deg, search_step_s, tolerance_s
//   PathOptions    [5]  model, step_s, refraction, pressure_hpa, temperature_c
//   SearchOptions  [7]  model, step_s, tolerance_s, refraction, pressure_hpa,
//                       temperature_c, max_separation_deg
//   Camera        [11]  width, height, fx, fy, cx, cy, k1, k2, k3, p1, p2
//   View          [17]  w, x, y, z, screen_rotation, heading_offset_deg, Camera[11]
//   line         [2 n]  azimuth_deg, altitude_deg for each point
//   Quaternion     [4]  w, x, y, z
//   Vector3        [3]  x, y, z
//   HeadingEstimate, HeadingMeasurement [2]  offset_deg, variance_deg2
//   MagnetometerOptions [1]  sensor_sigma_deg
//   HeadingFilter [55]  offset_deg, bias_ut[3], disturbance_ut[3], covariance[49]
//   HeadingFilterOptions [5]  drift_deg2_per_s, bias_drift_ut2_per_s, disturbance_sigma_ut,
//                       disturbance_time_s, noise_sigma_ut
//   MoonOptions    [3]  refraction, pressure_hpa, temperature_c
//   AzimuthCrossing [3] unix_utc, azimuth_deg, altitude_deg (results, for each crossing)
//   ElevationGrid  [6]  north_latitude_deg, west_longitude_deg, latitude_step_deg,
//                       longitude_step_deg, rows, columns; the heights are a separate
//                       float array (row by row), because a tile has more than a million.
//   HorizonOptions [6]  azimuth_step_deg, max_distance_m, eye_height_m, use_grid_elevation,
//                       observer_elevation_m, refraction_coefficient
//   HorizonProfile [3 + 2 n]  azimuth_step_deg, observer_elevation_m, n, then
//                       altitude_deg[n], then distance_m[n]
//   TerrainEventOptions [7]  model, refraction, pressure_hpa, temperature_c, limb_deg,
//                       search_step_s, tolerance_s
//
// Results:
//   DayEvents      kind, culmination_utc, culmination_altitude_deg, n,
//                  then unix_utc, rising for each crossing
//   path           unix_utc, azimuth_deg, altitude_deg for each point
//   Projection [3] valid, x, y
//   polylines      count, then for each polyline: n, then x, y for each point
//   TimeMatch  [4] unix_utc, separation_deg, azimuth_deg, altitude_deg
//   ElevationTile [3] latitude_deg, longitude_deg, distance_m (for each tile)
//   TerrainEvents  kind, n, then unix_utc, rising for each crossing
//
// Booleans are 0 or 1, and enums are their ordinals. std::invalid_argument becomes
// IllegalArgumentException.

#include <jni.h>

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <stdexcept>
#include <string>
#include <vector>

#include "sunradar/sunradar.hpp"

namespace {

using namespace sunradar;

/// A read-only copy of a Java double array.
class Doubles {
 public:
  Doubles(JNIEnv* env, jdoubleArray array, const std::size_t expected, const char* name) {
    if (array == nullptr) {
      throw std::invalid_argument(std::string(name) + " is null");
    }
    const auto length = static_cast<std::size_t>(env->GetArrayLength(array));
    if (expected != 0 && length != expected) {
      throw std::invalid_argument(std::string(name) + " has the wrong length");
    }
    values_.resize(length);
    env->GetDoubleArrayRegion(array, 0, static_cast<jsize>(length), values_.data());
  }

  [[nodiscard]] double operator[](const std::size_t i) const { return values_.at(i); }
  [[nodiscard]] std::size_t size() const { return values_.size(); }

 private:
  std::vector<double> values_;
};

jdoubleArray to_java(JNIEnv* env, const std::vector<double>& values) {
  jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(values.size()));
  if (result != nullptr) {
    env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
  }
  return result;
}

template <typename Enum>
Enum to_enum(const double value, const char* name) {
  // NaN fails the last comparison, because NaN is not equal to itself.
  if (value < 0 || value > 255 || std::floor(value) != value) {
    throw std::invalid_argument(std::string(name) + " is not a valid ordinal");
  }
  return static_cast<Enum>(static_cast<std::uint8_t>(value));
}

bool to_bool(const double value) { return value != 0; }

Observer observer(const Doubles& a) {
  return Observer{.latitude_deg = a[0], .longitude_deg = a[1]};
}

Horizontal horizontal(const Doubles& a) {
  return Horizontal{.azimuth_deg = a[0], .altitude_deg = a[1]};
}

Camera camera(const Doubles& a, const std::size_t o) {
  return Camera{.width = a[o],
                .height = a[o + 1],
                .fx = a[o + 2],
                .fy = a[o + 3],
                .cx = a[o + 4],
                .cy = a[o + 5],
                .k1 = a[o + 6],
                .k2 = a[o + 7],
                .k3 = a[o + 8],
                .p1 = a[o + 9],
                .p2 = a[o + 10]};
}

constexpr std::size_t kFilterLength = 7 + kHeadingFilterSize * kHeadingFilterSize;

HeadingFilter heading_filter(const Doubles& a) {
  HeadingFilter f{.offset_deg = a[0],
                  .bias_ut = {.x = a[1], .y = a[2], .z = a[3]},
                  .disturbance_ut = {.x = a[4], .y = a[5], .z = a[6]},
                  .covariance = {}};
  for (std::size_t i = 0; i < f.covariance.size(); ++i) {
    f.covariance[i] = a[7 + i];
  }
  return f;
}

std::vector<double> pack(const HeadingFilter& f) {
  std::vector<double> out{f.offset_deg,       f.bias_ut.x,        f.bias_ut.y,       f.bias_ut.z,
                          f.disturbance_ut.x, f.disturbance_ut.y, f.disturbance_ut.z};
  out.insert(out.end(), f.covariance.begin(), f.covariance.end());
  return out;
}

HeadingFilterOptions heading_filter_options(const Doubles& a) {
  return {.drift_deg2_per_s = a[0],
          .bias_drift_ut2_per_s = a[1],
          .disturbance_sigma_ut = a[2],
          .disturbance_time_s = a[3],
          .noise_sigma_ut = a[4]};
}

View view(const Doubles& a) {
  return View{.device_to_enu = {.w = a[0], .x = a[1], .y = a[2], .z = a[3]},
              .screen_rotation = to_enum<ScreenRotation>(a[4], "screen_rotation"),
              .heading_offset_deg = a[5],
              .camera = camera(a, 6)};
}

std::vector<Horizontal> line(const Doubles& a) {
  if (a.size() % 2 != 0) {
    throw std::invalid_argument("line has an odd length");
  }
  std::vector<Horizontal> result;
  result.reserve(a.size() / 2);
  for (std::size_t i = 0; i < a.size(); i += 2) {
    result.push_back({.azimuth_deg = a[i], .altitude_deg = a[i + 1]});
  }
  return result;
}

std::vector<double> pack(const std::vector<Polyline>& polylines) {
  std::vector<double> out{static_cast<double>(polylines.size())};
  for (const Polyline& p : polylines) {
    out.push_back(static_cast<double>(p.size()));
    for (const ScreenPoint& s : p) {
      out.push_back(s.x);
      out.push_back(s.y);
    }
  }
  return out;
}

std::vector<double> pack(const TimeMatch& m) {
  return {m.unix_utc, m.separation_deg, m.sun.azimuth_deg, m.sun.altitude_deg};
}

/// Runs `body` and converts C++ exceptions to Java exceptions.
template <typename T, typename F>
T guarded(JNIEnv* env, const T failure, F&& body) noexcept {
  try {
    return body();
  } catch (const std::invalid_argument& e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), e.what());
  } catch (const std::exception& e) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
  } catch (...) {
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "unknown C++ exception");
  }
  return failure;
}

}  // namespace

#define SUNRADAR_JNI(name) \
  extern "C" JNIEXPORT auto JNICALL Java_at_stefl_sunradar_core_SunradarNative_##name

SUNRADAR_JNI(version)(JNIEnv* env, jclass)->jstring {
  return guarded<jstring>(
      env, nullptr, [&] { return env->NewStringUTF(std::string(sunradar::version()).c_str()); });
}

SUNRADAR_JNI(sunPosition)
(JNIEnv* env, jclass, jdouble unix_utc, jdoubleArray obs, jdoubleArray opts)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles o(env, opts, 4, "options");
    const Horizontal h =
        sun_position(unix_utc, observer(Doubles(env, obs, 2, "observer")),
                     {.model = to_enum<SolarModel>(o[0], "model"),
                      .refraction = to_bool(o[1]),
                      .atmosphere = {.pressure_hpa = o[2], .temperature_c = o[3]}});
    return to_java(env, {h.azimuth_deg, h.altitude_deg});
  });
}

SUNRADAR_JNI(sunEvents)
(JNIEnv* env, jclass, jdoubleArray obs, jdouble start, jdouble end, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles o(env, opts, 4, "options");
    const DayEvents e = sun_events(observer(Doubles(env, obs, 2, "observer")), start, end,
                                   {.model = to_enum<SolarModel>(o[0], "model"),
                                    .altitude_deg = o[1],
                                    .search_step_s = o[2],
                                    .tolerance_s = o[3]});
    std::vector<double> out{static_cast<double>(e.kind), e.culmination_utc,
                            e.culmination_altitude_deg, static_cast<double>(e.crossings.size())};
    for (const Crossing& c : e.crossings) {
      out.push_back(c.unix_utc);
      out.push_back(c.rising ? 1 : 0);
    }
    return to_java(env, out);
  });
}

SUNRADAR_JNI(sunPath)
(JNIEnv* env, jclass, jdoubleArray obs, jdouble start, jdouble end, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles o(env, opts, 5, "options");
    const auto path = sun_path(observer(Doubles(env, obs, 2, "observer")), start, end,
                               {.model = to_enum<SolarModel>(o[0], "model"),
                                .step_s = o[1],
                                .refraction = to_bool(o[2]),
                                .atmosphere = {.pressure_hpa = o[3], .temperature_c = o[4]}});
    std::vector<double> out;
    out.reserve(3 * path.size());
    for (const PathPoint& p : path) {
      out.push_back(p.unix_utc);
      out.push_back(p.position.azimuth_deg);
      out.push_back(p.position.altitude_deg);
    }
    return to_java(env, out);
  });
}

SUNRADAR_JNI(magneticDeclinationDeg)
(JNIEnv* env, jclass, jdoubleArray obs, jdouble unix_utc)->jdouble {
  return guarded<jdouble>(env, 0.0, [&] {
    return magnetic_declination_deg(observer(Doubles(env, obs, 2, "observer")), unix_utc);
  });
}

SUNRADAR_JNI(cameraFromFov)
(JNIEnv* env, jclass, jdouble width, jdouble height, jdouble fov)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Camera c = camera_from_fov(width, height, fov);
    return to_java(env, {c.width, c.height, c.fx, c.fy, c.cx, c.cy, c.k1, c.k2, c.k3, c.p1, c.p2});
  });
}

SUNRADAR_JNI(project)
(JNIEnv* env, jclass, jdoubleArray v, jdoubleArray direction)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Projection p = project(view(Doubles(env, v, 17, "view")),
                                 horizontal(Doubles(env, direction, 2, "direction")));
    return to_java(env, {p.valid ? 1.0 : 0.0, p.point.x, p.point.y});
  });
}

SUNRADAR_JNI(unproject)
(JNIEnv* env, jclass, jdoubleArray v, jdoubleArray point)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles p(env, point, 2, "point");
    const Horizontal h = unproject(view(Doubles(env, v, 17, "view")), {.x = p[0], .y = p[1]});
    return to_java(env, {h.azimuth_deg, h.altitude_deg});
  });
}

SUNRADAR_JNI(projectLine)
(JNIEnv* env, jclass, jdoubleArray v, jdoubleArray directions)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    return to_java(env, pack(project_line(view(Doubles(env, v, 17, "view")),
                                          line(Doubles(env, directions, 0, "line")))));
  });
}

SUNRADAR_JNI(horizonLine)(JNIEnv* env, jclass, jdoubleArray v)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    return to_java(env, pack(horizon_line(view(Doubles(env, v, 17, "view")))));
  });
}

namespace {

SearchOptions search_options(const Doubles& o) {
  return {.model = to_enum<SolarModel>(o[0], "model"),
          .step_s = o[1],
          .tolerance_s = o[2],
          .refraction = to_bool(o[3]),
          .atmosphere = {.pressure_hpa = o[4], .temperature_c = o[5]},
          .max_separation_deg = o[6]};
}

}  // namespace

SUNRADAR_JNI(findTime)
(JNIEnv* env, jclass, jdoubleArray obs, jdoubleArray direction, jdouble start, jdouble end,
 jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    return to_java(env, pack(find_time(observer(Doubles(env, obs, 2, "observer")),
                                       horizontal(Doubles(env, direction, 2, "direction")), start,
                                       end, search_options(Doubles(env, opts, 7, "options")))));
  });
}

SUNRADAR_JNI(findDates)
(JNIEnv* env, jclass, jdoubleArray obs, jdoubleArray direction, jdouble start, jdouble end,
 jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const auto matches = find_dates(observer(Doubles(env, obs, 2, "observer")),
                                    horizontal(Doubles(env, direction, 2, "direction")), start, end,
                                    search_options(Doubles(env, opts, 7, "options")));
    std::vector<double> out;
    for (const TimeMatch& m : matches) {
      const auto packed = pack(m);
      out.insert(out.end(), packed.begin(), packed.end());
    }
    return to_java(env, out);
  });
}

SUNRADAR_JNI(headingPredict)
(JNIEnv* env, jclass, jdoubleArray estimate, jdouble dt_s, jdouble drift)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles e(env, estimate, 2, "estimate");
    const HeadingEstimate r =
        heading_predict({.offset_deg = e[0], .variance_deg2 = e[1]}, dt_s, drift);
    return to_java(env, {r.offset_deg, r.variance_deg2});
  });
}

SUNRADAR_JNI(headingUpdate)
(JNIEnv* env, jclass, jdoubleArray estimate, jdoubleArray measurement)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles e(env, estimate, 2, "estimate");
    const Doubles m(env, measurement, 2, "measurement");
    const HeadingEstimate r = heading_update({.offset_deg = e[0], .variance_deg2 = e[1]},
                                             {.offset_deg = m[0], .variance_deg2 = m[1]});
    return to_java(env, {r.offset_deg, r.variance_deg2});
  });
}

SUNRADAR_JNI(headingFromMagnetometer)
(JNIEnv* env, jclass, jdoubleArray quaternion, jdoubleArray field, jdoubleArray obs,
 jdouble unix_utc, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles q(env, quaternion, 4, "quaternion");
    const Doubles f(env, field, 3, "field");
    const Doubles o(env, opts, 1, "options");
    const HeadingMeasurement r = heading_from_magnetometer(
        {.w = q[0], .x = q[1], .y = q[2], .z = q[3]}, {.x = f[0], .y = f[1], .z = f[2]},
        observer(Doubles(env, obs, 2, "observer")), unix_utc, {.sensor_sigma_deg = o[0]});
    return to_java(env, {r.offset_deg, r.variance_deg2});
  });
}

SUNRADAR_JNI(headingFromDirection)
(JNIEnv* env, jclass, jdoubleArray v, jdoubleArray point, jdouble true_azimuth_deg,
 jdouble sigma_deg)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles p(env, point, 2, "point");
    const HeadingMeasurement r = heading_from_direction(
        view(Doubles(env, v, 17, "view")), {.x = p[0], .y = p[1]}, true_azimuth_deg, sigma_deg);
    return to_java(env, {r.offset_deg, r.variance_deg2});
  });
}

SUNRADAR_JNI(headingFilterStart)
(JNIEnv* env, jclass, jdoubleArray bias, jdouble bias_sigma_ut, jdoubleArray opts)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles b(env, bias, 3, "bias");
    return to_java(
        env, pack(heading_filter_start({.x = b[0], .y = b[1], .z = b[2]}, bias_sigma_ut,
                                       heading_filter_options(Doubles(env, opts, 5, "options")))));
  });
}

SUNRADAR_JNI(headingFilterPredict)
(JNIEnv* env, jclass, jdoubleArray filter, jdouble dt_s, jdoubleArray opts)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    return to_java(env, pack(heading_filter_predict(
                            heading_filter(Doubles(env, filter, kFilterLength, "filter")), dt_s,
                            heading_filter_options(Doubles(env, opts, 5, "options")))));
  });
}

SUNRADAR_JNI(headingFilterUpdate)
(JNIEnv* env, jclass, jdoubleArray filter, jdoubleArray measurement)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles m(env, measurement, 2, "measurement");
    return to_java(env, pack(heading_filter_update(
                            heading_filter(Doubles(env, filter, kFilterLength, "filter")),
                            {.offset_deg = m[0], .variance_deg2 = m[1]})));
  });
}

SUNRADAR_JNI(headingFilterUpdateMagnetometer)
(JNIEnv* env, jclass, jdoubleArray filter, jdoubleArray quaternion, jdoubleArray field,
 jdoubleArray obs, jdouble unix_utc, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles q(env, quaternion, 4, "quaternion");
    const Doubles f(env, field, 3, "field");
    return to_java(
        env, pack(heading_filter_update_magnetometer(
                 heading_filter(Doubles(env, filter, kFilterLength, "filter")),
                 {.w = q[0], .x = q[1], .y = q[2], .z = q[3]}, {.x = f[0], .y = f[1], .z = f[2]},
                 observer(Doubles(env, obs, 2, "observer")), unix_utc,
                 heading_filter_options(Doubles(env, opts, 5, "options")))));
  });
}

SUNRADAR_JNI(findAzimuthTimes)
(JNIEnv* env, jclass, jdoubleArray obs, jdouble azimuth_deg, jdouble start, jdouble end,
 jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const auto crossings =
        find_azimuth_times(observer(Doubles(env, obs, 2, "observer")), azimuth_deg, start, end,
                           search_options(Doubles(env, opts, 7, "options")));
    std::vector<double> out;
    for (const AzimuthCrossing& c : crossings) {
      out.push_back(c.unix_utc);
      out.push_back(c.sun.azimuth_deg);
      out.push_back(c.sun.altitude_deg);
    }
    return to_java(env, out);
  });
}

SUNRADAR_JNI(moonPosition)
(JNIEnv* env, jclass, jdouble unix_utc, jdoubleArray obs, jdoubleArray opts)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles o(env, opts, 3, "options");
    const Horizontal h = moon_position(
        unix_utc, observer(Doubles(env, obs, 2, "observer")),
        {.refraction = to_bool(o[0]), .atmosphere = {.pressure_hpa = o[1], .temperature_c = o[2]}});
    return to_java(env, {h.azimuth_deg, h.altitude_deg});
  });
}

SUNRADAR_JNI(moonIlluminatedFraction)(JNIEnv* env, jclass, jdouble unix_utc)->jdouble {
  return guarded<jdouble>(env, 0.0, [&] { return moon_illuminated_fraction(unix_utc); });
}

namespace {

int to_int(const double value, const char* name) {
  // NaN fails the last comparison, because NaN is not equal to itself.
  if (value < 0 || value > 1e9 || std::floor(value) != value) {
    throw std::invalid_argument(std::string(name) + " is not a valid count");
  }
  return static_cast<int>(value);
}

std::vector<double> pack(const HorizonProfile& p) {
  std::vector<double> out{p.azimuth_step_deg, p.observer_elevation_m,
                          static_cast<double>(p.altitude_deg.size())};
  out.insert(out.end(), p.altitude_deg.begin(), p.altitude_deg.end());
  out.insert(out.end(), p.distance_m.begin(), p.distance_m.end());
  return out;
}

HorizonProfile unpack_profile(const Doubles& a) {
  if (a.size() < 3) {
    throw std::invalid_argument("profile is too short");
  }
  const auto n = static_cast<std::size_t>(to_int(a[2], "profile size"));
  if (a.size() != 3 + (2 * n)) {
    throw std::invalid_argument("profile has the wrong length");
  }
  HorizonProfile p{.azimuth_step_deg = a[0],
                   .altitude_deg = std::vector<double>(n),
                   .distance_m = std::vector<double>(n),
                   .observer_elevation_m = a[1]};
  for (std::size_t i = 0; i < n; ++i) {
    p.altitude_deg[i] = a[3 + i];
    p.distance_m[i] = a[3 + n + i];
  }
  return p;
}

HorizonOptions horizon_options(const Doubles& o) {
  return {.azimuth_step_deg = o[0],
          .max_distance_m = o[1],
          .eye_height_m = o[2],
          .use_grid_elevation = to_bool(o[3]),
          .observer_elevation_m = o[4],
          .refraction_coefficient = o[5]};
}

TerrainEventOptions terrain_event_options(const Doubles& o) {
  return {.model = to_enum<SolarModel>(o[0], "model"),
          .refraction = to_bool(o[1]),
          .atmosphere = {.pressure_hpa = o[2], .temperature_c = o[3]},
          .limb_deg = o[4],
          .search_step_s = o[5],
          .tolerance_s = o[6]};
}

}  // namespace

// Returns the heights and writes the 6 other values of the grid into `header`.
SUNRADAR_JNI(readElevationGeotiff)
(JNIEnv* env, jclass, jbyteArray data, jint level, jdoubleArray header)->jfloatArray {
  return guarded<jfloatArray>(env, nullptr, [&]() -> jfloatArray {
    if (data == nullptr || header == nullptr) {
      throw std::invalid_argument("data is null");
    }
    if (env->GetArrayLength(header) != 6) {
      throw std::invalid_argument("header has the wrong length");
    }
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(env->GetArrayLength(data)));
    env->GetByteArrayRegion(data, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<jbyte*>(bytes.data()));
    const ElevationGrid grid = read_elevation_geotiff(bytes, level);
    const std::array<double, 6> h{
        grid.north_latitude_deg, grid.west_longitude_deg,        grid.latitude_step_deg,
        grid.longitude_step_deg, static_cast<double>(grid.rows), static_cast<double>(grid.columns)};
    env->SetDoubleArrayRegion(header, 0, 6, h.data());
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(grid.heights_m.size()));
    if (result != nullptr) {
      env->SetFloatArrayRegion(result, 0, static_cast<jsize>(grid.heights_m.size()),
                               grid.heights_m.data());
    }
    return result;
  });
}

SUNRADAR_JNI(elevationTiles)
(JNIEnv* env, jclass, jdoubleArray obs, jdouble max_distance_m)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const auto tiles = elevation_tiles(observer(Doubles(env, obs, 2, "observer")), max_distance_m);
    std::vector<double> out;
    out.reserve(3 * tiles.size());
    for (const ElevationTile& t : tiles) {
      out.push_back(t.latitude_deg);
      out.push_back(t.longitude_deg);
      out.push_back(t.distance_m);
    }
    return to_java(env, out);
  });
}

SUNRADAR_JNI(horizonProfile)
(JNIEnv* env, jclass, jdoubleArray obs, jdoubleArray headers, jobjectArray heights,
 jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles h(env, headers, 0, "headers");
    if (heights == nullptr) {
      throw std::invalid_argument("heights is null");
    }
    const auto count = static_cast<std::size_t>(env->GetArrayLength(heights));
    if (h.size() != 6 * count) {
      throw std::invalid_argument("headers has the wrong length");
    }
    std::vector<ElevationGrid> tiles(count);
    for (std::size_t i = 0; i < count; ++i) {
      ElevationGrid& g = tiles[i];
      g.north_latitude_deg = h[6 * i];
      g.west_longitude_deg = h[(6 * i) + 1];
      g.latitude_step_deg = h[(6 * i) + 2];
      g.longitude_step_deg = h[(6 * i) + 3];
      g.rows = to_int(h[(6 * i) + 4], "rows");
      g.columns = to_int(h[(6 * i) + 5], "columns");
      auto* const array =
          static_cast<jfloatArray>(env->GetObjectArrayElement(heights, static_cast<jsize>(i)));
      if (array == nullptr) {
        throw std::invalid_argument("heights_m is null");
      }
      g.heights_m.resize(static_cast<std::size_t>(env->GetArrayLength(array)));
      env->GetFloatArrayRegion(array, 0, static_cast<jsize>(g.heights_m.size()),
                               g.heights_m.data());
      env->DeleteLocalRef(array);
    }
    return to_java(env, pack(horizon_profile(observer(Doubles(env, obs, 2, "observer")), tiles,
                                             horizon_options(Doubles(env, opts, 6, "options")))));
  });
}

SUNRADAR_JNI(terrainAltitudeDeg)
(JNIEnv* env, jclass, jdoubleArray profile, jdouble azimuth_deg)->jdouble {
  return guarded<jdouble>(env, 0.0, [&] {
    return terrain_altitude_deg(unpack_profile(Doubles(env, profile, 0, "profile")), azimuth_deg);
  });
}

SUNRADAR_JNI(terrainEvents)
(JNIEnv* env, jclass, jdoubleArray obs, jdoubleArray profile, jdouble start, jdouble end,
 jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const TerrainEvents e =
        terrain_events(observer(Doubles(env, obs, 2, "observer")),
                       unpack_profile(Doubles(env, profile, 0, "profile")), start, end,
                       terrain_event_options(Doubles(env, opts, 7, "options")));
    std::vector<double> out{static_cast<double>(e.kind), static_cast<double>(e.crossings.size())};
    for (const Crossing& c : e.crossings) {
      out.push_back(c.unix_utc);
      out.push_back(c.rising ? 1 : 0);
    }
    return to_java(env, out);
  });
}

// The C++ defaults of the option structs, Camera, View, and the heading filter, in the layouts
// above, so the instrumented tests can check that the Kotlin defaults are the same.
SUNRADAR_JNI(defaults)(JNIEnv* env, jclass)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const SunOptions s;
    const EventOptions e;
    const PathOptions p;
    const SearchOptions q;
    const Camera c;
    const View v;
    const HeadingFilterOptions f;
    const HorizonOptions h;
    const TerrainEventOptions t;
    return to_java(env, {static_cast<double>(s.model),
                         s.refraction ? 1.0 : 0.0,
                         s.atmosphere.pressure_hpa,
                         s.atmosphere.temperature_c,
                         static_cast<double>(e.model),
                         e.altitude_deg,
                         e.search_step_s,
                         e.tolerance_s,
                         static_cast<double>(p.model),
                         p.step_s,
                         p.refraction ? 1.0 : 0.0,
                         p.atmosphere.pressure_hpa,
                         p.atmosphere.temperature_c,
                         static_cast<double>(q.model),
                         q.step_s,
                         q.tolerance_s,
                         q.refraction ? 1.0 : 0.0,
                         q.atmosphere.pressure_hpa,
                         q.atmosphere.temperature_c,
                         q.max_separation_deg,
                         c.width,
                         c.height,
                         c.fx,
                         c.fy,
                         c.cx,
                         c.cy,
                         c.k1,
                         c.k2,
                         c.k3,
                         c.p1,
                         c.p2,
                         v.device_to_enu.w,
                         v.device_to_enu.x,
                         v.device_to_enu.y,
                         v.device_to_enu.z,
                         static_cast<double>(v.screen_rotation),
                         v.heading_offset_deg,
                         MagnetometerOptions{}.sensor_sigma_deg,
                         HeadingEstimate{}.offset_deg,
                         HeadingEstimate{}.variance_deg2,
                         MoonOptions{}.refraction ? 1.0 : 0.0,
                         MoonOptions{}.atmosphere.pressure_hpa,
                         MoonOptions{}.atmosphere.temperature_c,
                         kDefaultHeadingDriftDeg2PerS,
                         f.drift_deg2_per_s,
                         f.bias_drift_ut2_per_s,
                         f.disturbance_sigma_ut,
                         f.disturbance_time_s,
                         f.noise_sigma_ut,
                         h.azimuth_step_deg,
                         h.max_distance_m,
                         h.eye_height_m,
                         h.use_grid_elevation ? 1.0 : 0.0,
                         h.observer_elevation_m,
                         h.refraction_coefficient,
                         static_cast<double>(t.model),
                         t.refraction ? 1.0 : 0.0,
                         t.atmosphere.pressure_hpa,
                         t.atmosphere.temperature_c,
                         t.limb_deg,
                         t.search_step_s,
                         t.tolerance_s});
  });
}
