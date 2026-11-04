// The Python binding of the public API (src/almucantar/*.hpp). It must not include
// an internal header.

#include <pybind11/pybind11.h>
#include <pybind11/stl.h>

#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "almucantar/almucantar.hpp"

namespace py = pybind11;
using namespace pybind11::literals;

namespace {

/// Formats `x` the way Python's repr() formats a float: the shortest exact form.
std::string num(const double x) { return py::repr(py::float_(x)).cast<std::string>(); }

std::string repr(const almucantar::Observer& o) {
  return "Observer(latitude_deg=" + num(o.latitude_deg) +
         ", longitude_deg=" + num(o.longitude_deg) + ")";
}

std::string repr(const almucantar::Horizontal& h) {
  return "Horizontal(azimuth_deg=" + num(h.azimuth_deg) + ", altitude_deg=" + num(h.altitude_deg) +
         ")";
}

std::string repr(const almucantar::Atmosphere& a) {
  return "Atmosphere(pressure_hpa=" + num(a.pressure_hpa) +
         ", temperature_c=" + num(a.temperature_c) + ")";
}

void bind_types(const py::module_& m) {
  using almucantar::Atmosphere;
  using almucantar::Horizontal;
  using almucantar::Observer;

  py::class_<Observer>(m, "Observer", "A place on the Earth, in geodetic coordinates (WGS 84).")
      .def(py::init([](const double latitude_deg, const double longitude_deg) {
             return Observer{.latitude_deg = latitude_deg, .longitude_deg = longitude_deg};
           }),
           "latitude_deg"_a = 0.0, "longitude_deg"_a = 0.0)
      .def_readwrite("latitude_deg", &Observer::latitude_deg, "north is positive, -90 to 90")
      .def_readwrite("longitude_deg", &Observer::longitude_deg, "east is positive, -180 to 180")
      .def("__repr__", [](const Observer& o) { return repr(o); });

  py::class_<Horizontal>(m, "Horizontal", "A direction in the local horizontal system.")
      .def(py::init([](const double azimuth_deg, const double altitude_deg) {
             return Horizontal{.azimuth_deg = azimuth_deg, .altitude_deg = altitude_deg};
           }),
           "azimuth_deg"_a = 0.0, "altitude_deg"_a = 0.0)
      .def_readwrite("azimuth_deg", &Horizontal::azimuth_deg,
                     "from true north, clockwise (east is 90), 0 to 360")
      .def_readwrite("altitude_deg", &Horizontal::altitude_deg, "above the horizon, -90 to 90")
      .def("__repr__", [](const Horizontal& h) { return repr(h); });

  py::class_<Atmosphere>(m, "Atmosphere", "The state of the air near the observer.")
      .def(py::init([](const double pressure_hpa, const double temperature_c) {
             return Atmosphere{.pressure_hpa = pressure_hpa, .temperature_c = temperature_c};
           }),
           "pressure_hpa"_a = Atmosphere{}.pressure_hpa,
           "temperature_c"_a = Atmosphere{}.temperature_c)
      .def_readwrite("pressure_hpa", &Atmosphere::pressure_hpa)
      .def_readwrite("temperature_c", &Atmosphere::temperature_c)
      .def("__repr__", [](const Atmosphere& a) { return repr(a); });
}

void bind_moon(py::module_& m) {
  using namespace almucantar;
  const MoonOptions defaults;
  py::class_<MoonOptions>(m, "MoonOptions")
      .def(py::init([](const bool refraction, const Atmosphere& atmosphere) {
             return MoonOptions{.refraction = refraction, .atmosphere = atmosphere};
           }),
           "refraction"_a = defaults.refraction, "atmosphere"_a = defaults.atmosphere)
      .def_readwrite("refraction", &MoonOptions::refraction)
      .def_readwrite("atmosphere", &MoonOptions::atmosphere);
  m.def("moon_position", &moon_position, "unix_utc"_a, "observer"_a, "options"_a = MoonOptions{},
        "Returns the apparent topocentric position of the center of the moon.");
  m.def("moon_illuminated_fraction", &moon_illuminated_fraction, "unix_utc"_a,
        "Returns the illuminated fraction of the disc of the moon, 0 (new) to 1 (full).");
}

void bind_heading(py::module_& m) {
  using namespace almucantar;

  py::class_<Vector3>(m, "Vector3", "A vector for sensor values.")
      .def(py::init([](const double x, const double y, const double z) {
             return Vector3{.x = x, .y = y, .z = z};
           }),
           "x"_a = 0.0, "y"_a = 0.0, "z"_a = 0.0)
      .def_readwrite("x", &Vector3::x)
      .def_readwrite("y", &Vector3::y)
      .def_readwrite("z", &Vector3::z);

  m.attr("UNKNOWN_HEADING_VARIANCE_DEG2") = kUnknownHeadingVarianceDeg2;
  m.attr("DEFAULT_HEADING_DRIFT_DEG2_PER_S") = kDefaultHeadingDriftDeg2PerS;

  py::class_<HeadingEstimate>(m, "HeadingEstimate")
      .def(py::init([](const double offset_deg, const double variance_deg2) {
             return HeadingEstimate{.offset_deg = offset_deg, .variance_deg2 = variance_deg2};
           }),
           "offset_deg"_a = 0.0, "variance_deg2"_a = kUnknownHeadingVarianceDeg2)
      .def_readwrite("offset_deg", &HeadingEstimate::offset_deg)
      .def_readwrite("variance_deg2", &HeadingEstimate::variance_deg2)
      .def("__repr__", [](const HeadingEstimate& e) {
        return "HeadingEstimate(offset_deg=" + num(e.offset_deg) +
               ", variance_deg2=" + num(e.variance_deg2) + ")";
      });

  py::class_<HeadingMeasurement>(m, "HeadingMeasurement")
      .def(py::init([](const double offset_deg, const double variance_deg2) {
             return HeadingMeasurement{.offset_deg = offset_deg, .variance_deg2 = variance_deg2};
           }),
           "offset_deg"_a, "variance_deg2"_a)
      .def_readwrite("offset_deg", &HeadingMeasurement::offset_deg)
      .def_readwrite("variance_deg2", &HeadingMeasurement::variance_deg2)
      .def("__repr__", [](const HeadingMeasurement& e) {
        return "HeadingMeasurement(offset_deg=" + num(e.offset_deg) +
               ", variance_deg2=" + num(e.variance_deg2) + ")";
      });

  py::class_<MagnetometerOptions>(m, "MagnetometerOptions")
      .def(py::init([](const double sensor_sigma_deg) {
             return MagnetometerOptions{.sensor_sigma_deg = sensor_sigma_deg};
           }),
           "sensor_sigma_deg"_a = MagnetometerOptions{}.sensor_sigma_deg)
      .def_readwrite("sensor_sigma_deg", &MagnetometerOptions::sensor_sigma_deg);

  m.def("heading_predict", &heading_predict, "estimate"_a, "dt_s"_a,
        "drift_deg2_per_s"_a = kDefaultHeadingDriftDeg2PerS,
        "Increases the variance for the time dt_s, because the yaw of the frame drifts.");
  m.def("heading_update", &heading_update, "estimate"_a, "measurement"_a,
        "Combines the estimate with a measurement (a Kalman update on the circle).");
  m.def("heading_from_magnetometer", &heading_from_magnetometer, "device_to_frame"_a, "field_ut"_a,
        "observer"_a, "unix_utc"_a, "options"_a = MagnetometerOptions{},
        "The heading offset from a magnetometer, with a variance from the field disturbance.");
  m.def("heading_from_direction", &heading_from_direction, "view"_a, "point"_a,
        "true_azimuth_deg"_a, "sigma_deg"_a,
        "The heading offset from a screen point that shows a known true azimuth.");

  m.attr("HEADING_FILTER_SIZE") = kHeadingFilterSize;

  const HeadingFilterOptions filter_defaults;
  py::class_<HeadingFilterOptions>(m, "HeadingFilterOptions")
      .def(py::init([](const double drift_deg2_per_s, const double bias_drift_ut2_per_s,
                       const double disturbance_sigma_ut, const double disturbance_time_s,
                       const double noise_sigma_ut) {
             return HeadingFilterOptions{.drift_deg2_per_s = drift_deg2_per_s,
                                         .bias_drift_ut2_per_s = bias_drift_ut2_per_s,
                                         .disturbance_sigma_ut = disturbance_sigma_ut,
                                         .disturbance_time_s = disturbance_time_s,
                                         .noise_sigma_ut = noise_sigma_ut};
           }),
           "drift_deg2_per_s"_a = filter_defaults.drift_deg2_per_s,
           "bias_drift_ut2_per_s"_a = filter_defaults.bias_drift_ut2_per_s,
           "disturbance_sigma_ut"_a = filter_defaults.disturbance_sigma_ut,
           "disturbance_time_s"_a = filter_defaults.disturbance_time_s,
           "noise_sigma_ut"_a = filter_defaults.noise_sigma_ut)
      .def_readwrite("drift_deg2_per_s", &HeadingFilterOptions::drift_deg2_per_s)
      .def_readwrite("bias_drift_ut2_per_s", &HeadingFilterOptions::bias_drift_ut2_per_s)
      .def_readwrite("disturbance_sigma_ut", &HeadingFilterOptions::disturbance_sigma_ut)
      .def_readwrite("disturbance_time_s", &HeadingFilterOptions::disturbance_time_s)
      .def_readwrite("noise_sigma_ut", &HeadingFilterOptions::noise_sigma_ut);

  py::class_<HeadingFilter>(m, "HeadingFilter",
                            "The heading filter with the magnetometer. Make it with "
                            "heading_filter_start().")
      .def(py::init<>())
      .def_readwrite("offset_deg", &HeadingFilter::offset_deg)
      .def_readwrite("bias_ut", &HeadingFilter::bias_ut)
      .def_readwrite("disturbance_ut", &HeadingFilter::disturbance_ut)
      .def_readwrite("covariance", &HeadingFilter::covariance)
      .def_property_readonly(
          "variance_deg2", [](const HeadingFilter& f) { return f.covariance[0]; },
          "The variance of the offset.")
      .def("__repr__", [](const HeadingFilter& f) {
        return "HeadingFilter(offset_deg=" + num(f.offset_deg) +
               ", variance_deg2=" + num(f.covariance[0]) + ")";
      });

  m.def("heading_filter_start", &heading_filter_start, "bias_ut"_a, "bias_sigma_ut"_a,
        "options"_a = HeadingFilterOptions{},
        "A heading filter with an unknown offset and a first guess of the bias.");
  m.def("heading_filter_predict", &heading_filter_predict, "filter"_a, "dt_s"_a,
        "options"_a = HeadingFilterOptions{},
        "Moves the filter forward by dt_s: the variances grow, and the disturbance decays.");
  m.def("heading_filter_update", &heading_filter_update, "filter"_a, "measurement"_a,
        "Combines the filter with a measurement of the offset, for example a sun tap.");
  m.def("heading_filter_update_magnetometer", &heading_filter_update_magnetometer, "filter"_a,
        "device_to_frame"_a, "field_ut"_a, "observer"_a, "unix_utc"_a,
        "options"_a = HeadingFilterOptions{},
        "Combines the filter with one magnetometer sample (about one per second).");
}

void bind_search(py::module_& m) {
  using namespace almucantar;

  const SearchOptions defaults;
  py::class_<SearchOptions>(m, "SearchOptions")
      .def(py::init([](const SolarModel model, const double step_s, const double tolerance_s,
                       const bool refraction, const Atmosphere& atmosphere,
                       const double max_separation_deg) {
             return SearchOptions{.model = model,
                                  .step_s = step_s,
                                  .tolerance_s = tolerance_s,
                                  .refraction = refraction,
                                  .atmosphere = atmosphere,
                                  .max_separation_deg = max_separation_deg};
           }),
           "model"_a = defaults.model, "step_s"_a = defaults.step_s,
           "tolerance_s"_a = defaults.tolerance_s, "refraction"_a = defaults.refraction,
           "atmosphere"_a = defaults.atmosphere,
           "max_separation_deg"_a = defaults.max_separation_deg)
      .def_readwrite("model", &SearchOptions::model)
      .def_readwrite("step_s", &SearchOptions::step_s)
      .def_readwrite("tolerance_s", &SearchOptions::tolerance_s)
      .def_readwrite("refraction", &SearchOptions::refraction)
      .def_readwrite("atmosphere", &SearchOptions::atmosphere)
      .def_readwrite("max_separation_deg", &SearchOptions::max_separation_deg);

  py::class_<TimeMatch>(m, "TimeMatch", "The time when the sun is nearest to a direction.")
      .def_readonly("unix_utc", &TimeMatch::unix_utc)
      .def_readonly("separation_deg", &TimeMatch::separation_deg)
      .def_readonly("sun", &TimeMatch::sun)
      .def("__repr__", [](const TimeMatch& t) {
        return "TimeMatch(unix_utc=" + num(t.unix_utc) +
               ", separation_deg=" + num(t.separation_deg) + ", sun=" + repr(t.sun) + ")";
      });

  m.def("find_time", &find_time, "observer"_a, "direction"_a, "start_utc"_a, "end_utc"_a,
        "options"_a = SearchOptions{},
        "Finds the time between start_utc and end_utc when the sun is nearest to direction.");
  py::class_<AzimuthCrossing>(m, "AzimuthCrossing", "The sun has a given azimuth.")
      .def_readonly("unix_utc", &AzimuthCrossing::unix_utc)
      .def_readonly("sun", &AzimuthCrossing::sun)
      .def("__repr__", [](const AzimuthCrossing& c) {
        return "AzimuthCrossing(unix_utc=" + num(c.unix_utc) + ", sun=" + repr(c.sun) + ")";
      });
  m.def("find_azimuth_times", &find_azimuth_times, "observer"_a, "azimuth_deg"_a, "start_utc"_a,
        "end_utc"_a, "options"_a = SearchOptions{},
        "Finds the times between start_utc and end_utc when the sun has the azimuth azimuth_deg.");
  m.def("find_dates", &find_dates, "observer"_a, "direction"_a, "start_utc"_a, "end_utc"_a,
        "options"_a = SearchOptions{},
        "Finds the dates when the sun goes through direction, one 24-hour window at a time.");
}

void bind_view(py::module_& m) {
  using namespace almucantar;

  py::class_<Quaternion>(m, "Quaternion",
                         "A unit quaternion. For the view: device frame to East-North-Up.")
      .def(py::init([](const double w, const double x, const double y, const double z) {
             return Quaternion{.w = w, .x = x, .y = y, .z = z};
           }),
           "w"_a = 1.0, "x"_a = 0.0, "y"_a = 0.0, "z"_a = 0.0)
      .def_readwrite("w", &Quaternion::w)
      .def_readwrite("x", &Quaternion::x)
      .def_readwrite("y", &Quaternion::y)
      .def_readwrite("z", &Quaternion::z)
      .def("__repr__", [](const Quaternion& q) {
        return "Quaternion(w=" + num(q.w) + ", x=" + num(q.x) + ", y=" + num(q.y) +
               ", z=" + num(q.z) + ")";
      });

  py::class_<ScreenPoint>(m, "ScreenPoint", "A point on the screen in pixels, y goes down.")
      .def(py::init([](const double x, const double y) { return ScreenPoint{.x = x, .y = y}; }),
           "x"_a = 0.0, "y"_a = 0.0)
      .def_readwrite("x", &ScreenPoint::x)
      .def_readwrite("y", &ScreenPoint::y)
      .def("__repr__", [](const ScreenPoint& p) {
        return "ScreenPoint(x=" + num(p.x) + ", y=" + num(p.y) + ")";
      });

  py::enum_<ScreenRotation>(m, "ScreenRotation", "The meaning of Android Surface.ROTATION_*.")
      .value("rotation_0", ScreenRotation::rotation_0)
      .value("rotation_90", ScreenRotation::rotation_90)
      .value("rotation_180", ScreenRotation::rotation_180)
      .value("rotation_270", ScreenRotation::rotation_270);

  const Camera camera_defaults;
  py::class_<Camera>(m, "Camera", "A pinhole camera with Brown-Conrady distortion, in pixels.")
      .def(py::init([](const double width, const double height, const double fx, const double fy,
                       const double cx, const double cy, const double k1, const double k2,
                       const double k3, const double p1, const double p2) {
             return Camera{.width = width,
                           .height = height,
                           .fx = fx,
                           .fy = fy,
                           .cx = cx,
                           .cy = cy,
                           .k1 = k1,
                           .k2 = k2,
                           .k3 = k3,
                           .p1 = p1,
                           .p2 = p2};
           }),
           "width"_a = camera_defaults.width, "height"_a = camera_defaults.height,
           "fx"_a = camera_defaults.fx, "fy"_a = camera_defaults.fy, "cx"_a = camera_defaults.cx,
           "cy"_a = camera_defaults.cy, "k1"_a = 0.0, "k2"_a = 0.0, "k3"_a = 0.0, "p1"_a = 0.0,
           "p2"_a = 0.0)
      .def_readwrite("width", &Camera::width)
      .def_readwrite("height", &Camera::height)
      .def_readwrite("fx", &Camera::fx)
      .def_readwrite("fy", &Camera::fy)
      .def_readwrite("cx", &Camera::cx)
      .def_readwrite("cy", &Camera::cy)
      .def_readwrite("k1", &Camera::k1)
      .def_readwrite("k2", &Camera::k2)
      .def_readwrite("k3", &Camera::k3)
      .def_readwrite("p1", &Camera::p1)
      .def_readwrite("p2", &Camera::p2);

  const View view_defaults;
  py::class_<View>(m, "View", "Everything that defines what the screen shows.")
      .def(py::init([](const Quaternion& device_to_enu, const ScreenRotation screen_rotation,
                       const double heading_offset_deg, const Camera& camera) {
             return View{.device_to_enu = device_to_enu,
                         .screen_rotation = screen_rotation,
                         .heading_offset_deg = heading_offset_deg,
                         .camera = camera};
           }),
           "device_to_enu"_a = view_defaults.device_to_enu,
           "screen_rotation"_a = view_defaults.screen_rotation,
           "heading_offset_deg"_a = view_defaults.heading_offset_deg,
           "camera"_a = view_defaults.camera)
      .def_readwrite("device_to_enu", &View::device_to_enu)
      .def_readwrite("screen_rotation", &View::screen_rotation)
      .def_readwrite("heading_offset_deg", &View::heading_offset_deg)
      .def_readwrite("camera", &View::camera);

  py::class_<Projection>(m, "Projection")
      .def_readonly("valid", &Projection::valid,
                    "True if the direction is in front and inside the valid cone")
      .def_readonly("point", &Projection::point)
      .def("__repr__", [](const Projection& p) {
        return std::string("Projection(valid=") + (p.valid ? "True" : "False") +
               ", point=ScreenPoint(x=" + num(p.point.x) + ", y=" + num(p.point.y) + "))";
      });

  m.def("magnetic_declination_deg", &magnetic_declination_deg, "observer"_a, "unix_utc"_a,
        "Returns the magnetic declination (WMM2025), positive to the east.");
  m.def("camera_from_fov", &camera_from_fov, "width"_a, "height"_a, "horizontal_fov_deg"_a,
        "Returns a camera without distortion with the given horizontal field of view.");
  m.def("project", &project, "view"_a, "direction"_a, "Projects a direction to the screen.");
  m.def("project_points", &project_points, "view"_a, "directions"_a,
        "Projects many directions in one call. Returns a list of Projection.");
  m.def("unproject", &unproject, "view"_a, "point"_a,
        "Returns the direction that the screen point shows.");
  m.def("project_line", &project_line, "view"_a, "line"_a,
        "Projects a line of directions, clipped to the valid area. Returns a list of "
        "polylines.");
  m.def("project_path", &project_path, "view"_a, "path"_a,
        "Projects a path of the sun (see sun_path). Returns a list of polylines.");
  m.def("horizon_line", &horizon_line, "view"_a,
        "Projects the astronomical horizon. Returns a list of polylines.");
}

void bind_sun(py::module_& m) {
  using namespace almucantar;

  py::enum_<SolarModel>(m, "SolarModel", "The model that calculates the position of the sun.")
      .value("noaa", SolarModel::noaa, "NOAA solar calculator (Meeus)")
      .value("astronomy_engine", SolarModel::astronomy_engine, "Astronomy Engine (more precise)");

  py::enum_<DayKind>(m, "DayKind")
      .value("normal", DayKind::normal, "the sun crosses the threshold at least once")
      .value("polar_day", DayKind::polar_day, "the sun stays above the threshold")
      .value("polar_night", DayKind::polar_night, "the sun stays below the threshold");

  m.attr("SUNRISE_ALTITUDE_DEG") = kSunriseAltitudeDeg;

  const SunOptions sun_defaults;
  py::class_<SunOptions>(m, "SunOptions")
      .def(
          py::init([](const SolarModel model, const bool refraction, const Atmosphere& atmosphere) {
            return SunOptions{.model = model, .refraction = refraction, .atmosphere = atmosphere};
          }),
          "model"_a = sun_defaults.model, "refraction"_a = sun_defaults.refraction,
          "atmosphere"_a = sun_defaults.atmosphere)
      .def_readwrite("model", &SunOptions::model)
      .def_readwrite("refraction", &SunOptions::refraction)
      .def_readwrite("atmosphere", &SunOptions::atmosphere);

  const EventOptions event_defaults;
  py::class_<EventOptions>(m, "EventOptions")
      .def(py::init([](const SolarModel model, const double altitude_deg,
                       const double search_step_s, const double tolerance_s) {
             return EventOptions{.model = model,
                                 .altitude_deg = altitude_deg,
                                 .search_step_s = search_step_s,
                                 .tolerance_s = tolerance_s};
           }),
           "model"_a = event_defaults.model, "altitude_deg"_a = event_defaults.altitude_deg,
           "search_step_s"_a = event_defaults.search_step_s,
           "tolerance_s"_a = event_defaults.tolerance_s)
      .def_readwrite("model", &EventOptions::model)
      .def_readwrite("altitude_deg", &EventOptions::altitude_deg)
      .def_readwrite("search_step_s", &EventOptions::search_step_s)
      .def_readwrite("tolerance_s", &EventOptions::tolerance_s);

  py::class_<Crossing>(m, "Crossing", "The sun crosses the threshold altitude.")
      .def_readonly("unix_utc", &Crossing::unix_utc)
      .def_readonly("rising", &Crossing::rising, "True for a sunrise, False for a sunset")
      .def("__repr__", [](const Crossing& c) {
        return "Crossing(unix_utc=" + num(c.unix_utc) +
               ", rising=" + (c.rising ? "True" : "False") + ")";
      });

  py::class_<DayEvents>(m, "DayEvents")
      .def_readonly("kind", &DayEvents::kind)
      .def_readonly("crossings", &DayEvents::crossings, "all crossings, in time order")
      .def_readonly("culmination_utc", &DayEvents::culmination_utc)
      .def_readonly("culmination_altitude_deg", &DayEvents::culmination_altitude_deg);

  const PathOptions path_defaults;
  py::class_<PathOptions>(m, "PathOptions")
      .def(py::init([](const SolarModel model, const double step_s, const bool refraction,
                       const Atmosphere& atmosphere) {
             return PathOptions{.model = model,
                                .step_s = step_s,
                                .refraction = refraction,
                                .atmosphere = atmosphere};
           }),
           "model"_a = path_defaults.model, "step_s"_a = path_defaults.step_s,
           "refraction"_a = path_defaults.refraction, "atmosphere"_a = path_defaults.atmosphere)
      .def_readwrite("model", &PathOptions::model)
      .def_readwrite("step_s", &PathOptions::step_s)
      .def_readwrite("refraction", &PathOptions::refraction)
      .def_readwrite("atmosphere", &PathOptions::atmosphere);

  py::class_<PathPoint>(m, "PathPoint")
      .def_readonly("unix_utc", &PathPoint::unix_utc)
      .def_readonly("position", &PathPoint::position)
      .def("__repr__", [](const PathPoint& p) {
        return "PathPoint(unix_utc=" + num(p.unix_utc) + ", position=" + repr(p.position) + ")";
      });

  m.def("sun_position", &sun_position, "unix_utc"_a, "observer"_a, "options"_a = SunOptions{},
        "Returns the position of the center of the sun at unix_utc for observer.");
  m.def("sun_events", &sun_events, "observer"_a, "start_utc"_a, "end_utc"_a,
        "options"_a = EventOptions{},
        "Finds the crossings of the threshold altitude and the culmination between "
        "start_utc and end_utc.");
  m.def("sun_path", &sun_path, "observer"_a, "start_utc"_a, "end_utc"_a,
        "options"_a = PathOptions{},
        "Returns the path of the sun at the times start_utc + k * step_s up to end_utc.");
}

void bind_terrain(py::module_& m) {
  using namespace almucantar;
  py::class_<ElevationGrid>(
      m, "ElevationGrid",
      "A DEM as a grid of heights. Row 0 is in the north, column 0 in the west.")
      .def(py::init([](const double north_latitude_deg, const double west_longitude_deg,
                       const double latitude_step_deg, const double longitude_step_deg,
                       const int rows, const int columns, std::vector<float> heights_m) {
             return ElevationGrid{.north_latitude_deg = north_latitude_deg,
                                  .west_longitude_deg = west_longitude_deg,
                                  .latitude_step_deg = latitude_step_deg,
                                  .longitude_step_deg = longitude_step_deg,
                                  .rows = rows,
                                  .columns = columns,
                                  .heights_m = std::move(heights_m)};
           }),
           "north_latitude_deg"_a, "west_longitude_deg"_a, "latitude_step_deg"_a,
           "longitude_step_deg"_a, "rows"_a, "columns"_a, "heights_m"_a)
      .def_readwrite("north_latitude_deg", &ElevationGrid::north_latitude_deg)
      .def_readwrite("west_longitude_deg", &ElevationGrid::west_longitude_deg)
      .def_readwrite("latitude_step_deg", &ElevationGrid::latitude_step_deg)
      .def_readwrite("longitude_step_deg", &ElevationGrid::longitude_step_deg)
      .def_readwrite("rows", &ElevationGrid::rows)
      .def_readwrite("columns", &ElevationGrid::columns)
      .def_readwrite("heights_m", &ElevationGrid::heights_m,
                     "row by row, in meters; NaN is a sample without data");

  const HorizonOptions horizon;
  py::class_<HorizonOptions>(m, "HorizonOptions")
      .def(py::init([](const double azimuth_step_deg, const double max_distance_m,
                       const double eye_height_m, const bool use_grid_elevation,
                       const double observer_elevation_m, const double refraction_coefficient,
                       const double min_distance_m) {
             return HorizonOptions{.azimuth_step_deg = azimuth_step_deg,
                                   .max_distance_m = max_distance_m,
                                   .eye_height_m = eye_height_m,
                                   .use_grid_elevation = use_grid_elevation,
                                   .observer_elevation_m = observer_elevation_m,
                                   .refraction_coefficient = refraction_coefficient,
                                   .min_distance_m = min_distance_m};
           }),
           "azimuth_step_deg"_a = horizon.azimuth_step_deg,
           "max_distance_m"_a = horizon.max_distance_m, "eye_height_m"_a = horizon.eye_height_m,
           "use_grid_elevation"_a = horizon.use_grid_elevation,
           "observer_elevation_m"_a = horizon.observer_elevation_m,
           "refraction_coefficient"_a = horizon.refraction_coefficient,
           "min_distance_m"_a = horizon.min_distance_m)
      .def_readwrite("azimuth_step_deg", &HorizonOptions::azimuth_step_deg)
      .def_readwrite("max_distance_m", &HorizonOptions::max_distance_m)
      .def_readwrite("eye_height_m", &HorizonOptions::eye_height_m)
      .def_readwrite("use_grid_elevation", &HorizonOptions::use_grid_elevation)
      .def_readwrite("observer_elevation_m", &HorizonOptions::observer_elevation_m)
      .def_readwrite("refraction_coefficient", &HorizonOptions::refraction_coefficient)
      .def_readwrite("min_distance_m", &HorizonOptions::min_distance_m,
                     "the terrain nearer than this is not used (for a surface model)");

  py::class_<HorizonProfile>(m, "HorizonProfile",
                             "The altitude of the terrain horizon for each azimuth.")
      .def(py::init([](const double azimuth_step_deg, std::vector<double> altitude_deg,
                       std::vector<double> distance_m, const double observer_elevation_m) {
             return HorizonProfile{.azimuth_step_deg = azimuth_step_deg,
                                   .altitude_deg = std::move(altitude_deg),
                                   .distance_m = std::move(distance_m),
                                   .observer_elevation_m = observer_elevation_m};
           }),
           "azimuth_step_deg"_a, "altitude_deg"_a, "distance_m"_a,
           "observer_elevation_m"_a = HorizonProfile{}.observer_elevation_m)
      .def_readwrite("azimuth_step_deg", &HorizonProfile::azimuth_step_deg)
      .def_readwrite("altitude_deg", &HorizonProfile::altitude_deg, "NaN without data")
      .def_readwrite("distance_m", &HorizonProfile::distance_m, "NaN without data")
      .def_readwrite("observer_elevation_m", &HorizonProfile::observer_elevation_m);

  m.def(
      "read_elevation_geotiff",
      [](const py::bytes& data, const int level) {
        const std::string_view view(data);
        return read_elevation_geotiff(std::vector<std::uint8_t>(view.begin(), view.end()), level);
      },
      "data"_a, "level"_a = 0, "Reads a GeoTIFF of heights, for example a Copernicus tile.");
  m.def(
      "elevation_geotiff_prefix_size",
      [](const py::bytes& header, const int level) {
        const std::string_view view(header);
        return elevation_geotiff_prefix_size(std::vector<std::uint8_t>(view.begin(), view.end()),
                                             level);
      },
      "header"_a, "level"_a,
      "Returns the bytes from the start of a GeoTIFF that a level needs, or 0 if the header is "
      "too short.");
  py::class_<ElevationTile>(m, "ElevationTile",
                            "A tile of 1° by 1°, named by its south-west corner.")
      .def(py::init([](const int latitude_deg, const int longitude_deg, const double distance_m) {
             return ElevationTile{.latitude_deg = latitude_deg,
                                  .longitude_deg = longitude_deg,
                                  .distance_m = distance_m};
           }),
           "latitude_deg"_a, "longitude_deg"_a, "distance_m"_a = ElevationTile{}.distance_m)
      .def_readwrite("latitude_deg", &ElevationTile::latitude_deg)
      .def_readwrite("longitude_deg", &ElevationTile::longitude_deg)
      .def_readwrite("distance_m", &ElevationTile::distance_m)
      .def("__repr__", [](const ElevationTile& t) {
        return "ElevationTile(latitude_deg=" + std::to_string(t.latitude_deg) +
               ", longitude_deg=" + std::to_string(t.longitude_deg) +
               ", distance_m=" + num(t.distance_m) + ")";
      });
  m.def("elevation_tiles", &elevation_tiles, "observer"_a, "max_distance_m"_a,
        "Returns the tiles of 1° by 1° that a horizon profile needs, the nearest first.");
  py::class_<MapTile>(m, "MapTile", "A tile of the Web Mercator scheme of web maps.")
      .def(py::init([](const int zoom, const int x, const int y, const double distance_m) {
             return MapTile{.zoom = zoom, .x = x, .y = y, .distance_m = distance_m};
           }),
           "zoom"_a, "x"_a, "y"_a, "distance_m"_a = MapTile{}.distance_m)
      .def_readwrite("zoom", &MapTile::zoom)
      .def_readwrite("x", &MapTile::x)
      .def_readwrite("y", &MapTile::y)
      .def_readwrite("distance_m", &MapTile::distance_m)
      .def("__repr__", [](const MapTile& t) {
        return "MapTile(zoom=" + std::to_string(t.zoom) + ", x=" + std::to_string(t.x) +
               ", y=" + std::to_string(t.y) + ", distance_m=" + num(t.distance_m) + ")";
      });
  m.def("map_tiles", &map_tiles, "observer"_a, "max_distance_m"_a, "zoom"_a,
        "Returns the Web Mercator tiles that a horizon profile needs, the nearest first.");
  m.def(
      "terrarium_grid",
      [](const py::bytes& rgba, const int width, const int height, const MapTile& tile) {
        const std::string_view view(rgba);
        return terrarium_grid(std::vector<std::uint8_t>(view.begin(), view.end()), width, height,
                              tile);
      },
      "rgba"_a, "width"_a, "height"_a, "tile"_a,
      "Converts the RGBA pixels of a Terrarium elevation tile to a grid.");
  m.def("horizon_profile", &horizon_profile, "observer"_a, "tiles"_a,
        "options"_a = HorizonOptions{}, "Calculates the terrain horizon from the grids in tiles.");
  m.def("terrain_altitude_deg", &terrain_altitude_deg, "profile"_a, "azimuth_deg"_a,
        "Returns the altitude of the terrain horizon at an azimuth (linear interpolation).");

  py::enum_<Body>(m, "Body", "A body of the sky, for the terrain and for the planner.")
      .value("sun", Body::sun)
      .value("moon", Body::moon)
      .value("galactic_center", Body::galactic_center)
      .value("mercury", Body::mercury)
      .value("venus", Body::venus)
      .value("mars", Body::mars)
      .value("jupiter", Body::jupiter)
      .value("saturn", Body::saturn)
      .value("uranus", Body::uranus)
      .value("neptune", Body::neptune);

  const TerrainEventOptions events;
  py::class_<TerrainEventOptions>(m, "TerrainEventOptions")
      .def(py::init([](const Body body, const SolarModel model, const bool refraction,
                       const Atmosphere& atmosphere, const double limb_deg,
                       const double search_step_s, const double tolerance_s) {
             return TerrainEventOptions{.body = body,
                                        .model = model,
                                        .refraction = refraction,
                                        .atmosphere = atmosphere,
                                        .limb_deg = limb_deg,
                                        .search_step_s = search_step_s,
                                        .tolerance_s = tolerance_s};
           }),
           "body"_a = events.body, "model"_a = events.model, "refraction"_a = events.refraction,
           "atmosphere"_a = events.atmosphere, "limb_deg"_a = events.limb_deg,
           "search_step_s"_a = events.search_step_s, "tolerance_s"_a = events.tolerance_s)
      .def_readwrite("body", &TerrainEventOptions::body)
      .def_readwrite("model", &TerrainEventOptions::model)
      .def_readwrite("refraction", &TerrainEventOptions::refraction)
      .def_readwrite("atmosphere", &TerrainEventOptions::atmosphere)
      .def_readwrite("limb_deg", &TerrainEventOptions::limb_deg)
      .def_readwrite("search_step_s", &TerrainEventOptions::search_step_s)
      .def_readwrite("tolerance_s", &TerrainEventOptions::tolerance_s);

  py::class_<TerrainEvents>(m, "TerrainEvents",
                            "The times when the body appears or goes away behind the terrain.")
      .def_readonly("kind", &TerrainEvents::kind)
      .def_readonly("crossings", &TerrainEvents::crossings, "rising is an appearance");
  m.def("terrain_events", &terrain_events, "observer"_a, "profile"_a, "start_utc"_a, "end_utc"_a,
        "options"_a = TerrainEventOptions{},
        "Finds the times when a body crosses the terrain horizon.");
}

void bind_sky(py::module_& m) {
  using namespace almucantar;
  py::enum_<Planet>(m, "Planet")
      .value("mercury", Planet::mercury)
      .value("venus", Planet::venus)
      .value("mars", Planet::mars)
      .value("jupiter", Planet::jupiter)
      .value("saturn", Planet::saturn)
      .value("uranus", Planet::uranus)
      .value("neptune", Planet::neptune);

  const SkyOptions defaults;
  py::class_<SkyOptions>(m, "SkyOptions")
      .def(py::init([](const bool refraction, const Atmosphere& atmosphere) {
             return SkyOptions{.refraction = refraction, .atmosphere = atmosphere};
           }),
           "refraction"_a = defaults.refraction, "atmosphere"_a = defaults.atmosphere)
      .def_readwrite("refraction", &SkyOptions::refraction)
      .def_readwrite("atmosphere", &SkyOptions::atmosphere);

  m.def("planet_position", &planet_position, "planet"_a, "unix_utc"_a, "observer"_a,
        "options"_a = SkyOptions{},
        "Returns the apparent topocentric position of the center of a planet.");
  m.def("planet_magnitude", &planet_magnitude, "planet"_a, "unix_utc"_a,
        "Returns the visual magnitude of a planet. A smaller value is brighter.");
  m.attr("GALACTIC_CENTER_LONGITUDE_DEG") = kGalacticCenterLongitudeDeg;
  m.attr("GALACTIC_CENTER_LATITUDE_DEG") = kGalacticCenterLatitudeDeg;
  m.def("galactic_position", &galactic_position, "galactic_longitude_deg"_a,
        "galactic_latitude_deg"_a, "unix_utc"_a, "observer"_a, "options"_a = SkyOptions{},
        "Returns the position of a direction in the galactic system for the observer.");
  m.def("galactic_plane", &galactic_plane, "unix_utc"_a, "observer"_a, "step_deg"_a = 1.0,
        "options"_a = SkyOptions{},
        "Returns the galactic plane (the center line of the Milky Way) for the observer.");

  py::class_<StarCatalog>(m, "StarCatalog",
                          "A catalogue of stars as columns: J2000 positions and proper motions.")
      .def(py::init([](std::vector<double> ra_deg, std::vector<double> dec_deg,
                       std::vector<double> pm_ra_arcsec_per_year,
                       std::vector<double> pm_dec_arcsec_per_year) {
             return StarCatalog{.ra_deg = std::move(ra_deg),
                                .dec_deg = std::move(dec_deg),
                                .pm_ra_arcsec_per_year = std::move(pm_ra_arcsec_per_year),
                                .pm_dec_arcsec_per_year = std::move(pm_dec_arcsec_per_year)};
           }),
           "ra_deg"_a = std::vector<double>{}, "dec_deg"_a = std::vector<double>{},
           "pm_ra_arcsec_per_year"_a = std::vector<double>{},
           "pm_dec_arcsec_per_year"_a = std::vector<double>{})
      .def_readwrite("ra_deg", &StarCatalog::ra_deg)
      .def_readwrite("dec_deg", &StarCatalog::dec_deg)
      .def_readwrite("pm_ra_arcsec_per_year", &StarCatalog::pm_ra_arcsec_per_year)
      .def_readwrite("pm_dec_arcsec_per_year", &StarCatalog::pm_dec_arcsec_per_year);
  m.def("star_positions", &star_positions, "catalog"_a, "unix_utc"_a, "observer"_a,
        "options"_a = SkyOptions{},
        "Returns the apparent positions of all stars of the catalogue for the observer.");
}

void bind_plan(py::module_& m) {
  using namespace almucantar;
  const BodyOptions body_defaults;
  py::class_<BodyOptions>(m, "BodyOptions")
      .def(
          py::init([](const SolarModel model, const bool refraction, const Atmosphere& atmosphere) {
            return BodyOptions{.model = model, .refraction = refraction, .atmosphere = atmosphere};
          }),
          "model"_a = body_defaults.model, "refraction"_a = body_defaults.refraction,
          "atmosphere"_a = body_defaults.atmosphere)
      .def_readwrite("model", &BodyOptions::model)
      .def_readwrite("refraction", &BodyOptions::refraction)
      .def_readwrite("atmosphere", &BodyOptions::atmosphere);
  m.def("body_position", &body_position, "body"_a, "unix_utc"_a, "observer"_a,
        "options"_a = BodyOptions{}, "Returns the apparent topocentric position of a body.");

  py::class_<Site>(m, "Site", "A point with its height above sea level.")
      .def(py::init(
               [](const double latitude_deg, const double longitude_deg, const double elevation_m) {
                 return Site{.latitude_deg = latitude_deg,
                             .longitude_deg = longitude_deg,
                             .elevation_m = elevation_m};
               }),
           "latitude_deg"_a = 0.0, "longitude_deg"_a = 0.0, "elevation_m"_a = 0.0)
      .def_readwrite("latitude_deg", &Site::latitude_deg)
      .def_readwrite("longitude_deg", &Site::longitude_deg)
      .def_readwrite("elevation_m", &Site::elevation_m)
      .def("__repr__", [](const Site& s) {
        return "Site(latitude_deg=" + num(s.latitude_deg) +
               ", longitude_deg=" + num(s.longitude_deg) + ", elevation_m=" + num(s.elevation_m) +
               ")";
      });

  const SightOptions sight_defaults;
  py::class_<SightOptions>(m, "SightOptions")
      .def(py::init([](const double refraction_coefficient, const double target_margin_m) {
             return SightOptions{.refraction_coefficient = refraction_coefficient,
                                 .target_margin_m = target_margin_m};
           }),
           "refraction_coefficient"_a = sight_defaults.refraction_coefficient,
           "target_margin_m"_a = sight_defaults.target_margin_m)
      .def_readwrite("refraction_coefficient", &SightOptions::refraction_coefficient)
      .def_readwrite("target_margin_m", &SightOptions::target_margin_m);
  py::class_<Sight>(m, "Sight", "The direction from one site to another, and the distance.")
      .def_readonly("direction", &Sight::direction)
      .def_readonly("distance_m", &Sight::distance_m)
      .def("__repr__", [](const Sight& s) {
        return "Sight(direction=" + repr(s.direction) + ", distance_m=" + num(s.distance_m) + ")";
      });
  m.def("sight", &sight, "from_"_a, "to"_a, "options"_a = SightOptions{},
        "Returns the direction in which one site sees another, with the curvature of the Earth.");

  const AlignmentOptions alignment_defaults;
  py::class_<AlignmentOptions>(m, "AlignmentOptions")
      .def(py::init([](const BodyOptions& body, const double max_separation_deg,
                       const double tolerance_s) {
             return AlignmentOptions{.body = body,
                                     .max_separation_deg = max_separation_deg,
                                     .tolerance_s = tolerance_s};
           }),
           "body"_a = alignment_defaults.body,
           "max_separation_deg"_a = alignment_defaults.max_separation_deg,
           "tolerance_s"_a = alignment_defaults.tolerance_s)
      .def_readwrite("body", &AlignmentOptions::body)
      .def_readwrite("max_separation_deg", &AlignmentOptions::max_separation_deg)
      .def_readwrite("tolerance_s", &AlignmentOptions::tolerance_s);
  py::class_<Alignment>(m, "Alignment", "A time when a body is near a direction.")
      .def_readonly("unix_utc", &Alignment::unix_utc)
      .def_readonly("separation_deg", &Alignment::separation_deg)
      .def_readonly("body", &Alignment::body)
      .def("__repr__", [](const Alignment& a) {
        return "Alignment(unix_utc=" + num(a.unix_utc) +
               ", separation_deg=" + num(a.separation_deg) + ", body=" + repr(a.body) + ")";
      });
  m.def("find_alignments", &find_alignments, "body"_a, "observer"_a, "direction"_a, "start_utc"_a,
        "end_utc"_a, "options"_a = AlignmentOptions{},
        "Finds the times when a body is in a fixed direction, for example behind a landmark.");

  const AlignmentLineOptions line_defaults;
  py::class_<AlignmentLineOptions>(m, "AlignmentLineOptions")
      .def(py::init([](const BodyOptions& body, const double min_distance_m,
                       const double max_distance_m, const double step_m, const double eye_height_m,
                       const double refraction_coefficient) {
             return AlignmentLineOptions{.body = body,
                                         .min_distance_m = min_distance_m,
                                         .max_distance_m = max_distance_m,
                                         .step_m = step_m,
                                         .eye_height_m = eye_height_m,
                                         .refraction_coefficient = refraction_coefficient};
           }),
           "body"_a = line_defaults.body, "min_distance_m"_a = line_defaults.min_distance_m,
           "max_distance_m"_a = line_defaults.max_distance_m, "step_m"_a = line_defaults.step_m,
           "eye_height_m"_a = line_defaults.eye_height_m,
           "refraction_coefficient"_a = line_defaults.refraction_coefficient)
      .def_readwrite("body", &AlignmentLineOptions::body)
      .def_readwrite("min_distance_m", &AlignmentLineOptions::min_distance_m)
      .def_readwrite("max_distance_m", &AlignmentLineOptions::max_distance_m)
      .def_readwrite("step_m", &AlignmentLineOptions::step_m)
      .def_readwrite("eye_height_m", &AlignmentLineOptions::eye_height_m)
      .def_readwrite("refraction_coefficient", &AlignmentLineOptions::refraction_coefficient);
  py::class_<LinePoint>(m, "LinePoint", "A place on the line of an alignment.")
      .def_readonly("place", &LinePoint::place)
      .def_readonly("distance_m", &LinePoint::distance_m)
      .def_readonly("eye_elevation_m", &LinePoint::eye_elevation_m)
      .def_readonly("ground_elevation_m", &LinePoint::ground_elevation_m)
      .def_readonly("body", &LinePoint::body)
      .def("__repr__", [](const LinePoint& p) {
        return "LinePoint(place=" + repr(p.place) + ", distance_m=" + num(p.distance_m) +
               ", eye_elevation_m=" + num(p.eye_elevation_m) +
               ", ground_elevation_m=" + num(p.ground_elevation_m) + ", body=" + repr(p.body) + ")";
      });
  py::class_<AlignmentLine>(m, "AlignmentLine", "The places of an alignment, and the spots.")
      .def_readonly("points", &AlignmentLine::points)
      .def_readonly("spots", &AlignmentLine::spots);
  m.def("alignment_line", &alignment_line, "body"_a, "target"_a, "unix_utc"_a,
        "tiles"_a = std::vector<ElevationGrid>{}, "options"_a = AlignmentLineOptions{},
        "Returns the places from which a body is behind a landmark at a time.");

  py::class_<LineOfSight>(m, "LineOfSight", "Whether one site sees another over the terrain.")
      .def_readonly("visible", &LineOfSight::visible)
      .def_readonly("clearance_deg", &LineOfSight::clearance_deg)
      .def_readonly("blocking_distance_m", &LineOfSight::blocking_distance_m)
      .def_readonly("blocking", &LineOfSight::blocking)
      .def("__repr__", [](const LineOfSight& l) {
        return std::string("LineOfSight(visible=") + (l.visible ? "True" : "False") +
               ", clearance_deg=" + num(l.clearance_deg) +
               ", blocking_distance_m=" + num(l.blocking_distance_m) + ")";
      });
  m.def("line_of_sight", &line_of_sight, "from_"_a, "to"_a, "tiles"_a, "options"_a = SightOptions{},
        "Checks whether one site sees another over the terrain.");

  const DarkOptions dark_defaults;
  py::class_<DarkOptions>(m, "DarkOptions")
      .def(py::init([](const double max_sun_altitude_deg, const double max_moon_altitude_deg,
                       const double max_moon_fraction, const bool galactic_center,
                       const double min_galactic_center_altitude_deg, const double step_s,
                       const double tolerance_s) {
             return DarkOptions{
                 .max_sun_altitude_deg = max_sun_altitude_deg,
                 .max_moon_altitude_deg = max_moon_altitude_deg,
                 .max_moon_fraction = max_moon_fraction,
                 .galactic_center = galactic_center,
                 .min_galactic_center_altitude_deg = min_galactic_center_altitude_deg,
                 .step_s = step_s,
                 .tolerance_s = tolerance_s};
           }),
           "max_sun_altitude_deg"_a = dark_defaults.max_sun_altitude_deg,
           "max_moon_altitude_deg"_a = dark_defaults.max_moon_altitude_deg,
           "max_moon_fraction"_a = dark_defaults.max_moon_fraction,
           "galactic_center"_a = dark_defaults.galactic_center,
           "min_galactic_center_altitude_deg"_a = dark_defaults.min_galactic_center_altitude_deg,
           "step_s"_a = dark_defaults.step_s, "tolerance_s"_a = dark_defaults.tolerance_s)
      .def_readwrite("max_sun_altitude_deg", &DarkOptions::max_sun_altitude_deg)
      .def_readwrite("max_moon_altitude_deg", &DarkOptions::max_moon_altitude_deg)
      .def_readwrite("max_moon_fraction", &DarkOptions::max_moon_fraction)
      .def_readwrite("galactic_center", &DarkOptions::galactic_center)
      .def_readwrite("min_galactic_center_altitude_deg",
                     &DarkOptions::min_galactic_center_altitude_deg)
      .def_readwrite("step_s", &DarkOptions::step_s)
      .def_readwrite("tolerance_s", &DarkOptions::tolerance_s);
  py::class_<TimeWindow>(m, "TimeWindow", "An interval of time.")
      .def_readonly("start_utc", &TimeWindow::start_utc)
      .def_readonly("end_utc", &TimeWindow::end_utc)
      .def("__repr__", [](const TimeWindow& w) {
        return "TimeWindow(start_utc=" + num(w.start_utc) + ", end_utc=" + num(w.end_utc) + ")";
      });
  m.def(
      "dark_windows", &dark_windows, "observer"_a, "start_utc"_a, "end_utc"_a,
      "options"_a = DarkOptions{}, "profile"_a = HorizonProfile{},
      "Finds the windows when the sky is dark: no sun, no moon, and the galactic center if asked.");
}

void bind_eclipse(py::module_& m) {
  using namespace almucantar;
  py::enum_<EclipseKind>(m, "EclipseKind")
      .value("penumbral", EclipseKind::penumbral)
      .value("partial", EclipseKind::partial)
      .value("annular", EclipseKind::annular)
      .value("total", EclipseKind::total);
  py::class_<LunarEclipse>(m, "LunarEclipse",
                           "A lunar eclipse; NaN for a phase that does not happen.")
      .def_readonly("kind", &LunarEclipse::kind)
      .def_readonly("peak_utc", &LunarEclipse::peak_utc)
      .def_readonly("obscuration", &LunarEclipse::obscuration)
      .def_readonly("penumbral_begin_utc", &LunarEclipse::penumbral_begin_utc)
      .def_readonly("partial_begin_utc", &LunarEclipse::partial_begin_utc)
      .def_readonly("total_begin_utc", &LunarEclipse::total_begin_utc)
      .def_readonly("total_end_utc", &LunarEclipse::total_end_utc)
      .def_readonly("partial_end_utc", &LunarEclipse::partial_end_utc)
      .def_readonly("penumbral_end_utc", &LunarEclipse::penumbral_end_utc);
  m.def("lunar_eclipses", &lunar_eclipses, "start_utc"_a, "end_utc"_a,
        "Returns the lunar eclipses with the peak between start_utc and end_utc.");
  py::class_<SolarEclipse>(m, "SolarEclipse", "A solar eclipse for the whole Earth.")
      .def_readonly("kind", &SolarEclipse::kind)
      .def_readonly("peak_utc", &SolarEclipse::peak_utc)
      .def_readonly("peak_place", &SolarEclipse::peak_place)
      .def_readonly("obscuration", &SolarEclipse::obscuration);
  m.def("solar_eclipses", &solar_eclipses, "start_utc"_a, "end_utc"_a,
        "Returns the solar eclipses with the peak between start_utc and end_utc.");
  py::class_<LocalSolarEclipse>(m, "LocalSolarEclipse", "A solar eclipse seen from one place.")
      .def_readonly("kind", &LocalSolarEclipse::kind)
      .def_readonly("obscuration", &LocalSolarEclipse::obscuration)
      .def_readonly("partial_begin_utc", &LocalSolarEclipse::partial_begin_utc)
      .def_readonly("total_begin_utc", &LocalSolarEclipse::total_begin_utc)
      .def_readonly("peak_utc", &LocalSolarEclipse::peak_utc)
      .def_readonly("total_end_utc", &LocalSolarEclipse::total_end_utc)
      .def_readonly("partial_end_utc", &LocalSolarEclipse::partial_end_utc)
      .def_readonly("peak_sun_altitude_deg", &LocalSolarEclipse::peak_sun_altitude_deg);
  m.def("local_solar_eclipses", &local_solar_eclipses, "observer"_a, "start_utc"_a, "end_utc"_a,
        "Returns the solar eclipses of a place with the peak between start_utc and end_utc.");
  py::class_<ShadowPoint>(m, "ShadowPoint", "A point of the central line of a solar eclipse.")
      .def_readonly("unix_utc", &ShadowPoint::unix_utc)
      .def_readonly("place", &ShadowPoint::place)
      .def_readonly("umbra_radius_km", &ShadowPoint::umbra_radius_km);
  m.def("eclipse_central_line", &eclipse_central_line, "peak_utc"_a, "step_s"_a = 60.0,
        "Returns the central line of the solar eclipse with the peak at peak_utc.");
}

}  // namespace

PYBIND11_MODULE(_core, m) {
  m.doc() = "The Python binding of the almucantar core.";
  bind_types(m);
  bind_sun(m);
  bind_view(m);
  bind_search(m);
  bind_heading(m);
  bind_moon(m);
  bind_terrain(m);
  bind_sky(m);
  bind_plan(m);
  bind_eclipse(m);
  m.def(
      "version", [] { return std::string(almucantar::version()); },
      "Returns the version of the core library.");
}
