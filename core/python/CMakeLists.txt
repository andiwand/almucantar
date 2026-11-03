# The Python binding (package `almucantar`). The top-level CMakeLists.txt includes
# this directory if ALMUCANTAR_PYTHON is ON.

find_package(Python 3.10 REQUIRED COMPONENTS Interpreter Development.Module)
find_package(pybind11 CONFIG REQUIRED)

pybind11_add_module(almucantar_python src/module.cpp)
target_link_libraries(almucantar_python PRIVATE almucantar::almucantar almucantar::options)
# The `$<1:...>` genex stops multi-config generators from adding a subdirectory
# per configuration. The module must be in the `almucantar` package directory.
set_target_properties(almucantar_python PROPERTIES
  OUTPUT_NAME "_core"
  LIBRARY_OUTPUT_DIRECTORY "$<1:${CMAKE_CURRENT_BINARY_DIR}/almucantar>")

# Copy the Python sources next to the module, so the build tree can be imported
# with PYTHONPATH=<build>/python.
add_custom_target(almucantar_python_package
  COMMAND "${CMAKE_COMMAND}" -E copy_directory
          "${CMAKE_CURRENT_SOURCE_DIR}/almucantar" "${CMAKE_CURRENT_BINARY_DIR}/almucantar")
add_dependencies(almucantar_python almucantar_python_package)

install(TARGETS almucantar_python LIBRARY DESTINATION almucantar COMPONENT python)
install(DIRECTORY almucantar/ DESTINATION almucantar COMPONENT python
  FILES_MATCHING PATTERN "*.py")

if(ALMUCANTAR_TESTS)
  add_test(NAME almucantar_python_pytest
    COMMAND Python::Interpreter -m pytest "${CMAKE_CURRENT_SOURCE_DIR}/tests" -v)
  set_tests_properties(almucantar_python_pytest PROPERTIES
    ENVIRONMENT "PYTHONPATH=${CMAKE_CURRENT_BINARY_DIR}")
endif()
