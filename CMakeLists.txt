cmake_minimum_required(VERSION 3.24)

project(sunradar
  VERSION 0.1.0
  DESCRIPTION "Sun position, sun path and projection for a camera overlay"
  LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(SUNRADAR_BUILD_TESTS "Build the unit tests of the core" ON)
option(SUNRADAR_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" OFF)
option(SUNRADAR_SANITIZE "Build with AddressSanitizer and UndefinedBehaviorSanitizer" OFF)

include(cmake/compiler_options.cmake)

if(SUNRADAR_BUILD_TESTS)
  enable_testing()
endif()

add_subdirectory(core)
