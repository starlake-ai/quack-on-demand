// Windows-only DuckDB internal-symbol shims.
//
// The vendored duckdb serializer sources we compile on Windows (see
// CMakeLists.txt) reference a few DuckDB internals that the prebuilt duckdb.dll
// does not export and whose real source files (src/main/config.cpp,
// src/common/value_operations/comparison_operations.cpp) would drag in most of
// DuckDB if compiled. We replicate their exact leaf semantics from
// duckdb @ 14eca11bd9 (the pin behind libduckdb v1.5.4). Kept WIN32-only via
// CMakeLists.txt; on Linux/macOS libduckdb already provides these.
//
// DUCKDB_STATIC_BUILD is defined for the whole Windows build, so DUCKDB_API is
// empty here and these definitions have plain linkage matching the callers.

#include "duckdb/main/config.hpp"
#include "duckdb/common/value_operations/value_operations.hpp"

namespace duckdb {

// src/main/config.cpp: Default() == FromString("v0.10.2") with manually_set
// cleared. src/storage/storage_info.cpp's version table maps "v0.10.2" ->
// serialization_version 64.
SerializationCompatibility SerializationCompatibility::Default() {
	SerializationCompatibility result;
	result.duckdb_version = "v0.10.2";
	result.serialization_version = 64;
	result.manually_set = false;
	return result;
}

// src/main/config.cpp verbatim.
SerializationCompatibility SerializationCompatibility::FromIndex(const idx_t version) {
	SerializationCompatibility result;
	result.duckdb_version = "";
	result.serialization_version = version;
	result.manually_set = false;
	return result;
}

// src/main/config.cpp verbatim.
bool SerializationCompatibility::Compare(idx_t property_version) const {
	return property_version <= serialization_version;
}

// Only reached from the serializer's optional-property default-skip path.
// Returning false disables that write-side optimization (every property is
// serialized explicitly), which is wire-correct: the reader always reads the
// explicit value it finds. This avoids compiling comparison_operations.cpp,
// which pulls in the vector / planner / variant machinery.
bool ValueOperations::NotDistinctFrom(const Value &, const Value &) {
	return false;
}

} // namespace duckdb
