cmake_minimum_required(VERSION 3.20)
project(quackwire CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Default to Release so a bare `cmake -B build` (no -DCMAKE_BUILD_TYPE) still
# produces an optimised library. CI matrix (Task 8) passes Release explicitly.
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

find_package(JNI REQUIRED)

add_library(quackwire SHARED src/quackwire.cpp)
target_include_directories(quackwire PRIVATE ${JNI_INCLUDE_DIRS})

# duckdb-quack message layer - three files only; rest excluded per design
# doc 9.4 because they pull in httplib / the HTTP server / scan extension.
set(DUCKDB_QUACK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/thirdparty/duckdb-quack)
target_sources(quackwire PRIVATE
  ${DUCKDB_QUACK_DIR}/src/quack_message.cpp
  ${DUCKDB_QUACK_DIR}/src/serialize_quack_message.cpp
  ${DUCKDB_QUACK_DIR}/src/quack_uri.cpp
)

# Order matters: include/shim/ comes first so quack_message.cpp's
# `#include "quack_server.hpp"` resolves to our minimal stub (which only
# forward-declares QuackServer::QUACK_VERSION) instead of the upstream
# header that transitively drags in httplib.hpp. The BEFORE keyword
# guarantees this entry stays at the very front of the include search
# path, even if a later `target_include_directories` call appends paths
# under the duckdb-quack tree.
target_include_directories(quackwire BEFORE PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/include/shim
)
target_include_directories(quackwire PRIVATE
  ${DUCKDB_QUACK_DIR}/src/include
)

# libduckdb v1.5.4 link target.
#
# Path 1 (chosen): system install via Homebrew on macOS / apt on Linux.
# On this developer machine `brew info duckdb` reports stable 1.5.4 - an
# exact match for the version duckdb-quack at 40de7badae41 was built against.
# Task 8 (CI matrix) will likely switch CI to FetchContent on the v1.5.4
# release artifact for reproducibility, but for local dev the system
# package is the fastest path to a working link.
find_path(DUCKDB_INCLUDE_DIR duckdb.hpp HINTS /opt/homebrew/include /usr/local/include /usr/include)
find_library(DUCKDB_LIB duckdb HINTS /opt/homebrew/lib /usr/local/lib /usr/lib)
if(NOT DUCKDB_INCLUDE_DIR OR NOT DUCKDB_LIB)
  message(FATAL_ERROR "libduckdb not found. Install via 'brew install duckdb' (macOS) or 'apt install libduckdb-dev' (Linux), or wire up FetchContent against the v1.5.4 release artifact.")
endif()
message(STATUS "quackwire: using libduckdb headers at ${DUCKDB_INCLUDE_DIR}")
message(STATUS "quackwire: linking libduckdb at ${DUCKDB_LIB}")
target_include_directories(quackwire PRIVATE ${DUCKDB_INCLUDE_DIR})
target_link_libraries(quackwire PRIVATE ${DUCKDB_LIB})

if(APPLE)
  set_target_properties(quackwire PROPERTIES SUFFIX ".dylib")
endif()