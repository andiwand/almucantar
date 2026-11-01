// The C API (include/sunradar_c.h) over the public C++ API. It must not include an internal
// header. No function lets an exception escape: each one returns a status, and sr_last_error()
// has the message.

#include "sunradar_c.h"

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

#include "sunradar/sunradar.hpp"

namespace {

using namespace sunradar;

thread_local std::string last_error;

template <typename F>
sr_status guarded(F&& f) noexcept {
  try {
    f();
    last_error.clear();
    return SR_OK;
  } catch (const std::invalid_argument& e) {
    last_error = e.what();
    return SR_INVALID_ARGUMENT;
  } catch (const std::exception& e) {
    last_error = e.what();
    return SR_ERROR;
  } catch (...) {
    last_error = "unknown C++ exception";
    return SR_ERROR;
  }
}

template <typename Enum>
Enum to_enum(const int value, const char* name) {
  if (value < 0 || value > 255) {
    throw std::invalid_argument(std::string(name) + " is not a valid value");
  }
  return static_cast<Enum>(static_cast<std::uint8_t>(value));
}

void require_out(const void* pointer) {
  if (pointer == nullptr) {
    throw std::invalid_argument("the output pointer is null");
  }
}

/// Copies a vector into an array that the caller frees with sr_free().
template <typename T>
T* copy_out(const std::vector<T>& values) {
  if (values.empty()) {
    return nullptr;
  }
  auto* result = static_cast<T*>(std::malloc(values.size() * sizeof(T)));
  if (result == nullptr) {
    throw std::bad_alloc();
  }
  std::memcpy(result, values.data(), values.size() * sizeof(T));
  return result;
}

Observer to_cpp(const sr_observer o) {
  return {.latitude_deg = o.latitude_deg, .longitude_deg = o.longitude_deg};
}
Horizontal to_cpp(const sr_horizontal h) {
  return {.azimuth_deg = h.azimuth_deg, .altitude_deg = h.altitude_deg};
}
Atmosphere to_cpp(const sr_atmosphere a) {
  return {.pressure_hpa = a.pressure_hpa, .temperature_c = a.temperature_c};
}
Quaternion to_cpp(const sr_quaternion q) { return {.w = q.w, .x = q.x, .y = q.y, .z = q.z}; }
ScreenPoint to_cpp(const sr_screen_point p) { return {.x = p.x, .y = p.y}; }
Camera to_cpp(const sr_camera& c) {
  return {.width = c.width,
          .height = c.height,
          .fx = c.fx,
          .fy = c.fy,
          .cx = c.cx,
          .cy = c.cy,
          .k1 = c.k1,
          .k2 = c.k2,
          .k3 = c.k3,
          .p1 = c.p1,
          .p2 = c.p2};
}
View to_cpp(const sr_view& v) {
  return {.device_to_enu = to_cpp(v.device_to_enu),
          .screen_rotation = to_enum<ScreenRotation>(v.screen_rotation, "screen_rotation"),
          .heading_offset_deg = v.heading_offset_deg,
          .camera = to_cpp(v.camera)};
}
SearchOptions to_cpp(const sr_search_options& o) {
  return {.model = to_enum<SolarModel>(o.model, "model"),
          .step_s = o.step_s,
          .tolerance_s = o.tolerance_s,
          .refraction = o.refraction,
          .atmosphere = to_cpp(o.atmosphere),
          .max_separation_deg = o.max_separation_deg};
}

sr_horizontal to_c(const Horizontal& h) { return {h.azimuth_deg, h.altitude_deg}; }
sr_atmosphere to_c(const Atmosphere& a) { return {a.pressure_hpa, a.temperature_c}; }
sr_camera to_c(const Camera& c) {
  return {c.width, c.height, c.fx, c.fy, c.cx, c.cy, c.k1, c.k2, c.k3, c.p1, c.p2};
}
sr_time_match to_c(const TimeMatch& m) { return {m.unix_utc, m.separation_deg, to_c(m.sun)}; }

void fill(const std::vector<Polyline>& lines, sr_polylines* out) {
  std::vector<sr_screen_point> points;
  std::vector<size_t> counts;
  for (const Polyline& line : lines) {
    counts.push_back(line.size());
    for (const ScreenPoint& p : line) {
      points.push_back({p.x, p.y});
    }
  }
  out->points = copy_out(points);
  out->counts = copy_out(counts);
  out->line_count = counts.size();
}

}  // namespace

extern "C" {

sr_sun_options sr_default_sun_options(void) {
  const SunOptions d;
  return {static_cast<int>(d.model), d.refraction, to_c(d.atmosphere)};
}
sr_event_options sr_default_event_options(void) {
  const EventOptions d;
  return {static_cast<int>(d.model), d.altitude_deg, d.search_step_s, d.tolerance_s};
}
sr_path_options sr_default_path_options(void) {
  const PathOptions d;
  return {static_cast<int>(d.model), d.step_s, d.refraction, to_c(d.atmosphere)};
}
sr_search_options sr_default_search_options(void) {
  const SearchOptions d;
  return {static_cast<int>(d.model), d.step_s, d.tolerance_s, d.refraction, to_c(d.atmosphere),
          d.max_separation_deg};
}
sr_moon_options sr_default_moon_options(void) {
  const MoonOptions d;
  return {d.refraction, to_c(d.atmosphere)};
}
sr_magnetometer_options sr_default_magnetometer_options(void) {
  return {MagnetometerOptions{}.sensor_sigma_deg};
}
sr_heading_estimate sr_default_heading_estimate(void) {
  const HeadingEstimate d;
  return {d.offset_deg, d.variance_deg2};
}
sr_camera sr_default_camera(void) { return to_c(Camera{}); }
sr_view sr_default_view(void) {
  const View d;
  return {{d.device_to_enu.w, d.device_to_enu.x, d.device_to_enu.y, d.device_to_enu.z},
          static_cast<int>(d.screen_rotation),
          d.heading_offset_deg,
          to_c(d.camera)};
}
double sr_sunrise_altitude_deg(void) { return kSunriseAltitudeDeg; }

const char* sr_version(void) {
  static const std::string kVersion(version());
  return kVersion.c_str();
}
const char* sr_last_error(void) { return last_error.c_str(); }
void sr_free(void* pointer) { std::free(pointer); }

sr_status sr_sun_position(double t, sr_observer o, sr_sun_options opt, sr_horizontal* out) {
  return guarded([&] {
    require_out(out);
    *out = to_c(sun_position(t, to_cpp(o),
                             {.model = to_enum<SolarModel>(opt.model, "model"),
                              .refraction = opt.refraction,
                              .atmosphere = to_cpp(opt.atmosphere)}));
  });
}

sr_status sr_sun_events(sr_observer o, double start, double end, sr_event_options opt,
                        sr_day_events* out) {
  return guarded([&] {
    require_out(out);
    const DayEvents e = sun_events(to_cpp(o), start, end,
                                   {.model = to_enum<SolarModel>(opt.model, "model"),
                                    .altitude_deg = opt.altitude_deg,
                                    .search_step_s = opt.search_step_s,
                                    .tolerance_s = opt.tolerance_s});
    std::vector<sr_crossing> crossings;
    for (const Crossing& c : e.crossings) {
      crossings.push_back({c.unix_utc, c.rising});
    }
    *out = {static_cast<int>(e.kind), copy_out(crossings), crossings.size(), e.culmination_utc,
            e.culmination_altitude_deg};
  });
}

sr_status sr_sun_path(sr_observer o, double start, double end, sr_path_options opt,
                      sr_path_point** out, size_t* count) {
  return guarded([&] {
    require_out(out);
    require_out(count);
    std::vector<sr_path_point> points;
    for (const PathPoint& p : sun_path(to_cpp(o), start, end,
                                       {.model = to_enum<SolarModel>(opt.model, "model"),
                                        .step_s = opt.step_s,
                                        .refraction = opt.refraction,
                                        .atmosphere = to_cpp(opt.atmosphere)})) {
      points.push_back({p.unix_utc, to_c(p.position)});
    }
    *out = copy_out(points);
    *count = points.size();
  });
}

sr_status sr_moon_position(double t, sr_observer o, sr_moon_options opt, sr_horizontal* out) {
  return guarded([&] {
    require_out(out);
    *out = to_c(moon_position(
        t, to_cpp(o), {.refraction = opt.refraction, .atmosphere = to_cpp(opt.atmosphere)}));
  });
}

sr_status sr_moon_illuminated_fraction(double t, double* out) {
  return guarded([&] {
    require_out(out);
    *out = moon_illuminated_fraction(t);
  });
}

sr_status sr_magnetic_declination_deg(sr_observer o, double t, double* out) {
  return guarded([&] {
    require_out(out);
    *out = magnetic_declination_deg(to_cpp(o), t);
  });
}

sr_status sr_camera_from_fov(double w, double h, double fov, sr_camera* out) {
  return guarded([&] {
    require_out(out);
    *out = to_c(camera_from_fov(w, h, fov));
  });
}

sr_status sr_project(sr_view v, sr_horizontal d, sr_projection* out) {
  return guarded([&] {
    require_out(out);
    const Projection p = project(to_cpp(v), to_cpp(d));
    *out = {p.valid, {p.point.x, p.point.y}};
  });
}

sr_status sr_unproject(sr_view v, sr_screen_point p, sr_horizontal* out) {
  return guarded([&] {
    require_out(out);
    *out = to_c(unproject(to_cpp(v), to_cpp(p)));
  });
}

sr_status sr_project_line(sr_view v, const sr_horizontal* line, size_t count, sr_polylines* out) {
  return guarded([&] {
    require_out(out);
    if (line == nullptr && count > 0) {
      throw std::invalid_argument("line is null");
    }
    std::vector<Horizontal> directions;
    directions.reserve(count);
    for (size_t i = 0; i < count; ++i) {
      directions.push_back(to_cpp(line[i]));
    }
    fill(project_line(to_cpp(v), directions), out);
  });
}

sr_status sr_horizon_line(sr_view v, sr_polylines* out) {
  return guarded([&] {
    require_out(out);
    fill(horizon_line(to_cpp(v)), out);
  });
}

void sr_free_polylines(sr_polylines* lines) {
  if (lines == nullptr) {
    return;
  }
  std::free(lines->points);
  std::free(lines->counts);
  *lines = {};
}

sr_status sr_find_time(sr_observer o, sr_horizontal d, double start, double end,
                       sr_search_options opt, sr_time_match* out) {
  return guarded([&] {
    require_out(out);
    *out = to_c(find_time(to_cpp(o), to_cpp(d), start, end, to_cpp(opt)));
  });
}

sr_status sr_find_dates(sr_observer o, sr_horizontal d, double start, double end,
                        sr_search_options opt, sr_time_match** out, size_t* count) {
  return guarded([&] {
    require_out(out);
    require_out(count);
    std::vector<sr_time_match> matches;
    for (const TimeMatch& m : find_dates(to_cpp(o), to_cpp(d), start, end, to_cpp(opt))) {
      matches.push_back(to_c(m));
    }
    *out = copy_out(matches);
    *count = matches.size();
  });
}

sr_status sr_find_azimuth_times(sr_observer o, double azimuth, double start, double end,
                                sr_search_options opt, sr_azimuth_crossing** out, size_t* count) {
  return guarded([&] {
    require_out(out);
    require_out(count);
    std::vector<sr_azimuth_crossing> crossings;
    for (const AzimuthCrossing& c :
         find_azimuth_times(to_cpp(o), azimuth, start, end, to_cpp(opt))) {
      crossings.push_back({c.unix_utc, to_c(c.sun)});
    }
    *out = copy_out(crossings);
    *count = crossings.size();
  });
}

sr_status sr_heading_predict(sr_heading_estimate e, double dt, double drift,
                             sr_heading_estimate* out) {
  return guarded([&] {
    require_out(out);
    const HeadingEstimate r =
        heading_predict({.offset_deg = e.offset_deg, .variance_deg2 = e.variance_deg2}, dt, drift);
    *out = {r.offset_deg, r.variance_deg2};
  });
}

sr_status sr_heading_update(sr_heading_estimate e, sr_heading_measurement m,
                            sr_heading_estimate* out) {
  return guarded([&] {
    require_out(out);
    const HeadingEstimate r =
        heading_update({.offset_deg = e.offset_deg, .variance_deg2 = e.variance_deg2},
                       {.offset_deg = m.offset_deg, .variance_deg2 = m.variance_deg2});
    *out = {r.offset_deg, r.variance_deg2};
  });
}

sr_status sr_heading_from_magnetometer(sr_quaternion q, sr_vector3 f, sr_observer o, double t,
                                       sr_magnetometer_options opt, sr_heading_measurement* out) {
  return guarded([&] {
    require_out(out);
    const HeadingMeasurement r =
        heading_from_magnetometer(to_cpp(q), {.x = f.x, .y = f.y, .z = f.z}, to_cpp(o), t,
                                  {.sensor_sigma_deg = opt.sensor_sigma_deg});
    *out = {r.offset_deg, r.variance_deg2};
  });
}

sr_status sr_heading_from_direction(sr_view v, sr_screen_point p, double az, double sigma,
                                    sr_heading_measurement* out) {
  return guarded([&] {
    require_out(out);
    const HeadingMeasurement r = heading_from_direction(to_cpp(v), to_cpp(p), az, sigma);
    *out = {r.offset_deg, r.variance_deg2};
  });
}

}  // extern "C"
