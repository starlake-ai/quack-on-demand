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
#include "crypto.hpp"
#include "duckdb/catalog/catalog_transaction.hpp"
#include "duckdb/main/secret/secret.hpp"
#include "duckdb/main/secret/secret_manager.hpp"
#include "json.hpp"
#include "test_http_server.hpp"
#include "test_verdicts.hpp"

#include <atomic>
#include <chrono>
#include <filesystem>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>

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
	            "QoD: COPY option filename_pattern is computed", "a computed file name pattern");
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
	Check("", replica.Requests() != asked, "", "and asks no verdict");
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

	if (failures == 0) {
		std::cout << "authz_engine_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
