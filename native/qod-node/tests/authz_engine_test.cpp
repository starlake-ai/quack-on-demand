// Engine tests for node_hooks: qod_session, qod_screen, the plan verdicts (post_bind, pre_optimize) and
// the connection state's backstops on a real DuckDB instance. A connection stands in for a quack client
// connection by carrying quack's session tag, which quack inserts on every connection it creates. Built
// with -DQOD_NODE_TESTS=ON, run by CTest.

#include "authz.hpp"
#include "authz_stats.hpp"
#include "decide_client.hpp"
#include "duckdb.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "duckdb/main/config.hpp"
#include "node_hooks.hpp"
#include "sql_script.hpp"
#include "test_handles.hpp"
#include "crypto.hpp"
#include "duckdb/catalog/catalog_transaction.hpp"
#include "duckdb/main/secret/secret.hpp"
#include "duckdb/main/secret/secret_manager.hpp"
#include "duckdb/optimizer/optimizer_extension.hpp"
#include "duckdb/planner/operator/logical_limit.hpp"
#include "duckdb/planner/planner_extension.hpp"
#include "json.hpp"
#include "test_http_server.hpp"
#include "test_verdicts.hpp"
#include "verdict.hpp"

#include <atomic>
#include <chrono>
#include <filesystem>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {

using qod_test::kKey;
using qod_test::kNode;
using qod_test::Mint;
using qod_test::MintSpec;
using qod_test::MintWith;
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

//! The first value `sql` answers on `con` as DuckDB renders it, or its error.
std::string FirstValue(duckdb::Connection &con, const std::string &sql) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		return "error: " + result->GetError();
	}
	auto chunk = result->FetchRaw();
	return chunk && chunk->size() > 0 ? chunk->GetValue(0, 0).ToString() : "no row";
}

struct FakeQuackSession : public duckdb::ClientContextState {};

void TagAsQuackSession(duckdb::Connection &con) {
	con.context->registered_state->Insert(qod::kQuackSessionKey, duckdb::make_shared_ptr<FakeQuackSession>());
}

int64_t now_ms = 1761200000000;

//! What the stand-in decision endpoint was asked: (principal id, policy version, description), oldest
//! first.
struct Asked {
	std::string principal;
	int64_t policy_version;
	std::string description;
};
std::vector<Asked> decide_calls;

//! The stand-in for the manager's decision endpoint: allows every description but the ones the test
//! marks, `"statement":"LIMIT"` (the plan a later post_bind rewrote) and the `forbidden` table, and
//! is unavailable for the `unavailable` table.
qod::DecideResult TestDecider(const qod::DecideCall &call) {
	decide_calls.push_back(Asked {call.principal.id, call.policy_version, call.description});
	qod::DecideResult result;
	if (call.description.find("\"name\":\"unavailable\"") != std::string::npos) {
		result.status = qod::DecideResult::Status::Unavailable;
		result.reason = "authorization unavailable";
		result.retryable = true;
	} else if (call.description.find("\"statement\":\"LIMIT\"") != std::string::npos ||
	           call.description.find("\"name\":\"forbidden\"") != std::string::npos) {
		result.status = qod::DecideResult::Status::Refuse;
		result.reason = "denied by test";
		result.has_verdict = true;
		result.verdict.expires_at = now_ms + 60000;
	} else {
		result.status = qod::DecideResult::Status::Allow;
		result.has_verdict = true;
		// The `short_lived` table's verdicts run out within planning when `expire_in_planning` is on; a
		// plan calling reverse() gets one for an hour, as an OPA tenant's may (its decision cache TTL).
		result.verdict.expires_at =
		    now_ms + (call.description.find("\"name\":\"short_lived\"") != std::string::npos  ? 10
		              : call.description.find("\"name\":\"reverse\"") != std::string::npos    ? 3600000
		                                                                                          : 60000);
	}
	return result;
}

//! An optimizer that runs after qod-node's pre_optimize: once `expire_in_planning` is on, it moves the
//! clock 20 ms on (one shot), as planning that outlasts a verdict would (T10-N2).
bool expire_in_planning = false;

void ExpireInPlanning(duckdb::OptimizerExtensionInput &input, duckdb::unique_ptr<duckdb::LogicalOperator> &plan) {
	if (expire_in_planning) {
		expire_in_planning = false;
		now_ms += 20;
	}
}

//! A post_bind registered after qod-node's: wraps a PROJECTION in a LIMIT while `rewrite` is on, as an
//! extension that changes the plan after qod-node described it would.
bool rewrite = false;

void Rewrite(duckdb::PlannerExtensionInput &input, duckdb::BoundStatement &statement) {
	if (!rewrite || !statement.plan || statement.plan->type != duckdb::LogicalOperatorType::LOGICAL_PROJECTION) {
		return;
	}
	auto limit =
	    duckdb::make_uniq<duckdb::LogicalLimit>(duckdb::BoundLimitNode::ConstantValue(100), duckdb::BoundLimitNode());
	limit->children.push_back(std::move(statement.plan));
	statement.plan = std::move(limit);
}

//! A handle for `principal_id` with the given flags, valid for a minute from now.
std::string Flagged(const std::string &principal_id, bool superuser, bool system) {
	qod_test::MintSpec spec;
	spec.principal_id = principal_id;
	spec.exp = now_ms + 60000;
	spec.superuser = superuser;
	spec.system = system;
	return qod_test::MintWith(spec);
}

void ExpectAsked(size_t before, bool expected, const std::string &what) {
	if ((decide_calls.size() > before) != expected) {
		std::cerr << "FAIL: " << what << (expected ? ": the decision endpoint was not asked" : ": it was asked")
		          << std::endl;
		failures++;
	}
}

//! True when a call since `before` was for a description holding `needle` (and policy version
//! `policy_version`, unless negative).
bool AskedFor(size_t before, const std::string &needle, int64_t policy_version = -1) {
	for (auto i = before; i < decide_calls.size(); i++) {
		if (decide_calls[i].description.find(needle) != std::string::npos &&
		    (policy_version < 0 || decide_calls[i].policy_version == policy_version)) {
			return true;
		}
	}
	return false;
}

//! A handle valid for a minute from now.
std::string Fresh(const std::string &principal_id, const std::string &node = kNode, int64_t policy_version = 1) {
	return Mint(principal_id, now_ms + 60000, node, policy_version);
}

//! The screen as quack calls it, for connection `connection_id` and the statement `sql`.
std::string ScreenCall(const std::string &connection_id, const std::string &sql) {
	return std::string("SELECT ") + qod::kScreenFunction + "(" + qod::QuoteLiteral(connection_id) + ", " +
	       qod::QuoteLiteral(sql) + ")";
}

//! qod_authz_stats()'s counter `column` as `con` reads it; -1 when the read fails.
int64_t Counter(duckdb::Connection &con, const std::string &column) {
	auto value = FirstValue(con, "SELECT " + column + " FROM system.main.qod_authz_stats()");
	try {
		return std::stoll(value);
	} catch (std::exception &) {
		return -1;
	}
}

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(kNode, kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz, TestDecider);
	duckdb::PlannerExtension rewriter;
	rewriter.post_bind_function = Rewrite;
	duckdb::PlannerExtension::Register(duckdb::DBConfig::GetConfig(*db.instance), rewriter);
	duckdb::OptimizerExtension expirer;
	expirer.optimize_function = ExpireInPlanning;
	duckdb::OptimizerExtension::Register(duckdb::DBConfig::GetConfig(*db.instance), expirer);
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
	// A refused prelude leaves the connection with no current principal, alice's included, until a
	// prelude succeeds whole.
	ExpectQuery(alice, "SELECT 42", "no verified principal", "no statement runs after a refused prelude");
	ExpectQuery(alice, Prelude(Fresh("u-alice", kNode, 2)), "", "alice's next prelude makes it current again");

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

	// ---- replacement scans (decision 16): off for a user session, kept for a superuser's
	auto csv = (std::filesystem::temp_directory_path() /
	            std::filesystem::u8path("qod-engine-" +
	                                    std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) +
	                                    ".csv"))
	               .u8string();
	ExpectQuery(internal, "COPY (SELECT 42 AS v) TO " + qod::QuoteLiteral(csv) + " (HEADER)", "", "the test file");
	ExpectQuery(internal, "SELECT v FROM " + qod::QuoteLiteral(csv), "", "an internal connection keeps them");
	duckdb::Connection ivy(db);
	TagAsQuackSession(ivy);
	ExpectQuery(ivy, Prelude(Fresh("u-ivy")), "", "ivy installs");
	ExpectQuery(ivy, "SELECT v FROM " + qod::QuoteLiteral(csv), "does not exist",
	            "a file in table position is no table for a user");
	ExpectQuery(ivy, "SELECT v FROM \"" + csv + "\"", "does not exist", "nor as a quoted identifier");
	ExpectQuery(ivy, "SELECT v FROM query_table(" + qod::QuoteLiteral(csv) + ")", "does not exist",
	            "nor through query_table");
	ExpectQuery(ivy, Prelude(Fresh("u-ivy", kNode, 2)), "", "a refresh");
	ExpectQuery(ivy, "SELECT v FROM " + qod::QuoteLiteral(csv), "does not exist", "still off after a refresh");
	duckdb::Connection root(db);
	TagAsQuackSession(root);
	MintSpec superuser;
	superuser.principal_id = "u-root";
	superuser.exp = now_ms + 60000;
	superuser.superuser = true;
	ExpectQuery(root, Prelude(MintWith(superuser)), "", "a superuser installs");
	ExpectQuery(root, "SELECT v FROM " + qod::QuoteLiteral(csv), "", "a superuser keeps replacement scans");

	// ---- the screen's content rules and location decisions, as quack calls them
	std::atomic<bool> refuse_locations {false};
	std::mutex seen_lock;
	std::string seen_description;
	std::string seen_handle;
	qod_test::TestHttpServer replica([&](const qod_test::HttpRequest &request) {
		auto body = qod::ParseJson(request.body);
		auto description = qod::FromBase64Url(body.Get("description", qod::JsonValue::Kind::STRING).string);
		{
			std::lock_guard<std::mutex> guard(seen_lock);
			seen_description = description;
			seen_handle = body.Get("handle", qod::JsonValue::Kind::STRING).string;
		}
		qod_test::VerdictFields fields;
		fields.kind = body.Get("kind", qod::JsonValue::Kind::STRING).string;
		fields.principal = "u-judy";
		fields.fingerprint = qod::Hex(qod::Sha256(description));
		// The manager stamps a screen verdict expiresAt = now (Addendum 4 item 5).
		fields.expires_at = now_ms;
		if (refuse_locations) {
			fields.decision = "refuse";
			fields.reason = "s3://other/x.csv is outside the tenant's authorized locations";
		}
		qod_test::HttpAnswer answer;
		answer.body = qod_test::SignVerdict(qod_test::VerdictJson(fields));
		return answer;
	});
	MintSpec judy;
	judy.principal_id = "u-judy";
	judy.exp = now_ms + 60000;
	judy.replica = replica.Url();
	auto judy_handle = MintWith(judy);
	ExpectQuery(internal, ScreenCall("c-judy", Prelude(judy_handle)), "", "judy's prelude");
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT 1"), "", "a statement naming no location");
	Check("", replica.Requests() != 0, "", "asks no verdict");
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT * FROM read_csv('s3://acme-etl/in/x.csv')"), "",
	            "a location the replica allows");
	{
		std::lock_guard<std::mutex> guard(seen_lock);
		Check("", seen_description != "{\"locations\":[{\"access\":\"read\",\"given\":\"s3://acme-etl/in/x.csv\","
		                              "\"resolved\":\"s3://acme-etl/in/x.csv\",\"via\":\"read_csv\"}],\"v\":1}",
		      "", "the screen description sent");
		Check("", seen_handle != judy_handle, "", "with the connection's handle");
	}
	Check("", replica.Last().headers["x-qod-signature"].empty(), "", "signed");
	auto asked = replica.Requests();
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT * FROM read_csv('s3://acme-etl/in/x.csv')"), "",
	            "the same location again");
	Check("", replica.Requests() != asked + 1, "", "is asked again: screen verdicts are never cached");
	refuse_locations = true;
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT * FROM read_csv('s3://other/x.csv')"),
	            "QoD: s3://other/x.csv is outside the tenant's authorized locations", "a location the replica refuses");
	refuse_locations = false;
	asked = replica.Requests();
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT * FROM read_csv('x.csv')"), "QoD: x.csv ",
	            "a relative path is refused on the node");
	ExpectQuery(internal, ScreenCall("c-judy", "DESCRIBE t"), "reserved to superusers", "a content rule");
	// Decision 23: a user's secret, in any storage, and the settings an S3 request is signed with.
	ExpectQuery(internal, ScreenCall("c-judy", "CREATE OR REPLACE SECRET qod_uc_0123abcd IN connection (TYPE s3)"),
	            "QoD: secrets are reserved to superusers", "a user's connection secret");
	ExpectQuery(internal, ScreenCall("c-judy", "EXPLAIN DROP SECRET s FROM transaction"),
	            "QoD: secrets are reserved to superusers", "a user's DROP SECRET behind EXPLAIN");
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT * FROM query('CREATE SECRET s (TYPE s3)')"),
	            "QoD: secrets are reserved to superusers", "a user's CREATE SECRET inside query()");
	ExpectQuery(internal, ScreenCall("c-judy", "EXPLAIN SET s3_endpoint = 'evil.example.com'"),
	            "QoD: SET and RESET of s3_endpoint are reserved to superusers", "a user's SET of an s3 setting");
	ExpectQuery(internal, ScreenCall("c-judy", "SET SESSION enable_server_cert_verification = false"),
	            "QoD: SET and RESET of enable_server_cert_verification are reserved to superusers",
	            "a user's session SET of certificate verification");
	ExpectQuery(internal,
	            ScreenCall("c-judy", "COPY t TO 's3://acme-etl/out/' (FORMAT parquet, PARTITION_BY (a), "
	                                 "FILENAME_PATTERN concat('..', '/', '..', '/pwn_{i}'))"),
	            "QoD: COPY option filename_pattern needs one literal string", "a computed file name pattern");
	ExpectQuery(internal,
	            ScreenCall("c-judy", "COPY t TO 's3://acme-etl/out/' (FORMAT parquet, PARTITION_BY (a), "
	                                 "FILENAME_PATTERN [X'2F2E2E2F2E2E2F78'])"),
	            "QoD: COPY option filename_pattern needs one literal string", "a file name pattern of hex bytes");
	// httpfs reads credentials from an s3 URL's query string: refused before any description is sent, and
	// the refusal does not quote it.
	ExpectQuery(internal,
	            ScreenCall("c-judy", "SELECT * FROM read_csv('s3://acme-etl/in/x.csv?s3_secret_access_key="
	                                 "qod-not-a-real-password')"),
	            "QoD: s3://acme-etl/in/x.csv carries a query string or a fragment",
	            "an s3 URL with a query string is refused on the node");
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT * FROM read_csv('s3://acme-etl/in/x.csv#f')"),
	            "QoD: s3://acme-etl/in/x.csv carries a query string or a fragment",
	            "an s3 URL with a fragment is refused on the node");
	Check("", replica.Requests() != asked, "", "neither asks the replica");
	// Decision 23: a location one of the node's own S3 secrets would sign, over judy's (she has none
	// recorded), is refused on the node. A stand-in s3 type: httpfs registers the real one.
	duckdb::SecretType s3_type;
	s3_type.name = "s3";
	s3_type.deserializer = duckdb::KeyValueSecret::Deserialize<duckdb::KeyValueSecret>;
	s3_type.default_provider = "config";
	duckdb::SecretManager::Get(*db.instance).RegisterSecretType(s3_type);
	internal.context->RunFunctionInTransaction([&] {
		auto secret = duckdb::make_uniq<duckdb::KeyValueSecret>(duckdb::vector<std::string> {"s3://acme-etl/iceberg/"},
		                                                        duckdb::Identifier("s3"), duckdb::Identifier("config"),
		                                                        duckdb::Identifier("qod_ice_test"));
		auto transaction = duckdb::CatalogTransaction::GetSystemCatalogTransaction(*internal.context);
		duckdb::SecretManager::Get(*internal.context)
		    .RegisterSecret(transaction, std::move(secret), duckdb::OnCreateConflict::ERROR_ON_CONFLICT,
		                    duckdb::SecretPersistType::TEMPORARY, "memory");
	});
	asked = replica.Requests();
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT * FROM read_parquet('s3://acme-etl/iceberg/t/x.parquet')"),
	            "QoD: s3://acme-etl/iceberg/t/x.parquet would be signed with this node's own S3 keys",
	            "a location under a node secret's scope");
	Check("", replica.Requests() != asked, "", "is refused on the node");
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT * FROM read_csv('s3://acme-etl/in/x.csv')"), "",
	            "a location outside it is still the replica's to judge");
	// The scopes qod_session records for judy's handle: one as close as the node's wins the tie.
	authz->RecordCredentialScopes(authz->Verify(judy_handle).nonce, {"s3://acme-etl/iceberg/"}, now_ms + 60000);
	asked = replica.Requests();
	ExpectQuery(internal, ScreenCall("c-judy", "SELECT * FROM read_parquet('s3://acme-etl/iceberg/t/x.parquet')"), "",
	            "judy's own credential scoped as closely signs it");
	Check("", replica.Requests() != asked + 1, "", "and the replica judges it");
	MintSpec kim = judy;
	kim.principal_id = "u-kim";
	kim.replica = "http://127.0.0.1:" + std::to_string(qod_test::UnusedPort());
	ExpectQuery(internal, ScreenCall("c-kim", Prelude(MintWith(kim))), "",
	            "kim's prelude names a replica that is down");
	ExpectQuery(internal, ScreenCall("c-kim", "SELECT * FROM read_csv('s3://acme-etl/in/x.csv')"),
	            "QoD: authorization unavailable, retry", "no verdict, no statement, and the client may retry");
	{
		// A replica that answers 503: retryable too, with what it said.
		qod_test::TestHttpServer busy([](const qod_test::HttpRequest &) {
			qod_test::HttpAnswer answer;
			answer.status = 503;
			answer.body = "authorization context expired; retry";
			return answer;
		});
		MintSpec lou = judy;
		lou.principal_id = "u-lou";
		lou.replica = busy.Url();
		ExpectQuery(internal, ScreenCall("c-lou", Prelude(MintWith(lou))), "", "lou's prelude");
		ExpectQuery(internal, ScreenCall("c-lou", "SELECT * FROM read_csv('s3://acme-etl/in/x.csv')"),
		            "QoD: authorization unavailable, retry: ", "a 503 is retryable");
	}
	MintSpec root_screen = superuser;
	root_screen.replica = replica.Url();
	ExpectQuery(internal, ScreenCall("c-root", Prelude(MintWith(root_screen))), "", "a superuser's prelude");
	asked = replica.Requests();
	ExpectQuery(internal, ScreenCall("c-root", "SELECT * FROM read_csv('x.csv')"), "",
	            "a superuser's statement skips the content rules");
	ExpectQuery(internal, ScreenCall("c-root", "CREATE SECRET s IN connection (TYPE s3)"), "",
	            "a superuser's secret is not the screen's to refuse");
	// Task 9 review C1: superuser-class functions and bind-time slots are a superuser's own.
	for (auto own : {"SELECT current_setting('memory_limit')", "SELECT * FROM range(setval('seq1', 5))",
	                 "ALTER TABLE x ALTER v TYPE VARCHAR USING setval('seq1', 99)::VARCHAR",
	                 "EXPLAIN EXECUTE p(setval('seq1', 88))", "SELECT 1 LIMIT nextval('seq1')",
	                 "PRAGMA table_info(CASE WHEN nextval('seq1') > 0 THEN 't' END)"}) {
		ExpectQuery(internal, ScreenCall("c-root", own), "", std::string("a superuser's: ") + own);
	}
	Check("", replica.Requests() != asked, "", "and asks no verdict");

	// ---- Task 9 review C1: the node-kill forms are refused before bind, and the node stays valid. On an
	// unscreened instance (below) each raises an INTERNAL error and invalidates the database.
	std::vector<std::string> kills {"ALTER TABLE x ALTER v TYPE VARCHAR USING setval('seq1', 99)::VARCHAR",
	                                "EXPLAIN EXECUTE p(setval('seq1', 88))",
	                                "ALTER TABLE x ALTER v TYPE BIGINT USING nextval('seq1')",
	                                "EXPLAIN EXECUTE p(nextval('seq1'))",
	                                "PRAGMA table_info(CASE WHEN nextval('seq1') > 0 THEN 't' END)"};
	asked = replica.Requests();
	for (auto &kill : kills) {
		ExpectQuery(internal, ScreenCall("c-judy", kill), "QoD: ", "a node-kill form for a user: " + kill);
	}
	Check("", replica.Requests() != asked, "", "refused on the node, before any verdict");
	ExpectQuery(internal, "SELECT 42", "", "the node's database is still valid");
	duckdb::Connection after(db);
	ExpectQuery(after, "SELECT 42", "", "and so is a new connection's");
	{
		duckdb::DuckDB unscreened(nullptr);
		duckdb::Connection con(unscreened);
		ExpectQuery(con, "CREATE SEQUENCE seq1", "", "the unscreened instance's sequence");
		ExpectQuery(con, "CREATE TEMP TABLE x (v INT)", "", "its temp table");
		ExpectQuery(con, "INSERT INTO x VALUES (1)", "", "a row");
		ExpectQuery(con, kills[0], "read-only but has made changes", "what the screen prevents: an INTERNAL error");
		ExpectQuery(con, "SELECT 42", "database has been invalidated", "then every statement fails");
	}
	std::error_code ignored;
	std::filesystem::remove(std::filesystem::u8path(csv), ignored);

	// ---- the session's snapshot (the plan verdicts' decide calls): only while its last prelude
	// succeeded whole and its handle is current
	duckdb::Connection max(db);
	TagAsQuackSession(max);
	qod::Installed snapshot;
	Check("", qod::SessionSnapshot(*max.context, now_ms, snapshot), "", "no snapshot before a prelude");
	MintSpec max_spec;
	max_spec.principal_id = "u-max";
	max_spec.exp = now_ms + 60000;
	max_spec.replica = "http://127.0.0.1:20901";
	auto max_handle = MintWith(max_spec);
	ExpectQuery(max, Prelude(max_handle), "", "max installs");
	Check("",
	      !qod::SessionSnapshot(*max.context, now_ms, snapshot) || snapshot.handle != max_handle ||
	          snapshot.replica != "http://127.0.0.1:20901" || snapshot.principal.id != "u-max" ||
	          snapshot.ctx != std::string(64, 'c') || snapshot.exp != max_spec.exp,
	      "", "the snapshot carries the installing handle, its replica, principal, ctx and expiry");
	ExpectQuery(max, Prelude(Fresh("u-mallory")), "another principal", "a refused prelude on max's connection");
	Check("", qod::SessionSnapshot(*max.context, now_ms, snapshot), "", "no snapshot after a refused prelude");
	auto refreshed = Fresh("u-max", kNode, 2);
	ExpectQuery(max, Prelude(refreshed), "", "max's next prelude");
	Check("", !qod::SessionSnapshot(*max.context, now_ms, snapshot) || snapshot.handle != refreshed,
	      "", "the snapshot follows the refreshing handle");
	Check("", qod::SessionSnapshot(*max.context, now_ms + 61000, snapshot), "", "no snapshot once it expired");
	Check("", qod::SessionSnapshot(*internal.context, now_ms, snapshot), "", "no snapshot on an internal connection");

	// ---- verdicts: an ordinary principal's plans run only under the endpoint's allow
	duckdb::Connection frank(db);
	TagAsQuackSession(frank);
	ExpectQuery(frank, Prelude(Fresh("u-frank")), "", "frank installs");
	// Created on an internal connection: the stand-in refuses every plan that names it.
	ExpectQuery(internal, "CREATE TABLE forbidden AS SELECT 1 AS x", "", "a table the stand-in refuses");
	auto before = decide_calls.size();
	ExpectQuery(frank, "SELECT 7", "", "frank's statement runs under an allow");
	ExpectAsked(before, true, "frank's statement");
	ExpectQuery(frank, "SELECT * FROM forbidden", "QoD: denied by test", "a refused verdict");
	ExpectQuery(frank, "CREATE TABLE unavailable AS SELECT 1 AS x", "QoD: authorization unavailable, retry",
	            "an unavailable decision refuses");
	ExpectQuery(internal, "SELECT count(*) FROM unavailable", "does not exist", "and nothing ran");
	// Transaction control is decided like any statement, and runs under its allow.
	ExpectQuery(frank, "BEGIN", "", "frank opens a transaction");
	ExpectQuery(frank, "SELECT 70", "", "a statement inside it");
	ExpectQuery(frank, "ROLLBACK", "", "and rolls it back");
	// DuckDB lowers grouping sets and distinct aggregates before the pre_optimize hooks run
	// (Optimizer::LowerMandatoryAggregateRewrites): the description is the same, so one decision.
	for (auto sql : {"SELECT a, count(DISTINCT b) FROM (VALUES (1, 2), (1, 3)) t(a, b) GROUP BY GROUPING SETS ((a), ())",
	                 "SELECT a, sum(b) FROM (VALUES (1, 2)) t(a, b) GROUP BY ROLLUP (a)",
	                 "SELECT count(DISTINCT b), sum(DISTINCT b) FROM (VALUES (1, 2)) t(a, b)"}) {
		before = decide_calls.size();
		ExpectQuery(frank, sql, "", sql);
		if (decide_calls.size() != before + 1) {
			std::cerr << "FAIL: " << sql << ": " << (decide_calls.size() - before) << " decisions, not one" << std::endl;
			failures++;
		}
	}

	// ---- CONTRACT Addendum 4 item 32: a superuser-class call DuckDB evaluates while binding a view's body
	// never brings its value back, whether the statement binds or the binder fails on the value
	auto zone = FirstValue(internal, "SELECT current_setting('TimeZone')");
	for (auto sql : {"CREATE VIEW leak_cast AS SELECT CAST(current_setting('TimeZone') AS INTEGER) AS x",
	                 "CREATE VIEW leak_arg AS SELECT abs(CAST(current_setting('TimeZone') AS INTEGER)) AS x"}) {
		ExpectQuery(internal, sql, "", sql);
	}
	// A body DuckDB evaluates while binding (a table function's argument) that binds where it was created
	// (a schema named 5) and fails on frank's connection, whose schema is not a number: the binder's error
	// quotes the value.
	duckdb::Connection creator(db);
	for (auto sql : {"CREATE SCHEMA \"5\"", "USE memory.\"5\"",
	                 "CREATE VIEW memory.main.leak_bind AS SELECT * FROM range(CAST(current_setting('schema') AS INTEGER))"}) {
		ExpectQuery(creator, sql, "", sql);
	}
	auto binding = internal.Query("SELECT * FROM leak_bind");
	Check(binding->HasError() ? binding->GetError() : "", binding->HasError(), "Could not convert string 'main'",
	      "without the node, binding the view fails quoting the value (the case the refusal must win)");
	for (auto view : {"leak_cast", "leak_arg", "leak_bind"}) {
		auto read = frank.Query(std::string("SELECT * FROM ") + view);
		auto error = read->HasError() ? read->GetError() : std::string();
		if (error.find("QoD: function current_setting is superuser-only") == std::string::npos ||
		    error.find(zone) != std::string::npos || error.find("Could not convert") != std::string::npos) {
			std::cerr << "FAIL: reading " << view << " did not answer the node's refusal alone: " << error << std::endl;
			failures++;
		}
	}

	// ---- pre_optimize: a plan changed after qod-node described it is decided again
	rewrite = true;
	ExpectQuery(frank, "SELECT 8", "QoD: denied by test", "the plan another post_bind rewrote");
	rewrite = false;

	// ---- T10-N2: a verdict that runs out between post_bind and prepare finalization: DuckDB binds the
	// statement once more and it is decided again, instead of failing as one without a verdict
	ExpectQuery(internal, "CREATE TABLE short_lived AS SELECT 3 AS x", "", "a table with short verdicts");
	expire_in_planning = true;
	before = decide_calls.size();
	auto short_lived = FirstValue(frank, "SELECT x FROM short_lived");
	if (short_lived != "3") {
		std::cerr << "FAIL: the statement whose verdict ran out did not run: " << short_lived << std::endl;
		failures++;
	}
	if (expire_in_planning) {
		std::cerr << "FAIL: the clock was never moved during planning" << std::endl;
		failures++;
	}
	if (decide_calls.size() != before + 2) {
		std::cerr << "FAIL: a verdict that ran out in planning: " << (decide_calls.size() - before)
		          << " decisions, not two (the bind, and the bind again)" << std::endl;
		failures++;
	}

	// ---- a prepared statement runs under its verdict, and is decided again after a policy change
	auto stored = frank.Prepare("SELECT 9");
	Check(stored->HasError() ? stored->GetError() : "", stored->HasError(), "", "frank prepares");
	before = decide_calls.size();
	if (!stored->HasError()) {
		auto executed = stored->Execute();
		Check(executed->HasError() ? executed->GetError() : "", executed->HasError(), "",
		      "the prepared statement under its verdict");
	}
	if (AskedFor(before, "\"statement\":\"PROJECTION\"")) {
		std::cerr << "FAIL: a prepared statement under a live verdict was bound and decided again" << std::endl;
		failures++;
	}
	ExpectQuery(frank, Prelude(Fresh("u-frank", kNode, 3)), "", "a refresh to policy version 3");
	before = decide_calls.size();
	if (!stored->HasError()) {
		auto executed = stored->Execute();
		Check(executed->HasError() ? executed->GetError() : "", executed->HasError(), "",
		      "the prepared statement after the refresh");
	}
	if (!AskedFor(before, "\"statement\":\"PROJECTION\"", 3)) {
		std::cerr << "FAIL: the prepared statement was not decided again under policy version 3" << std::endl;
		failures++;
	}
	// SQL PREPARE and EXECUTE: the EXECUTE runs the plan PREPARE was decided for.
	ExpectQuery(frank, "PREPARE p9 AS SELECT * FROM forbidden", "QoD: denied by test",
	            "a PREPARE of a plan the endpoint refuses");
	ExpectQuery(frank, "PREPARE p10 AS SELECT 10", "", "a PREPARE the endpoint allows");
	ExpectQuery(frank, "EXECUTE p10", "", "and its EXECUTE");
	now_ms += 61000;
	ExpectQuery(frank, Prelude(Fresh("u-frank", kNode, 3)), "", "frank's next handle");
	before = decide_calls.size();
	ExpectQuery(frank, "EXECUTE p10", "", "an EXECUTE whose verdict expired");
	if (!AskedFor(before, "\"statement\":\"PROJECTION\"")) {
		std::cerr << "FAIL: a prepared statement whose verdict expired was not decided again" << std::endl;
		failures++;
	}
	// A-m1: a prepared statement keeps its verdict at most kMaxCachedVerdictMs, as the verdict cache does,
	// whatever the verdict's expiresAt says: a Rego change bumps no policy version. (A statement over no
	// table: a prepared read of one is bound again at every EXECUTE here, whatever its verdict.)
	auto long_lived = frank.Prepare("SELECT reverse('long')");
	Check(long_lived->HasError() ? long_lived->GetError() : "", long_lived->HasError(), "",
	      "frank prepares under an hour-long verdict");
	now_ms += 60000;
	ExpectQuery(frank, Prelude(Fresh("u-frank", kNode, 3)), "", "frank's handle a minute on");
	before = decide_calls.size();
	if (!long_lived->HasError()) {
		auto executed = long_lived->Execute();
		Check(executed->HasError() ? executed->GetError() : "", executed->HasError(), "",
		      "the prepared statement within the cap");
	}
	if (AskedFor(before, "\"name\":\"reverse\"")) {
		std::cerr << "FAIL: a prepared statement within kMaxCachedVerdictMs was decided again" << std::endl;
		failures++;
	}
	now_ms += qod::kMaxCachedVerdictMs;
	ExpectQuery(frank, Prelude(Fresh("u-frank", kNode, 3)), "", "frank's handle six minutes on");
	before = decide_calls.size();
	if (!long_lived->HasError()) {
		auto executed = long_lived->Execute();
		Check(executed->HasError() ? executed->GetError() : "", executed->HasError(), "",
		      "the prepared statement past the cap");
	}
	if (!AskedFor(before, "\"name\":\"reverse\"")) {
		std::cerr << "FAIL: a prepared statement ran past kMaxCachedVerdictMs without a new decision" << std::endl;
		failures++;
	}

	// ---- one principal per connection (T2-X1): another principal's handle never takes it over, here
	// with the parse screen's advisory record out of the way (the session is called directly)
	auto prepared_before = frank.Prepare("SELECT 13");
	Check(prepared_before->HasError() ? prepared_before->GetError() : "", prepared_before->HasError(), "",
	      "frank prepares before the refused handle");
	before = decide_calls.size();
	ExpectQuery(frank, Prelude(Fresh("u-grace", kNode, 3)), "another principal", "grace's handle on frank's connection");
	ExpectQuery(frank, "SELECT 11", "no verified principal", "nothing runs after it");
	if (!prepared_before->HasError()) {
		// Task 7 N2: its verdict is still live, but no statement runs until a prelude succeeds whole.
		auto executed = prepared_before->Execute();
		Check(executed->HasError() ? executed->GetError() : "", executed->HasError(), "no verified principal",
		      "nor a statement prepared before it");
	}
	for (auto i = before; i < decide_calls.size(); i++) {
		if (decide_calls[i].principal != "u-frank") {
			std::cerr << "FAIL: a decision was asked for " << decide_calls[i].principal << " on frank's connection"
			          << std::endl;
			failures++;
		}
	}
	ExpectQuery(frank, Prelude(Fresh("u-frank", kNode, 3)), "", "frank's own handle");
	ExpectQuery(frank, "SELECT 12", "", "makes it frank's again");
	ExpectQuery(frank, "EXPLAIN ANALYZE SELECT * FROM forbidden", "QoD: denied by test",
	            "EXPLAIN ANALYZE runs what it wraps: decided like it");
	// A prepared statement DuckDB rebinds because the catalog changed is described and decided again:
	// the name it read now names a view over a table the endpoint refuses.
	ExpectQuery(internal, "CREATE TABLE swap AS SELECT 1 AS x", "", "a table frank may read");
	auto swapped = frank.Prepare("SELECT x FROM swap");
	Check(swapped->HasError() ? swapped->GetError() : "", swapped->HasError(), "", "frank prepares over it");
	ExpectQuery(internal, "DROP TABLE swap", "", "the table goes");
	ExpectQuery(internal, "CREATE VIEW swap AS SELECT x FROM forbidden", "", "a view over forbidden takes its name");
	if (!swapped->HasError()) {
		auto executed = swapped->Execute();
		Check(executed->HasError() ? executed->GetError() : "", executed->HasError(), "QoD: denied by test",
		      "the rebound statement is decided again, and refused");
	}

	// ---- superusers and the manager are admitted on the node, and still meet the local refusals
	duckdb::Connection boss(db);
	TagAsQuackSession(boss);
	ExpectQuery(boss, Prelude(Flagged("u-boss", true, false)), "", "a superuser installs");
	before = decide_calls.size();
	ExpectQuery(boss, "SELECT * FROM forbidden", "", "a superuser reads what the endpoint would refuse");
	ExpectAsked(before, false, "a superuser statement");
	ExpectQuery(boss, "SET enable_optimizer = false", "cannot be changed", "a superuser cannot disable the optimizer");
	duckdb::Connection manager(db);
	TagAsQuackSession(manager);
	ExpectQuery(manager, Prelude(Flagged("system", false, true)), "", "the manager installs its system principal");
	before = decide_calls.size();
	ExpectQuery(manager, "SELECT 1", "", "a system statement");
	ExpectAsked(before, false, "a system statement");
	ExpectQuery(manager, "RESET disabled_optimizers", "cannot be changed", "nor can the manager's statements");

	// ---- secure views are judged against the session default path the handle carries (CONTRACT
	// Addendum 4 items 11 and 22), the target of the router's USE prelude; nothing a statement does
	// changes what passes
	ExpectQuery(internal, "ATTACH ':memory:' AS lake", "", "a second catalog");
	ExpectQuery(internal, "CREATE SCHEMA lake.other", "", "with another schema");
	ExpectQuery(internal, "CREATE TABLE lake.main.t AS SELECT 5 AS id", "", "a base table");
	ExpectQuery(internal, "CREATE SECURE VIEW lake.main.sv AS SELECT id FROM lake.main.t", "", "a secure view over it");
	MintSpec on_lake;
	on_lake.principal_id = "u-nina";
	on_lake.path = "lake.main";
	auto lake_handle = [&] {
		on_lake.exp = now_ms + 60000;
		return Prelude(MintWith(on_lake));
	};
	duckdb::Connection nina(db);
	TagAsQuackSession(nina);
	ExpectQuery(nina, lake_handle(), "", "nina installs, her pool's path lake.main");
	ExpectQuery(nina, "USE lake.other; SELECT id FROM lake.main.sv", "search path differs",
	            "her own leading USE, first on her connection, is not the expected path");
	ExpectQuery(nina, lake_handle(), "", "her next handle");
	ExpectQuery(nina, "USE lake.main; SELECT id FROM sv", "", "a secure-view read after the manager's USE prelude");
	ExpectQuery(nina, lake_handle(), "", "her next handle");
	ExpectQuery(nina, "USE lake.main; USE lake.other; SELECT id FROM lake.main.sv", "search path differs",
	            "a USE batched after the manager's");
	ExpectQuery(nina, lake_handle(), "", "her next handle");
	ExpectQuery(nina, "BEGIN", "", "nina opens a transaction");
	ExpectQuery(nina, lake_handle(), "", "her next handle");
	ExpectQuery(nina, "COMMIT; USE lake.main; SELECT id FROM sv", "", "the prelude spliced after a leading COMMIT");
	ExpectQuery(nina, lake_handle(), "", "her next handle");
	ExpectQuery(nina, "USE lake.main; SELECT id FROM sv", "", "the manager's prelude puts the expected path back");
	ExpectQuery(nina, Prelude(Fresh("u-nina")), "", "a handle whose pool has no USE prelude (empty path)");
	ExpectQuery(nina, "USE lake.main; SELECT id FROM lake.main.sv", "search path differs",
	            "with an empty path only the session default passes");
	ExpectQuery(nina, Prelude(Fresh("u-nina")), "", "her next handle, still no path");
	ExpectQuery(nina, "USE memory.main; SELECT id FROM lake.main.sv", "", "the session default does");

	// The prelude itself is never described: its argument is a credential. (dave's macros named
	// qod_session are objects, not this table function entry.)
	for (auto &call : decide_calls) {
		if (call.description.find("\"name\":\"qod_session\",\"schema\":\"main\",\"type\":\"table\"") !=
		    std::string::npos) {
			std::cerr << "FAIL: a prelude reached the decision endpoint" << std::endl;
			failures++;
		}
	}

	// ---- Task 8 M7: COPY TO under a core format runs only through DuckDB's own copy function
	auto out_dir = std::filesystem::temp_directory_path() /
	               std::filesystem::u8path("qod-engine-copy-" +
	                                       std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
	std::filesystem::create_directories(out_dir);
	auto out = std::filesystem::canonical(out_dir).u8string();
	ExpectQuery(frank, Prelude(Fresh("u-frank", kNode, 3)), "", "frank's next handle");
	ExpectQuery(frank, "COPY (SELECT 1 AS x) TO " + qod::QuoteLiteral(out + "/x.parquet") + " (FORMAT parquet)", "",
	            "a COPY TO parquet");
	ExpectQuery(frank, Prelude(Fresh("u-frank", kNode, 3)), "", "frank's next handle");
	ExpectQuery(frank, "COPY (SELECT 1 AS x) TO " + qod::QuoteLiteral(out + "/x.csv") + " (FORMAT csv)", "",
	            "a COPY TO csv");
	std::filesystem::remove_all(out_dir, ignored);

	// ---- Task 19 part C: DuckDB binds each part of its three-part ADD COLUMN ... DEFAULT rewrite as a
	// statement of its own, so each is decided, the UPDATE with the sequence update its nextval makes
	ExpectQuery(internal, "CREATE TABLE memory.main.t19 AS SELECT range AS id FROM range(3)", "", "a table");
	ExpectQuery(internal, "CREATE SEQUENCE memory.main.s19", "", "a sequence");
	ExpectQuery(internal, "CREATE SEQUENCE memory.main.forbidden", "", "a sequence the decider refuses");
	duckdb::Connection lena(db);
	TagAsQuackSession(lena);
	ExpectQuery(lena, Prelude(Fresh("u-lena")), "", "lena installs");
	auto before_add = decide_calls.size();
	ExpectQuery(lena, "ALTER TABLE memory.main.t19 ADD COLUMN n BIGINT DEFAULT nextval('memory.main.s19')", "",
	            "an ADD COLUMN whose default draws from a sequence");
	auto sequence_update = std::string("{\"access\":\"update\",\"catalog\":\"memory\",\"catalogType\":\"duckdb\","
	                                   "\"columns\":[],\"kind\":\"sequence\",\"name\":\"s19\"");
	Check("", !AskedFor(before_add, "\"statement\":\"UPDATE\"") || !AskedFor(before_add, sequence_update), "",
	      "its UPDATE was decided with the sequence update");
	Check("", !AskedFor(before_add, "\"statement\":\"ALTER\""), "", "and its ALTERs were decided");
	Check("", FirstValue(internal, "SELECT count(DISTINCT n) FROM memory.main.t19 WHERE n IS NOT NULL") != "3", "",
	      "the existing rows got values from the sequence");
	ExpectQuery(lena, "ALTER TABLE memory.main.t19 ADD COLUMN m BIGINT DEFAULT nextval('memory.main.forbidden')",
	            "denied by test", "refused when its sequence update is refused");
	auto columns_m = "SELECT count(*) FROM duckdb_columns() WHERE table_name = 't19' AND column_name = 'm'";
	Check("", FirstValue(internal, columns_m) != "0", "", "and no part of it stays: the parts run in one transaction");

	// ---- Task 8 (decision 26): qod_authz_stats() is the system principal's read of the node's counters
	auto &stats = authz->Stats();
	const std::string stats_sql = "SELECT * FROM system.main.qod_authz_stats()";
	const std::string stats_refused = "QoD: function qod_authz_stats is superuser-only";
	ExpectQuery(internal, stats_sql, stats_refused, "an internal connection cannot read the counters");
	duckdb::Connection nobody(db);
	TagAsQuackSession(nobody);
	ExpectQuery(nobody, stats_sql, stats_refused, "nor a quack connection without a principal");
	duckdb::Connection quinn(db);
	TagAsQuackSession(quinn);
	ExpectQuery(quinn, Prelude(Fresh("u-quinn")), "", "quinn installs");
	ExpectQuery(quinn, stats_sql, stats_refused, "nor a tenant user");
	duckdb::Connection rhea(db);
	TagAsQuackSession(rhea);
	ExpectQuery(rhea, Prelude(Flagged("u-rhea", true, false)), "", "a superuser installs");
	ExpectQuery(rhea, stats_sql, stats_refused, "nor a superuser: the system principal only");
	duckdb::Connection probe(db);
	TagAsQuackSession(probe);
	ExpectQuery(probe, Prelude(Flagged("system", false, true)), "",
	            "the manager's probe installs the system principal");
	{
		auto result = probe.Query(stats_sql);
		auto &expected = qod::AuthzStatsColumns();
		auto shaped = !result->HasError() && result->RowCount() == 1 && result->GetNames().size() == expected.size() &&
		              expected.size() == 7;
		for (size_t i = 0; shaped && i < expected.size(); i++) {
			shaped = result->GetNames()[i].GetIdentifierName() == expected[i] &&
			         result->GetTypes()[i] == duckdb::LogicalType::BIGINT;
		}
		Check(result->HasError() ? result->GetError() : "", !shaped, "",
		      "the system principal reads one row of seven BIGINT counters, by name, in order");
	}
	Check("", Counter(probe, "local_refusals") != stats.local_refusals.load(), "",
	      "local_refusals is the node's counter");
	Check("", Counter(probe, "screen_calls") != stats.screen_calls.load(), "", "and so is screen_calls");

	// What counts as a local refusal: the node's own refusal of a statement on a connection whose
	// principal is current, never the refusal for want of a principal (the probe's canary).
	auto local = stats.local_refusals.load();
	ExpectQuery(quinn, "SET GLOBAL quack_authorization_function = 'x'", "cannot be changed",
	            "a refusal the describer makes");
	Check("", stats.local_refusals.load() != local + 1, "", "counts one local refusal");
	local = stats.local_refusals.load();
	ExpectQuery(nobody, "SELECT 1", "no verified principal", "a statement without a principal");
	Check("", stats.local_refusals.load() != local, "", "is not a local refusal");
	auto screens = stats.screen_calls.load();
	ExpectQuery(internal, ScreenCall("c-stats", Prelude(Fresh("u-sam"))), "", "the screen admits sam's prelude");
	ExpectQuery(internal, ScreenCall("c-stats", "DESCRIBE t"), "reserved to superusers",
	            "and refuses one of sam's statements by a content rule");
	Check("", stats.screen_calls.load() != screens + 2, "", "two statements screened");
	Check("", stats.local_refusals.load() != local + 1, "", "the content rule's refusal is local");
	local = stats.local_refusals.load();
	ExpectQuery(internal, ScreenCall("c-stats-none", "SELECT 1"), "no verified principal",
	            "the screen refuses a connection it holds no principal for");
	Check("", stats.screen_calls.load() != screens + 3, "", "a third statement screened");
	Check("", stats.local_refusals.load() != local, "", "and its refusal is not local");

	if (failures == 0) {
		std::cout << "authz_engine_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
