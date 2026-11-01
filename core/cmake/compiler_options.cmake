# Compiler options for all targets of the project. Dependencies from Conan do not
# get these options, because Conan builds them separately.

add_library(sunradar_options INTERFACE)
add_library(sunradar::options ALIAS sunradar_options)

target_compile_options(sunradar_options INTERFACE
  -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Wdouble-promotion)

# GCC before version 15 warns about a designated initializer that leaves out a member
# with a default value, for example `PathOptions{.step_s = 60}`. The API depends on
# this form, so the warning is off for GCC.
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
  target_compile_options(sunradar_options INTERFACE -Wno-missing-field-initializers)
endif()

if(SUNRADAR_WARNINGS_AS_ERRORS)
  target_compile_options(sunradar_options INTERFACE -Werror)
endif()

if(SUNRADAR_SANITIZE)
  target_compile_options(sunradar_options INTERFACE
    -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all)
  target_link_options(sunradar_options INTERFACE -fsanitize=address,undefined)
endif()
