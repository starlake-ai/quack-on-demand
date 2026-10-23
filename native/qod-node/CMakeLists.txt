cmake_minimum_required(VERSION 3.20)
project(qod_node CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# Spike build. DUCKDB_HOME (env var or -DDUCKDB_HOME=) points at a directory holding the release
# libduckdb under lib/ and the FULL src/include tree of the same tag under include/: the
# .duckdb/<version>/ cache the qod CLI and run-jar.sh provision has both. Nothing else is searched,
# so a system DuckDB of another version can never be picked up by accident.
if(NOT DUCKDB_HOME)
  set(DUCKDB_HOME $ENV{DUCKDB_HOME})
endif()
find_path(DUCKDB_INCLUDE_DIR duckdb.hpp HINTS ${DUCKDB_HOME}/include NO_DEFAULT_PATH)
find_library(DUCKDB_LIB duckdb HINTS ${DUCKDB_HOME}/lib NO_DEFAULT_PATH)
if(NOT DUCKDB_INCLUDE_DIR OR NOT DUCKDB_LIB)
  message(FATAL_ERROR "set DUCKDB_HOME to a directory holding include/ (full src/include tree) and lib/ (libduckdb)")
endif()
if(NOT EXISTS ${DUCKDB_INCLUDE_DIR}/duckdb/planner/planner_extension.hpp)
  message(FATAL_ERROR "${DUCKDB_INCLUDE_DIR} lacks DuckDB's internal headers (copy src/include of the same tag)")
endif()
message(STATUS "qod-node: headers ${DUCKDB_INCLUDE_DIR}, library ${DUCKDB_LIB}")

add_executable(qod-node src/main.cpp src/plan_probe.cpp src/screen_probe.cpp src/stop_signal.cpp)
target_include_directories(qod-node PRIVATE ${DUCKDB_INCLUDE_DIR} src)
target_link_libraries(qod-node PRIVATE ${DUCKDB_LIB})

# Spike only: find libduckdb where it was linked from. The real binary will rely on the qod CLI's
# DUCKDB_CACHE_DIR preload, like libquackwire.
get_filename_component(DUCKDB_LIB_DIR ${DUCKDB_LIB} DIRECTORY)
set_target_properties(qod-node PROPERTIES BUILD_RPATH ${DUCKDB_LIB_DIR})
if(UNIX AND NOT APPLE)
  # Bind every symbol at load, so a libduckdb missing one fails at start, not mid-query.
  target_link_options(qod-node PRIVATE "LINKER:-z,now")
endif()
