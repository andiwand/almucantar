#include <catch2/catch_test_macros.hpp>

#include "almucantar/version.hpp"

TEST_CASE("version is not empty", "[version]") { REQUIRE_FALSE(almucantar::version().empty()); }

TEST_CASE("version has the format major.minor.patch", "[version]") {
  const auto v = almucantar::version();
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
