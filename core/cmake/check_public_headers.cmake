# Fails if a public header includes an internal header. Run with
#   cmake -DSOURCE_DIR=<core/src/sunradar> -P check_public_headers.cmake

file(GLOB public_headers "${SOURCE_DIR}/*.hpp")
if(NOT public_headers)
  message(FATAL_ERROR "no public headers in ${SOURCE_DIR}")
endif()

set(failed FALSE)
foreach(header IN LISTS public_headers)
  file(STRINGS "${header}" includes REGEX "^[ \t]*#[ \t]*include[ \t]*[\"<]sunradar/internal/")
  if(includes)
    message(SEND_ERROR "${header} includes an internal header: ${includes}")
    set(failed TRUE)
  endif()
endforeach()

if(failed)
  message(FATAL_ERROR "the public API depends on internal headers")
endif()
