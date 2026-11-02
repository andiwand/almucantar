# The C API for the Swift package (core/apple). The core includes this directory if
# SUNRADAR_APPLE is ON. apple/build_xcframework.py builds it for each Apple slice and merges
# libsunradar_c.a with libsunradar.a into one static library per slice.

add_library(sunradar_c STATIC src/sunradar_c.cpp)
target_include_directories(sunradar_c PUBLIC include)
target_link_libraries(sunradar_c PUBLIC sunradar::sunradar PRIVATE sunradar::options)

if(SUNRADAR_TESTS)
  add_executable(sunradar_c_test test/sunradar_c_test.c)
  target_link_libraries(sunradar_c_test PRIVATE sunradar_c)
  target_compile_definitions(sunradar_c_test
                             PRIVATE SUNRADAR_TEST_DATA_DIR="${PROJECT_SOURCE_DIR}/test/data")
  # The test is C, so the C++ standard library must be linked explicitly.
  set_target_properties(sunradar_c_test PROPERTIES LINKER_LANGUAGE CXX)
  add_test(NAME sunradar_c_test COMMAND sunradar_c_test)
endif()
