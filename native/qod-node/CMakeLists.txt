cmake_minimum_required(VERSION 3.20)
project(qod_node CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# Spike build. DUCKDB_HOME (env var or -DDUCKDB_HOME=) points at a directory holding an official DuckDB
# release library under lib/ and the FULL src/include tree of the same tag under include/. Nothing else
# is searched, so a system DuckDB of another version can never be picked up by accident.
#
#   shared (default): lib/ holds the release libduckdb (duckdb-shared-libs-<platform>.tar.gz); the
#                     .duckdb/<version>/ cache the qod CLI and run-jar.sh provision has this layout.
#   static (QOD_NODE_DUCKDB_STATIC=ON): lib/ holds the release duckdb-static-libs-<platform>.tar.gz.
#                     Required on Windows, where the release duckdb.dll does not export the internal C++
#                     symbols the planner hooks use; the official duckdb.exe is built from these same
#                     libraries.
option(QOD_NODE_DUCKDB_STATIC "Link the official DuckDB release static libraries" OFF)

if(NOT DUCKDB_HOME)
  set(DUCKDB_HOME $ENV{DUCKDB_HOME})
endif()
find_path(DUCKDB_INCLUDE_DIR duckdb.hpp HINTS ${DUCKDB_HOME}/include NO_DEFAULT_PATH)
if(NOT DUCKDB_INCLUDE_DIR OR NOT EXISTS ${DUCKDB_INCLUDE_DIR}/duckdb/planner/planner_extension.hpp)
  message(FATAL_ERROR "DUCKDB_HOME/include must hold DuckDB's full src/include tree of the release tag")
endif()

add_executable(qod-node src/main.cpp src/plan_probe.cpp src/screen_probe.cpp src/stop_signal.cpp)
target_include_directories(qod-node PRIVATE ${DUCKDB_INCLUDE_DIR} src)

if(QOD_NODE_DUCKDB_STATIC)
  file(GLOB DUCKDB_STATIC_LIBS ${DUCKDB_HOME}/lib/*${CMAKE_STATIC_LIBRARY_SUFFIX})
  # The CLI's own library is not part of the engine.
  list(FILTER DUCKDB_STATIC_LIBS EXCLUDE REGEX "duckdb_shell")
  list(FIND DUCKDB_STATIC_LIBS ${DUCKDB_HOME}/lib/${CMAKE_STATIC_LIBRARY_PREFIX}duckdb_static${CMAKE_STATIC_LIBRARY_SUFFIX} core_index)
  if(core_index EQUAL -1)
    message(FATAL_ERROR "DUCKDB_HOME/lib lacks duckdb_static (unpack duckdb-static-libs-<platform>.tar.gz there)")
  endif()
  message(STATUS "qod-node: static DuckDB from ${DUCKDB_HOME}/lib: ${DUCKDB_STATIC_LIBS}")
  target_sources(qod-node PRIVATE src/static_extensions.cpp)
  # DUCKDB_STATIC_BUILD: without it the headers declare every DuckDB function dllimport on Windows.
  target_compile_definitions(qod-node PRIVATE DUCKDB_STATIC_BUILD QOD_NODE_DUCKDB_STATIC)
  if(MSVC)
    # The release static libraries use the static MSVC runtime (DuckDB's CMAKE_MSVC_RUNTIME_LIBRARY).
    set_property(TARGET qod-node PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded")
    target_link_libraries(qod-node PRIVATE ${DUCKDB_STATIC_LIBS} ws2_32 rstrtmgr bcrypt)
  else()
    # duckdb_static and the built-in extension libraries reference each other.
    find_package(Threads REQUIRED)
    if(APPLE)
      target_link_libraries(qod-node PRIVATE ${DUCKDB_STATIC_LIBS})
    else()
      target_link_libraries(qod-node PRIVATE -Wl,--start-group ${DUCKDB_STATIC_LIBS} -Wl,--end-group)
    endif()
    target_link_libraries(qod-node PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
  endif()
else()
  find_library(DUCKDB_LIB duckdb HINTS ${DUCKDB_HOME}/lib NO_DEFAULT_PATH)
  if(NOT DUCKDB_LIB)
    message(FATAL_ERROR "DUCKDB_HOME/lib lacks the release libduckdb (unpack duckdb-shared-libs-<platform>.tar.gz there)")
  endif()
  message(STATUS "qod-node: shared DuckDB ${DUCKDB_LIB}")
  target_link_libraries(qod-node PRIVATE ${DUCKDB_LIB})
  # Spike only: find libduckdb where it was linked from. The real binary will rely on the qod CLI's
  # DUCKDB_CACHE_DIR preload, like libquackwire.
  get_filename_component(DUCKDB_LIB_DIR ${DUCKDB_LIB} DIRECTORY)
  set_target_properties(qod-node PROPERTIES BUILD_RPATH ${DUCKDB_LIB_DIR})
endif()

if(UNIX AND NOT APPLE)
  # Bind every symbol at load, so a library missing one fails at start, not mid-query.
  target_link_options(qod-node PRIVATE "LINKER:-z,now")
endif()
