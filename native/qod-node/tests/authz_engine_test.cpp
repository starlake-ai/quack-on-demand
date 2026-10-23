// Engine tests for node_hooks: qod_session, qod_screen and the post_bind backstop on a real DuckDB
// instance. A connection stands in for a quack client connection by carrying quack's session tag,
// which quack inserts on every connection it creates. Built with -DQOD_NODE_TESTS=ON, run by CTest.

#include "authz.hpp"
#include "duckdb.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "node_hooks.hpp"
#include "sql_script.hpp"
#include "test_handles.hpp"

#include <iostream>
#include <memory>
#include <string>

namespace {

using qod_test::kKey;
using qod_test::kNode;
using qod_test::Mint;
using qod_test::Prelude;

int failures = 0;

//! Runs sql on con; expects success, or (needle non-empty) an error mentioning needle.
void ExpectQuery(duckdb::Connection &con, const std::string &sql, const std::string &needle, const std::string &what) {
	auto result = con.Query(sql);
	if (needle.empty()) {
		if (result->HasError()) {
			std::cerr << "FAIL: " << what << ": " << result->GetError() << std::endl;
			failures++;
		}
		return;
	}
	if (!result->HasError()) {
		std::cerr << "FAIL: accepted: " << what << std::endl;
		failures++;
	} else if (result->GetError().find(needle) == std::string::npos) {
		std::cerr << "FAIL: " << what << ": \"" << result->GetError() << "\" does not mention \"" << needle << "\""
		          << std::endl;
		failures++;
	}
}

struct FakeQuackSession : public duckdb::ClientContextState {};

void TagAsQuackSession(duckdb::Connection &con) {
	con.context->registered_state->Insert(qod::kQuackSessionKey, duckdb::make_shared_ptr<FakeQuackSession>());
}

int64_t now_ms = 1761200000000;

//! A handle valid for a minute from now.
std::string Fresh(const std::string &principal_id, const std::string &node = kNode, int64_t policy_version = 1) {
	return Mint(principal_id, now_ms + 60000, node, policy_version);
}

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(kNode, kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz);
	// Stands in for the setting quack registers, so SET reaches post_bind.
	duckdb::DBConfig::GetConfig(*db.instance)
	    .AddExtensionOption("quack_authorization_function", "test stand-in", duckdb::LogicalType::VARCHAR,
	                        duckdb::Value("x"), nullptr, duckdb::SetScope::GLOBAL);

	// ---- the backstop: a quack connection runs nothing without a principal
	duckdb::Connection internal(db);
	ExpectQuery(internal, "SELECT 1", "", "an internal connection runs without a principal");
	duckdb::Connection alice(db);
	TagAsQuackSession(alice);
	ExpectQuery(alice, "SELECT 1", "no verified principal", "a quack connection without a principal");
	ExpectQuery(alice, "CALL qod_session('garbage')", "session handle refused", "a malformed handle");
	ExpectQuery(alice, Prelude(Fresh("u-alice", "quack-other-node")), "another node", "a handle for another node");

	auto first = Fresh("u-alice");
	ExpectQuery(alice, Prelude(first), "", "the prelude installs alice");
	ExpectQuery(alice, "SELECT 42", "", "alice's statement runs");
	ExpectQuery(alice, "SET GLOBAL quack_authorization_function = 'x'", "cannot be changed",
	            "a session cannot replace the parse screen");
	ExpectQuery(alice, "RESET quack_authorization_function", "cannot be changed", "nor reset it");
	ExpectQuery(alice, Prelude(Fresh("u-alice", kNode, 2)), "", "a refresh with a newer policy version");
	ExpectQuery(alice, Prelude(Fresh("u-alice", kNode, 1)), "older policy version", "a policy version downgrade");
	ExpectQuery(alice, Prelude(Fresh("u-mallory")), "another principal", "another principal on alice's connection");

	duckdb::Connection bob(db);
	TagAsQuackSession(bob);
	ExpectQuery(bob, Prelude(first), "already used", "alice's handle replayed on another connection");

	// ---- expiry: the installed principal lasts as long as its handle
	now_ms += 61000;
	ExpectQuery(alice, "SELECT 1", "no verified principal", "an expired principal");
	ExpectQuery(alice, Prelude(Fresh("u-alice", kNode, 2)), "", "a fresh handle refreshes it");
	ExpectQuery(alice, "SELECT 1", "", "and the connection runs again");

	// ---- the parse screen, as quack calls it on a connection of its own
	ExpectQuery(internal, "SELECT system.main.qod_screen('c-1', 'SELECT 1')", "no verified principal",
	            "the screen refuses a connection it has no principal for");
	ExpectQuery(internal, "SELECT system.main.qod_screen('c-1', " + qod::QuoteLiteral(Prelude(Fresh("u-carol"))) + ")",
	            "", "the screen admits the prelude");
	ExpectQuery(internal, "SELECT system.main.qod_screen('c-1', 'SELECT 1')", "", "and the connection's next statement");
	ExpectQuery(internal, "SELECT system.main.qod_screen('c-2', 'SELECT 1')", "no verified principal",
	            "a principal recorded for one connection only");
	ExpectQuery(internal, "SELECT system.main.qod_screen('c-1', " + qod::QuoteLiteral(Prelude(Fresh("u-dave"))) + ")",
	            "another principal", "the screen refuses another principal on the connection");
	ExpectQuery(alice, "SELECT system.main.qod_screen('c-3', 'SELECT 1')", "authorization callback",
	            "a session cannot call the screen");

	if (failures == 0) {
		std::cout << "authz_engine_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
