cmake_minimum_required(VERSION 3.20)
project(qod_node CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# qod-node links an official, unmodified DuckDB release library. DUCKDB_HOME (env var or
# -DDUCKDB_HOME=) points at a directory holding that library under lib/ and the FULL src/include tree
# of the same tag under include/ (the release archives carry only the C headers; qod-node uses
# DuckDB's internal C++ API). Nothing else is searched, so a system DuckDB of another version can
# never be picked up by accident.
#
#   shared (default): lib/ holds the release libduckdb (duckdb-shared-libs-<platform>.tar.gz). Linux
#                     and macOS. The .duckdb/<version>/ cache the qod CLI and run-jar.sh provision
#                     has this layout.
#   static (QOD_NODE_DUCKDB_STATIC=ON): lib/ holds duckdb-static-libs-<platform>.tar.gz, the
#                     libraries the official duckdb.exe is built from. Windows, where the release
#                     duckdb.dll does not export the internal C++ symbols qod-node uses.
option(QOD_NODE_DUCKDB_STATIC "Link the official DuckDB release static libraries" OFF)
option(QOD_NODE_RELATIVE_RPATH "Find libduckdb only in ../lib next to the binary (vendored builds)" OFF)
option(QOD_NODE_TESTS "Build the tests (ctest)" OFF)

# Handle and verdict protocol generation, recorded in the build marker.
set(QOD_NODE_PROTOCOL 2)

if(NOT DUCKDB_HOME)
  set(DUCKDB_HOME $ENV{DUCKDB_HOME})
endif()
find_path(DUCKDB_INCLUDE_DIR duckdb.hpp HINTS ${DUCKDB_HOME}/include NO_DEFAULT_PATH)
if(NOT DUCKDB_INCLUDE_DIR OR NOT EXISTS ${DUCKDB_INCLUDE_DIR}/duckdb/planner/planner_extension.hpp)
  message(FATAL_ERROR "DUCKDB_HOME/include must hold DuckDB's full src/include tree of the release tag")
endif()

# ---- the DuckDB library, as one interface target -----------------------------------------------
add_library(qod_node_duckdb INTERFACE)
target_include_directories(qod_node_duckdb INTERFACE ${DUCKDB_INCLUDE_DIR})
if(QOD_NODE_DUCKDB_STATIC)
  file(GLOB DUCKDB_STATIC_LIBS ${DUCKDB_HOME}/lib/*${CMAKE_STATIC_LIBRARY_SUFFIX})
  # The CLI's own library is not part of the engine.
  list(FILTER DUCKDB_STATIC_LIBS EXCLUDE REGEX "duckdb_shell")
  list(FIND DUCKDB_STATIC_LIBS
    ${DUCKDB_HOME}/lib/${CMAKE_STATIC_LIBRARY_PREFIX}duckdb_static${CMAKE_STATIC_LIBRARY_SUFFIX} core_index)
  if(core_index EQUAL -1)
    message(FATAL_ERROR "DUCKDB_HOME/lib lacks duckdb_static (unpack duckdb-static-libs-<platform>.tar.gz there)")
  endif()
  message(STATUS "qod-node: static DuckDB from ${DUCKDB_HOME}/lib")
  # DUCKDB_STATIC_BUILD: without it the headers declare every DuckDB function dllimport on Windows.
  target_compile_definitions(qod_node_duckdb INTERFACE DUCKDB_STATIC_BUILD QOD_NODE_DUCKDB_STATIC)
  if(MSVC)
    # The release static libraries use the static MSVC runtime (DuckDB's CMAKE_MSVC_RUNTIME_LIBRARY).
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
    target_link_libraries(qod_node_duckdb INTERFACE ${DUCKDB_STATIC_LIBS} ws2_32 rstrtmgr bcrypt)
  elseif(APPLE)
    find_package(Threads REQUIRED)
    target_link_libraries(qod_node_duckdb INTERFACE ${DUCKDB_STATIC_LIBS} Threads::Threads)
  else()
    # duckdb_static and the built-in extension libraries reference each other.
    find_package(Threads REQUIRED)
    target_link_libraries(qod_node_duckdb INTERFACE
      -Wl,--start-group ${DUCKDB_STATIC_LIBS} -Wl,--end-group Threads::Threads ${CMAKE_DL_LIBS})
  endif()
else()
  find_library(DUCKDB_LIB duckdb HINTS ${DUCKDB_HOME}/lib NO_DEFAULT_PATH)
  if(NOT DUCKDB_LIB)
    message(FATAL_ERROR "DUCKDB_HOME/lib lacks the release libduckdb (unpack duckdb-shared-libs-<platform>.tar.gz there)")
  endif()
  message(STATUS "qod-node: shared DuckDB ${DUCKDB_LIB}")
  target_link_libraries(qod_node_duckdb INTERFACE ${DUCKDB_LIB})
endif()

# ---- build marker -------------------------------------------------------------------------------
# DuckDB version and source id: asked of the libduckdb being linked (a tiny program run at configure
# time), so the marker cannot name another DuckDB than the one the binary links. Static builds and
# cross builds pass -DQOD_NODE_DUCKDB_VERSION= and -DQOD_NODE_DUCKDB_SOURCE_ID= instead.
set(QOD_NODE_DUCKDB_VERSION "" CACHE STRING "DuckDB version the build marker records (default: probe)")
set(QOD_NODE_DUCKDB_SOURCE_ID "" CACHE STRING "DuckDB source id the build marker records (default: probe)")
set(duckdb_version "${QOD_NODE_DUCKDB_VERSION}")
set(duckdb_source "${QOD_NODE_DUCKDB_SOURCE_ID}")
if(NOT duckdb_version OR NOT duckdb_source)
  if(QOD_NODE_DUCKDB_STATIC)
    message(FATAL_ERROR "a static build must pass -DQOD_NODE_DUCKDB_VERSION= and -DQOD_NODE_DUCKDB_SOURCE_ID=")
  endif()
  try_run(probe_run probe_compiled
    ${CMAKE_BINARY_DIR}/duckdb_version_probe
    ${CMAKE_CURRENT_SOURCE_DIR}/cmake/duckdb_version_probe.cpp
    CMAKE_FLAGS "-DINCLUDE_DIRECTORIES=${DUCKDB_INCLUDE_DIR}"
    LINK_LIBRARIES ${DUCKDB_LIB}
    CXX_STANDARD 17
    COMPILE_OUTPUT_VARIABLE probe_compile_out
    RUN_OUTPUT_VARIABLE probe_out)
  if(NOT probe_compiled OR NOT probe_run EQUAL 0)
    message(FATAL_ERROR "cannot probe the version of ${DUCKDB_LIB} (compiled=${probe_compiled}, run=${probe_run}); pass -DQOD_NODE_DUCKDB_VERSION= and -DQOD_NODE_DUCKDB_SOURCE_ID=.\n${probe_compile_out}")
  endif()
  string(STRIP "${probe_out}" probe_out)
  list(LENGTH probe_out probe_len)
  if(NOT probe_len EQUAL 2)
    message(FATAL_ERROR "unexpected DuckDB version probe output: ${probe_out}")
  endif()
  list(GET probe_out 0 duckdb_version)
  list(GET probe_out 1 duckdb_source)
endif()
if(NOT duckdb_version MATCHES "^v?[0-9]+\\.[0-9]+\\.[0-9]+" OR NOT duckdb_source MATCHES "^[0-9a-f]+$")
  message(FATAL_ERROR "implausible DuckDB version/source id: '${duckdb_version}' / '${duckdb_source}'")
endif()
message(STATUS "qod-node: build marker protocol=${QOD_NODE_PROTOCOL} duckdb=${duckdb_version} duckdb_source=${duckdb_source}")

# ---- function lists the parse screen and the describer embed ----------------------------------------
# file-functions.txt (class File) and admitted-table-functions.txt (classes Allowed and Listing), written
# with the Scala classification by scripts/dev/gen-qod-node-classification.sh, become constant arrays in
# generated/file_functions.hpp. Each line must be a lowercase function name, the lines sorted.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
  ${CMAKE_CURRENT_SOURCE_DIR}/file-functions.txt ${CMAKE_CURRENT_SOURCE_DIR}/admitted-table-functions.txt)
function(qod_node_name_array file out)
  file(STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/${file} names)
  set(previous "")
  set(entries "")
  foreach(name IN LISTS names)
    if(NOT "${name}" MATCHES "^[a-z_][a-z0-9_]*$")
      message(FATAL_ERROR "${file}: '${name}' is not a lowercase function name")
    endif()
    if(NOT "${previous}" STREQUAL "" AND NOT "${previous}" STRLESS "${name}")
      message(FATAL_ERROR "${file}: '${name}' is out of order or repeated")
    endif()
    string(APPEND entries "    \"${name}\",\n")
    set(previous "${name}")
  endforeach()
  if("${entries}" STREQUAL "")
    message(FATAL_ERROR "${file} is empty")
  endif()
  set(${out} "${entries}" PARENT_SCOPE)
endfunction()
qod_node_name_array(file-functions.txt QOD_NODE_FILE_FUNCTIONS)
qod_node_name_array(admitted-table-functions.txt QOD_NODE_ADMITTED_TABLE_FUNCTIONS)
configure_file(cmake/file_functions.hpp.in ${CMAKE_BINARY_DIR}/generated/file_functions.hpp @ONLY)

# ---- qod-node -----------------------------------------------------------------------------------
set(QOD_NODE_CORE_SOURCES
  src/authz.cpp
  src/build_info.cpp
  src/credentials.cpp
  src/crypto.cpp
  src/decide_client.cpp
  src/functions_list.cpp
  src/handle.cpp
  src/http_post.cpp
  src/init_runner.cpp
  src/json.cpp
  src/json_writer.cpp
  src/lifetime.cpp
  src/node_config.cpp
  src/node_hooks.cpp
  src/options.cpp
  src/secrets.cpp
  src/settings_list.cpp
  src/socket_compat.cpp
  src/sql_script.cpp
  src/utf8_args.cpp
  src/verdict.cpp)
if(QOD_NODE_DUCKDB_STATIC)
  list(APPEND QOD_NODE_CORE_SOURCES src/static_extensions.cpp)
endif()
add_library(qod_node_core STATIC ${QOD_NODE_CORE_SOURCES})
target_include_directories(qod_node_core PUBLIC src ${CMAKE_BINARY_DIR}/generated)
target_link_libraries(qod_node_core PUBLIC qod_node_duckdb)
if(WIN32)
  # CommandLineToArgvW (utf8_args.cpp); Winsock (socket_compat.cpp).
  target_link_libraries(qod_node_core PUBLIC shell32 ws2_32)
endif()
target_compile_definitions(qod_node_core PRIVATE
  QOD_NODE_PROTOCOL=${QOD_NODE_PROTOCOL}
  "QOD_NODE_DUCKDB_VERSION=\"${duckdb_version}\""
  "QOD_NODE_DUCKDB_SOURCE_ID=\"${duckdb_source}\"")

add_executable(qod-node src/main.cpp)
target_link_libraries(qod-node PRIVATE qod_node_core)
if(NOT QOD_NODE_DUCKDB_STATIC)
  # libduckdb is found in ../lib next to the binary: the manager installs a bundled qod-node as
  # <dir>/bin/qod-node with <dir>/lib linking to the libduckdb it loaded (QodNodeBinary.scala).
  # A development build also finds the libduckdb it was linked from, first; a vendored build
  # (QOD_NODE_RELATIVE_RPATH=ON) carries nothing of the machine that built it.
  if(APPLE)
    set(QOD_NODE_LIB_RPATH "@executable_path/../lib")
  else()
    set(QOD_NODE_LIB_RPATH "$ORIGIN/../lib")
  endif()
  get_filename_component(DUCKDB_LIB_DIR ${DUCKDB_LIB} DIRECTORY)
  if(QOD_NODE_RELATIVE_RPATH)
    # The install rpath alone: CMake would otherwise add the directory of every full-path library
    # the target links (libduckdb's, on this machine) to the build rpath.
    set_target_properties(qod-node PROPERTIES BUILD_WITH_INSTALL_RPATH ON
      INSTALL_RPATH "${QOD_NODE_LIB_RPATH}")
  else()
    set_target_properties(qod-node PROPERTIES BUILD_RPATH "${DUCKDB_LIB_DIR};${QOD_NODE_LIB_RPATH}")
  endif()
endif()
if(UNIX AND NOT APPLE)
  # Bind every symbol at load, so a libduckdb missing one fails at start, not mid-query.
  target_link_options(qod-node PRIVATE "LINKER:-z,now")
endif()

# ---- tests --------------------------------------------------------------------------------------
if(QOD_NODE_TESTS)
  enable_testing()
  # One test program per tests/<name>.cpp, linked like qod-node.
  function(qod_node_test name)
    add_executable(${name} tests/${name}.cpp)
    target_link_libraries(${name} PRIVATE qod_node_core)
    if(NOT QOD_NODE_DUCKDB_STATIC)
      set_target_properties(${name} PROPERTIES BUILD_RPATH ${DUCKDB_LIB_DIR})
    endif()
  endfunction()
  qod_node_test(sql_script_test)
  add_test(NAME sql_script COMMAND sql_script_test)
  qod_node_test(crypto_test)
  add_test(NAME crypto COMMAND crypto_test)
  qod_node_test(handle_test)
  add_test(NAME handle COMMAND handle_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/handle-v1.txt)
  qod_node_test(authz_test)
  add_test(NAME authz COMMAND authz_test)
  qod_node_test(authz_engine_test)
  add_test(NAME authz_engine COMMAND authz_engine_test)
  find_package(Threads REQUIRED)
  # A real HTTP server on 127.0.0.1 for the decide client's tests, on every platform.
  add_library(qod_node_test_http STATIC tests/test_http_server.cpp)
  target_link_libraries(qod_node_test_http PUBLIC qod_node_core Threads::Threads)
  qod_node_test(verdict_test)
  target_link_libraries(verdict_test PRIVATE Threads::Threads)
  add_test(NAME verdict COMMAND verdict_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/verdict-v1.txt)
  qod_node_test(http_post_test)
  target_link_libraries(http_post_test PRIVATE qod_node_test_http)
  add_test(NAME http_post COMMAND http_post_test)
  qod_node_test(decide_client_test)
  target_link_libraries(decide_client_test PRIVATE qod_node_test_http)
  add_test(NAME decide_client COMMAND decide_client_test)
  qod_node_test(credentials_test)
  target_link_libraries(credentials_test PRIVATE qod_node_test_http)
  add_test(NAME credentials COMMAND credentials_test)
  qod_node_test(credentials_engine_test)
  target_link_libraries(credentials_engine_test PRIVATE qod_node_test_http)
  add_test(NAME credentials_engine COMMAND credentials_engine_test)
  qod_node_test(json_writer_test)
  add_test(NAME json_writer COMMAND json_writer_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/canonical-json-v1.txt)
  qod_node_test(node_config_test)
  add_test(NAME node_config COMMAND node_config_test)
  qod_node_test(s3_env_test)
  add_test(NAME s3_env COMMAND s3_env_test)
  # Fake credentials in this test's environment only, never in the shell's.
  set_tests_properties(s3_env PROPERTIES SKIP_RETURN_CODE 77 ENVIRONMENT
    "AWS_ACCESS_KEY_ID=AKIAQODFAKETEST00000;AWS_SECRET_ACCESS_KEY=qod-fake-secret-not-a-key;AWS_SESSION_TOKEN=qod-fake-session-token;AWS_REGION=qod-fake-region-1;DUCKDB_S3_ENDPOINT=qod-fake-endpoint.invalid;DUCKDB_S3_KMS_KEY_ID=qod-fake-kms-key")
  qod_node_test(functions_list_test)
  add_test(NAME functions_list COMMAND functions_list_test)
  qod_node_test(handle_tool)
  find_program(BASH bash)
  if(BASH AND NOT WIN32)
    add_test(NAME process COMMAND ${BASH} ${CMAKE_CURRENT_SOURCE_DIR}/tests/process_test.sh
      $<TARGET_FILE:qod-node> $<TARGET_FILE:handle_tool>)
  endif()
endif()
