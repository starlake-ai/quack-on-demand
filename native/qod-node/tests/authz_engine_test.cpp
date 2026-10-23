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

//! Records a failure when `error` (empty: success) does not match `needle` (empty: expect success).
void Check(const std::string &error, bool failed, const std::string &needle, const std::string &what) {
	if (needle.empty()) {
		if (failed) {
			std::cerr << "FAIL: " << what << ": " << error << std::endl;
			failures++;
		}
	} else if (!failed) {
		std::cerr << "FAIL: accepted: " << what << std::endl;
		failures++;
	} else if (error.find(needle) == std::string::npos) {
		std::cerr << "FAIL: " << what << ": \"" << error << "\" does not mention \"" << needle << "\"" << std::endl;
		failures++;
	}
}

//! Runs sql on con; expects success, or (needle non-empty) an error mentioning needle.
void ExpectQuery(duckdb::Connection &con, const std::string &sql, const std::string &needle, const std::string &what) {
	auto result = con.Query(sql);
	Check(result->HasError() ? result->GetError() : "", result->HasError(), needle, what);
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

//! The screen as quack calls it, for connection `connection_id` and the statement `sql`.
std::string ScreenCall(const std::string &connection_id, const std::string &sql) {
	return std::string("SELECT ") + qod::kScreenFunction + "(" + qod::QuoteLiteral(connection_id) + ", " +
	       qod::QuoteLiteral(sql) + ")";
}

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(kNode, kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz);
	// Stand in for the two settings quack registers, so SET and RESET reach post_bind.
	auto &config = duckdb::DBConfig::GetConfig(*db.instance);
	config.AddExtensionOption("quack_authorization_function", "test stand-in", duckdb::LogicalType::VARCHAR,
	                          duckdb::Value("x"), nullptr, duckdb::SetScope::GLOBAL);
	config.AddExtensionOption("quack_authentication_function", "test stand-in", duckdb::LogicalType::VARCHAR,
	                          duckdb::Value("x"), nullptr, duckdb::SetScope::GLOBAL);

	// ---- the backstop: a quack connection runs nothing without a principal
	duckdb::Connection internal(db);
	ExpectQuery(internal, "SELECT 1", "", "an internal connection runs without a principal");
	duckdb::Connection alice(db);
	TagAsQuackSession(alice);
	ExpectQuery(alice, "SELECT 1", "no verified principal", "a quack connection without a principal");
	ExpectQuery(alice, "CALL system.main.qod_session('garbage')", "session handle refused", "a malformed handle");
	ExpectQuery(alice, Prelude(Fresh("u-alice", "quack-other-node")), "another node", "a handle for another node");

	auto first = Fresh("u-alice");
	ExpectQuery(alice, Prelude(first), "", "the prelude installs alice");
	ExpectQuery(alice, "SELECT 42", "", "alice's statement runs");

	// ---- quack's callbacks cannot be changed from a session, nor through EXPLAIN ANALYZE
	ExpectQuery(alice, "SET GLOBAL quack_authorization_function = 'x'", "cannot be changed",
	            "a session cannot replace the parse screen");
	ExpectQuery(alice, "RESET quack_authorization_function", "cannot be changed", "nor reset it");
	ExpectQuery(alice, "EXPLAIN ANALYZE SET GLOBAL quack_authorization_function = 'x'", "cannot be changed",
	            "nor through EXPLAIN ANALYZE");
	ExpectQuery(alice, "SET GLOBAL quack_authentication_function = 'x'", "cannot be changed",
	            "nor the authentication callback");
	ExpectQuery(alice, "EXPLAIN ANALYZE RESET GLOBAL quack_authentication_function", "cannot be changed",
	            "nor reset it through EXPLAIN ANALYZE");

	// ---- refresh rules
	ExpectQuery(alice, Prelude(Fresh("u-alice", kNode, 2)), "", "a refresh with a newer policy version");
	ExpectQuery(alice, Prelude(Fresh("u-alice", kNode, 1)), "older policy version", "a policy version downgrade");
	ExpectQuery(alice, Prelude(Fresh("u-mallory")), "another principal", "another principal on alice's connection");

	duckdb::Connection bob(db);
	TagAsQuackSession(bob);
	ExpectQuery(bob, Prelude(first), "already used", "alice's handle replayed on another connection");

	// ---- a statement prepared while the principal is current runs only while it still is: quack
	// prepares, then executes
	auto prepared = alice.Prepare("SELECT 42");
	Check(prepared->HasError() ? prepared->GetError() : "", prepared->HasError(), "",
	      "a statement prepared while the principal is current");

	// ---- expiry: the installed principal lasts as long as its handle, and stays for the connection
	now_ms += 61000;
	ExpectQuery(alice, "SELECT 1", "no verified principal", "an expired principal");
	if (!prepared->HasError()) {
		auto executed = prepared->Execute();
		Check(executed->HasError() ? executed->GetError() : "", executed->HasError(), "no verified principal",
		      "a prepared statement executed after its principal expired");
	}
	ExpectQuery(alice, Prelude(Fresh("u-mallory")), "another principal",
	            "an expired connection still keeps its principal");
	ExpectQuery(alice, Prelude(Fresh("u-alice", kNode, 2)), "", "a fresh handle refreshes it");
	ExpectQuery(alice, "SELECT 1", "", "and the connection runs again");

	// ---- a macro named qod_session shadows nothing: the prelude is qualified
	duckdb::Connection dave(db);
	TagAsQuackSession(dave);
	ExpectQuery(dave, Prelude(Fresh("u-dave")), "", "dave installs");
	ExpectQuery(dave, "CREATE TEMP MACRO qod_session(h) AS TABLE SELECT true AS installed", "",
	            "a session creates a temp macro named qod_session");
	ExpectQuery(dave, "CREATE MACRO qod_session(h) AS TABLE SELECT true AS installed", "",
	            "and a persistent one in the default catalog");
	now_ms += 61000;
	ExpectQuery(dave, Prelude(Fresh("u-dave")), "", "dave's own prelude still reaches qod_session");
	ExpectQuery(dave, "SELECT 1", "", "so dave's principal is current again");
	duckdb::Connection erin(db);
	TagAsQuackSession(erin);
	ExpectQuery(erin, Prelude(Fresh("u-erin")), "", "another connection's prelude too");
	ExpectQuery(erin, "SELECT 1", "", "and installs erin");

	// ---- the parse screen, as quack calls it on a connection of its own
	ExpectQuery(internal, ScreenCall("c-1", "SELECT 1"), "no verified principal",
	            "the screen refuses a connection it has no principal for");
	ExpectQuery(internal, ScreenCall("c-1", Prelude(Fresh("u-carol"))), "", "the screen admits the prelude");
	ExpectQuery(internal, ScreenCall("c-1", "SELECT 1"), "", "and the connection's next statement");
	ExpectQuery(internal, ScreenCall("c-2", "SELECT 1"), "no verified principal",
	            "a principal recorded for one connection only");
	ExpectQuery(internal, ScreenCall("c-1", Prelude(Fresh("u-dave"))), "another principal",
	            "the screen refuses another principal on the connection");
	ExpectQuery(erin, ScreenCall("c-3", "SELECT 1"), "authorization callback", "a session cannot call the screen");

	if (failures == 0) {
		std::cout << "authz_engine_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
