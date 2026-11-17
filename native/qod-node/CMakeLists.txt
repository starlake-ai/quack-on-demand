cmake_minimum_required(VERSION 3.20)
# A macOS search build (QOD_NODE_SEARCH, below) compiles zstd's C sources through zstd-sys, which take the
# SDK's version as their deployment target unless one is set (28 link warnings, and a binary that records the
# SDK's macOS as its lowest); Corrosion hands this value to cc-rs as MACOSX_DEPLOYMENT_TARGET. 15.0 is what the
# shipped osx binaries record (search spec 5.1). Set before project(), which reads it; a non-empty -D wins. An
# empty value counts as unset: project() leaves an empty cache entry in a tree first configured without search.
if(QOD_NODE_SEARCH AND CMAKE_HOST_APPLE AND NOT CMAKE_OSX_DEPLOYMENT_TARGET)
  set(CMAKE_OSX_DEPLOYMENT_TARGET 15.0 CACHE STRING "Lowest macOS a search build of qod-node starts on" FORCE)
endif()
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
set(QOD_NODE_PROTOCOL 6)

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
  # Named, not left to libc: before glibc 2.34 pthreads and dl are libraries of their own, and CI builds
  # the Linux binaries against 2.28 (scripts/release/glibc-floor.sh).
  find_package(Threads REQUIRED)
  target_link_libraries(qod_node_duckdb INTERFACE ${DUCKDB_LIB} Threads::Threads ${CMAKE_DL_LIBS})
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
  ${CMAKE_CURRENT_SOURCE_DIR}/file-functions.txt ${CMAKE_CURRENT_SOURCE_DIR}/admitted-table-functions.txt
  ${CMAKE_CURRENT_SOURCE_DIR}/init-settings.txt ${CMAKE_CURRENT_SOURCE_DIR}/init-extensions.txt)
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
# The tenant initSql allowlist (init_policy.cpp): settings a tenant admin's initSql may set, extensions it
# may install or load. Same shape as the lists above, written by hand.
qod_node_name_array(init-settings.txt QOD_NODE_INIT_SETTINGS)
qod_node_name_array(init-extensions.txt QOD_NODE_INIT_EXTENSIONS)
configure_file(cmake/init_policy_lists.hpp.in ${CMAKE_BINARY_DIR}/generated/init_policy_lists.hpp @ONLY)

# ---- qod-node -----------------------------------------------------------------------------------
set(QOD_NODE_CORE_SOURCES
  src/authz.cpp
  src/authz_stats.cpp
  src/build_info.cpp
  src/credentials.cpp
  src/crypto.cpp
  src/decide_client.cpp
  src/describer.cpp
  src/function_classes.cpp
  src/functions_list.cpp
  src/handle.cpp
  src/http_post.cpp
  src/init_policy.cpp
  src/init_runner.cpp
  src/json.cpp
  src/json_writer.cpp
  src/lifetime.cpp
  src/locations.cpp
  src/node_config.cpp
  src/node_hooks.cpp
  src/options.cpp
  src/screen_rules.cpp
  src/secrets.cpp
  src/settings_list.cpp
  src/socket_compat.cpp
  src/spill_dir.cpp
  src/sql_script.cpp
  src/utf8_args.cpp
  src/verdict.cpp
  src/wire_text.cpp)
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

# QoD Search (spec 5, 7): the Rust qod_search_core, linked through Corrosion, and the node's search
# functions. OFF by default: every shipped configure names it explicitly (OWNER RULING 2026-11-16, D2).
# Corrosion adds the system libraries the Rust standard library needs (rustc --print native-static-libs:
# ws2_32, userenv, bcrypt, ntdll on MSVC; libSystem pieces on macOS; pthread, dl, m on Linux) to the
# imported target's link interface. qod_node_core compiles against the library's header and links no
# build of it: qod-node links the plain build, and the test programs QOD_SEARCH_LIB or the test-hooks
# build (below).
option(QOD_NODE_SEARCH "Link qod_search_core (Rust) into qod-node" OFF)
# Test programs only: link them against a second build of qod_search_core with its `test-hooks` feature
# (the qs_test_* counters), and compile them with QOD_SEARCH_TEST_HOOKS. qod-node itself never links it.
option(QOD_SEARCH_TEST_HOOKS
       "With QOD_NODE_SEARCH and QOD_NODE_TESTS, link the test programs against qod_search_core built with its test-hooks feature"
       OFF)
set(QOD_SEARCH_LIB "")
if(QOD_NODE_SEARCH)
  if(CMAKE_VERSION VERSION_LESS 3.22)
    message(FATAL_ERROR "QOD_NODE_SEARCH needs CMake 3.22 (Corrosion v0.6.1)")
  endif()
  # The Rust release is pinned in native/qod-search/rust-toolchain.toml. Corrosion picks rustup's
  # default toolchain and never reads that file, so its channel becomes Rust_TOOLCHAIN (a -D wins), and
  # a configure without that release installed fails, naming it.
  file(STRINGS ${CMAKE_CURRENT_SOURCE_DIR}/../qod-search/rust-toolchain.toml _qod_rust_channel
       REGEX "^channel *= *\"[^\"]+\"")
  string(REGEX REPLACE "^channel *= *\"([^\"]+)\".*" "\\1" _qod_rust_channel "${_qod_rust_channel}")
  if(NOT _qod_rust_channel)
    message(FATAL_ERROR "no channel in native/qod-search/rust-toolchain.toml")
  endif()
  if(NOT DEFINED Rust_TOOLCHAIN)
    set(Rust_TOOLCHAIN "${_qod_rust_channel}" CACHE STRING "rustup toolchain for qod_search_core")
  endif()
  include(FetchContent)
  FetchContent_Declare(Corrosion GIT_REPOSITORY https://github.com/corrosion-rs/corrosion.git GIT_TAG v0.6.1)
  FetchContent_MakeAvailable(Corrosion)
  if(NOT Rust_VERSION VERSION_EQUAL _qod_rust_channel)
    message(FATAL_ERROR "qod_search_core is pinned to Rust ${_qod_rust_channel} (rust-toolchain.toml); "
                        "Corrosion found ${Rust_VERSION} (toolchain ${Rust_TOOLCHAIN})")
  endif()
  corrosion_import_crate(MANIFEST_PATH ${CMAKE_CURRENT_SOURCE_DIR}/../qod-search/Cargo.toml
                         CRATES qod_search_core CRATE_TYPES staticlib PROFILE release LOCKED)
  set(_qod_search_rustflags "")
  if(MSVC AND QOD_NODE_DUCKDB_STATIC)
    # The DuckDB release static libraries use the static CRT (MultiThreaded, above); Rust on MSVC links
    # the dynamic one unless told otherwise, and the two cannot share one binary.
    corrosion_add_target_rustflags(qod_search_core -Ctarget-feature=+crt-static)
    set(_qod_search_rustflags "-Ctarget-feature=+crt-static")
  endif()
  target_include_directories(qod_node_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/../qod-search/include)
  # The search table functions, registered by main.cpp under QOD_NODE_SEARCH (PUBLIC below, so qod-node
  # and the tests see it too): qod_search, qod_search_matches and qod_search_build_segment and their
  # post_bind hook; the custom sink their input drain runs in (spec 7.6); the snapshot source (7.1 step 2,
  # the named exception of section 13); the lineage check (4.2 rules 1, 2, 7, 9); bundle I/O and the
  # open-segment cache (6.2); visibility at S and the liveness cache (6.3, 7.5); the fallback (9); the
  # index records (6.1).
  target_sources(qod_node_core PRIVATE src/search_functions.cpp src/search_sink.cpp src/search_snapshot.cpp
                 src/search_lineage.cpp src/search_segments.cpp src/search_visibility.cpp
                 src/search_fallback.cpp src/search_records.cpp src/search_memory.cpp src/search_rowmap.cpp
                 src/search_path_probe.cpp)
  target_compile_definitions(qod_node_core PUBLIC QOD_NODE_SEARCH=1)
  set(QOD_SEARCH_LIB qod_search_core)
  if(QOD_SEARCH_TEST_HOOKS AND QOD_NODE_TESTS)
    # A second cargo build of the same crate and lock, in a target directory of its own, with the
    # test-hooks feature: an imported library carrying the plain build's native libraries.
    if(MSVC)
      set(_hooks_name qod_search_core.lib)
    else()
      set(_hooks_name libqod_search_core.a)
    endif()
    set(_hooks_dir ${CMAKE_BINARY_DIR}/qod-search-test-hooks)
    set(_hooks_lib ${_hooks_dir}/release/${_hooks_name})
    file(GLOB_RECURSE _hooks_sources CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/../qod-search/src/*.rs)
    # zstd's C sources take the deployment target the plain build gets from Corrosion (top of this file).
    set(_hooks_env "")
    if(APPLE AND CMAKE_OSX_DEPLOYMENT_TARGET)
      set(_hooks_env "MACOSX_DEPLOYMENT_TARGET=${CMAKE_OSX_DEPLOYMENT_TARGET}")
    endif()
    add_custom_command(OUTPUT ${_hooks_lib}
      COMMAND ${CMAKE_COMMAND} -E env "RUSTUP_TOOLCHAIN=${Rust_TOOLCHAIN}" "RUSTFLAGS=${_qod_search_rustflags}" ${_hooks_env}
              ${Rust_CARGO_CACHED} build --release --locked --lib --features test-hooks
              --manifest-path ${CMAKE_CURRENT_SOURCE_DIR}/../qod-search/Cargo.toml --target-dir ${_hooks_dir}
      DEPENDS ${_hooks_sources} ${CMAKE_CURRENT_SOURCE_DIR}/../qod-search/Cargo.toml
              ${CMAKE_CURRENT_SOURCE_DIR}/../qod-search/Cargo.lock
      WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../qod-search
      COMMENT "qod_search_core with the test-hooks feature (test programs only)"
      USES_TERMINAL)
    add_custom_target(qod_search_core_test_hooks_build DEPENDS ${_hooks_lib})
    add_library(qod_search_core_test_hooks STATIC IMPORTED GLOBAL)
    set_target_properties(qod_search_core_test_hooks PROPERTIES IMPORTED_LOCATION ${_hooks_lib})
    get_target_property(_qod_search_native qod_search_core-static INTERFACE_LINK_LIBRARIES)
    if(_qod_search_native)
      set_property(TARGET qod_search_core_test_hooks PROPERTY INTERFACE_LINK_LIBRARIES ${_qod_search_native})
    endif()
    add_dependencies(qod_search_core_test_hooks qod_search_core_test_hooks_build)
  endif()
endif()

add_executable(qod-node src/main.cpp)
target_link_libraries(qod-node PRIVATE qod_node_core ${QOD_SEARCH_LIB})
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
  # The test programs' build of the node core: the same sources and settings as qod_node_core, compiled with
  # QOD_NODE_TEST_HOOKS, which declares and defines the node's C++ test hooks (the *ForTest functions). qod-node
  # links qod_node_core only, so no shipped binary (Windows' is built with QOD_NODE_TESTS=ON) carries a hook.
  get_target_property(_qod_core_sources qod_node_core SOURCES)
  add_library(qod_node_core_hooks STATIC ${_qod_core_sources})
  foreach(_prop INCLUDE_DIRECTORIES INTERFACE_INCLUDE_DIRECTORIES COMPILE_DEFINITIONS INTERFACE_COMPILE_DEFINITIONS
                COMPILE_OPTIONS INTERFACE_COMPILE_OPTIONS LINK_LIBRARIES INTERFACE_LINK_LIBRARIES)
    get_target_property(_value qod_node_core ${_prop})
    if(_value)
      set_property(TARGET qod_node_core_hooks PROPERTY ${_prop} "${_value}")
    endif()
  endforeach()
  target_compile_definitions(qod_node_core_hooks PUBLIC QOD_NODE_TEST_HOOKS=1)
  # One test program per tests/<name>.cpp, linked like qod-node but against the hooks build of the core.
  function(qod_node_test name)
    add_executable(${name} tests/${name}.cpp)
    target_link_libraries(${name} PRIVATE qod_node_core_hooks)
    if(TARGET qod_search_core_test_hooks)
      target_link_libraries(${name} PRIVATE qod_search_core_test_hooks)
      target_compile_definitions(${name} PRIVATE QOD_SEARCH_TEST_HOOKS=1)
    elseif(QOD_SEARCH_LIB)
      target_link_libraries(${name} PRIVATE ${QOD_SEARCH_LIB})
    endif()
    if(QOD_NODE_DUCKDB_STATIC)
      # The static extensions registered before main, as qod-node's main registers them: a source of
      # the program itself, so the linker cannot drop it as an unreferenced archive member.
      target_sources(${name} PRIVATE tests/static_registration.cpp)
    else()
      set_target_properties(${name} PROPERTIES BUILD_RPATH ${DUCKDB_LIB_DIR})
    endif()
  endfunction()
  qod_node_test(sql_script_test)
  add_test(NAME sql_script COMMAND sql_script_test)
  qod_node_test(crypto_test)
  add_test(NAME crypto COMMAND crypto_test)
  qod_node_test(handle_test)
  add_test(NAME handle COMMAND handle_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/handle-v4.txt)
  qod_node_test(authz_test)
  add_test(NAME authz COMMAND authz_test)
  qod_node_test(authz_engine_test)
  add_test(NAME authz_engine COMMAND authz_engine_test)
  qod_node_test(describer_test)
  add_test(NAME describer COMMAND describer_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/descriptions)
  qod_node_test(describer_extension_test)
  add_test(NAME describer_extensions COMMAND describer_extension_test)
  qod_node_test(written_catalogs_test)
  add_test(NAME written_catalogs COMMAND written_catalogs_test)
  set_tests_properties(describer_extensions PROPERTIES SKIP_RETURN_CODE 77)
  qod_node_test(enforcement_extension_test)
  add_test(NAME enforcement_extensions COMMAND enforcement_extension_test)
  set_tests_properties(enforcement_extensions PROPERTIES SKIP_RETURN_CODE 77)
  if(NOT WIN32)
    # --build-info ends with search=on or search=off after the unchanged marker (search review M17).
    if(QOD_NODE_SEARCH)
      set(_qod_search_word on)
    else()
      set(_qod_search_word off)
    endif()
    add_test(NAME build_info_search COMMAND sh -c
      "$<TARGET_FILE:qod-node> --build-info | grep -Eq '^qod-node-build-info:protocol=[0-9]+;duckdb=v[0-9.]+;duckdb_source=[0-9a-f]+ search=${_qod_search_word}$'")
  endif()
  if(QOD_NODE_SEARCH)
    # The library links into qod-node.
    qod_node_test(search_link_test)
    add_test(NAME search_link COMMAND search_link_test)
    # ResolveSnapshotView against the catalog's own answers, on a DuckDB-file catalog and (QOD_P2_PG_DSN_FILE)
    # a Postgres one. Exits 77 when ducklake cannot load.
    qod_node_test(search_snapshot_test)
    add_test(NAME search_snapshot COMMAND search_snapshot_test)
    set_tests_properties(search_snapshot PROPERTIES SKIP_RETURN_CODE 77)
    # RowidMap (ruling 10): rowid to its one live (segment, doc), on hand-made segments.
    qod_node_test(search_rowmap_test)
    add_test(NAME search_rowmap COMMAND search_rowmap_test)
    # The custom sink operator (spec 7.6), through a sink function the test program registers itself.
    qod_node_test(search_sink_test)
    add_test(NAME search_sink COMMAND search_sink_test)
    # qod_search and qod_search_matches end to end on a DuckLake (lineage refusals, results against a fresh
    # single-segment reference, the describer's reads, the sink, prepared statements, the index records, the
    # build). Exits 77 when ducklake cannot load.
    qod_node_test(search_engine_test)
    target_link_libraries(search_engine_test PRIVATE qod_node_test_http)
    add_test(NAME search_engine COMMAND search_engine_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/descriptions)
    set_tests_properties(search_engine PROPERTIES SKIP_RETURN_CODE 77)
    # G1 and G2 on a Postgres catalog (Task 13): skipped (77) unless QOD_P3_PG_DSN_FILE names a DSN file.
    add_test(NAME search_engine_postgres
             COMMAND search_engine_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/descriptions --postgres)
    set_tests_properties(search_engine_postgres PROPERTIES SKIP_RETURN_CODE 77)
    # The signed per-node index record fetch against a local replica answering canned signed bodies.
    qod_node_test(search_records_test)
    target_link_libraries(search_records_test PRIVATE qod_node_test_http)
    add_test(NAME search_records COMMAND search_records_test)
    # The cross-cutting suites (spec 12 Phase 1 items 18 to 21 and 23, invariants 2, 3, 4, 5, 7 and 8): the
    # property test (seeded: QOD_SEARCH_PROPERTY_SEED, QOD_SEARCH_PROPERTY_CASES), the adversarial list, bundle
    # removal and snapshots, limits and cancellation. They share tests/search_fixtures.hpp with the engine test.
    # The property test's reference tokenizes with the library's qs_test_tokenize, so it runs only where the
    # test programs link the test-hooks build (QOD_SEARCH_TEST_HOOKS), and exits 77 elsewhere.
    foreach(_search_suite property adversarial removal limits)
      qod_node_test(search_${_search_suite}_test)
      target_link_libraries(search_${_search_suite}_test PRIVATE qod_node_test_http)
      add_test(NAME search_${_search_suite} COMMAND search_${_search_suite}_test)
      set_tests_properties(search_${_search_suite} PROPERTIES SKIP_RETURN_CODE 77)
    endforeach()
    if(NOT WIN32)
      # No thread-local state in the sink or the search functions (Q3: a final can run on another thread).
      add_test(NAME search_sink_no_tls COMMAND sh -c
        "! grep -n thread_local ${CMAKE_CURRENT_SOURCE_DIR}/src/search_*.cpp")
      # No InternalException on any search path: DuckDB 2.0 invalidates the whole database on one raised
      # during execution, and prints one with a stack trace (review C1, I3).
      add_test(NAME search_no_internal_exception COMMAND sh -c
        "! grep -n InternalException ${CMAKE_CURRENT_SOURCE_DIR}/src/search_*.cpp")
    endif()
    # The library qod-node links carries no test-only export (the test-hooks feature's qs_test_*, which only the
    # test programs' second build has), read with the llvm-nm of the pinned Rust release: Apple's nm prints nothing
    # for rustc's objects and so cannot fail. Positive controls: the plain library exports the C ABI, the test-hooks
    # build exports qs_test_tokenize, and (outside Windows, whose programs keep no symbol table) qod-node holds the
    # library's qs_search and no qs_test_.
    execute_process(COMMAND ${CMAKE_COMMAND} -E env "RUSTUP_TOOLCHAIN=${Rust_TOOLCHAIN}" ${Rust_COMPILER_CACHED} --print sysroot
                    OUTPUT_VARIABLE _qod_rust_sysroot OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE _qod_sysroot_rc)
    set(QOD_RUST_LLVM_NM "")
    if(_qod_sysroot_rc EQUAL 0 AND _qod_rust_sysroot)
      file(TO_CMAKE_PATH "${_qod_rust_sysroot}" _qod_rust_sysroot)
      file(GLOB _qod_rust_llvm_nms "${_qod_rust_sysroot}/lib/rustlib/*/bin/llvm-nm${CMAKE_EXECUTABLE_SUFFIX}")
      if(_qod_rust_llvm_nms)
        list(SORT _qod_rust_llvm_nms)
        list(GET _qod_rust_llvm_nms 0 QOD_RUST_LLVM_NM)
      endif()
    endif()
    if(QOD_RUST_LLVM_NM)
      set(_qod_lib_checks
        -DFILE_1=$<TARGET_FILE:qod_search_core-static> -DREQUIRE_1=qs_search+qs_matches_next+qs_builder_new
        -DFORBID_1=qs_test_)
      set(_qod_lib_count 1)
      if(TARGET qod_search_core_test_hooks)
        math(EXPR _qod_lib_count "${_qod_lib_count} + 1")
        list(APPEND _qod_lib_checks
          -DFILE_${_qod_lib_count}=$<TARGET_FILE:qod_search_core_test_hooks> -DREQUIRE_${_qod_lib_count}=qs_test_tokenize)
      endif()
      if(NOT WIN32)
        math(EXPR _qod_lib_count "${_qod_lib_count} + 1")
        list(APPEND _qod_lib_checks
          -DFILE_${_qod_lib_count}=$<TARGET_FILE:qod-node> -DREQUIRE_${_qod_lib_count}=qs_search
          -DFORBID_${_qod_lib_count}=qs_test_)
      endif()
      add_test(NAME search_library_symbols COMMAND ${CMAKE_COMMAND} -DTOOL=${QOD_RUST_LLVM_NM}
               -DCHECKS=${_qod_lib_count} ${_qod_lib_checks} -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/check_symbols.cmake)
    else()
      message(WARNING "qod-node: no llvm-nm in the Rust ${Rust_TOOLCHAIN} toolchain, so ctest has no "
                      "search_library_symbols; install it with: rustup component add llvm-tools --toolchain ${Rust_TOOLCHAIN}")
    endif()
  endif()
  # No C++ test hook in qod-node, on every build (QOD_NODE_TEST_HOOKS is the test programs' core only; Windows ships
  # a qod-node.exe built with QOD_NODE_TESTS=ON). Positive controls: the test programs' core holds the hooks
  # (NodeInternalPassesForTest, in every build), and qod-node holds BuildInfo (and RegisterSearchFunctions in a
  # search build), so a tool that reads nothing, or a stripped binary, fails rather than passing unchecked. An MSVC
  # program keeps no symbol table, so on Windows the tool reads the core library qod-node links instead: dumpbin
  # beside the linker, else one on PATH, else an llvm-nm (the Rust toolchain's, then LLVM's).
  set(_qod_node_symbols BuildInfo)
  if(QOD_NODE_SEARCH)
    set(_qod_node_symbols BuildInfo+RegisterSearchFunctions)
  endif()
  set(QOD_SYMBOL_TOOL "")
  set(QOD_SYMBOL_TOOL_ARG "")
  if(WIN32)
    get_filename_component(_qod_linker_dir "${CMAKE_LINKER}" DIRECTORY)
    find_program(QOD_DUMPBIN dumpbin HINTS "${_qod_linker_dir}")
    find_program(QOD_LLVM_NM llvm-nm)
    if(QOD_DUMPBIN)
      set(QOD_SYMBOL_TOOL "${QOD_DUMPBIN}")
      set(QOD_SYMBOL_TOOL_ARG /symbols)
    elseif(QOD_RUST_LLVM_NM)
      set(QOD_SYMBOL_TOOL "${QOD_RUST_LLVM_NM}")
    elseif(QOD_LLVM_NM)
      set(QOD_SYMBOL_TOOL "${QOD_LLVM_NM}")
    endif()
    set(_qod_node_file $<TARGET_FILE:qod_node_core>)
  else()
    find_program(QOD_NM nm)
    if(QOD_NM)
      set(QOD_SYMBOL_TOOL "${QOD_NM}")
    endif()
    set(_qod_node_file $<TARGET_FILE:qod-node>)
  endif()
  if(QOD_SYMBOL_TOOL)
    message(STATUS "qod-node: the test-hook symbol gate reads symbols with ${QOD_SYMBOL_TOOL}")
    add_test(NAME no_test_hooks_in_node COMMAND ${CMAKE_COMMAND} -DTOOL=${QOD_SYMBOL_TOOL} -DTOOL_ARG=${QOD_SYMBOL_TOOL_ARG}
             -DCHECKS=2 -DFILE_1=$<TARGET_FILE:qod_node_core_hooks> -DREQUIRE_1=NodeInternalPassesForTest
             -DFILE_2=${_qod_node_file} -DREQUIRE_2=${_qod_node_symbols} -DFORBID_2=ForTest
             -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/check_symbols.cmake)
  else()
    message(WARNING "qod-node: no nm, dumpbin or llvm-nm found, so ctest has no no_test_hooks_in_node")
  endif()
  find_package(Threads REQUIRED)
  # A real HTTP server on 127.0.0.1 for the decide client's tests, on every platform.
  add_library(qod_node_test_http STATIC tests/test_http_server.cpp)
  target_link_libraries(qod_node_test_http PUBLIC qod_node_core_hooks Threads::Threads)
  qod_node_test(verdict_test)
  target_link_libraries(verdict_test PRIVATE Threads::Threads)
  add_test(NAME verdict COMMAND verdict_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/verdict-v1.txt
           ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/verdict-v1-search.txt)
  qod_node_test(http_post_test)
  target_link_libraries(http_post_test PRIVATE qod_node_test_http)
  add_test(NAME http_post COMMAND http_post_test)
  qod_node_test(decide_client_test)
  target_link_libraries(decide_client_test PRIVATE qod_node_test_http)
  add_test(NAME decide_client COMMAND decide_client_test)
  qod_node_test(locations_test)
  add_test(NAME locations COMMAND locations_test)
  qod_node_test(screen_rules_test)
  add_test(NAME screen_rules COMMAND screen_rules_test)
  qod_node_test(init_policy_test)
  add_test(NAME init_policy COMMAND init_policy_test)
  # The screen's location decisions ask a real replica.
  target_link_libraries(authz_engine_test PRIVATE qod_node_test_http)
  qod_node_test(credentials_test)
  target_link_libraries(credentials_test PRIVATE qod_node_test_http)
  add_test(NAME credentials COMMAND credentials_test)
  qod_node_test(credentials_engine_test)
  target_link_libraries(credentials_engine_test PRIVATE qod_node_test_http)
  add_test(NAME credentials_engine COMMAND credentials_engine_test)
  qod_node_test(read_only_engine_test)
  target_link_libraries(read_only_engine_test PRIVATE qod_node_test_http)
  add_test(NAME read_only_engine COMMAND read_only_engine_test)
  qod_node_test(json_writer_test)
  add_test(NAME json_writer COMMAND json_writer_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/golden/canonical-json-v1.txt)
  qod_node_test(node_config_test)
  add_test(NAME node_config COMMAND node_config_test)
  qod_node_test(spill_dir_test)
  add_test(NAME spill_dir COMMAND spill_dir_test)
  qod_node_test(s3_env_test)
  add_test(NAME s3_env COMMAND s3_env_test)
  # Fake credentials in this test's environment only, never in the shell's.
  set_tests_properties(s3_env PROPERTIES SKIP_RETURN_CODE 77 ENVIRONMENT
    "AWS_ACCESS_KEY_ID=AKIAQODFAKETEST00000;AWS_SECRET_ACCESS_KEY=qod-fake-secret-not-a-key;AWS_SESSION_TOKEN=qod-fake-session-token;AWS_REGION=qod-fake-region-1;DUCKDB_S3_ENDPOINT=qod-fake-endpoint.invalid;DUCKDB_S3_KMS_KEY_ID=qod-fake-kms-key")
  qod_node_test(functions_list_test)
  add_test(NAME functions_list COMMAND functions_list_test)
  qod_node_test(handle_tool)
  # Task 14's per-step timings, run by hand on a quiet machine (no add_test: timings are not tests).
  if(NOT WIN32)
    qod_node_test(authz_bench)
    target_link_libraries(authz_bench PRIVATE qod_node_test_http)
  endif()
  find_program(BASH bash)
  if(BASH AND NOT WIN32)
    add_test(NAME process COMMAND ${BASH} ${CMAKE_CURRENT_SOURCE_DIR}/tests/process_test.sh
      $<TARGET_FILE:qod-node> $<TARGET_FILE:handle_tool>)
  endif()
endif()
