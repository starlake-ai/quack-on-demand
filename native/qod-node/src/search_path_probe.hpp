#pragma once

#include <string>

namespace qod {
namespace search {

//! Windows only: true when the local `path` (UTF-8) is not there, by GetFileAttributesW failing with
//! ERROR_FILE_NOT_FOUND or ERROR_PATH_NOT_FOUND (a missing file or a missing folder on its way), and false for
//! anything else (it exists, access is denied, the name is invalid, ...). DuckDB's local file system returns no
//! handle for FILE_FLAGS_NULL_IF_NOT_EXISTS on ERROR_FILE_NOT_FOUND alone, where POSIX's ENOENT covers both
//! (search_segments.cpp). In a unit of its own so that windows.h's macros (CreateDirectory, ...) reach no other.
//! Always false off Windows.
bool LocalPathNotFound(const std::string &path);

} // namespace search
} // namespace qod
