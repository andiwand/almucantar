find_package(Catch2 3 REQUIRED)
include(Catch)

add_executable(almucantar_test
  src/poses.cpp
  src/reference_data.cpp
  src/test_events.cpp
  src/test_geotiff.cpp
  src/test_heading.cpp
  src/test_magnetic.cpp
  src/test_moon.cpp
  src/test_path.cpp
  src/test_search.cpp
  src/test_sky.cpp
  src/test_sun.cpp
  src/test_terrain.cpp
  src/test_version.cpp
  src/test_view.cpp
  src/internal/test_noaa_model.cpp
  src/internal/test_refraction.cpp
  src/internal/test_time.cpp
  src/internal/test_wmm.cpp)

target_compile_definitions(almucantar_test PRIVATE
  ALMUCANTAR_TEST_DATA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/data")

target_link_libraries(almucantar_test PRIVATE
  almucantar::almucantar
  almucantar::options
  Catch2::Catch2WithMain)
target_include_directories(almucantar_test PRIVATE src)

catch_discover_tests(almucantar_test)

add_test(NAME public_headers_do_not_include_internal_headers
  COMMAND ${CMAKE_COMMAND} -DSOURCE_DIR=${PROJECT_SOURCE_DIR}/src/almucantar
          -P ${PROJECT_SOURCE_DIR}/cmake/check_public_headers.cmake)

# Each public header must compile alone, with only the public headers on the
# include path, the way a consumer of the installed package sees them.
set(ALMUCANTAR_PUBLIC_INCLUDE_DIR "${CMAKE_CURRENT_BINARY_DIR}/public_include")
file(GLOB public_headers RELATIVE "${PROJECT_SOURCE_DIR}/src/almucantar"
  "${PROJECT_SOURCE_DIR}/src/almucantar/*.hpp")
set(header_check_sources)
foreach(header IN LISTS public_headers)
  configure_file("${PROJECT_SOURCE_DIR}/src/almucantar/${header}"
    "${ALMUCANTAR_PUBLIC_INCLUDE_DIR}/almucantar/${header}" COPYONLY)
  string(REPLACE ".hpp" "" name "${header}")
  set(source "${CMAKE_CURRENT_BINARY_DIR}/header_check/${name}.cpp")
  file(WRITE "${source}" "#include <almucantar/${header}>\n")
  list(APPEND header_check_sources "${source}")
endforeach()
add_library(almucantar_public_header_check OBJECT ${header_check_sources})
target_include_directories(almucantar_public_header_check PRIVATE "${ALMUCANTAR_PUBLIC_INCLUDE_DIR}")
target_link_libraries(almucantar_public_header_check PRIVATE almucantar::options)
