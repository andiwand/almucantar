#include <catch2/catch_test_macros.hpp>

#include "sunradar/version.hpp"

TEST_CASE("version is not empty", "[version]") {
  REQUIRE_FALSE(sunradar::version().empty());
}

TEST_CASE("version has the format major.minor.patch", "[version]") {
  const auto v = sunradar::version();
  int dots = 0;
  for (const char c : v) {
    if (c == '.') {
      ++dots;
    } else {
      REQUIRE((c >= '0' && c <= '9'));
    }
  }
  REQUIRE(dots == 2);
}
