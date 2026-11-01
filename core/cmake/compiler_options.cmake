# Compiler options for all targets of the project. Dependencies from Conan do not
# get these options, because Conan builds them separately.

add_library(sunradar_options INTERFACE)
add_library(sunradar::options ALIAS sunradar_options)

target_compile_options(sunradar_options INTERFACE
  -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Wdouble-promotion)

if(SUNRADAR_WARNINGS_AS_ERRORS)
  target_compile_options(sunradar_options INTERFACE -Werror)
endif()

if(SUNRADAR_SANITIZE)
  target_compile_options(sunradar_options INTERFACE
    -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all)
  target_link_options(sunradar_options INTERFACE -fsanitize=address,undefined)
endif()
