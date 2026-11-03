# Compiler options for all targets of the project. Dependencies from Conan do not
# get these options, because Conan builds them separately.

add_library(almucantar_options INTERFACE)
add_library(almucantar::options ALIAS almucantar_options)

target_compile_options(almucantar_options INTERFACE
  -Wall -Wextra -Wpedantic -Wconversion -Wshadow -Wdouble-promotion)

# GCC before version 15 warns about a designated initializer that leaves out a member
# with a default value, for example `PathOptions{.step_s = 60}`. The API depends on
# this form, so the warning is off for GCC.
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
  target_compile_options(almucantar_options INTERFACE -Wno-missing-field-initializers)
endif()

if(ALMUCANTAR_WARNINGS_AS_ERRORS)
  target_compile_options(almucantar_options INTERFACE -Werror)
endif()

if(ALMUCANTAR_SANITIZE)
  target_compile_options(almucantar_options INTERFACE
    -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all)
  target_link_options(almucantar_options INTERFACE -fsanitize=address,undefined)
endif()
