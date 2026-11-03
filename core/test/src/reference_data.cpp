#include "reference_data.hpp"

#include <array>
#include <cmath>
#include <fstream>
#include <map>
#include <numbers>
#include <sstream>
#include <stdexcept>

namespace almucantar::test {

namespace {

const std::map<std::string, Observer>& sites() {
  // The same values as in test/tools/fetch_horizons.py.
  static const std::map<std::string, Observer> kSites = {
      {"quito", {.latitude_deg = -0.1807, .longitude_deg = -78.4678}},
      {"vienna", {.latitude_deg = 48.2082, .longitude_deg = 16.3738}},
      {"sydney", {.latitude_deg = -33.8688, .longitude_deg = 151.2093}},
      {"tromso", {.latitude_deg = 69.6492, .longitude_deg = 18.9553}},
      {"mcmurdo", {.latitude_deg = -77.8419, .longitude_deg = 166.6863}},
  };
  return kSites;
}

std::vector<std::vector<std::string>> read_csv(const std::string& name) {
  const std::string path = std::string(ALMUCANTAR_TEST_DATA_DIR) + "/" + name;
  std::ifstream file(path);
  if (!file) {
    throw std::runtime_error("cannot open " + path);
  }
  std::vector<std::vector<std::string>> rows;
  std::string line;
  std::getline(file, line);  // header
  while (std::getline(file, line)) {
    std::vector<std::string> fields;
    std::stringstream stream(line);
    std::string field;
    while (std::getline(stream, field, ',')) {
      fields.push_back(field);
    }
    rows.push_back(std::move(fields));
  }
  return rows;
}

std::vector<ReferencePosition> read_positions(const std::string& name) {
  std::vector<ReferencePosition> result;
  for (const auto& f : read_csv(name)) {
    result.push_back(ReferencePosition{
        .site = f.at(0),
        .unix_utc = std::stod(f.at(1)),
        .position = {.azimuth_deg = std::stod(f.at(2)), .altitude_deg = std::stod(f.at(3))},
    });
  }
  return result;
}

}  // namespace

Observer site(const std::string& name) { return sites().at(name); }

const std::vector<std::string>& site_names() {
  static const std::vector<std::string> kNames = [] {
    std::vector<std::string> names;
    for (const auto& [name, _] : sites()) {
      names.push_back(name);
    }
    return names;
  }();
  return kNames;
}

const std::vector<ReferencePosition>& airless_positions() {
  static const auto kData = read_positions("sun_airless.csv");
  return kData;
}

const std::vector<ReferencePosition>& refracted_positions() {
  static const auto kData = read_positions("sun_refracted.csv");
  return kData;
}

const std::vector<ReferencePosition>& moon_positions() {
  static const auto kData = read_positions("moon_airless.csv");
  return kData;
}

const std::vector<ReferencePlanetPosition>& planet_positions() {
  static const auto kData = [] {
    std::vector<ReferencePlanetPosition> result;
    for (const auto& f : read_csv("planets_airless.csv")) {
      result.push_back(ReferencePlanetPosition{
          .site = f.at(0),
          .planet = f.at(1),
          .unix_utc = std::stod(f.at(2)),
          .position = {.azimuth_deg = std::stod(f.at(3)), .altitude_deg = std::stod(f.at(4))},
      });
    }
    return result;
  }();
  return kData;
}

const std::vector<ReferenceEvent>& events() {
  static const auto kData = [] {
    std::vector<ReferenceEvent> result;
    for (const auto& f : read_csv("sun_events.csv")) {
      result.push_back(
          ReferenceEvent{.site = f.at(0), .kind = f.at(1), .unix_utc = std::stod(f.at(2))});
    }
    return result;
  }();
  return kData;
}

double angular_separation_deg(Horizontal a, Horizontal b) {
  constexpr double kD = std::numbers::pi / 180.0;
  const auto vec = [](Horizontal h) {
    const double ca = std::cos(h.altitude_deg * kD);
    return std::array<double, 3>{ca * std::sin(h.azimuth_deg * kD),
                                 ca * std::cos(h.azimuth_deg * kD), std::sin(h.altitude_deg * kD)};
  };
  const auto x = vec(a);
  const auto y = vec(b);
  const double dot = x[0] * y[0] + x[1] * y[1] + x[2] * y[2];
  const double cross =
      std::hypot(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]);
  return std::atan2(cross, dot) / kD;
}

}  // namespace almucantar::test
