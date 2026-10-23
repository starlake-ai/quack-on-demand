// qod-node: the DuckDB process that serves one Quack node.
//
// It opens a DuckDB instance from an official release library, runs the init SQL the spawn script
// assembled (statement by statement, best effort, as the DuckDB CLI did), then serves over quack
// until a stop signal arrives or the spawn wrapper that started it ends; either one, while it is still
// starting, ends it at once. What must not fail silently runs strict, here rather than in the SQL: the
// secret directory, loading quack, the parse screen and quack_serve; the identity checks (node_hooks)
// are registered before any SQL runs.

#include "authz.hpp"
#include "build_info.hpp"
#include "duckdb.hpp"
#include "init_runner.hpp"
#include "lifetime.hpp"
#include "node_hooks.hpp"
#include "options.hpp"
#include "secrets.hpp"
#include "settings_list.hpp"
#include "sql_script.hpp"
#include "utf8_args.hpp"
#ifdef QOD_NODE_DUCKDB_STATIC
#include "static_extensions.hpp"
#endif

#include <iostream>
#include <memory>
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

//! What qod-node reads from its secrets directory itself.
struct NodeSecrets {
	std::string token;
	std::string node_key;
};

// The secrets directory goes whether or not the reads succeed: nothing in it is needed again.
NodeSecrets ReadNodeSecrets(const NodeOptions &options) {
	try {
		NodeSecrets secrets {ReadSecret(options.secrets_dir, "QOD_NODE_TOKEN"), ReadNodeKey(options.secrets_dir)};
		DisposeSecrets(options.secrets_dir, options.secrets_ephemeral);
		return secrets;
	} catch (...) {
		DisposeSecrets(options.secrets_dir, options.secrets_ephemeral);
		throw;
	}
}

int Serve(const NodeOptions &options) {
	auto secrets = ReadNodeSecrets(options);

	duckdb::DuckDB db(nullptr);
	// Before any SQL: qod_session, the parse screen and the post_bind backstop apply to every
	// connection quack opens from here on.
	RegisterNodeAuthz(*db.instance, std::make_shared<NodeAuthz>(options.node_id, secrets.node_key));
	duckdb::Connection con(db);

	// Before any CREATE SECRET or ATTACH: once duckdb_secrets() has put the secret manager in use,
	// DuckDB refuses to change secret_directory from any session.
	RunStrict(con, "SET secret_directory = " + QuoteLiteral(options.secret_directory), "setting the secret directory");
	RunStrict(con, "SELECT count(*) FROM duckdb_secrets()", "starting the secret manager");

	RunInitFiles(con, options);

	RunStrict(con, "INSTALL quack", "installing quack");
	RunStrict(con, "LOAD quack", "loading quack");
	// After the init SQL, so nothing it ran can replace the screen.
	RunStrict(con, std::string("SET GLOBAL quack_authorization_function = ") + QuoteLiteral(kScreenFunction),
	          "installing the parse screen");
	auto served = RunStrict(con, ServeStatement(options.bind, options.port, secrets.token), "quack_serve");
	auto chunk = served->FetchRaw();
	std::cerr << "[qod-node] serving on " << (chunk && chunk->size() > 0 ? chunk->GetValue(0, 0).ToString() : "?")
	          << std::endl;
	MarkServing();
	std::cerr << "[qod-node] ready" << std::endl;

	auto reason = WaitForStop();
	std::cerr << "[qod-node] stopping: " << reason << std::endl;
	return 0;
}

} // namespace

int main(int argc, char **argv) {
	NodeOptions options;
	try {
		options = ParseOptions(Utf8Arguments(argc, argv));
	} catch (std::invalid_argument &error) {
		std::cerr << "qod-node: " << error.what() << "\n" << Usage();
		return 2;
	} catch (std::exception &error) {
		std::cerr << "qod-node: " << error.what() << std::endl;
		return 1;
	}
	if (options.mode == NodeOptions::Mode::BUILD_INFO) {
		std::cout << BuildInfo() << std::endl;
#ifndef QOD_NODE_DUCKDB_STATIC
		// Also the libduckdb this process loaded: the manager runs --build-info at boot to learn the
		// binary starts here, against the release it was built for.
		try {
			CheckLinkedDuckDB();
		} catch (std::exception &error) {
			std::cerr << "[qod-node] " << error.what() << std::endl;
			return 1;
		}
#endif
		return 0;
	}

	StartStopWatch(options.parent_pid);
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
