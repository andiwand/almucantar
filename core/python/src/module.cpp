// The Python binding of the public API (src/sunradar/*.hpp). It must not include
// an internal header.

#include <pybind11/pybind11.h>
#include <pybind11/stl.h>

#include <string>
#include <utility>
#include <vector>

#include "sunradar/sunradar.hpp"

namespace py = pybind11;
using namespace pybind11::literals;

namespace {

/// Formats `x` the way Python's repr() formats a float: the shortest exact form.
std::string num(const double x) { return py::repr(py::float_(x)).cast<std::string>(); }

std::string repr(const sunradar::Observer& o) {
  return "Observer(latitude_deg=" + num(o.latitude_deg) +
         ", longitude_deg=" + num(o.longitude_deg) + ")";
}

std::string repr(const sunradar::Horizontal& h) {
  return "Horizontal(azimuth_deg=" + num(h.azimuth_deg) + ", altitude_deg=" + num(h.altitude_deg) +
         ")";
}

std::string repr(const sunradar::Atmosphere& a) {
  return "Atmosphere(pressure_hpa=" + num(a.pressure_hpa) +
         ", temperature_c=" + num(a.temperature_c) + ")";
}

void bind_types(const py::module_& m) {
  using sunradar::Atmosphere;
  using sunradar::Horizontal;
  using sunradar::Observer;

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
  using namespace sunradar;
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
  using namespace sunradar;

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
  using namespace sunradar;

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
  using namespace sunradar;

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
  using namespace sunradar;

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
  using namespace sunradar;
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
                       const double observer_elevation_m, const double refraction_coefficient) {
             return HorizonOptions{.azimuth_step_deg = azimuth_step_deg,
                                   .max_distance_m = max_distance_m,
                                   .eye_height_m = eye_height_m,
                                   .use_grid_elevation = use_grid_elevation,
                                   .observer_elevation_m = observer_elevation_m,
                                   .refraction_coefficient = refraction_coefficient};
           }),
           "azimuth_step_deg"_a = horizon.azimuth_step_deg,
           "max_distance_m"_a = horizon.max_distance_m, "eye_height_m"_a = horizon.eye_height_m,
           "use_grid_elevation"_a = horizon.use_grid_elevation,
           "observer_elevation_m"_a = horizon.observer_elevation_m,
           "refraction_coefficient"_a = horizon.refraction_coefficient)
      .def_readwrite("azimuth_step_deg", &HorizonOptions::azimuth_step_deg)
      .def_readwrite("max_distance_m", &HorizonOptions::max_distance_m)
      .def_readwrite("eye_height_m", &HorizonOptions::eye_height_m)
      .def_readwrite("use_grid_elevation", &HorizonOptions::use_grid_elevation)
      .def_readwrite("observer_elevation_m", &HorizonOptions::observer_elevation_m)
      .def_readwrite("refraction_coefficient", &HorizonOptions::refraction_coefficient);

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

  m.def("horizon_profile", &horizon_profile, "observer"_a, "grid"_a, "options"_a = HorizonOptions{},
        "Calculates the terrain horizon of the observer.");
  m.def("terrain_altitude_deg", &terrain_altitude_deg, "profile"_a, "azimuth_deg"_a,
        "Returns the altitude of the terrain horizon at an azimuth (linear interpolation).");

  const TerrainEventOptions events;
  py::class_<TerrainEventOptions>(m, "TerrainEventOptions")
      .def(
          py::init([](const SolarModel model, const bool refraction, const Atmosphere& atmosphere,
                      const double limb_deg, const double search_step_s, const double tolerance_s) {
            return TerrainEventOptions{.model = model,
                                       .refraction = refraction,
                                       .atmosphere = atmosphere,
                                       .limb_deg = limb_deg,
                                       .search_step_s = search_step_s,
                                       .tolerance_s = tolerance_s};
          }),
          "model"_a = events.model, "refraction"_a = events.refraction,
          "atmosphere"_a = events.atmosphere, "limb_deg"_a = events.limb_deg,
          "search_step_s"_a = events.search_step_s, "tolerance_s"_a = events.tolerance_s)
      .def_readwrite("model", &TerrainEventOptions::model)
      .def_readwrite("refraction", &TerrainEventOptions::refraction)
      .def_readwrite("atmosphere", &TerrainEventOptions::atmosphere)
      .def_readwrite("limb_deg", &TerrainEventOptions::limb_deg)
      .def_readwrite("search_step_s", &TerrainEventOptions::search_step_s)
      .def_readwrite("tolerance_s", &TerrainEventOptions::tolerance_s);

  py::class_<TerrainEvents>(m, "TerrainEvents",
                            "The times when the sun appears or goes away behind the terrain.")
      .def_readonly("kind", &TerrainEvents::kind)
      .def_readonly("crossings", &TerrainEvents::crossings, "rising is an appearance");
  m.def("terrain_events", &terrain_events, "observer"_a, "profile"_a, "start_utc"_a, "end_utc"_a,
        "options"_a = TerrainEventOptions{},
        "Finds the times when the sun crosses the terrain horizon.");
}

}  // namespace

PYBIND11_MODULE(_core, m) {
  m.doc() = "The Python binding of the sunradar core.";
  bind_types(m);
  bind_sun(m);
  bind_view(m);
  bind_search(m);
  bind_heading(m);
  bind_moon(m);
  bind_terrain(m);
  m.def(
      "version", [] { return std::string(sunradar::version()); },
      "Returns the version of the core library.");
}
