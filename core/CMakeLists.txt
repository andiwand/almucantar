cmake_minimum_required(VERSION 3.24)

project(almucantar
  VERSION 0.2.0
  DESCRIPTION "Sun position, sun path and projection for a camera overlay"
  LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(ALMUCANTAR_TESTS "Build the C++ tests" ON)
option(ALMUCANTAR_PYTHON "Build the Python binding" OFF)
option(ALMUCANTAR_JNI "Build the JNI binding for the Android AAR" OFF)
option(ALMUCANTAR_WASM "Build the WebAssembly binding for the npm package" OFF)
option(ALMUCANTAR_APPLE "Build the C API for the Swift package" OFF)
option(ALMUCANTAR_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" OFF)
option(ALMUCANTAR_SANITIZE "Build with AddressSanitizer and UndefinedBehaviorSanitizer" OFF)

include(GNUInstallDirs)
include(cmake/compiler_options.cmake)

set(ALMUCANTAR_SOURCE_FILES
  src/almucantar/eclipse.cpp
  src/almucantar/heading.cpp
  src/almucantar/magnetic.cpp
  src/almucantar/moon.cpp
  src/almucantar/search.cpp
  src/almucantar/plan.cpp
  src/almucantar/sky.cpp
  src/almucantar/sun.cpp
  src/almucantar/terrain.cpp
  src/almucantar/version.cpp
  src/almucantar/view.cpp
  src/almucantar/internal/astronomy_engine.cpp
  src/almucantar/internal/events.cpp
  src/almucantar/internal/geotiff.cpp
  src/almucantar/internal/noaa_model.cpp
  src/almucantar/internal/path.cpp
  src/almucantar/internal/projection.cpp
  src/almucantar/internal/refraction.cpp
  src/almucantar/internal/search.cpp
  src/almucantar/internal/dem.cpp
  src/almucantar/internal/solar_model.cpp
  src/almucantar/internal/time.cpp
  src/almucantar/internal/validate.cpp
  src/almucantar/internal/wmm.cpp
  src/almucantar/internal/wmm_coefficients.cpp)

# Astronomy Engine (MIT, vendored). An object library, so its code goes into libalmucantar and a
# consumer links one library. Our strict warnings do not apply to it.
add_library(astronomy_engine OBJECT third_party/astronomy-engine/astronomy.c)
set_target_properties(astronomy_engine PROPERTIES POSITION_INDEPENDENT_CODE ON C_STANDARD 99)
if(NOT MSVC)
  target_link_libraries(astronomy_engine PRIVATE m)
endif()

# miniz (MIT, from Conan), for the inflate of the GeoTIFF reader. It is a static library, so a
# consumer of the static libalmucantar also links libminiz (the Conan package says so, and
# apple/build_xcframework.py merges it into the xcframework).
find_package(miniz REQUIRED CONFIG)

add_library(almucantar ${ALMUCANTAR_SOURCE_FILES} $<TARGET_OBJECTS:astronomy_engine>)
add_library(almucantar::almucantar ALIAS almucantar)

# The build tree exposes src/, so the tests can include the internal headers. The
# install tree has only the public headers (see below).
target_include_directories(almucantar PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/src>
  $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}>)

# A wheel build gets its version from git (setuptools-scm through scikit-build-core),
# so the library and the Python package report the same version.
set(ALMUCANTAR_VERSION_STRING "${PROJECT_VERSION}")
if(DEFINED SKBUILD_PROJECT_VERSION_FULL)
  set(ALMUCANTAR_VERSION_STRING "${SKBUILD_PROJECT_VERSION_FULL}")
endif()
target_compile_definitions(almucantar PRIVATE ALMUCANTAR_VERSION="${ALMUCANTAR_VERSION_STRING}")
target_include_directories(almucantar SYSTEM PRIVATE third_party/astronomy-engine)
target_link_libraries(almucantar PRIVATE miniz::miniz)
if(NOT MSVC AND NOT APPLE)
  target_link_libraries(almucantar PUBLIC m)
endif()
target_link_libraries(almucantar PRIVATE almucantar::options)

# The bindings and static libraries in shared objects need position-independent code.
set_target_properties(almucantar PROPERTIES POSITION_INDEPENDENT_CODE ON)

# The WASM binding has its own tests (node), also without the C++ tests. CTest finds tests
# only if enable_testing() is in this file.
if(ALMUCANTAR_TESTS OR ALMUCANTAR_WASM)
  enable_testing()
endif()

if(ALMUCANTAR_TESTS)
  add_subdirectory(test)
endif()

if(ALMUCANTAR_PYTHON)
  add_subdirectory(python)
endif()

if(ALMUCANTAR_JNI)
  add_subdirectory(android/jni)
endif()

if(ALMUCANTAR_WASM)
  add_subdirectory(wasm)
endif()

if(ALMUCANTAR_APPLE)
  add_subdirectory(apple)
endif()

# Install the public headers only. The internal headers stay in the source tree.
install(DIRECTORY src/almucantar/
  DESTINATION "${CMAKE_INSTALL_INCLUDEDIR}/almucantar"
  COMPONENT core
  FILES_MATCHING PATTERN "*.hpp"
  PATTERN "internal" EXCLUDE)
install(TARGETS almucantar
  COMPONENT core
  LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}"
  ARCHIVE DESTINATION "${CMAKE_INSTALL_LIBDIR}")
