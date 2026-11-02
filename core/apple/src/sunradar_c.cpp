// The C API (include/sunradar_c.h) over the public C++ API. It must not include an internal
// header. No function lets an exception escape: each one returns a status, and sr_last_error()
// has the message.

#include "sunradar_c.h"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <iterator>
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
  // Both arrays or none: the caller does not free the result of a failed call.
  sr_screen_point* const p = copy_out(points);
  try {
    out->counts = copy_out(counts);
  } catch (...) {
    std::free(p);
    throw;
  }
  out->points = p;
  out->line_count = counts.size();
}

static_assert(SR_HEADING_FILTER_SIZE == kHeadingFilterSize);

HeadingFilterOptions to_cpp(const sr_heading_filter_options& o) {
  return {.drift_deg2_per_s = o.drift_deg2_per_s,
          .bias_drift_ut2_per_s = o.bias_drift_ut2_per_s,
          .disturbance_sigma_ut = o.disturbance_sigma_ut,
          .disturbance_time_s = o.disturbance_time_s,
          .noise_sigma_ut = o.noise_sigma_ut};
}

HeadingFilter to_cpp(const sr_heading_filter* f) {
  if (f == nullptr) throw std::invalid_argument("filter is null");
  HeadingFilter out{.offset_deg = f->offset_deg,
                    .bias_ut = {.x = f->bias_ut.x, .y = f->bias_ut.y, .z = f->bias_ut.z},
                    .disturbance_ut = {.x = f->disturbance_ut.x,
                                       .y = f->disturbance_ut.y,
                                       .z = f->disturbance_ut.z},
                    .covariance = {}};
  std::ranges::copy(f->covariance, out.covariance.begin());
  return out;
}

void to_c(const HeadingFilter& f, sr_heading_filter* out) {
  out->offset_deg = f.offset_deg;
  out->bias_ut = {f.bias_ut.x, f.bias_ut.y, f.bias_ut.z};
  out->disturbance_ut = {f.disturbance_ut.x, f.disturbance_ut.y, f.disturbance_ut.z};
  std::ranges::copy(f.covariance, std::begin(out->covariance));
}

HorizonOptions to_cpp(const sr_horizon_options& o) {
  return {.azimuth_step_deg = o.azimuth_step_deg,
          .max_distance_m = o.max_distance_m,
          .eye_height_m = o.eye_height_m,
          .use_grid_elevation = o.use_grid_elevation,
          .observer_elevation_m = o.observer_elevation_m,
          .refraction_coefficient = o.refraction_coefficient};
}

TerrainEventOptions to_cpp(const sr_terrain_event_options& o) {
  return {.body = to_enum<Body>(o.body, "body"),
          .model = to_enum<SolarModel>(o.model, "model"),
          .refraction = o.refraction,
          .atmosphere = to_cpp(o.atmosphere),
          .limb_deg = o.limb_deg,
          .search_step_s = o.search_step_s,
          .tolerance_s = o.tolerance_s};
}

/// Copies a grid of the caller into C++. The C++ API takes a std::vector, so this copy of the
/// heights is necessary.
ElevationGrid to_cpp(const sr_elevation_grid& g) {
  const size_t size = g.rows > 0 && g.columns > 0
                          ? static_cast<size_t>(g.rows) * static_cast<size_t>(g.columns)
                          : 0;
  if (g.heights_m == nullptr && size > 0) {
    throw std::invalid_argument("heights_m is null");
  }
  ElevationGrid out{.north_latitude_deg = g.north_latitude_deg,
                    .west_longitude_deg = g.west_longitude_deg,
                    .latitude_step_deg = g.latitude_step_deg,
                    .longitude_step_deg = g.longitude_step_deg,
                    .rows = g.rows,
                    .columns = g.columns,
                    .heights_m = {}};
  if (size > 0) {
    out.heights_m.assign(g.heights_m, g.heights_m + size);
  }
  return out;
}

HorizonProfile to_cpp(const sr_horizon_profile* p) {
  if (p == nullptr) {
    throw std::invalid_argument("profile is null");
  }
  if (p->count > 0 && (p->altitude_deg == nullptr || p->distance_m == nullptr)) {
    throw std::invalid_argument("profile has a null array");
  }
  HorizonProfile out{.azimuth_step_deg = p->azimuth_step_deg,
                     .altitude_deg = {},
                     .distance_m = {},
                     .observer_elevation_m = p->observer_elevation_m};
  if (p->count > 0) {
    out.altitude_deg.assign(p->altitude_deg, p->altitude_deg + p->count);
    out.distance_m.assign(p->distance_m, p->distance_m + p->count);
  }
  return out;
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
sr_heading_filter_options sr_default_heading_filter_options(void) {
  const HeadingFilterOptions d;
  return {d.drift_deg2_per_s, d.bias_drift_ut2_per_s, d.disturbance_sigma_ut, d.disturbance_time_s,
          d.noise_sigma_ut};
}
sr_horizon_options sr_default_horizon_options(void) {
  const HorizonOptions d;
  return {d.azimuth_step_deg,   d.max_distance_m,       d.eye_height_m,
          d.use_grid_elevation, d.observer_elevation_m, d.refraction_coefficient};
}
sr_terrain_event_options sr_default_terrain_event_options(void) {
  const TerrainEventOptions d;
  return {static_cast<int>(d.model), d.refraction,  to_c(d.atmosphere),      d.limb_deg,
          d.search_step_s,           d.tolerance_s, static_cast<int>(d.body)};
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
double sr_unknown_heading_variance_deg2(void) { return kUnknownHeadingVarianceDeg2; }
double sr_default_heading_drift_deg2_per_s(void) { return kDefaultHeadingDriftDeg2PerS; }

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

sr_status sr_heading_filter_start(sr_vector3 b, double sigma, sr_heading_filter_options opt,
                                  sr_heading_filter* out) {
  return guarded([&] {
    require_out(out);
    to_c(heading_filter_start({.x = b.x, .y = b.y, .z = b.z}, sigma, to_cpp(opt)), out);
  });
}

sr_status sr_heading_filter_predict(const sr_heading_filter* f, double dt,
                                    sr_heading_filter_options opt, sr_heading_filter* out) {
  return guarded([&] {
    require_out(out);
    to_c(heading_filter_predict(to_cpp(f), dt, to_cpp(opt)), out);
  });
}

sr_status sr_heading_filter_update(const sr_heading_filter* f, sr_heading_measurement m,
                                   sr_heading_filter* out) {
  return guarded([&] {
    require_out(out);
    to_c(heading_filter_update(to_cpp(f),
                               {.offset_deg = m.offset_deg, .variance_deg2 = m.variance_deg2}),
         out);
  });
}

sr_status sr_heading_filter_update_magnetometer(const sr_heading_filter* f, sr_quaternion q,
                                                sr_vector3 field, sr_observer o, double t,
                                                sr_heading_filter_options opt,
                                                sr_heading_filter* out) {
  return guarded([&] {
    require_out(out);
    to_c(heading_filter_update_magnetometer(to_cpp(f), to_cpp(q),
                                            {.x = field.x, .y = field.y, .z = field.z}, to_cpp(o),
                                            t, to_cpp(opt)),
         out);
  });
}

sr_status sr_elevation_geotiff_prefix_size(const uint8_t* header, size_t size, int level,
                                           uint32_t* out) {
  return guarded([&] {
    require_out(out);
    if (header == nullptr && size > 0) {
      throw std::invalid_argument("header is null");
    }
    const std::vector<std::uint8_t> bytes =
        size > 0 ? std::vector<std::uint8_t>(header, header + size) : std::vector<std::uint8_t>{};
    *out = elevation_geotiff_prefix_size(bytes, level);
  });
}

sr_status sr_read_elevation_geotiff(const uint8_t* data, size_t size, int level,
                                    sr_elevation_grid* out) {
  return guarded([&] {
    require_out(out);
    if (data == nullptr && size > 0) {
      throw std::invalid_argument("data is null");
    }
    const std::vector<std::uint8_t> bytes =
        size > 0 ? std::vector<std::uint8_t>(data, data + size) : std::vector<std::uint8_t>{};
    const ElevationGrid g = read_elevation_geotiff(bytes, level);
    *out = {g.north_latitude_deg,
            g.west_longitude_deg,
            g.latitude_step_deg,
            g.longitude_step_deg,
            g.rows,
            g.columns,
            copy_out(g.heights_m)};
  });
}

void sr_free_elevation_grid(sr_elevation_grid* grid) {
  if (grid == nullptr) {
    return;
  }
  std::free(grid->heights_m);
  *grid = {};
}

sr_status sr_elevation_tiles(sr_observer o, double max_distance, sr_elevation_tile** out,
                             size_t* count) {
  return guarded([&] {
    require_out(out);
    require_out(count);
    std::vector<sr_elevation_tile> tiles;
    for (const ElevationTile& t : elevation_tiles(to_cpp(o), max_distance)) {
      tiles.push_back({t.latitude_deg, t.longitude_deg, t.distance_m});
    }
    *out = copy_out(tiles);
    *count = tiles.size();
  });
}

sr_status sr_calculate_horizon_profile(sr_observer o, const sr_elevation_grid* tiles,
                                       size_t tile_count, sr_horizon_options opt,
                                       sr_horizon_profile* out) {
  return guarded([&] {
    require_out(out);
    if (tiles == nullptr && tile_count > 0) {
      throw std::invalid_argument("tiles is null");
    }
    std::vector<ElevationGrid> grids;
    grids.reserve(tile_count);
    for (size_t i = 0; i < tile_count; ++i) {
      grids.push_back(to_cpp(tiles[i]));
    }
    const HorizonProfile p = horizon_profile(to_cpp(o), grids, to_cpp(opt));
    // Both arrays or none: the caller does not free the result of a failed call.
    double* const altitude = copy_out(p.altitude_deg);
    try {
      out->distance_m = copy_out(p.distance_m);
    } catch (...) {
      std::free(altitude);
      throw;
    }
    out->altitude_deg = altitude;
    out->count = p.altitude_deg.size();
    out->azimuth_step_deg = p.azimuth_step_deg;
    out->observer_elevation_m = p.observer_elevation_m;
  });
}

void sr_free_horizon_profile(sr_horizon_profile* profile) {
  if (profile == nullptr) {
    return;
  }
  std::free(profile->altitude_deg);
  std::free(profile->distance_m);
  *profile = {};
}

sr_status sr_terrain_altitude_deg(const sr_horizon_profile* profile, double azimuth, double* out) {
  return guarded([&] {
    require_out(out);
    *out = terrain_altitude_deg(to_cpp(profile), azimuth);
  });
}

sr_status sr_find_terrain_events(sr_observer o, const sr_horizon_profile* profile, double start,
                                 double end, sr_terrain_event_options opt, sr_terrain_events* out) {
  return guarded([&] {
    require_out(out);
    const TerrainEvents e = terrain_events(to_cpp(o), to_cpp(profile), start, end, to_cpp(opt));
    std::vector<sr_crossing> crossings;
    for (const Crossing& c : e.crossings) {
      crossings.push_back({c.unix_utc, c.rising});
    }
    *out = {static_cast<int>(e.kind), copy_out(crossings), crossings.size()};
  });
}

}  // extern "C"
