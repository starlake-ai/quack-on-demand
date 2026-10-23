// qod-node spike host.
//
// Opens a DuckDB instance from the release libduckdb, registers the planner and optimizer hooks
// before any SQL runs, executes the init files statement by statement, then waits for a stop
// request while quack_serve (started by the init SQL) serves on its own threads.

#include "duckdb.hpp"
#include "duckdb/main/config.hpp"
#include "duckdb/parser/sql_statement.hpp"
#include "plan_probe.hpp"
#include "screen_probe.hpp"
#include "stop_signal.hpp"
#ifdef QOD_NODE_DUCKDB_STATIC
#include "static_extensions.hpp"
#endif

#include <fstream>
#include <iostream>
#include <sstream>

using namespace duckdb;

static string ReadFile(const string &path) {
	std::ifstream in(path);
	if (!in) {
		throw std::runtime_error("cannot read " + path);
	}
	std::stringstream buffer;
	buffer << in.rdbuf();
	return buffer.str();
}

static const char *ScopeTargetName(SettingScopeTarget target) {
	switch (target) {
	case SettingScopeTarget::GLOBAL_ONLY:
		return "global_only";
	case SettingScopeTarget::LOCAL_ONLY:
		return "local_only";
	case SettingScopeTarget::GLOBAL_DEFAULT:
		return "global_default";
	case SettingScopeTarget::LOCAL_DEFAULT:
		return "local_default";
	default:
		return "invalid";
	}
}

static const char *SetScopeName(SetScope scope) {
	switch (scope) {
	case SetScope::AUTOMATIC:
		return "automatic";
	case SetScope::LOCAL:
		return "local";
	case SetScope::SESSION:
		return "session";
	case SetScope::GLOBAL:
		return "global";
	default:
		return "variable";
	}
}

//! Prints every setting the instance knows, core and extension, with the scope metadata DuckDB keeps
//! for it at runtime: the input a settings classification would be generated from.
static void ListSettings(DBConfig &config) {
	for (idx_t i = 0; i < DBConfig::GetOptionCount(); i++) {
		auto option = DBConfig::GetOptionByIndex(i);
		// Settings implemented through custom setters carry no scope target: which setters exist is
		// the only runtime hint (global, local, or both).
		string setters = string(option->set_global ? "g" : "-") + (option->set_local ? "l" : "-");
		std::cout << "core\t" << option->name << "\t" << ScopeTargetName(option->scope) << "\t" << setters
		          << std::endl;
	}
	for (auto &entry : config.GetExtensionSettings()) {
		std::cout << "extension\t" << entry.first.GetIdentifierName() << "\t"
		          << SetScopeName(entry.second.default_scope) << std::endl;
	}
}

int main(int argc, char **argv) {
	vector<string> init_files;
	bool list_settings = false;
	for (int i = 1; i < argc; i++) {
		string arg = argv[i];
		if (arg == "--init" && i + 1 < argc) {
			init_files.push_back(argv[++i]);
		} else if (arg == "--list-settings") {
			list_settings = true;
		} else {
			std::cerr << "usage: qod-node [--init <file>]... [--list-settings]" << std::endl;
			return 2;
		}
	}

	qod::PrepareStopSignal();
#ifdef QOD_NODE_DUCKDB_STATIC
	qod::RegisterStaticExtensions();
#endif

	DuckDB db(nullptr);
	qod::RegisterProbes(DBConfig::GetConfig(*db.instance));
	qod::RegisterScreenProbe(*db.instance);

	Connection init(db);
	for (auto &file : init_files) {
		auto statements = init.ExtractStatements(ReadFile(file));
		for (auto &statement : statements) {
			auto type = StatementTypeToString(statement->type);
			auto result = init.Query(std::move(statement));
			if (result->HasError()) {
				std::cerr << "[init] " << type << " failed: " << result->GetError() << std::endl;
			} else {
				std::cerr << "[init] " << type << " ok" << std::endl;
			}
		}
	}

	if (list_settings) {
		ListSettings(DBConfig::GetConfig(*db.instance));
		return 0;
	}

	std::cerr << "[qod-node] ready; SIGINT or SIGTERM stops it" << std::endl;
	qod::WaitForStopSignal();
	std::cerr << "[qod-node] stopping" << std::endl;
	return 0;
}
