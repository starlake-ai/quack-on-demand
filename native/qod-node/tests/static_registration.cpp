// Static builds only (CMakeLists.txt compiles this file into every test program when
// QOD_NODE_DUCKDB_STATIC is on): registers what the release static libraries link like extensions
// before main runs, as qod-node's main does before it opens a database. Without it a test's
// in-memory DuckDB has no core_functions, parquet, json or icu (sum, current_setting, COPY TO
// parquet, the TimeZone and Calendar settings) and cannot INSTALL or LOAD, unlike the shared
// libduckdb the Linux and macOS tests load. DuckDB keeps its registry in a function-local static so
// that registrations during static initialization find it constructed, and registering the same
// describe function again is a no-op.

#include "static_extensions.hpp"

#include <cstdio>
#include <cstdlib>
#include <exception>

namespace {

struct RegisterBeforeMain {
	RegisterBeforeMain() {
		try {
			qod::RegisterStaticExtensions();
		} catch (std::exception &error) {
			std::fprintf(stderr, "FAIL: %s\n", error.what());
			std::exit(1);
		}
	}
};

const RegisterBeforeMain register_before_main;

} // namespace
