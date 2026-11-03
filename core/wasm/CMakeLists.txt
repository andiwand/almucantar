# The WebAssembly binding (npm package @almucantar/core). The core includes this directory if
# ALMUCANTAR_WASM is ON. Build it with the Conan profile conan/profiles/emscripten.

if(NOT EMSCRIPTEN)
  message(FATAL_ERROR "ALMUCANTAR_WASM needs Emscripten (use the Conan profile conan/profiles/emscripten)")
endif()

set(ALMUCANTAR_WASM_DIST "${CMAKE_CURRENT_BINARY_DIR}/dist")

add_executable(almucantar_wasm src/bindings.cpp)
target_link_libraries(almucantar_wasm PRIVATE almucantar::almucantar almucantar::options)
target_link_options(almucantar_wasm PRIVATE
  -lembind
  -sMODULARIZE=1
  -sEXPORT_ES6=1
  -sEXPORT_NAME=createAlmucantarModule
  -sENVIRONMENT=web,worker,node
  -sALLOW_MEMORY_GROWTH=1
  -sFILESYSTEM=0)
set_target_properties(almucantar_wasm PROPERTIES
  OUTPUT_NAME "almucantar"
  SUFFIX ".mjs"
  RUNTIME_OUTPUT_DIRECTORY "${ALMUCANTAR_WASM_DIST}")

# The hand-written half of the package goes next to the generated module.
add_custom_target(almucantar_wasm_package
  COMMAND ${CMAKE_COMMAND} -E make_directory "${ALMUCANTAR_WASM_DIST}")
foreach(file package.json index.js index.d.ts README.md)
  add_custom_command(TARGET almucantar_wasm_package POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${CMAKE_CURRENT_SOURCE_DIR}/js/${file}" "${ALMUCANTAR_WASM_DIST}/${file}")
endforeach()
add_custom_command(TARGET almucantar_wasm_package POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different
          "${PROJECT_SOURCE_DIR}/LICENSE" "${ALMUCANTAR_WASM_DIST}/LICENSE")
add_dependencies(almucantar_wasm almucantar_wasm_package)

# The tests need node 18 or later (node --test). emsdk brings an older node, and the Conan build
# environment puts it first on the PATH, so ALMUCANTAR_NODE can name another one.
set(ALMUCANTAR_NODE "" CACHE FILEPATH "The node (18 or later) for the tests of the WASM binding")
if(ALMUCANTAR_NODE)
  set(node "${ALMUCANTAR_NODE}")
else()
  find_program(node NAMES node NO_CMAKE_FIND_ROOT_PATH)
endif()
set(node_ok FALSE)
if(node)
  execute_process(COMMAND "${node}" --version OUTPUT_VARIABLE node_version OUTPUT_STRIP_TRAILING_WHITESPACE)
  string(REGEX MATCH "^v([0-9]+)" _ "${node_version}")
  if(CMAKE_MATCH_1 GREATER_EQUAL 18)
    set(node_ok TRUE)
  endif()
endif()
if(NOT node_ok)
  message(WARNING "No node 18 or later (found '${node}' ${node_version}); the WASM tests are skipped. "
                  "Set ALMUCANTAR_NODE.")
else()
  # The test files explicitly: node 22 does not take a directory for --test, node 20 does.
  file(GLOB test_files "${CMAKE_CURRENT_SOURCE_DIR}/tests/*.test.mjs")
  add_test(NAME almucantar_wasm_node
    COMMAND "${node}" --test ${test_files}
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}")
  set_tests_properties(almucantar_wasm_node PROPERTIES
    ENVIRONMENT "ALMUCANTAR_WASM_DIST=${ALMUCANTAR_WASM_DIST}")
endif()
