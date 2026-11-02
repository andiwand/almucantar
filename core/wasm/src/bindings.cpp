// The WebAssembly binding of the public API (src/sunradar/*.hpp) for the npm package
// @sunradar/core. It must not include an internal header.
//
// Rules (from the WASM binding of OpenDocument.core):
// - Values cross as plain JS objects (value_object) or arrays, so they can go through
//   postMessage to a Web Worker. No embind class handles.
// - Enums cross as numbers. The mirror structs below have integer fields for them.
// - std::invalid_argument becomes a JS RangeError with the same message.
// - The field names are camelCase, the style of JS.

#include <emscripten/bind.h>
#include <emscripten/val.h>

#include <cstdint>
#include <exception>
#include <stdexcept>
#include <string>
#include <vector>

#include "sunradar/sunradar.hpp"

namespace {

using emscripten::val;
using namespace sunradar;

template <typename F>
auto guarded(F&& f) -> decltype(f()) {
  try {
    return f();
  } catch (const std::invalid_argument& e) {
    val::global("RangeError").new_(std::string(e.what())).throw_();
  } catch (const std::exception& e) {
    val::global("Error").new_(std::string(e.what())).throw_();
  } catch (...) {
    val::global("Error").new_(std::string("unknown error")).throw_();
  }
  return decltype(f())();
}

template <typename Enum>
Enum to_enum(const int value, const char* name) {
  if (value < 0 || value > 255) {
    throw std::invalid_argument(std::string(name) + " is not a valid value");
  }
  return static_cast<Enum>(static_cast<std::uint8_t>(value));
}

int to_int(const SolarModel model) { return static_cast<int>(model); }

// Mirror structs with integer fields instead of enums. Each default comes from the C++ struct.
struct SunOptionsJs {
  int model = to_int(SunOptions{}.model);
  bool refraction = SunOptions{}.refraction;
  Atmosphere atmosphere = SunOptions{}.atmosphere;
  [[nodiscard]] SunOptions get() const {
    return {.model = to_enum<SolarModel>(model, "model"),
            .refraction = refraction,
            .atmosphere = atmosphere};
  }
};

struct EventOptionsJs {
  int model = to_int(EventOptions{}.model);
  double altitudeDeg = EventOptions{}.altitude_deg;
  double searchStepS = EventOptions{}.search_step_s;
  double toleranceS = EventOptions{}.tolerance_s;
  [[nodiscard]] EventOptions get() const {
    return {.model = to_enum<SolarModel>(model, "model"),
            .altitude_deg = altitudeDeg,
            .search_step_s = searchStepS,
            .tolerance_s = toleranceS};
  }
};

struct PathOptionsJs {
  int model = to_int(PathOptions{}.model);
  double stepS = PathOptions{}.step_s;
  bool refraction = PathOptions{}.refraction;
  Atmosphere atmosphere = PathOptions{}.atmosphere;
  [[nodiscard]] PathOptions get() const {
    return {.model = to_enum<SolarModel>(model, "model"),
            .step_s = stepS,
            .refraction = refraction,
            .atmosphere = atmosphere};
  }
};

struct SearchOptionsJs {
  int model = to_int(SearchOptions{}.model);
  double stepS = SearchOptions{}.step_s;
  double toleranceS = SearchOptions{}.tolerance_s;
  bool refraction = SearchOptions{}.refraction;
  Atmosphere atmosphere = SearchOptions{}.atmosphere;
  double maxSeparationDeg = SearchOptions{}.max_separation_deg;
  [[nodiscard]] SearchOptions get() const {
    return {.model = to_enum<SolarModel>(model, "model"),
            .step_s = stepS,
            .tolerance_s = toleranceS,
            .refraction = refraction,
            .atmosphere = atmosphere,
            .max_separation_deg = maxSeparationDeg};
  }
};

struct ViewJs {
  Quaternion deviceToEnu = View{}.device_to_enu;
  int screenRotation = static_cast<int>(View{}.screen_rotation);
  double headingOffsetDeg = View{}.heading_offset_deg;
  Camera camera = View{}.camera;
  [[nodiscard]] View get() const {
    return {.device_to_enu = deviceToEnu,
            .screen_rotation = to_enum<ScreenRotation>(screenRotation, "screenRotation"),
            .heading_offset_deg = headingOffsetDeg,
            .camera = camera};
  }
};

struct TerrainEventOptionsJs {
  int model = to_int(TerrainEventOptions{}.model);
  bool refraction = TerrainEventOptions{}.refraction;
  Atmosphere atmosphere = TerrainEventOptions{}.atmosphere;
  double limbDeg = TerrainEventOptions{}.limb_deg;
  double searchStepS = TerrainEventOptions{}.search_step_s;
  double toleranceS = TerrainEventOptions{}.tolerance_s;
  [[nodiscard]] TerrainEventOptions get() const {
    return {.model = to_enum<SolarModel>(model, "model"),
            .refraction = refraction,
            .atmosphere = atmosphere,
            .limb_deg = limbDeg,
            .search_step_s = searchStepS,
            .tolerance_s = toleranceS};
  }
};

val horizontal(const Horizontal& h) {
  val o = val::object();
  o.set("azimuthDeg", h.azimuth_deg);
  o.set("altitudeDeg", h.altitude_deg);
  return o;
}

val polylines(const std::vector<Polyline>& lines) {
  val result = val::array();
  for (const Polyline& line : lines) {
    val points = val::array();
    for (const ScreenPoint& p : line) {
      val point = val::object();
      point.set("x", p.x);
      point.set("y", p.y);
      points.call<void>("push", point);
    }
    result.call<void>("push", points);
  }
  return result;
}

val time_match(const TimeMatch& m) {
  val o = val::object();
  o.set("unixUtc", m.unix_utc);
  o.set("separationDeg", m.separation_deg);
  o.set("sun", horizontal(m.sun));
  return o;
}

std::vector<Horizontal> to_line(const val& array) {
  std::vector<Horizontal> line;
  const auto n = array["length"].as<unsigned>();
  line.reserve(n);
  for (unsigned i = 0; i < n; ++i) {
    const val p = array[i];
    line.push_back({.azimuth_deg = p["azimuthDeg"].as<double>(),
                    .altitude_deg = p["altitudeDeg"].as<double>()});
  }
  return line;
}

std::string version_js() { return std::string(version()); }

Horizontal sun_position_js(double t, const Observer& o, const SunOptionsJs& opt) {
  return guarded([&] { return sun_position(t, o, opt.get()); });
}

val sun_events_js(const Observer& o, double start, double end, const EventOptionsJs& opt) {
  return guarded([&] {
    const DayEvents e = sun_events(o, start, end, opt.get());
    val result = val::object();
    result.set("kind", static_cast<int>(e.kind));
    val crossings = val::array();
    for (const Crossing& c : e.crossings) {
      val x = val::object();
      x.set("unixUtc", c.unix_utc);
      x.set("rising", c.rising);
      crossings.call<void>("push", x);
    }
    result.set("crossings", crossings);
    result.set("culminationUtc", e.culmination_utc);
    result.set("culminationAltitudeDeg", e.culmination_altitude_deg);
    return result;
  });
}

val sun_path_js(const Observer& o, double start, double end, const PathOptionsJs& opt) {
  return guarded([&] {
    val result = val::array();
    for (const PathPoint& p : sun_path(o, start, end, opt.get())) {
      val x = val::object();
      x.set("unixUtc", p.unix_utc);
      x.set("position", horizontal(p.position));
      result.call<void>("push", x);
    }
    return result;
  });
}

Horizontal moon_position_js(double t, const Observer& o, const MoonOptions& opt) {
  return guarded([&] { return moon_position(t, o, opt); });
}

double moon_illuminated_fraction_js(double t) {
  return guarded([&] { return moon_illuminated_fraction(t); });
}

double magnetic_declination_js(const Observer& o, double t) {
  return guarded([&] { return magnetic_declination_deg(o, t); });
}

Camera camera_from_fov_js(double w, double h, double fov) {
  return guarded([&] { return camera_from_fov(w, h, fov); });
}

Projection project_js(const ViewJs& v, const Horizontal& d) {
  return guarded([&] { return project(v.get(), d); });
}

Horizontal unproject_js(const ViewJs& v, const ScreenPoint& p) {
  return guarded([&] { return unproject(v.get(), p); });
}

val project_line_js(const ViewJs& v, const val& line) {
  return guarded([&] { return polylines(project_line(v.get(), to_line(line))); });
}

val horizon_line_js(const ViewJs& v) {
  return guarded([&] { return polylines(horizon_line(v.get())); });
}

val find_time_js(const Observer& o, const Horizontal& d, double start, double end,
                 const SearchOptionsJs& opt) {
  return guarded([&] { return time_match(find_time(o, d, start, end, opt.get())); });
}

val find_dates_js(const Observer& o, const Horizontal& d, double start, double end,
                  const SearchOptionsJs& opt) {
  return guarded([&] {
    val result = val::array();
    for (const TimeMatch& m : find_dates(o, d, start, end, opt.get())) {
      result.call<void>("push", time_match(m));
    }
    return result;
  });
}

val find_azimuth_times_js(const Observer& o, double azimuth, double start, double end,
                          const SearchOptionsJs& opt) {
  return guarded([&] {
    val result = val::array();
    for (const AzimuthCrossing& c : find_azimuth_times(o, azimuth, start, end, opt.get())) {
      val x = val::object();
      x.set("unixUtc", c.unix_utc);
      x.set("sun", horizontal(c.sun));
      result.call<void>("push", x);
    }
    return result;
  });
}

HeadingEstimate heading_predict_js(const HeadingEstimate& e, double dt, double drift) {
  return guarded([&] { return heading_predict(e, dt, drift); });
}

HeadingEstimate heading_update_js(const HeadingEstimate& e, const HeadingMeasurement& m) {
  return guarded([&] { return heading_update(e, m); });
}

HeadingMeasurement heading_from_magnetometer_js(const Quaternion& q, const Vector3& f,
                                                const Observer& o, double t,
                                                const MagnetometerOptions& opt) {
  return guarded([&] { return heading_from_magnetometer(q, f, o, t, opt); });
}

HeadingMeasurement heading_from_direction_js(const ViewJs& v, const ScreenPoint& p, double az,
                                             double sigma) {
  return guarded([&] { return heading_from_direction(v.get(), p, az, sigma); });
}

/// A HeadingFilter crosses as a plain object with the covariance as a JS array.
val to_js(const HeadingFilter& f) {
  val out = val::object();
  out.set("offsetDeg", f.offset_deg);
  out.set("biasUt", f.bias_ut);
  out.set("disturbanceUt", f.disturbance_ut);
  val covariance = val::array();
  for (const double c : f.covariance) {
    covariance.call<void>("push", c);
  }
  out.set("covariance", covariance);
  return out;
}

HeadingFilter heading_filter_from_js(const val& v) {
  HeadingFilter f{.offset_deg = v["offsetDeg"].as<double>(),
                  .bias_ut = v["biasUt"].as<Vector3>(),
                  .disturbance_ut = v["disturbanceUt"].as<Vector3>(),
                  .covariance = {}};
  const val covariance = v["covariance"];
  if (covariance["length"].as<std::size_t>() != f.covariance.size()) {
    throw std::invalid_argument("covariance has the wrong length");
  }
  for (std::size_t i = 0; i < f.covariance.size(); ++i)
    f.covariance[i] = covariance[i].as<double>();
  return f;
}

val heading_filter_start_js(const Vector3& bias, double sigma, const HeadingFilterOptions& opt) {
  return guarded([&] { return to_js(heading_filter_start(bias, sigma, opt)); });
}

val heading_filter_predict_js(const val& f, double dt, const HeadingFilterOptions& opt) {
  return guarded([&] { return to_js(heading_filter_predict(heading_filter_from_js(f), dt, opt)); });
}

val heading_filter_update_js(const val& f, const HeadingMeasurement& m) {
  return guarded([&] { return to_js(heading_filter_update(heading_filter_from_js(f), m)); });
}

val heading_filter_update_magnetometer_js(const val& f, const Quaternion& q, const Vector3& field,
                                          const Observer& o, double t,
                                          const HeadingFilterOptions& opt) {
  return guarded([&] {
    return to_js(
        heading_filter_update_magnetometer(heading_filter_from_js(f), q, field, o, t, opt));
  });
}

// The terrain. A grid has many heights, so they cross as a Float32Array, not as a JS array.

/// Copies `values` out of the memory of the module into a new typed array.
template <typename T>
val typed_copy(const std::vector<T>& values) {
  return val(emscripten::typed_memory_view(values.size(), values.data())).call<val>("slice");
}

val grid_to_js(const ElevationGrid& g) {
  val o = val::object();
  o.set("northLatitudeDeg", g.north_latitude_deg);
  o.set("westLongitudeDeg", g.west_longitude_deg);
  o.set("latitudeStepDeg", g.latitude_step_deg);
  o.set("longitudeStepDeg", g.longitude_step_deg);
  o.set("rows", g.rows);
  o.set("columns", g.columns);
  o.set("heightsM", typed_copy(g.heights_m));
  return o;
}

ElevationGrid grid_from_js(const val& v) {
  return {.north_latitude_deg = v["northLatitudeDeg"].as<double>(),
          .west_longitude_deg = v["westLongitudeDeg"].as<double>(),
          .latitude_step_deg = v["latitudeStepDeg"].as<double>(),
          .longitude_step_deg = v["longitudeStepDeg"].as<double>(),
          .rows = v["rows"].as<int>(),
          .columns = v["columns"].as<int>(),
          .heights_m = emscripten::convertJSArrayToNumberVector<float>(v["heightsM"])};
}

val profile_to_js(const HorizonProfile& p) {
  val o = val::object();
  o.set("azimuthStepDeg", p.azimuth_step_deg);
  o.set("altitudeDeg", typed_copy(p.altitude_deg));
  o.set("distanceM", typed_copy(p.distance_m));
  o.set("observerElevationM", p.observer_elevation_m);
  return o;
}

HorizonProfile profile_from_js(const val& v) {
  return {.azimuth_step_deg = v["azimuthStepDeg"].as<double>(),
          .altitude_deg = emscripten::convertJSArrayToNumberVector<double>(v["altitudeDeg"]),
          .distance_m = emscripten::convertJSArrayToNumberVector<double>(v["distanceM"]),
          .observer_elevation_m = v["observerElevationM"].as<double>()};
}

val read_elevation_geotiff_js(const val& data, int level) {
  return guarded([&] {
    return grid_to_js(read_elevation_geotiff(
        emscripten::convertJSArrayToNumberVector<std::uint8_t>(data), level));
  });
}

val elevation_tiles_js(const Observer& o, double max_distance_m) {
  return guarded([&] {
    val result = val::array();
    for (const ElevationTile& t : elevation_tiles(o, max_distance_m)) {
      val x = val::object();
      x.set("latitudeDeg", t.latitude_deg);
      x.set("longitudeDeg", t.longitude_deg);
      x.set("distanceM", t.distance_m);
      result.call<void>("push", x);
    }
    return result;
  });
}

val map_tiles_js(const Observer& o, double max_distance_m, int zoom) {
  return guarded([&] {
    val result = val::array();
    for (const MapTile& t : map_tiles(o, max_distance_m, zoom)) {
      val x = val::object();
      x.set("zoom", t.zoom);
      x.set("x", t.x);
      x.set("y", t.y);
      x.set("distanceM", t.distance_m);
      result.call<void>("push", x);
    }
    return result;
  });
}

val terrarium_grid_js(const val& rgba, int width, int height, const val& tile) {
  return guarded([&] {
    const MapTile t{
        .zoom = tile["zoom"].as<int>(), .x = tile["x"].as<int>(), .y = tile["y"].as<int>()};
    return grid_to_js(terrarium_grid(emscripten::convertJSArrayToNumberVector<std::uint8_t>(rgba),
                                     width, height, t));
  });
}

val horizon_profile_js(const Observer& o, const val& tiles, const HorizonOptions& opt) {
  return guarded([&] {
    std::vector<ElevationGrid> grids;
    const auto count = tiles["length"].as<std::size_t>();
    grids.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
      grids.push_back(grid_from_js(tiles[i]));
    }
    return profile_to_js(horizon_profile(o, grids, opt));
  });
}

double terrain_altitude_deg_js(const val& profile, double azimuth_deg) {
  return guarded([&] { return terrain_altitude_deg(profile_from_js(profile), azimuth_deg); });
}

val terrain_events_js(const Observer& o, const val& profile, double start, double end,
                      const TerrainEventOptionsJs& opt) {
  return guarded([&] {
    const TerrainEvents e = terrain_events(o, profile_from_js(profile), start, end, opt.get());
    val result = val::object();
    result.set("kind", static_cast<int>(e.kind));
    val crossings = val::array();
    for (const Crossing& c : e.crossings) {
      val x = val::object();
      x.set("unixUtc", c.unix_utc);
      x.set("rising", c.rising);
      crossings.call<void>("push", x);
    }
    result.set("crossings", crossings);
    return result;
  });
}

/// The C++ defaults of the option structs, the view, and the heading filter, for index.js.
val defaults_js() {
  val d = val::object();
  d.set("sunOptions", SunOptionsJs{});
  d.set("eventOptions", EventOptionsJs{});
  d.set("pathOptions", PathOptionsJs{});
  d.set("searchOptions", SearchOptionsJs{});
  d.set("moonOptions", MoonOptions{});
  d.set("horizonOptions", HorizonOptions{});
  d.set("terrainEventOptions", TerrainEventOptionsJs{});
  d.set("magnetometerOptions", MagnetometerOptions{});
  d.set("headingEstimate", HeadingEstimate{});
  d.set("camera", Camera{});
  d.set("view", ViewJs{});
  d.set("sunriseAltitudeDeg", kSunriseAltitudeDeg);
  d.set("headingDriftDeg2PerS", kDefaultHeadingDriftDeg2PerS);
  d.set("headingFilterOptions", HeadingFilterOptions{});
  d.set("headingFilterSize", kHeadingFilterSize);
  return d;
}

}  // namespace

EMSCRIPTEN_BINDINGS(sunradar) {
  using namespace emscripten;

  value_object<Observer>("Observer")
      .field("latitudeDeg", &Observer::latitude_deg)
      .field("longitudeDeg", &Observer::longitude_deg);
  value_object<Horizontal>("Horizontal")
      .field("azimuthDeg", &Horizontal::azimuth_deg)
      .field("altitudeDeg", &Horizontal::altitude_deg);
  value_object<Atmosphere>("Atmosphere")
      .field("pressureHpa", &Atmosphere::pressure_hpa)
      .field("temperatureC", &Atmosphere::temperature_c);
  value_object<Quaternion>("Quaternion")
      .field("w", &Quaternion::w)
      .field("x", &Quaternion::x)
      .field("y", &Quaternion::y)
      .field("z", &Quaternion::z);
  value_object<Vector3>("Vector3")
      .field("x", &Vector3::x)
      .field("y", &Vector3::y)
      .field("z", &Vector3::z);
  value_object<ScreenPoint>("ScreenPoint").field("x", &ScreenPoint::x).field("y", &ScreenPoint::y);
  value_object<SunOptionsJs>("SunOptions")
      .field("model", &SunOptionsJs::model)
      .field("refraction", &SunOptionsJs::refraction)
      .field("atmosphere", &SunOptionsJs::atmosphere);
  value_object<EventOptionsJs>("EventOptions")
      .field("model", &EventOptionsJs::model)
      .field("altitudeDeg", &EventOptionsJs::altitudeDeg)
      .field("searchStepS", &EventOptionsJs::searchStepS)
      .field("toleranceS", &EventOptionsJs::toleranceS);
  value_object<PathOptionsJs>("PathOptions")
      .field("model", &PathOptionsJs::model)
      .field("stepS", &PathOptionsJs::stepS)
      .field("refraction", &PathOptionsJs::refraction)
      .field("atmosphere", &PathOptionsJs::atmosphere);
  value_object<SearchOptionsJs>("SearchOptions")
      .field("model", &SearchOptionsJs::model)
      .field("stepS", &SearchOptionsJs::stepS)
      .field("toleranceS", &SearchOptionsJs::toleranceS)
      .field("refraction", &SearchOptionsJs::refraction)
      .field("atmosphere", &SearchOptionsJs::atmosphere)
      .field("maxSeparationDeg", &SearchOptionsJs::maxSeparationDeg);
  value_object<MoonOptions>("MoonOptions")
      .field("refraction", &MoonOptions::refraction)
      .field("atmosphere", &MoonOptions::atmosphere);
  value_object<HorizonOptions>("HorizonOptions")
      .field("azimuthStepDeg", &HorizonOptions::azimuth_step_deg)
      .field("maxDistanceM", &HorizonOptions::max_distance_m)
      .field("eyeHeightM", &HorizonOptions::eye_height_m)
      .field("useGridElevation", &HorizonOptions::use_grid_elevation)
      .field("observerElevationM", &HorizonOptions::observer_elevation_m)
      .field("refractionCoefficient", &HorizonOptions::refraction_coefficient);
  value_object<TerrainEventOptionsJs>("TerrainEventOptions")
      .field("model", &TerrainEventOptionsJs::model)
      .field("refraction", &TerrainEventOptionsJs::refraction)
      .field("atmosphere", &TerrainEventOptionsJs::atmosphere)
      .field("limbDeg", &TerrainEventOptionsJs::limbDeg)
      .field("searchStepS", &TerrainEventOptionsJs::searchStepS)
      .field("toleranceS", &TerrainEventOptionsJs::toleranceS);
  value_object<Camera>("Camera")
      .field("width", &Camera::width)
      .field("height", &Camera::height)
      .field("fx", &Camera::fx)
      .field("fy", &Camera::fy)
      .field("cx", &Camera::cx)
      .field("cy", &Camera::cy)
      .field("k1", &Camera::k1)
      .field("k2", &Camera::k2)
      .field("k3", &Camera::k3)
      .field("p1", &Camera::p1)
      .field("p2", &Camera::p2);
  value_object<ViewJs>("View")
      .field("deviceToEnu", &ViewJs::deviceToEnu)
      .field("screenRotation", &ViewJs::screenRotation)
      .field("headingOffsetDeg", &ViewJs::headingOffsetDeg)
      .field("camera", &ViewJs::camera);
  value_object<Projection>("Projection")
      .field("valid", &Projection::valid)
      .field("point", &Projection::point);
  value_object<HeadingEstimate>("HeadingEstimate")
      .field("offsetDeg", &HeadingEstimate::offset_deg)
      .field("varianceDeg2", &HeadingEstimate::variance_deg2);
  value_object<HeadingMeasurement>("HeadingMeasurement")
      .field("offsetDeg", &HeadingMeasurement::offset_deg)
      .field("varianceDeg2", &HeadingMeasurement::variance_deg2);
  value_object<MagnetometerOptions>("MagnetometerOptions")
      .field("sensorSigmaDeg", &MagnetometerOptions::sensor_sigma_deg);
  value_object<HeadingFilterOptions>("HeadingFilterOptions")
      .field("driftDeg2PerS", &HeadingFilterOptions::drift_deg2_per_s)
      .field("biasDriftUt2PerS", &HeadingFilterOptions::bias_drift_ut2_per_s)
      .field("disturbanceSigmaUt", &HeadingFilterOptions::disturbance_sigma_ut)
      .field("disturbanceTimeS", &HeadingFilterOptions::disturbance_time_s)
      .field("noiseSigmaUt", &HeadingFilterOptions::noise_sigma_ut);

  function("version", &version_js);
  function("defaults", &defaults_js);
  function("sunPosition", &sun_position_js);
  function("sunEvents", &sun_events_js);
  function("sunPath", &sun_path_js);
  function("moonPosition", &moon_position_js);
  function("moonIlluminatedFraction", &moon_illuminated_fraction_js);
  function("readElevationGeotiff", &read_elevation_geotiff_js);
  function("elevationTiles", &elevation_tiles_js);
  function("mapTiles", &map_tiles_js);
  function("terrariumGrid", &terrarium_grid_js);
  function("horizonProfile", &horizon_profile_js);
  function("terrainAltitudeDeg", &terrain_altitude_deg_js);
  function("terrainEvents", &terrain_events_js);
  function("magneticDeclinationDeg", &magnetic_declination_js);
  function("cameraFromFov", &camera_from_fov_js);
  function("project", &project_js);
  function("unproject", &unproject_js);
  function("projectLine", &project_line_js);
  function("horizonLine", &horizon_line_js);
  function("findTime", &find_time_js);
  function("findDates", &find_dates_js);
  function("findAzimuthTimes", &find_azimuth_times_js);
  function("headingPredict", &heading_predict_js);
  function("headingUpdate", &heading_update_js);
  function("headingFromMagnetometer", &heading_from_magnetometer_js);
  function("headingFromDirection", &heading_from_direction_js);
  function("headingFilterStart", &heading_filter_start_js);
  function("headingFilterPredict", &heading_filter_predict_js);
  function("headingFilterUpdate", &heading_filter_update_js);
  function("headingFilterUpdateMagnetometer", &heading_filter_update_magnetometer_js);
}
