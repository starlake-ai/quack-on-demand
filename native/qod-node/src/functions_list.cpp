#include "functions_list.hpp"

#include "json_writer.hpp"

#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace qod {

void ListFunctions(duckdb::Connection &con, std::ostream &out) {
	// One row per name, schema and type: overloads collapse; an alias keeps the name it stands for.
	auto result = con.Query("SELECT function_name, schema_name, function_type, max(alias_of) "
	                        "FROM duckdb_functions() "
	                        "WHERE database_name = 'system' AND function_type NOT IN ('macro', 'table_macro') "
	                        "GROUP BY ALL");
	if (result->HasError()) {
		throw std::runtime_error("listing the functions failed: " + result->GetError());
	}
	std::vector<std::string> elements;
	for (auto chunk = result->FetchRaw(); chunk && chunk->size() > 0; chunk = result->FetchRaw()) {
		for (duckdb::idx_t row = 0; row < chunk->size(); row++) {
			auto alias = chunk->GetValue(3, row);
			elements.push_back(
			    CanonicalJson(JsonObject({{"aliasOf", alias.IsNull() ? JsonNull() : JsonString(alias.ToString())},
			                              {"name", JsonString(chunk->GetValue(0, row).ToString())},
			                              {"schema", JsonString(chunk->GetValue(1, row).ToString())},
			                              {"type", JsonString(chunk->GetValue(2, row).ToString())}})));
		}
	}
	out << SortedJsonLines(std::move(elements));
}

} // namespace qod
