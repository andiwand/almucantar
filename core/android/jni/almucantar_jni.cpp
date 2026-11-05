// The JNI binding of the public API (src/almucantar/*.hpp) for the Kotlin class
// at.stefl.almucantar.core.AlmucantarNative. It must not include an internal header.
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
//   HorizonOptions [7]  azimuth_step_deg, max_distance_m, eye_height_m, use_grid_elevation,
//                       observer_elevation_m, refraction_coefficient, min_distance_m
//   HorizonProfile [3 + 2 n]  azimuth_step_deg, observer_elevation_m, n, then
//                       altitude_deg[n], then distance_m[n]
//   TerrainEventOptions [8]  model, refraction, pressure_hpa, temperature_c, limb_deg,
//                       search_step_s, tolerance_s, body
//   SkyOptions     [3]  refraction, pressure_hpa, temperature_c
//   StarCatalog         four separate arrays: ra_deg, dec_deg, pm_ra_arcsec_per_year,
//                       pm_dec_arcsec_per_year (the last two can be empty)
//   tiles               the headers of all grids in one array (6 values for each grid, as
//                       ElevationGrid), and the heights in an array of float arrays
//   BodyOptions    [4]  model, refraction, pressure_hpa, temperature_c
//   Site           [3]  latitude_deg, longitude_deg, elevation_m
//   SightOptions   [2]  refraction_coefficient, target_margin_m
//   AlignmentOptions [6]  BodyOptions[4], max_separation_deg, tolerance_s
//   AlignmentLineOptions [9]  BodyOptions[4], min_distance_m, max_distance_m, step_m,
//                       eye_height_m, refraction_coefficient
//   DarkOptions    [7]  max_sun_altitude_deg, max_moon_altitude_deg, max_moon_fraction,
//                       galactic_center, min_galactic_center_altitude_deg, step_s, tolerance_s
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
//   points  [2 n]  x, y for each direction of projectPoints; NaN if it is not valid
//   Sight      [3] azimuth_deg, altitude_deg, distance_m
//   Alignment  [4] unix_utc, separation_deg, azimuth_deg, altitude_deg (for each alignment)
//   LinePoint  [7] latitude_deg, longitude_deg, distance_m, eye_elevation_m,
//                  ground_elevation_m, azimuth_deg, altitude_deg
//   AlignmentLine  n_points, n_spots, then LinePoint[7] for each point, then for each spot
//   LineOfSight [5] visible, clearance_deg, blocking_distance_m, latitude_deg, longitude_deg
//   TimeWindow [2] start_utc, end_utc (for each window)
//   LunarEclipse [9] kind, peak_utc, obscuration, penumbral_begin_utc, partial_begin_utc,
//                  total_begin_utc, total_end_utc, partial_end_utc, penumbral_end_utc
//   SolarEclipse [5] kind, peak_utc, latitude_deg, longitude_deg, obscuration
//   LocalSolarEclipse [8] kind, obscuration, partial_begin_utc, total_begin_utc, peak_utc,
//                  total_end_utc, partial_end_utc, peak_sun_altitude_deg
//   ShadowPoint [4] unix_utc, latitude_deg, longitude_deg, umbra_radius_km
//
// A list of results is the records one after the other, without a count.
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
#include <utility>
#include <vector>

#include "almucantar/almucantar.hpp"

namespace {

using namespace almucantar;

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
  /// Moves the values out, for a column of a catalogue.
  [[nodiscard]] std::vector<double> take() { return std::move(values_); }

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

#define ALMUCANTAR_JNI(name) \
  extern "C" JNIEXPORT auto JNICALL Java_at_stefl_almucantar_core_AlmucantarNative_##name

ALMUCANTAR_JNI(version)(JNIEnv* env, jclass)->jstring {
  return guarded<jstring>(
      env, nullptr, [&] { return env->NewStringUTF(std::string(almucantar::version()).c_str()); });
}

ALMUCANTAR_JNI(sunPosition)
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

ALMUCANTAR_JNI(sunEvents)
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

ALMUCANTAR_JNI(sunPath)
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

ALMUCANTAR_JNI(magneticDeclinationDeg)
(JNIEnv* env, jclass, jdoubleArray obs, jdouble unix_utc)->jdouble {
  return guarded<jdouble>(env, 0.0, [&] {
    return magnetic_declination_deg(observer(Doubles(env, obs, 2, "observer")), unix_utc);
  });
}

ALMUCANTAR_JNI(cameraFromFov)
(JNIEnv* env, jclass, jdouble width, jdouble height, jdouble fov)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Camera c = camera_from_fov(width, height, fov);
    return to_java(env, {c.width, c.height, c.fx, c.fy, c.cx, c.cy, c.k1, c.k2, c.k3, c.p1, c.p2});
  });
}

ALMUCANTAR_JNI(project)
(JNIEnv* env, jclass, jdoubleArray v, jdoubleArray direction)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Projection p = project(view(Doubles(env, v, 17, "view")),
                                 horizontal(Doubles(env, direction, 2, "direction")));
    return to_java(env, {p.valid ? 1.0 : 0.0, p.point.x, p.point.y});
  });
}

ALMUCANTAR_JNI(unproject)
(JNIEnv* env, jclass, jdoubleArray v, jdoubleArray point)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles p(env, point, 2, "point");
    const Horizontal h = unproject(view(Doubles(env, v, 17, "view")), {.x = p[0], .y = p[1]});
    return to_java(env, {h.azimuth_deg, h.altitude_deg});
  });
}

ALMUCANTAR_JNI(projectLine)
(JNIEnv* env, jclass, jdoubleArray v, jdoubleArray directions)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    return to_java(env, pack(project_line(view(Doubles(env, v, 17, "view")),
                                          line(Doubles(env, directions, 0, "line")))));
  });
}

ALMUCANTAR_JNI(horizonLine)(JNIEnv* env, jclass, jdoubleArray v)->jdoubleArray {
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

ALMUCANTAR_JNI(findTime)
(JNIEnv* env, jclass, jdoubleArray obs, jdoubleArray direction, jdouble start, jdouble end,
 jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    return to_java(env, pack(find_time(observer(Doubles(env, obs, 2, "observer")),
                                       horizontal(Doubles(env, direction, 2, "direction")), start,
                                       end, search_options(Doubles(env, opts, 7, "options")))));
  });
}

ALMUCANTAR_JNI(findDates)
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

ALMUCANTAR_JNI(headingPredict)
(JNIEnv* env, jclass, jdoubleArray estimate, jdouble dt_s, jdouble drift)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles e(env, estimate, 2, "estimate");
    const HeadingEstimate r =
        heading_predict({.offset_deg = e[0], .variance_deg2 = e[1]}, dt_s, drift);
    return to_java(env, {r.offset_deg, r.variance_deg2});
  });
}

ALMUCANTAR_JNI(headingUpdate)
(JNIEnv* env, jclass, jdoubleArray estimate, jdoubleArray measurement)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles e(env, estimate, 2, "estimate");
    const Doubles m(env, measurement, 2, "measurement");
    const HeadingEstimate r = heading_update({.offset_deg = e[0], .variance_deg2 = e[1]},
                                             {.offset_deg = m[0], .variance_deg2 = m[1]});
    return to_java(env, {r.offset_deg, r.variance_deg2});
  });
}

ALMUCANTAR_JNI(headingFromMagnetometer)
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

ALMUCANTAR_JNI(headingFromDirection)
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

ALMUCANTAR_JNI(headingFilterStart)
(JNIEnv* env, jclass, jdoubleArray bias, jdouble bias_sigma_ut, jdoubleArray opts)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles b(env, bias, 3, "bias");
    return to_java(
        env, pack(heading_filter_start({.x = b[0], .y = b[1], .z = b[2]}, bias_sigma_ut,
                                       heading_filter_options(Doubles(env, opts, 5, "options")))));
  });
}

ALMUCANTAR_JNI(headingFilterPredict)
(JNIEnv* env, jclass, jdoubleArray filter, jdouble dt_s, jdoubleArray opts)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    return to_java(env, pack(heading_filter_predict(
                            heading_filter(Doubles(env, filter, kFilterLength, "filter")), dt_s,
                            heading_filter_options(Doubles(env, opts, 5, "options")))));
  });
}

ALMUCANTAR_JNI(headingFilterUpdate)
(JNIEnv* env, jclass, jdoubleArray filter, jdoubleArray measurement)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles m(env, measurement, 2, "measurement");
    return to_java(env, pack(heading_filter_update(
                            heading_filter(Doubles(env, filter, kFilterLength, "filter")),
                            {.offset_deg = m[0], .variance_deg2 = m[1]})));
  });
}

ALMUCANTAR_JNI(headingFilterUpdateMagnetometer)
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

ALMUCANTAR_JNI(findAzimuthTimes)
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

ALMUCANTAR_JNI(moonPosition)
(JNIEnv* env, jclass, jdouble unix_utc, jdoubleArray obs, jdoubleArray opts)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles o(env, opts, 3, "options");
    const Horizontal h = moon_position(
        unix_utc, observer(Doubles(env, obs, 2, "observer")),
        {.refraction = to_bool(o[0]), .atmosphere = {.pressure_hpa = o[1], .temperature_c = o[2]}});
    return to_java(env, {h.azimuth_deg, h.altitude_deg});
  });
}

ALMUCANTAR_JNI(moonIlluminatedFraction)(JNIEnv* env, jclass, jdouble unix_utc)->jdouble {
  return guarded<jdouble>(env, 0.0, [&] { return moon_illuminated_fraction(unix_utc); });
}

namespace {

SkyOptions sky_options(const Doubles& o) {
  return {.refraction = to_bool(o[0]), .atmosphere = {.pressure_hpa = o[1], .temperature_c = o[2]}};
}

std::vector<double> flat(const std::vector<Horizontal>& directions) {
  std::vector<double> out;
  out.reserve(2 * directions.size());
  for (const Horizontal& h : directions) {
    out.push_back(h.azimuth_deg);
    out.push_back(h.altitude_deg);
  }
  return out;
}

}  // namespace

ALMUCANTAR_JNI(planetPosition)
(JNIEnv* env, jclass, jdouble planet, jdouble unix_utc, jdoubleArray obs, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Horizontal h = planet_position(to_enum<Planet>(planet, "planet"), unix_utc,
                                         observer(Doubles(env, obs, 2, "observer")),
                                         sky_options(Doubles(env, opts, 3, "options")));
    return to_java(env, {h.azimuth_deg, h.altitude_deg});
  });
}

ALMUCANTAR_JNI(planetMagnitude)(JNIEnv* env, jclass, jdouble planet, jdouble unix_utc)->jdouble {
  return guarded<jdouble>(
      env, 0.0, [&] { return planet_magnitude(to_enum<Planet>(planet, "planet"), unix_utc); });
}

ALMUCANTAR_JNI(galacticPosition)
(JNIEnv* env, jclass, jdouble l, jdouble b, jdouble unix_utc, jdoubleArray obs, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Horizontal h =
        galactic_position(l, b, unix_utc, observer(Doubles(env, obs, 2, "observer")),
                          sky_options(Doubles(env, opts, 3, "options")));
    return to_java(env, {h.azimuth_deg, h.altitude_deg});
  });
}

ALMUCANTAR_JNI(galacticPlane)
(JNIEnv* env, jclass, jdouble unix_utc, jdoubleArray obs, jdouble step, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    return to_java(env, flat(galactic_plane(unix_utc, observer(Doubles(env, obs, 2, "observer")),
                                            step, sky_options(Doubles(env, opts, 3, "options")))));
  });
}

ALMUCANTAR_JNI(starPositions)
(JNIEnv* env, jclass, jdoubleArray ra, jdoubleArray dec, jdoubleArray pm_ra, jdoubleArray pm_dec,
 jdouble unix_utc, jdoubleArray obs, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const StarCatalog catalog{
        .ra_deg = Doubles(env, ra, 0, "ra_deg").take(),
        .dec_deg = Doubles(env, dec, 0, "dec_deg").take(),
        .pm_ra_arcsec_per_year = Doubles(env, pm_ra, 0, "pm_ra_arcsec_per_year").take(),
        .pm_dec_arcsec_per_year = Doubles(env, pm_dec, 0, "pm_dec_arcsec_per_year").take()};
    return to_java(
        env, flat(star_positions(catalog, unix_utc, observer(Doubles(env, obs, 2, "observer")),
                                 sky_options(Doubles(env, opts, 3, "options")))));
  });
}

ALMUCANTAR_JNI(projectPoints)
(JNIEnv* env, jclass, jdoubleArray v, jdoubleArray directions)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const std::vector<Projection> points = project_points(
        view(Doubles(env, v, 17, "view")), line(Doubles(env, directions, 0, "directions")));
    std::vector<double> out;
    out.reserve(2 * points.size());
    for (const Projection& p : points) {
      out.push_back(p.valid ? p.point.x : std::nan(""));
      out.push_back(p.valid ? p.point.y : std::nan(""));
    }
    return to_java(env, out);
  });
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
          .refraction_coefficient = o[5],
          .min_distance_m = o[6]};
}

/// Makes the grids from the headers (6 values for each grid) and the heights of each grid.
std::vector<ElevationGrid> grids(JNIEnv* env, jdoubleArray headers, jobjectArray heights) {
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
    env->GetFloatArrayRegion(array, 0, static_cast<jsize>(g.heights_m.size()), g.heights_m.data());
    env->DeleteLocalRef(array);
  }
  return tiles;
}

TerrainEventOptions terrain_event_options(const Doubles& o) {
  // The body is the last value of the array, but the first field of the struct.
  return {.body = to_enum<Body>(o[7], "body"),
          .model = to_enum<SolarModel>(o[0], "model"),
          .refraction = to_bool(o[1]),
          .atmosphere = {.pressure_hpa = o[2], .temperature_c = o[3]},
          .limb_deg = o[4],
          .search_step_s = o[5],
          .tolerance_s = o[6]};
}

}  // namespace

ALMUCANTAR_JNI(elevationGeotiffPrefixSize)
(JNIEnv* env, jclass, jbyteArray header, jint level)->jlong {
  return guarded<jlong>(env, 0, [&]() -> jlong {
    if (header == nullptr) {
      throw std::invalid_argument("header is null");
    }
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(env->GetArrayLength(header)));
    env->GetByteArrayRegion(header, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<jbyte*>(bytes.data()));
    return static_cast<jlong>(elevation_geotiff_prefix_size(bytes, level));
  });
}

// Returns the heights and writes the 6 other values of the grid into `header`.
ALMUCANTAR_JNI(readElevationGeotiff)
(JNIEnv* env, jclass, jbyteArray data, jint level, jdoubleArray header)->jfloatArray {
  return guarded<jfloatArray>(env, nullptr, [&]() -> jfloatArray {
    if (data == nullptr || header == nullptr) {
      throw std::invalid_argument("data or header is null");
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

ALMUCANTAR_JNI(elevationTiles)
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

ALMUCANTAR_JNI(horizonProfile)
(JNIEnv* env, jclass, jdoubleArray obs, jdoubleArray headers, jobjectArray heights,
 jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const std::vector<ElevationGrid> tiles = grids(env, headers, heights);
    return to_java(env, pack(horizon_profile(observer(Doubles(env, obs, 2, "observer")), tiles,
                                             horizon_options(Doubles(env, opts, 7, "options")))));
  });
}

ALMUCANTAR_JNI(terrainAltitudeDeg)
(JNIEnv* env, jclass, jdoubleArray profile, jdouble azimuth_deg)->jdouble {
  return guarded<jdouble>(env, 0.0, [&] {
    return terrain_altitude_deg(unpack_profile(Doubles(env, profile, 0, "profile")), azimuth_deg);
  });
}

ALMUCANTAR_JNI(terrainEvents)
(JNIEnv* env, jclass, jdoubleArray obs, jdoubleArray profile, jdouble start, jdouble end,
 jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const TerrainEvents e =
        terrain_events(observer(Doubles(env, obs, 2, "observer")),
                       unpack_profile(Doubles(env, profile, 0, "profile")), start, end,
                       terrain_event_options(Doubles(env, opts, 8, "options")));
    std::vector<double> out{static_cast<double>(e.kind), static_cast<double>(e.crossings.size())};
    for (const Crossing& c : e.crossings) {
      out.push_back(c.unix_utc);
      out.push_back(c.rising ? 1 : 0);
    }
    return to_java(env, out);
  });
}

namespace {

BodyOptions body_options(const Doubles& o, const std::size_t i) {
  return {.model = to_enum<SolarModel>(o[i], "model"),
          .refraction = to_bool(o[i + 1]),
          .atmosphere = {.pressure_hpa = o[i + 2], .temperature_c = o[i + 3]}};
}

Site site(const Doubles& a) {
  return Site{.latitude_deg = a[0], .longitude_deg = a[1], .elevation_m = a[2]};
}

SightOptions sight_options(const Doubles& o) {
  return {.refraction_coefficient = o[0], .target_margin_m = o[1]};
}

void append(std::vector<double>& out, const LinePoint& p) {
  out.insert(out.end(),
             {p.place.latitude_deg, p.place.longitude_deg, p.distance_m, p.eye_elevation_m,
              p.ground_elevation_m, p.body.azimuth_deg, p.body.altitude_deg});
}

}  // namespace

ALMUCANTAR_JNI(bodyPosition)
(JNIEnv* env, jclass, jdouble body, jdouble unix_utc, jdoubleArray obs, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Horizontal h = body_position(to_enum<Body>(body, "body"), unix_utc,
                                       observer(Doubles(env, obs, 2, "observer")),
                                       body_options(Doubles(env, opts, 4, "options"), 0));
    return to_java(env, {h.azimuth_deg, h.altitude_deg});
  });
}

ALMUCANTAR_JNI(sight)
(JNIEnv* env, jclass, jdoubleArray from, jdoubleArray to, jdoubleArray opts)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Sight s = sight(site(Doubles(env, from, 3, "from")), site(Doubles(env, to, 3, "to")),
                          sight_options(Doubles(env, opts, 2, "options")));
    return to_java(env, {s.direction.azimuth_deg, s.direction.altitude_deg, s.distance_m});
  });
}

ALMUCANTAR_JNI(findAlignments)
(JNIEnv* env, jclass, jdouble body, jdoubleArray obs, jdoubleArray direction, jdouble start,
 jdouble end, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles o(env, opts, 6, "options");
    const auto alignments = find_alignments(
        to_enum<Body>(body, "body"), observer(Doubles(env, obs, 2, "observer")),
        horizontal(Doubles(env, direction, 2, "direction")), start, end,
        {.body = body_options(o, 0), .max_separation_deg = o[4], .tolerance_s = o[5]});
    std::vector<double> out;
    out.reserve(4 * alignments.size());
    for (const Alignment& a : alignments) {
      out.insert(out.end(),
                 {a.unix_utc, a.separation_deg, a.body.azimuth_deg, a.body.altitude_deg});
    }
    return to_java(env, out);
  });
}

ALMUCANTAR_JNI(alignmentLine)
(JNIEnv* env, jclass, jdouble body, jdoubleArray target, jdouble unix_utc, jdoubleArray headers,
 jobjectArray heights, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles o(env, opts, 9, "options");
    const AlignmentLine line =
        alignment_line(to_enum<Body>(body, "body"), site(Doubles(env, target, 3, "target")),
                       unix_utc, grids(env, headers, heights),
                       {.body = body_options(o, 0),
                        .min_distance_m = o[4],
                        .max_distance_m = o[5],
                        .step_m = o[6],
                        .eye_height_m = o[7],
                        .refraction_coefficient = o[8]});
    std::vector<double> out{static_cast<double>(line.points.size()),
                            static_cast<double>(line.spots.size())};
    out.reserve(2 + (7 * (line.points.size() + line.spots.size())));
    for (const LinePoint& p : line.points) {
      append(out, p);
    }
    for (const LinePoint& p : line.spots) {
      append(out, p);
    }
    return to_java(env, out);
  });
}

ALMUCANTAR_JNI(lineOfSight)
(JNIEnv* env, jclass, jdoubleArray from, jdoubleArray to, jdoubleArray headers,
 jobjectArray heights, jdoubleArray opts)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const LineOfSight l = line_of_sight(
        site(Doubles(env, from, 3, "from")), site(Doubles(env, to, 3, "to")),
        grids(env, headers, heights), sight_options(Doubles(env, opts, 2, "options")));
    return to_java(env, {l.visible ? 1.0 : 0.0, l.clearance_deg, l.blocking_distance_m,
                         l.blocking.latitude_deg, l.blocking.longitude_deg});
  });
}

// A null profile is the empty profile: the horizon of 0 degrees.
ALMUCANTAR_JNI(darkWindows)
(JNIEnv* env, jclass, jdoubleArray obs, jdouble start, jdouble end, jdoubleArray opts,
 jdoubleArray profile)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    const Doubles o(env, opts, 7, "options");
    const HorizonProfile p =
        profile == nullptr ? HorizonProfile{} : unpack_profile(Doubles(env, profile, 0, "profile"));
    const auto windows = dark_windows(observer(Doubles(env, obs, 2, "observer")), start, end,
                                      {.max_sun_altitude_deg = o[0],
                                       .max_moon_altitude_deg = o[1],
                                       .max_moon_fraction = o[2],
                                       .galactic_center = to_bool(o[3]),
                                       .min_galactic_center_altitude_deg = o[4],
                                       .step_s = o[5],
                                       .tolerance_s = o[6]},
                                      p);
    std::vector<double> out;
    out.reserve(2 * windows.size());
    for (const TimeWindow& w : windows) {
      out.push_back(w.start_utc);
      out.push_back(w.end_utc);
    }
    return to_java(env, out);
  });
}

ALMUCANTAR_JNI(lunarEclipses)(JNIEnv* env, jclass, jdouble start, jdouble end)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    std::vector<double> out;
    for (const LunarEclipse& e : lunar_eclipses(start, end)) {
      out.insert(out.end(), {static_cast<double>(e.kind), e.peak_utc, e.obscuration,
                             e.penumbral_begin_utc, e.partial_begin_utc, e.total_begin_utc,
                             e.total_end_utc, e.partial_end_utc, e.penumbral_end_utc});
    }
    return to_java(env, out);
  });
}

ALMUCANTAR_JNI(solarEclipses)(JNIEnv* env, jclass, jdouble start, jdouble end)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    std::vector<double> out;
    for (const SolarEclipse& e : solar_eclipses(start, end)) {
      out.insert(out.end(), {static_cast<double>(e.kind), e.peak_utc, e.peak_place.latitude_deg,
                             e.peak_place.longitude_deg, e.obscuration});
    }
    return to_java(env, out);
  });
}

ALMUCANTAR_JNI(localSolarEclipses)
(JNIEnv* env, jclass, jdoubleArray obs, jdouble start, jdouble end)->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    std::vector<double> out;
    for (const LocalSolarEclipse& e :
         local_solar_eclipses(observer(Doubles(env, obs, 2, "observer")), start, end)) {
      out.insert(out.end(), {static_cast<double>(e.kind), e.obscuration, e.partial_begin_utc,
                             e.total_begin_utc, e.peak_utc, e.total_end_utc, e.partial_end_utc,
                             e.peak_sun_altitude_deg});
    }
    return to_java(env, out);
  });
}

ALMUCANTAR_JNI(eclipseCentralLine)(JNIEnv* env, jclass, jdouble peak_utc, jdouble step_s)
    ->jdoubleArray {
  return guarded<jdoubleArray>(env, nullptr, [&] {
    std::vector<double> out;
    for (const ShadowPoint& p : eclipse_central_line(peak_utc, step_s)) {
      out.insert(out.end(),
                 {p.unix_utc, p.place.latitude_deg, p.place.longitude_deg, p.umbra_radius_km});
    }
    return to_java(env, out);
  });
}

// The C++ defaults of the option structs, Camera, View, and the heading filter, in the layouts
// above, so the instrumented tests can check that the Kotlin defaults are the same.
ALMUCANTAR_JNI(defaults)(JNIEnv* env, jclass)->jdoubleArray {
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
    const BodyOptions b;
    const SightOptions so;
    const AlignmentOptions a;
    const AlignmentLineOptions l;
    const DarkOptions d;
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
                         h.min_distance_m,
                         static_cast<double>(t.model),
                         t.refraction ? 1.0 : 0.0,
                         t.atmosphere.pressure_hpa,
                         t.atmosphere.temperature_c,
                         t.limb_deg,
                         t.search_step_s,
                         t.tolerance_s,
                         static_cast<double>(t.body),
                         SkyOptions{}.refraction ? 1.0 : 0.0,
                         SkyOptions{}.atmosphere.pressure_hpa,
                         SkyOptions{}.atmosphere.temperature_c,
                         static_cast<double>(b.model),
                         b.refraction ? 1.0 : 0.0,
                         b.atmosphere.pressure_hpa,
                         b.atmosphere.temperature_c,
                         so.refraction_coefficient,
                         so.target_margin_m,
                         static_cast<double>(a.body.model),
                         a.body.refraction ? 1.0 : 0.0,
                         a.body.atmosphere.pressure_hpa,
                         a.body.atmosphere.temperature_c,
                         a.max_separation_deg,
                         a.tolerance_s,
                         static_cast<double>(l.body.model),
                         l.body.refraction ? 1.0 : 0.0,
                         l.body.atmosphere.pressure_hpa,
                         l.body.atmosphere.temperature_c,
                         l.min_distance_m,
                         l.max_distance_m,
                         l.step_m,
                         l.eye_height_m,
                         l.refraction_coefficient,
                         d.max_sun_altitude_deg,
                         d.max_moon_altitude_deg,
                         d.max_moon_fraction,
                         d.galactic_center ? 1.0 : 0.0,
                         d.min_galactic_center_altitude_deg,
                         d.step_s,
                         d.tolerance_s});
  });
}
