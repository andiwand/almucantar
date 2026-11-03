#include <array>
#include <catch2/catch_test_macros.hpp>
#include <catch2/generators/catch_generators.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <cmath>
#include <limits>
#include <numbers>
#include <stdexcept>
#include <vector>

#include "almucantar/view.hpp"
#include "poses.hpp"
#include "reference_data.hpp"

using Catch::Matchers::WithinAbs;
using namespace almucantar;
using almucantar::test::from_axes;
using almucantar::test::upright;

namespace {

constexpr double kDeg = std::numbers::pi / 180.0;

View view_facing(const double azimuth_deg, const double pitch_deg = 0, const double roll_deg = 0,
                 const ScreenRotation rotation = ScreenRotation::rotation_0) {
  return View{.device_to_enu = upright(azimuth_deg, pitch_deg, roll_deg, rotation),
              .screen_rotation = rotation};
}

}  // namespace

TEST_CASE("project gives the pinhole positions", "[view]") {
  const auto rotation = GENERATE(ScreenRotation::rotation_0, ScreenRotation::rotation_90,
                                 ScreenRotation::rotation_180, ScreenRotation::rotation_270);
  INFO("rotation " << static_cast<int>(rotation));
  const View view = view_facing(0, 0, 0, rotation);
  const double f = view.camera.fx;

  const Projection center = project(view, {.azimuth_deg = 0, .altitude_deg = 0});
  REQUIRE(center.valid);
  CHECK_THAT(center.point.x, WithinAbs(500, 1e-9));
  CHECK_THAT(center.point.y, WithinAbs(500, 1e-9));

  const Projection right = project(view, {.azimuth_deg = 10, .altitude_deg = 0});
  REQUIRE(right.valid);
  CHECK_THAT(right.point.x, WithinAbs(500 + f * std::tan(10 * kDeg), 1e-9));
  CHECK_THAT(right.point.y, WithinAbs(500, 1e-9));

  const Projection up = project(view, {.azimuth_deg = 0, .altitude_deg = 10});
  REQUIRE(up.valid);
  CHECK_THAT(up.point.x, WithinAbs(500, 1e-9));
  CHECK_THAT(up.point.y, WithinAbs(500 - f * std::tan(10 * kDeg), 1e-9));
}

TEST_CASE("screen rotations have the meaning of Android Display.getRotation()", "[view]") {
  // The device is turned 90° counterclockwise and looks to the north: its top (+y) points
  // west, its right side (+x) points up, and its screen (+z) faces the user (south). Android
  // reports ROTATION_90 for this. A point to the east must be on the right of the screen, and
  // a point above the horizon must be above the center.
  const View view{.device_to_enu = from_axes({0, 0, 1}, {-1, 0, 0}, {0, -1, 0}),
                  .screen_rotation = ScreenRotation::rotation_90};
  const Projection east = project(view, {.azimuth_deg = 10, .altitude_deg = 0});
  const Projection up = project(view, {.azimuth_deg = 0, .altitude_deg = 10});
  REQUIRE(east.valid);
  REQUIRE(up.valid);
  CHECK(east.point.x > 500);
  CHECK_THAT(east.point.y, WithinAbs(500, 1e-9));
  CHECK(up.point.y < 500);
  CHECK_THAT(up.point.x, WithinAbs(500, 1e-9));

  // Turned clockwise instead (top to the east): ROTATION_270.
  const View cw{.device_to_enu = from_axes({0, 0, -1}, {1, 0, 0}, {0, -1, 0}),
                .screen_rotation = ScreenRotation::rotation_270};
  CHECK(project(cw, {.azimuth_deg = 10, .altitude_deg = 0}).point.x > 500);
  CHECK(project(cw, {.azimuth_deg = 0, .altitude_deg = 10}).point.y < 500);
}

TEST_CASE("project uses the heading offset", "[view]") {
  // The sensor says that the camera looks to north, but the true azimuth is 10° more.
  View view = view_facing(0);
  view.heading_offset_deg = 10;
  const Projection p = project(view, {.azimuth_deg = 10, .altitude_deg = 0});
  REQUIRE(p.valid);
  CHECK_THAT(p.point.x, WithinAbs(500, 1e-9));
  CHECK_THAT(p.point.y, WithinAbs(500, 1e-9));
}

TEST_CASE("project marks directions outside the valid cone", "[view]") {
  // 60° horizontal field of view on a square screen: the corner is at atan(√2 tan 30°)
  // = 39.2°, and the cone has the half angle atan(1.5 √2 tan 30°) = 50.7°.
  const View view = view_facing(0);
  CHECK(project(view, {.azimuth_deg = 50, .altitude_deg = 0}).valid);
  CHECK_FALSE(project(view, {.azimuth_deg = 52, .altitude_deg = 0}).valid);
  CHECK_FALSE(project(view, {.azimuth_deg = 180, .altitude_deg = 0}).valid);
  CHECK_FALSE(project(view, {.azimuth_deg = 0, .altitude_deg = 90}).valid);
}

TEST_CASE("project normalizes the quaternion", "[view]") {
  View view = view_facing(30, 20, 5);
  const Projection reference = project(view, {.azimuth_deg = 35, .altitude_deg = 22});
  view.device_to_enu.w *= 3;
  view.device_to_enu.x *= 3;
  view.device_to_enu.y *= 3;
  view.device_to_enu.z *= 3;
  const Projection scaled = project(view, {.azimuth_deg = 35, .altitude_deg = 22});
  REQUIRE(scaled.valid);
  CHECK_THAT(scaled.point.x, WithinAbs(reference.point.x, 1e-9));
  CHECK_THAT(scaled.point.y, WithinAbs(reference.point.y, 1e-9));
}

TEST_CASE("unproject is the inverse of project, with distortion", "[view]") {
  View view = view_facing(123, 35, -12, ScreenRotation::rotation_90);
  view.heading_offset_deg = 4.5;
  view.camera = Camera{.width = 1920,
                       .height = 1080,
                       .fx = 1500,
                       .fy = 1495,
                       .cx = 965,
                       .cy = 538,
                       .k1 = -0.08,
                       .k2 = 0.02,
                       .k3 = -0.004,
                       .p1 = 0.0008,
                       .p2 = -0.0005};

  SECTION("screen to direction to screen") {
    double max_error = 0;
    for (double x = 0; x <= 1920; x += 160) {
      for (double y = 0; y <= 1080; y += 90) {
        const Horizontal d = unproject(view, {x, y});
        const Projection p = project(view, d);
        REQUIRE(p.valid);
        max_error = std::max({max_error, std::abs(p.point.x - x), std::abs(p.point.y - y)});
      }
    }
    // The plan requires 1 px. The fixed-point iteration gives much better.
    CHECK(max_error < 1e-6);
  }

  SECTION("the center of the screen shows the principal direction") {
    const Horizontal d = unproject(view, {965, 538});
    CHECK_THAT(d.azimuth_deg, WithinAbs(127.5, 1e-9));
    CHECK_THAT(d.altitude_deg, WithinAbs(35, 1e-9));
  }
}

TEST_CASE("horizon_line of an upright device is a horizontal line", "[view]") {
  const View view = view_facing(200);
  const auto lines = horizon_line(view);
  REQUIRE(lines.size() == 1);
  const Polyline& line = lines[0];
  REQUIRE(line.size() > 90);
  for (std::size_t i = 0; i < line.size(); ++i) {
    CHECK_THAT(line[i].y, WithinAbs(500, 1e-6));
    if (i > 0) {
      CHECK(line[i].x > line[i - 1].x);  // from left to right without a gap
    }
  }
  // The ends are on the border of the valid cone (50.7°), outside the screen.
  CHECK(line.front().x < 0);
  CHECK(line.back().x > 1000);
}

TEST_CASE("horizon_line follows pitch and roll", "[view]") {
  SECTION("pitched up: the horizon is below the center") {
    const auto lines = horizon_line(view_facing(0, 10));
    REQUIRE(lines.size() == 1);
    for (const ScreenPoint p : lines[0]) {
      if (std::abs(p.x - 500) < 1) {
        CHECK_THAT(p.y, WithinAbs(500 + 866.0254037844386 * std::tan(10 * kDeg), 0.5));
      }
    }
  }

  SECTION("rolled: the horizon has the slope of the roll") {
    const auto lines = horizon_line(view_facing(0, 0, 10));
    REQUIRE(lines.size() == 1);
    const Polyline& line = lines[0];
    // A roll of the device clockwise turns the image of the horizon counterclockwise.
    for (std::size_t i = 1; i < line.size(); ++i) {
      const double slope = (line[i].y - line[i - 1].y) / (line[i].x - line[i - 1].x);
      CHECK_THAT(slope, WithinAbs(-std::tan(10 * kDeg), 1e-6));
    }
  }

  SECTION("looking at the zenith or the nadir: no horizon") {
    CHECK(horizon_line(view_facing(0, 90)).empty());
    CHECK(horizon_line(view_facing(0, -90)).empty());
  }
}

TEST_CASE("project_line clips at the valid cone", "[view]") {
  const View view = view_facing(0);
  std::vector<Horizontal> line;
  for (double a = -180; a <= 180; a += 2) {
    line.push_back({.azimuth_deg = a, .altitude_deg = 0});
  }
  const auto lines = project_line(view, line);
  REQUIRE(lines.size() == 1);
  // The clipped ends are on the cone: their directions are 50.7° from the center.
  for (const ScreenPoint end : {lines[0].front(), lines[0].back()}) {
    const Horizontal d = unproject(view, end);
    CHECK_THAT(std::abs(d.azimuth_deg > 180 ? d.azimuth_deg - 360 : d.azimuth_deg),
               WithinAbs(std::atan(1.5 * std::sqrt(2) * std::tan(30 * kDeg)) / kDeg, 1e-6));
  }

  SECTION("a line that leaves and enters again gives two polylines") {
    std::vector<Horizontal> around;
    for (double a = 0; a <= 360; a += 2) {
      around.push_back({.azimuth_deg = a, .altitude_deg = 0});
    }
    CHECK(project_line(view, around).size() == 2);
  }

  SECTION("a line outside the cone gives no polyline") {
    CHECK(project_line(view, {{.azimuth_deg = 180, .altitude_deg = 0},
                              {.azimuth_deg = 182, .altitude_deg = 0}})
              .empty());
    CHECK(project_line(view, {}).empty());
  }
}

TEST_CASE("projected lines change continuously when the view turns", "[view]") {
  // A regression test for flickering lines: the boundary point of a clipped line was
  // sometimes rejected because of rounding, and the line got a point at (0, 0) for one frame.
  // The view turns in small steps; each step must not make a line jump.
  const auto path = sun_path(test::site("vienna"), 1781992800, 1781992800 + 86400);
  const Camera camera = camera_from_fov(1080, 2400, 60);
  const auto length = [](const std::vector<Polyline>& lines) {
    double total = 0;
    for (const Polyline& l : lines) {
      for (std::size_t i = 1; i < l.size(); ++i) {
        total += std::hypot(l[i].x - l[i - 1].x, l[i].y - l[i - 1].y);
      }
    }
    return total;
  };
  const auto max_step = [](const std::vector<Polyline>& lines) {
    double result = 0;
    for (const Polyline& l : lines) {
      for (std::size_t i = 1; i < l.size(); ++i) {
        result = std::max(result, std::hypot(l[i].x - l[i - 1].x, l[i].y - l[i - 1].y));
      }
    }
    return result;
  };
  for (const double pitch : {-30.0, 0.0, 20.0, 45.0}) {
    for (const double roll : {0.0, 15.0}) {
      double previous_horizon = -1;
      for (double yaw = 0; yaw < 360; yaw += 0.05) {
        const View view{.device_to_enu = upright(yaw, pitch, roll), .camera = camera};
        const auto horizon = horizon_line(view);
        const auto projected = project_path(view, path);
        INFO("pitch " << pitch << " roll " << roll << " yaw " << yaw);
        // The horizon has 1° steps and the path 5-minute steps (at most 1.25°). With a
        // focal length of 1559 px, a step is far below 300 px, also near the border.
        REQUIRE(max_step(horizon) < 300);
        REQUIRE(max_step(projected) < 300);
        const double h = length(horizon);
        if (previous_horizon >= 0) {
          REQUIRE(std::abs(h - previous_horizon) < 100);
        }
        previous_horizon = h;
      }
    }
  }
}

TEST_CASE("project_path projects the positions of the path", "[view]") {
  // The path of the sun in Vienna on 2026-06-21 seen to the south, 40° up.
  const auto path = sun_path(test::site("vienna"), 1781992800, 1781992800 + 86400);
  const View view = view_facing(180, 40);
  const auto lines = project_path(view, path);
  REQUIRE(lines.size() == 1);
  // The noon point (about 65° high in the south) is 25° above the center.
  const Projection noon = project(view, sun_position(1782039420, test::site("vienna")));
  REQUIRE(noon.valid);
  CHECK(noon.point.y < 500);
  CHECK_THAT(noon.point.x, WithinAbs(500, 30));
}

TEST_CASE("camera_from_fov", "[view]") {
  const Camera c = camera_from_fov(1920, 1080, 70);
  CHECK_THAT(c.fx, WithinAbs(960 / std::tan(35 * kDeg), 1e-9));
  CHECK(c.fy == c.fx);
  CHECK(c.cx == 960);
  CHECK(c.cy == 540);
  CHECK(c.k1 == 0);
  const Camera d = camera_from_fov(1000, 1000, 60);
  CHECK_THAT(d.fx, WithinAbs(Camera{}.fx, 1e-9));

  CHECK_THROWS_AS(camera_from_fov(0, 1, 60), std::invalid_argument);
  CHECK_THROWS_AS(camera_from_fov(1, 1, 0), std::invalid_argument);
  CHECK_THROWS_AS(camera_from_fov(1, 1, 180), std::invalid_argument);
}

TEST_CASE("the view functions reject an invalid view", "[view]") {
  const double nan = std::numeric_limits<double>::quiet_NaN();
  const Horizontal d{};
  View view;
  view.device_to_enu = {0, 0, 0, 0};
  CHECK_THROWS_AS(project(view, d), std::invalid_argument);
  view = View{};
  view.device_to_enu.x = nan;
  CHECK_THROWS_AS(project(view, d), std::invalid_argument);
  view = View{};
  view.screen_rotation = static_cast<ScreenRotation>(4);
  CHECK_THROWS_AS(unproject(view, {}), std::invalid_argument);
  view = View{};
  view.camera.fx = 0;
  CHECK_THROWS_AS(horizon_line(view), std::invalid_argument);
  view = View{};
  view.camera.k1 = nan;
  CHECK_THROWS_AS(project_line(view, {d}), std::invalid_argument);
  view = View{};
  view.heading_offset_deg = nan;
  CHECK_THROWS_AS(project(view, d), std::invalid_argument);
  CHECK_THROWS_AS(project(View{}, {.azimuth_deg = nan}), std::invalid_argument);
  CHECK_THROWS_AS(unproject(View{}, {.x = nan}), std::invalid_argument);
}
