# The Python binding (package `sunradar`). The top-level CMakeLists.txt includes
# this directory if SUNRADAR_PYTHON is ON.

find_package(Python 3.10 REQUIRED COMPONENTS Interpreter Development.Module)
find_package(pybind11 CONFIG REQUIRED)

pybind11_add_module(sunradar_python src/module.cpp)
target_link_libraries(sunradar_python PRIVATE sunradar::sunradar sunradar::options)
# The `$<1:...>` genex stops multi-config generators from adding a subdirectory
# per configuration. The module must be in the `sunradar` package directory.
set_target_properties(sunradar_python PROPERTIES
  OUTPUT_NAME "_core"
  LIBRARY_OUTPUT_DIRECTORY "$<1:${CMAKE_CURRENT_BINARY_DIR}/sunradar>")

# Copy the Python sources next to the module, so the build tree can be imported
# with PYTHONPATH=<build>/python.
add_custom_target(sunradar_python_package
  COMMAND "${CMAKE_COMMAND}" -E copy_directory
          "${CMAKE_CURRENT_SOURCE_DIR}/sunradar" "${CMAKE_CURRENT_BINARY_DIR}/sunradar")
add_dependencies(sunradar_python sunradar_python_package)

install(TARGETS sunradar_python LIBRARY DESTINATION sunradar COMPONENT python)
install(DIRECTORY sunradar/ DESTINATION sunradar COMPONENT python
  FILES_MATCHING PATTERN "*.py")

if(SUNRADAR_TESTS)
  add_test(NAME sunradar_python_pytest
    COMMAND Python::Interpreter -m pytest "${CMAKE_CURRENT_SOURCE_DIR}/tests" -v)
  set_tests_properties(sunradar_python_pytest PROPERTIES
    ENVIRONMENT "PYTHONPATH=${CMAKE_CURRENT_BINARY_DIR}")
endif()
