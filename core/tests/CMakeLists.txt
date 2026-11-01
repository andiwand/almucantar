find_package(Catch2 3 REQUIRED)
include(Catch)

add_executable(sunradar_core_tests
  reference_data.cpp
  test_events.cpp
  test_noaa_model.cpp
  test_path.cpp
  test_refraction.cpp
  test_time.cpp
  test_version.cpp)

target_compile_definitions(sunradar_core_tests PRIVATE
  SUNRADAR_TEST_DATA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/data")

target_link_libraries(sunradar_core_tests PRIVATE
  sunradar::core
  sunradar::options
  Catch2::Catch2WithMain)

catch_discover_tests(sunradar_core_tests)
