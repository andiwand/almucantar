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

// Mirror structs with integer fields instead of enums.
struct SunOptionsJs {
  int model = 0;
  bool refraction = true;
  Atmosphere atmosphere;
  [[nodiscard]] SunOptions get() const {
    return {.model = to_enum<SolarModel>(model, "model"),
            .refraction = refraction,
            .atmosphere = atmosphere};
  }
};

struct EventOptionsJs {
  int model = 0;
  double altitudeDeg = kSunriseAltitudeDeg;
  double searchStepS = 600;
  double toleranceS = 0.5;
  [[nodiscard]] EventOptions get() const {
    return {.model = to_enum<SolarModel>(model, "model"),
            .altitude_deg = altitudeDeg,
            .search_step_s = searchStepS,
            .tolerance_s = toleranceS};
  }
};

struct PathOptionsJs {
  int model = 0;
  double stepS = 300;
  bool refraction = true;
  Atmosphere atmosphere;
  [[nodiscard]] PathOptions get() const {
    return {.model = to_enum<SolarModel>(model, "model"),
            .step_s = stepS,
            .refraction = refraction,
            .atmosphere = atmosphere};
  }
};

struct SearchOptionsJs {
  int model = 0;
  double stepS = 300;
  double toleranceS = 0.5;
  bool refraction = true;
  Atmosphere atmosphere;
  double maxSeparationDeg = 2;
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
  Quaternion deviceToEnu;
  int screenRotation = 0;
  double headingOffsetDeg = 0;
  Camera camera;
  [[nodiscard]] View get() const {
    return {.device_to_enu = deviceToEnu,
            .screen_rotation = to_enum<ScreenRotation>(screenRotation, "screenRotation"),
            .heading_offset_deg = headingOffsetDeg,
            .camera = camera};
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

/// The C++ defaults of the option structs and of Camera, for index.js.
val defaults_js() {
  val d = val::object();
  d.set("sunOptions", SunOptionsJs{});
  d.set("eventOptions", EventOptionsJs{});
  d.set("pathOptions", PathOptionsJs{});
  d.set("searchOptions", SearchOptionsJs{});
  d.set("moonOptions", MoonOptions{});
  d.set("magnetometerOptions", MagnetometerOptions{});
  d.set("headingEstimate", HeadingEstimate{});
  d.set("camera", Camera{});
  d.set("view", ViewJs{});
  d.set("sunriseAltitudeDeg", kSunriseAltitudeDeg);
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

  function("version", &version_js);
  function("defaults", &defaults_js);
  function("sunPosition", &sun_position_js);
  function("sunEvents", &sun_events_js);
  function("sunPath", &sun_path_js);
  function("moonPosition", &moon_position_js);
  function("moonIlluminatedFraction", &moon_illuminated_fraction_js);
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
}
