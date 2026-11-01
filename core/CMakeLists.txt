cmake_minimum_required(VERSION 3.24)

project(sunradar
  VERSION 0.1.0
  DESCRIPTION "Sun position, sun path and projection for a camera overlay"
  LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(SUNRADAR_TESTS "Build the C++ tests" ON)
option(SUNRADAR_PYTHON "Build the Python binding" OFF)
option(SUNRADAR_JNI "Build the JNI binding for the Android AAR" OFF)
option(SUNRADAR_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" OFF)
option(SUNRADAR_SANITIZE "Build with AddressSanitizer and UndefinedBehaviorSanitizer" OFF)

include(GNUInstallDirs)
include(cmake/compiler_options.cmake)

set(SUNRADAR_SOURCE_FILES
  src/sunradar/magnetic.cpp
  src/sunradar/search.cpp
  src/sunradar/sun.cpp
  src/sunradar/version.cpp
  src/sunradar/view.cpp
  src/sunradar/internal/events.cpp
  src/sunradar/internal/noaa_model.cpp
  src/sunradar/internal/path.cpp
  src/sunradar/internal/projection.cpp
  src/sunradar/internal/refraction.cpp
  src/sunradar/internal/search.cpp
  src/sunradar/internal/solar_model.cpp
  src/sunradar/internal/time.cpp
  src/sunradar/internal/validate.cpp
  src/sunradar/internal/wmm.cpp
  src/sunradar/internal/wmm_coefficients.cpp)

add_library(sunradar ${SUNRADAR_SOURCE_FILES})
add_library(sunradar::sunradar ALIAS sunradar)

# The build tree exposes src/, so the tests can include the internal headers. The
# install tree has only the public headers (see below).
target_include_directories(sunradar PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/src>
  $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}>)

# A wheel build gets its version from git (setuptools-scm through scikit-build-core),
# so the library and the Python package report the same version.
set(SUNRADAR_VERSION_STRING "${PROJECT_VERSION}")
if(DEFINED SKBUILD_PROJECT_VERSION_FULL)
  set(SUNRADAR_VERSION_STRING "${SKBUILD_PROJECT_VERSION_FULL}")
endif()
target_compile_definitions(sunradar PRIVATE SUNRADAR_VERSION="${SUNRADAR_VERSION_STRING}")
target_link_libraries(sunradar PRIVATE sunradar::options)

# The bindings and static libraries in shared objects need position-independent code.
set_target_properties(sunradar PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(SUNRADAR_TESTS)
  enable_testing()
  add_subdirectory(test)
endif()

if(SUNRADAR_PYTHON)
  add_subdirectory(python)
endif()

if(SUNRADAR_JNI)
  add_subdirectory(android/jni)
endif()

# Install the public headers only. The internal headers stay in the source tree.
install(DIRECTORY src/sunradar/
  DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}/sunradar"
  COMPONENT core
  FILES_MATCHING PATTERN "*.hpp"
  PATTERN "internal" EXCLUDE)
install(TARGETS sunradar
  COMPONENT core
  LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
  ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
