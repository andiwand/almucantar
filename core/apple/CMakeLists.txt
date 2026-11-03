# The C API for the Swift package (core/apple). The core includes this directory if
# ALMUCANTAR_APPLE is ON. apple/build_xcframework.py builds it for each Apple slice and merges
# libalmucantar_c.a with libalmucantar.a into one static library per slice.

add_library(almucantar_c STATIC src/almucantar_c.cpp)
target_include_directories(almucantar_c PUBLIC include)
target_link_libraries(almucantar_c PUBLIC almucantar::almucantar PRIVATE almucantar::options)

if(ALMUCANTAR_TESTS)
  add_executable(almucantar_c_test test/almucantar_c_test.c)
  target_link_libraries(almucantar_c_test PRIVATE almucantar_c)
  target_compile_definitions(almucantar_c_test
                             PRIVATE ALMUCANTAR_TEST_DATA_DIR="${PROJECT_SOURCE_DIR}/test/data")
  # The test is C, so the C++ standard library must be linked explicitly.
  set_target_properties(almucantar_c_test PROPERTIES LINKER_LANGUAGE CXX)
  add_test(NAME almucantar_c_test COMMAND almucantar_c_test)
endif()
