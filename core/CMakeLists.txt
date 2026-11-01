add_library(sunradar_core
  src/version.cpp)
add_library(sunradar::core ALIAS sunradar_core)

target_include_directories(sunradar_core PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  $<INSTALL_INTERFACE:include>)

target_compile_definitions(sunradar_core PRIVATE
  SUNRADAR_VERSION="${PROJECT_VERSION}")

target_link_libraries(sunradar_core PRIVATE sunradar::options)

if(SUNRADAR_BUILD_TESTS)
  add_subdirectory(tests)
endif()
