// The Python binding of the public API (src/sunradar/*.hpp). It must not include
// an internal header.

#include <pybind11/pybind11.h>
#include <pybind11/stl.h>

#include <string>

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
      .value("noaa", SolarModel::noaa, "NOAA solar calculator (Meeus)");

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

}  // namespace

PYBIND11_MODULE(_core, m) {
  m.doc() = "The Python binding of the sunradar core.";
  bind_types(m);
  bind_sun(m);
  bind_view(m);
  m.def(
      "version", [] { return std::string(sunradar::version()); },
      "Returns the version of the core library.");
}
