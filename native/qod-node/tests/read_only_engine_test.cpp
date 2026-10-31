// Engine tests for decision 24 on a real DuckDB instance: the handle's read-only catalogs refuse every
// principal's writes, DDL, ATTACH and DETACH into them, superuser and system included, before any
// verdict is reused (a prepared statement's, the verdict cache's) or asked for. A connection stands in
// for a quack client connection by carrying quack's session tag. Built with -DQOD_NODE_TESTS=ON, run by
// CTest.

#include "authz.hpp"
#include "crypto.hpp"
#include "decide_client.hpp"
#include "duckdb.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "json.hpp"
#include "node_hooks.hpp"
#include "sql_script.hpp"
#include "test_handles.hpp"
#include "test_http_server.hpp"
#include "test_verdicts.hpp"

#include <chrono>
#include <filesystem>
#include <iostream>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace {

using qod_test::kKey;
using qod_test::kNode;
using qod_test::MintSpec;
using qod_test::MintWith;
using qod_test::Prelude;

int failures = 0;

void Expect(bool condition, const std::string &what) {
	if (!condition) {
		std::cerr << "FAIL: " << what << std::endl;
		failures++;
	}
}

//! Runs sql on con; expects success, or (needle non-empty) an error mentioning needle.
void ExpectQuery(duckdb::Connection &con, const std::string &sql, const std::string &needle, const std::string &what) {
	auto result = con.Query(sql);
	if (needle.empty()) {
		if (result->HasError()) {
			std::cerr << "FAIL: " << what << ": " << result->GetError() << std::endl;
			failures++;
		}
	} else if (!result->HasError()) {
		std::cerr << "FAIL: accepted: " << what << std::endl;
		failures++;
	} else if (result->GetError().find(needle) == std::string::npos) {
		std::cerr << "FAIL: " << what << ": \"" << result->GetError() << "\" does not mention \"" << needle << "\""
		          << std::endl;
		failures++;
	}
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

//! How many plans the stand-in decision endpoint was asked about.
int decide_calls = 0;

//! The stand-in for the manager's decision endpoint: allows every plan, for a minute.
qod::DecideResult AllowAll(const qod::DecideCall &call) {
	decide_calls++;
	qod::DecideResult result;
	result.status = qod::DecideResult::Status::Allow;
	result.has_verdict = true;
	result.verdict.expires_at = now_ms + 60000;
	return result;
}

//! A handle for `principal_id`, valid for a minute, carrying `read_only`, from `replica`.
std::string HandleFor(const std::string &principal_id, std::vector<std::string> read_only, bool superuser = false,
                      bool system = false, const std::string &replica = "http://127.0.0.1:20900") {
	MintSpec spec;
	spec.principal_id = principal_id;
	spec.exp = now_ms + 60000;
	spec.superuser = superuser;
	spec.system = system;
	spec.replica = replica;
	spec.read_only = std::move(read_only);
	return MintWith(spec);
}

const std::string kRoReadOnly = "QoD: catalog ro is read-only";

} // namespace

int main() {
	auto dir = std::filesystem::temp_directory_path() /
	           std::filesystem::u8path("qod-read-only-" +
	                                   std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
	std::filesystem::create_directories(dir);
	auto file = [&](const std::string &name) {
		return qod::QuoteLiteral((dir / std::filesystem::u8path(name)).u8string());
	};

	{
		duckdb::DuckDB db(nullptr);
		auto authz = std::make_shared<qod::NodeAuthz>(kNode, kKey, [] { return now_ms; });
		qod::RegisterNodeAuthz(*db.instance, authz, AllowAll);

		// The node's own connection attaches, as the init SQL of a `sql` federated source does: two local
		// DuckDB files READ_WRITE (no engine layer protects either) and a mixed-case catalog.
		duckdb::Connection internal(db);
		ExpectQuery(internal, "ATTACH " + file("ro.duckdb") + " AS ro", "", "attach ro");
		ExpectQuery(internal, "CREATE TABLE ro.main.t AS SELECT 1 AS v", "", "ro's table");
		ExpectQuery(internal, "ATTACH " + file("rw.duckdb") + " AS rw", "", "attach rw");
		ExpectQuery(internal, "CREATE TABLE rw.main.t AS SELECT 1 AS v", "", "rw's table");
		ExpectQuery(internal, "ATTACH ':memory:' AS \"MixedRo\"", "", "attach MixedRo");
		ExpectQuery(internal, "CREATE TABLE \"MixedRo\".main.t (v INTEGER)", "", "MixedRo's table");
		ExpectQuery(internal, "CREATE TABLE memory.main.m AS SELECT 1 AS v", "", "a table to copy");

		// ---- a superuser: every write, DDL, ATTACH and DETACH into a read-only catalog is refused
		duckdb::Connection root(db);
		TagAsQuackSession(root);
		ExpectQuery(root, Prelude(HandleFor("u-root", {"mixedro", "ro"}, true)), "",
		            "a superuser installs with two read-only catalogs");
		for (auto sql : {"INSERT INTO ro.main.t VALUES (2)", "INSERT INTO ro.main.t SELECT v FROM rw.main.t",
		                 "UPDATE ro.main.t SET v = 3", "DELETE FROM ro.main.t", "TRUNCATE ro.main.t",
		                 "CREATE TABLE ro.main.x (v INTEGER)", "CREATE TABLE ro.main.x AS SELECT 1 AS v",
		                 "CREATE VIEW ro.main.w AS SELECT 1 AS v", "ALTER TABLE ro.main.t ADD COLUMN w INTEGER",
		                 "DROP TABLE ro.main.t", "CREATE SCHEMA ro.s", "DETACH ro",
		                 "ATTACH OR REPLACE ':memory:' AS ro", "COPY FROM DATABASE memory TO ro (SCHEMA)"}) {
			ExpectQuery(root, sql, kRoReadOnly, std::string("a superuser: ") + sql);
		}
		// Without AS, the catalog is named after the file: ro again.
		ExpectQuery(root, "ATTACH IF NOT EXISTS " + file("ro.duckdb"), kRoReadOnly,
		            "an ATTACH whose derived name is ro");
		ExpectQuery(root, "INSERT INTO \"MixedRo\".main.t VALUES (1)", "QoD: catalog mixedro is read-only",
		            "a catalog named in another case");
		ExpectQuery(root, "INSERT INTO MIXEDRO.main.t VALUES (1)", "QoD: catalog mixedro is read-only",
		            "and spelled in yet another");
		ExpectQuery(root, "USE ro", "", "a USE of the read-only catalog");
		ExpectQuery(root, "INSERT INTO t VALUES (2)", kRoReadOnly, "an unqualified write into it");
		ExpectQuery(root, "USE memory", "", "back to memory");
		// The nearest things that must still run: reads of it, writes elsewhere that read it, other
		// catalogs' ATTACH and DETACH.
		Expect(FirstValue(root, "SELECT count(*) FROM ro.main.t") == "1", "a superuser reads it");
		ExpectQuery(root, "INSERT INTO rw.main.t SELECT v FROM ro.main.t", "", "a write into rw reading ro");
		ExpectQuery(root, "CREATE TABLE memory.main.c AS SELECT v FROM ro.main.t", "", "a CTAS elsewhere reading ro");
		ExpectQuery(root, "ATTACH ':memory:' AS scratch", "", "another catalog's ATTACH");
		ExpectQuery(root, "DETACH scratch", "", "and its DETACH");

		// ---- the manager's own statements: the node applies the set whatever the principal (the manager
		// mints its system handles with none)
		duckdb::Connection manager(db);
		TagAsQuackSession(manager);
		ExpectQuery(manager, Prelude(HandleFor("system", {"ro"}, false, true)), "", "the system installs");
		ExpectQuery(manager, "INSERT INTO ro.main.t VALUES (2)", kRoReadOnly, "the system principal's write");
		Expect(FirstValue(manager, "SELECT count(*) FROM ro.main.t") == "1", "the system principal reads it");

		// ---- an ordinary principal: refused before the decision endpoint is asked
		duckdb::Connection alice(db);
		TagAsQuackSession(alice);
		ExpectQuery(alice, Prelude(HandleFor("u-alice", {"ro"})), "", "alice installs");
		auto before = decide_calls;
		ExpectQuery(alice, "INSERT INTO ro.main.t VALUES (2)", kRoReadOnly, "an ordinary principal's write");
		Expect(decide_calls == before, "refused without asking for a verdict");
		Expect(FirstValue(alice, "SELECT max(v) FROM ro.main.t") == "1", "her read");
		Expect(decide_calls > before, "her read is decided");
		ExpectQuery(alice, "INSERT INTO rw.main.t VALUES (4)", "", "her write into a writable catalog");

		Expect(FirstValue(internal, "SELECT count(*) FROM ro.main.t") == "1", "nothing landed in ro");
		Expect(FirstValue(internal, "SELECT count(*) FROM duckdb_databases() WHERE database_name = 'ro'") == "1",
		       "ro is still attached");

		// ---- a prepared write, prepared while ro was writable, is checked again once it is read-only: a
		// superuser's local allow included, through SQL and through the C++ API
		ExpectQuery(root, Prelude(HandleFor("u-root", {}, true)), "", "a refresh with nothing read-only");
		ExpectQuery(root, "PREPARE p AS INSERT INTO ro.main.t VALUES (7)", "", "a prepared write");
		ExpectQuery(root, "EXECUTE p", "", "runs while ro is writable");
		auto prepared = root.Prepare("INSERT INTO ro.main.t VALUES (9)");
		Expect(!prepared->HasError(), "a write prepared through the C++ API");
		ExpectQuery(root, Prelude(HandleFor("u-root", {"ro"}, true)), "", "a refresh that makes ro read-only");
		ExpectQuery(root, "EXECUTE p", kRoReadOnly, "the prepared write, executed again");
		ExpectQuery(root, "PREPARE q AS INSERT INTO ro.main.t VALUES (8)", kRoReadOnly, "a new prepared write");
		if (!prepared->HasError()) {
			auto executed = prepared->Execute();
			Expect(executed->HasError() && executed->GetError().find(kRoReadOnly) != std::string::npos,
			       "the C++ API's prepared write, executed again");
		}
		ExpectQuery(alice, Prelude(HandleFor("u-alice", {})), "", "alice, nothing read-only");
		ExpectQuery(alice, "PREPARE pa AS INSERT INTO ro.main.t VALUES (6)", "", "her prepared write");
		ExpectQuery(alice, Prelude(HandleFor("u-alice", {"ro"})), "", "alice, ro read-only");
		before = decide_calls;
		ExpectQuery(alice, "EXECUTE pa", kRoReadOnly, "her prepared write, executed again");
		Expect(decide_calls == before, "refused without asking for a verdict either");
		Expect(FirstValue(internal, "SELECT count(*) FROM ro.main.t") == "2", "only the run while writable landed");
	}

	// ---- the verdict cache: a verdict the node holds for a plan does not admit it into a catalog the next
	// handle makes read-only (the cache key is principal, policy version, ctx and fingerprint, none of
	// which changes)
	{
		duckdb::DuckDB db(nullptr);
		auto authz = std::make_shared<qod::NodeAuthz>(kNode, kKey, [] { return now_ms; });
		// No decider of its own: this node's DecideClient, with its verdict cache, asks the replica.
		qod::RegisterNodeAuthz(*db.instance, authz);
		qod_test::TestHttpServer replica([&](const qod_test::HttpRequest &request) {
			auto body = qod::ParseJson(request.body);
			auto description = qod::FromBase64Url(body.Get("description", qod::JsonValue::Kind::STRING).string);
			qod_test::VerdictFields fields;
			fields.kind = body.Get("kind", qod::JsonValue::Kind::STRING).string;
			fields.principal = "u-cache";
			fields.fingerprint = qod::Hex(qod::Sha256(description));
			fields.expires_at = now_ms + 60000;
			qod_test::HttpAnswer answer;
			answer.body = qod_test::SignVerdict(qod_test::VerdictJson(fields));
			return answer;
		});
		duckdb::Connection internal(db);
		ExpectQuery(internal, "ATTACH " + file("cached.duckdb") + " AS ro", "", "attach ro");
		ExpectQuery(internal, "CREATE TABLE ro.main.t AS SELECT 1 AS v", "", "ro's table");
		duckdb::Connection bob(db);
		TagAsQuackSession(bob);
		auto url = replica.Url();
		ExpectQuery(bob, Prelude(HandleFor("u-cache", {}, false, false, url)), "", "bob, nothing read-only");
		ExpectQuery(bob, "INSERT INTO ro.main.t VALUES (5)", "", "a write the replica allows");
		auto asked = replica.Requests();
		Expect(asked > 0, "the replica was asked");
		ExpectQuery(bob, Prelude(HandleFor("u-cache", {"ro"}, false, false, url)), "", "bob, ro read-only");
		ExpectQuery(bob, "INSERT INTO ro.main.t VALUES (5)", kRoReadOnly, "the cached verdict does not admit it");
		Expect(replica.Requests() == asked, "nor is the replica asked");
		ExpectQuery(bob, Prelude(HandleFor("u-cache", {}, false, false, url)), "", "bob, ro writable again");
		ExpectQuery(bob, "INSERT INTO ro.main.t VALUES (5)", "", "the cached verdict admits it again");
		Expect(replica.Requests() == asked, "from the cache");
	}

	std::error_code ignored;
	std::filesystem::remove_all(dir, ignored);
	if (failures == 0) {
		std::cout << "read_only_engine_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
