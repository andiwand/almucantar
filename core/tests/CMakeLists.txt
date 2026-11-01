find_package(Catch2 3 REQUIRED)
include(Catch)

add_executable(sunradar_core_tests
  test_version.cpp)

target_link_libraries(sunradar_core_tests PRIVATE
  sunradar::core
  sunradar::options
  Catch2::Catch2WithMain)

catch_discover_tests(sunradar_core_tests)
