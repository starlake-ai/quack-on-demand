#include "static_extensions.hpp"

#include "duckdb_static_extension.h"

#include <stdexcept>
#include <string>

extern "C" {
int32_t duckdb_extension_loadable_extensions_describe(duckdb_extension_descriptor *descriptor);
int32_t duckdb_extension_httplib_describe(duckdb_extension_descriptor *descriptor);
int32_t duckdb_extension_core_functions_describe(duckdb_extension_descriptor *descriptor);
int32_t duckdb_extension_parquet_describe(duckdb_extension_descriptor *descriptor);
int32_t duckdb_extension_json_describe(duckdb_extension_descriptor *descriptor);
int32_t duckdb_extension_icu_describe(duckdb_extension_descriptor *descriptor);
}

namespace qod {

void RegisterStaticExtensions() {
	struct Linked {
		const char *name;
		duckdb_extension_describe_t describe;
	};
	const Linked linked[] = {
	    {"loadable_extensions", duckdb_extension_loadable_extensions_describe},
	    {"httplib", duckdb_extension_httplib_describe},
	    {"core_functions", duckdb_extension_core_functions_describe},
	    {"parquet", duckdb_extension_parquet_describe},
	    {"json", duckdb_extension_json_describe},
	    {"icu", duckdb_extension_icu_describe},
	};
	for (auto &entry : linked) {
		if (duckdb_register_static_extension(entry.describe) != 0) {
			throw std::runtime_error(std::string("DuckDB refused the static registration of ") + entry.name);
		}
	}
}

} // namespace qod
