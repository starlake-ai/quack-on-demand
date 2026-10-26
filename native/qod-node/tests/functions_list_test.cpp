// Tests for the classification listings: --list-functions and --list-settings print a JSON array of
// canonical elements, one per line, sorted, with macros left out and qod-node's own functions in.
// Built with -DQOD_NODE_TESTS=ON, run by CTest.

#include "authz.hpp"
#include "duckdb.hpp"
#include "duckdb/main/config.hpp"
#include "file_functions.hpp"
#include "functions_list.hpp"
#include "json.hpp"
#include "json_writer.hpp"
#include "node_hooks.hpp"
#include "settings_list.hpp"

#include <iostream>
#include <iterator>
#include <memory>
#include <set>
#include <sstream>
#include <string>
#include <vector>

namespace {

int failures = 0;

void Expect(bool ok, const std::string &what) {
	if (!ok) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

//! The element lines of a listing, between "[" and "]", each without its separating comma. Records a
//! failure when the listing is not valid JSON or not one element per line.
std::vector<std::string> Elements(const std::string &listing, const std::string &what) {
	try {
		qod::ParseJson(listing);
	} catch (std::exception &error) {
		Expect(false, what + ": not JSON: " + error.what());
		return {};
	}
	std::vector<std::string> lines;
	std::istringstream in(listing);
	std::string line;
	while (std::getline(in, line)) {
		lines.push_back(line);
	}
	if (lines.size() < 2 || lines.front() != "[" || lines.back() != "]") {
		Expect(false, what + ": not one element per line between [ and ]");
		return {};
	}
	std::vector<std::string> elements;
	for (size_t i = 1; i + 1 < lines.size(); i++) {
		auto element = lines[i];
		if (i + 2 < lines.size()) {
			Expect(!element.empty() && element.back() == ',', what + ": no comma after element " + std::to_string(i));
			element.pop_back();
		}
		elements.push_back(element);
	}
	return elements;
}

//! Each element is canonical JSON and the elements strictly increase (sorted, none repeated).
void ExpectSortedCanonical(const std::vector<std::string> &elements, const std::string &what) {
	for (size_t i = 0; i < elements.size(); i++) {
		Expect(qod::CanonicalJson(qod::ParseJson(elements[i])) == elements[i], what + ": not canonical: " + elements[i]);
		if (i > 0) {
			Expect(elements[i - 1] < elements[i], what + ": out of order or repeated: " + elements[i]);
		}
	}
}

bool Contains(const std::vector<std::string> &elements, const std::string &element) {
	for (auto &e : elements) {
		if (e == element) {
			return true;
		}
	}
	return false;
}

} // namespace

int main() {
	Expect(qod::SortedJsonLines({}) == "[]\n", "an empty listing");
	Expect(qod::SortedJsonLines({"\"b\"", "\"a\"", "\"b\""}) == "[\n\"a\",\n\"b\"\n]\n", "sorted, duplicates dropped");

	duckdb::DuckDB db(nullptr);
	// As --list-functions does: qod-node's own functions are part of what a node resolves.
	qod::RegisterNodeAuthz(*db.instance, std::make_shared<qod::NodeAuthz>("qod-node-list", std::string(32, '\0')));
	duckdb::Connection con(db);
	// Macros are expanded while binding: what they call is classified, never the macro itself.
	con.Query("CREATE MACRO qod_test_macro(x) AS x + 1");
	con.Query("CREATE MACRO qod_test_table_macro() AS TABLE SELECT 1 AS v");

	std::ostringstream functions;
	qod::ListFunctions(con, functions);
	auto fns = Elements(functions.str(), "functions");
	ExpectSortedCanonical(fns, "functions");
	Expect(fns.size() > 500, "functions: only " + std::to_string(fns.size()) + " listed");
	for (auto &listed : {"{\"aliasOf\":null,\"name\":\"lower\",\"schema\":\"main\",\"type\":\"scalar\"}",
	                     "{\"aliasOf\":null,\"name\":\"read_parquet\",\"schema\":\"main\",\"type\":\"table\"}",
	                     "{\"aliasOf\":null,\"name\":\"qod_session\",\"schema\":\"main\",\"type\":\"table\"}",
	                     "{\"aliasOf\":null,\"name\":\"qod_screen\",\"schema\":\"main\",\"type\":\"scalar\"}"}) {
		Expect(Contains(fns, listed), std::string("functions: missing ") + listed);
	}
	for (auto &e : fns) {
		auto v = qod::ParseJson(e);
		Expect(v.object.size() == 4 && v.object.count("aliasOf") && v.object.count("name") && v.object.count("schema") &&
		           v.object.count("type"),
		       "functions: unexpected keys in " + e);
		auto &type = v.Get("type", qod::JsonValue::Kind::STRING).string;
		Expect(type != "macro" && type != "table_macro", "functions: a macro is listed: " + e);
		Expect(v.Get("name", qod::JsonValue::Kind::STRING).string.rfind("qod_test_", 0) != 0,
		       "functions: a user macro is listed: " + e);
	}

	auto &config = duckdb::DBConfig::GetConfig(*db.instance);
	config.AddExtensionOption("qod_test_option", "test option", duckdb::LogicalType::VARCHAR, duckdb::Value("x"), nullptr,
	                          duckdb::SetScope::SESSION);
	std::ostringstream settings;
	qod::ListSettings(config, settings);
	auto sets = Elements(settings.str(), "settings");
	ExpectSortedCanonical(sets, "settings");
	Expect(Contains(sets, "{\"kind\":\"alias\",\"name\":\"memory_limit\",\"target\":\"max_memory\"}"),
	       "settings: the memory_limit alias");
	Expect(Contains(sets, "{\"kind\":\"extension\",\"name\":\"qod_test_option\",\"scope\":\"session\",\"setters\":\"gl\"}"),
	       "settings: an extension option");
	bool threads = false;
	for (auto &e : sets) {
		auto v = qod::ParseJson(e);
		auto &kind = v.Get("kind", qod::JsonValue::Kind::STRING).string;
		Expect(kind == "core" || kind == "extension" || kind == "alias", "settings: unknown kind in " + e);
		threads = threads || (kind == "core" && v.Get("name", qod::JsonValue::Kind::STRING).string == "threads");
	}
	Expect(threads, "settings: the core setting threads is not listed");

	// The lists CMake embeds from file-functions.txt and admitted-table-functions.txt: sorted,
	// disjoint, and they say what the classification says.
	std::vector<std::string> file(std::begin(qod::kFileFunctions), std::end(qod::kFileFunctions));
	std::vector<std::string> admitted(std::begin(qod::kAdmittedTableFunctions), std::end(qod::kAdmittedTableFunctions));
	for (auto *list : {&file, &admitted}) {
		for (size_t i = 1; i < list->size(); i++) {
			Expect((*list)[i - 1] < (*list)[i], "embedded list out of order or repeated: " + (*list)[i]);
		}
	}
	std::set<std::string> file_set(file.begin(), file.end());
	std::set<std::string> admitted_set(admitted.begin(), admitted.end());
	for (auto &name : file) {
		Expect(!admitted_set.count(name), "both embedded lists name " + name);
	}
	for (auto *name : {"glob", "read_csv", "read_parquet", "read_text"}) {
		Expect(file_set.count(name), std::string("kFileFunctions lacks ") + name);
	}
	for (auto *name : {"duckdb_tables", "ducklake_set_commit_message", "qod_session", "query", "range",
	                   "scan_data_from_quack_client"}) {
		Expect(admitted_set.count(name), std::string("kAdmittedTableFunctions lacks ") + name);
	}
	// The nearest relatives of what the lists hold stay in neither: they are superuser-only.
	for (auto *name : {"duckdb_connection_count", "duckdb_databases", "ducklake_scan", "iceberg_scan",
	                   "json_execute_serialized_sql", "postgres_query", "pragma_table_info", "quack_query",
	                   "sql_auto_complete"}) {
		Expect(!file_set.count(name) && !admitted_set.count(name), std::string("an embedded list names ") + name);
	}

	if (failures == 0) {
		std::cout << "functions_list_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
