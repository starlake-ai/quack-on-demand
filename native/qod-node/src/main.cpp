// qod-node: the DuckDB process that serves one Quack node.
//
// It opens a DuckDB instance from an official release library, runs the init SQL the spawn script
// assembled (statement by statement, best effort, as the DuckDB CLI did), then serves over quack
// until a stop signal arrives or the spawn wrapper that started it ends. What must not fail silently
// runs strict, here rather than in the SQL: the secret directory, loading quack and quack_serve.

#include "build_info.hpp"
#include "duckdb.hpp"
#include "init_runner.hpp"
#include "lifetime.hpp"
#include "options.hpp"
#include "secrets.hpp"
#include "settings_list.hpp"
#include "sql_script.hpp"
#ifdef QOD_NODE_DUCKDB_STATIC
#include "static_extensions.hpp"
#endif

#include <iostream>
#include <stdexcept>

namespace {

using namespace qod;

void RunInitFiles(duckdb::Connection &con, const NodeOptions &options) {
	for (auto &file : options.init_files) {
		RunBestEffort(con, ReadAll(file), "init");
	}
}

int ListSettingsMode(const NodeOptions &options) {
	duckdb::DuckDB db(nullptr);
	duckdb::Connection con(db);
	RunInitFiles(con, options);
	ListSettings(duckdb::DBConfig::GetConfig(*db.instance), std::cout);
	return 0;
}

int Serve(const NodeOptions &options) {
	auto token = ReadSecret(options.secrets_dir, "QOD_NODE_TOKEN");
	DisposeSecrets(options.secrets_dir, options.secrets_ephemeral);

	duckdb::DuckDB db(nullptr);
	duckdb::Connection con(db);

	// Before any CREATE SECRET or ATTACH: once duckdb_secrets() has put the secret manager in use,
	// DuckDB refuses to change secret_directory from any session.
	RunStrict(con, "SET secret_directory = " + QuoteLiteral(options.secret_directory), "setting the secret directory");
	RunStrict(con, "SELECT count(*) FROM duckdb_secrets()", "starting the secret manager");

	RunInitFiles(con, options);

	RunStrict(con, "INSTALL quack", "installing quack");
	RunStrict(con, "LOAD quack", "loading quack");
	// Only listen_url is read back, so the token never reaches the output.
	auto served = RunStrict(con,
	                        "SELECT listen_url FROM quack_serve(" +
	                            QuoteLiteral("quack:" + options.bind + ":" + std::to_string(options.port)) +
	                            ", token := " + QuoteLiteral(token) +
	                            ", allow_other_hostname := true, disable_ssl := true)",
	                        "quack_serve");
	auto chunk = served->FetchRaw();
	std::cerr << "[qod-node] serving on " << (chunk && chunk->size() > 0 ? chunk->GetValue(0, 0).ToString() : "?")
	          << std::endl;
	std::cerr << "[qod-node] ready" << std::endl;

	auto reason = WaitForStop(options.parent_pid);
	std::cerr << "[qod-node] stopping: " << reason << std::endl;
	return 0;
}

} // namespace

int main(int argc, char **argv) {
	NodeOptions options;
	try {
		options = ParseOptions(argc, argv);
	} catch (std::invalid_argument &error) {
		std::cerr << "qod-node: " << error.what() << "\n" << Usage();
		return 2;
	}
	if (options.mode == NodeOptions::Mode::BUILD_INFO) {
		std::cout << BuildInfo() << std::endl;
		return 0;
	}

	PrepareStopSignals();
	try {
#ifdef QOD_NODE_DUCKDB_STATIC
		RegisterStaticExtensions();
#else
		CheckLinkedDuckDB();
#endif
		if (options.mode == NodeOptions::Mode::LIST_SETTINGS) {
			return ListSettingsMode(options);
		}
		return Serve(options);
	} catch (std::exception &error) {
		std::cerr << "[qod-node] " << error.what() << std::endl;
		return 1;
	}
}
