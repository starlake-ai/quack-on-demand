// Plan enforcement on a DuckLake catalog, for a tenant user: an insert, a flush of the inlined rows and
// reads run under verdicts, and DuckLake's own metadata queries, which bind on ClientContexts of their
// own (CONTRACT Addendum 4 items 7 and 8), are never decided as the user's statements. A secure view
// over a DuckLake table (in the node's memory catalog: DuckLake has none of its own) reads under the
// session default path the handle carries, the target of the manager's USE prelude (item 22). Exits 77
// (CTest skip) when ducklake cannot be installed or loaded here.

#include "authz.hpp"
#include "decide_client.hpp"
#include "duckdb.hpp"
#include "duckdb/main/client_context.hpp"
#include "duckdb/main/client_context_state.hpp"
#include "init_runner.hpp"
#include "node_hooks.hpp"
#include "sql_script.hpp"
#include "test_handles.hpp"

#include <filesystem>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#ifdef _WIN32
#include <process.h>
#define getpid _getpid
#else
#include <unistd.h>
#endif

namespace {

namespace fs = std::filesystem;

int failures = 0;

void Fail(const std::string &what) {
	std::cerr << "FAIL: " << what << std::endl;
	failures++;
}

struct FakeQuackSession : public duckdb::ClientContextState {};

int64_t now_ms = 1761200000000;

//! The prelude of a fresh handle for `principal_id` on a pool whose USE prelude targets `path`.
std::string HandleOn(const std::string &principal_id, const std::string &path) {
	qod_test::MintSpec spec;
	spec.principal_id = principal_id;
	spec.exp = now_ms + 60000;
	spec.path = path;
	return qod_test::Prelude(qod_test::MintWith(spec));
}

//! Every description the stand-in decision endpoint was asked about, oldest first.
std::vector<std::string> decided;

//! `sql` on `con`: its first column's values joined by ',', or "ERROR: <message>".
std::string Run(duckdb::Connection &con, const std::string &sql) {
	auto result = con.Query(sql);
	if (result->HasError()) {
		return "ERROR: " + result->GetError();
	}
	std::string out;
	while (auto chunk = result->FetchRaw()) {
		for (duckdb::idx_t row = 0; row < chunk->size(); row++) {
			out += (out.empty() ? "" : ",") + chunk->GetValue(0, row).ToString();
		}
	}
	return out;
}

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	auto authz = std::make_shared<qod::NodeAuthz>(qod_test::kNode, qod_test::kKey, [] { return now_ms; });
	qod::RegisterNodeAuthz(*db.instance, authz, [](const qod::DecideCall &call) {
		decided.push_back(call.description);
		qod::DecideResult allowed;
		allowed.status = qod::DecideResult::Status::Allow;
		allowed.has_verdict = true;
		allowed.verdict.expires_at = now_ms + 60000;
		return allowed;
	});
	duckdb::Connection internal(db);
	if (internal.Query("INSTALL ducklake")->HasError() || internal.Query("LOAD ducklake")->HasError()) {
		std::cout << "enforcement_extension_test: ducklake unavailable, skipped" << std::endl;
		return 77;
	}
	auto scratch = fs::temp_directory_path() / ("qod-enforcement-lake-" + std::to_string(::getpid()));
	fs::create_directories(scratch);
	auto dir = fs::canonical(scratch).generic_string();
	try {
		// The node's own setup, as its init SQL would run it: no principal, no checks.
		qod::RunStrict(internal,
		               "ATTACH 'ducklake:" + dir + "/lake.ducklake' AS lake (DATA_PATH '" + dir + "/lake_data/')",
		               "attach");
		qod::RunStrict(internal, "CREATE TABLE lake.main.t (id INTEGER, v VARCHAR)", "create");
		qod::RunStrict(internal, "CREATE SECURE VIEW memory.main.sv AS SELECT id FROM lake.main.t", "secure view");
	} catch (std::exception &error) {
		Fail(std::string("DuckLake setup: ") + error.what());
	}

	duckdb::Connection tina(db);
	tina.context->registered_state->Insert(qod::kQuackSessionKey, duckdb::make_shared_ptr<FakeQuackSession>());
	// Each statement as the router sends it: a fresh handle, then the USE prelude and the statement.
	auto as_tina = [&](const std::string &sql) {
		auto installed = Run(tina, HandleOn("u-tina", "lake.main"));
		if (installed.rfind("ERROR: ", 0) == 0) {
			return installed;
		}
		return Run(tina, "USE lake.main; " + sql);
	};
	auto expect = [&](const std::string &what, const std::string &got, const std::string &wanted) {
		if (got != wanted) {
			Fail(what + ": expected \"" + wanted + "\", got \"" + got + "\"");
		}
	};
	expect("tina inserts", as_tina("INSERT INTO t VALUES (1, 'a'), (2, 'b')"), "2");
	expect("tina reads the inlined rows", as_tina("SELECT count(*) FROM t"), "2");
	auto flushed = as_tina("CALL ducklake_flush_inlined_data('lake')");
	if (flushed.rfind("ERROR: ", 0) == 0) {
		Fail("tina flushes the inlined rows: " + flushed);
	}
	expect("tina reads the flushed rows", as_tina("SELECT string_agg(v, ',' ORDER BY id) FROM t"), "a,b");
	expect("tina reads through the secure view", as_tina("SELECT count(*) FROM memory.main.sv"), "2");
	// A path other than the handle's, a session's own leading USE included, never passes.
	duckdb::Connection uma(db);
	uma.context->registered_state->Insert(qod::kQuackSessionKey, duckdb::make_shared_ptr<FakeQuackSession>());
	expect("uma installs", Run(uma, HandleOn("u-uma", "lake.main")), "true");
	auto elsewhere = Run(uma, "USE memory.main; SELECT count(*) FROM memory.main.sv");
	if (elsewhere.find("search path differs") == std::string::npos) {
		Fail("a secure-view read under a path other than the handle's: " + elsewhere);
	}
	expect("uma's next handle", Run(uma, HandleOn("u-uma", "lake.main")), "true");
	expect("uma reads through the secure view after the prelude",
	       Run(uma, "USE lake.main; SELECT count(*) FROM memory.main.sv"), "2");

	// DuckLake's change feed with the table named by literals: decided as a read of that table (the data
	// diff summary's shape), never of DuckLake's metadata. (The parse screen, which refuses a computed name,
	// runs as quack's authorization callback, which this harness does not drive: screen_rules_test.)
	expect("tina updates", as_tina("UPDATE t SET v = 'c' WHERE id = 2"), "1");
	auto before = decided.size();
	auto head = Run(internal, "SELECT max(snapshot_id) FROM ducklake_snapshots('lake')");
	expect("tina counts t's changes",
	       as_tina("SELECT string_agg(change_type || ':' || n, ',' ORDER BY change_type) FROM (SELECT change_type, "
	               "count(*) AS n FROM ducklake_table_changes('lake', 'main', 't', 2, " + head +
	               ") GROUP BY change_type)"),
	       "insert:2,update_postimage:1,update_preimage:1");
	expect("tina reads t's insertions",
	       as_tina("SELECT count(*) FROM ducklake_table_insertions('lake', 'main', 't', 2, " + head + ")"), "3");
	auto feed_read = false;
	for (auto i = before; i < decided.size(); i++) {
		feed_read = feed_read || decided[i].find("\"catalog\":\"lake\",\"catalogType\":\"ducklake\","
		                                         "\"columns\":[\"id\",\"v\"],\"kind\":\"table\","
		                                         "\"name\":\"t\"") != std::string::npos;
	}
	if (!feed_read) {
		Fail("tina's change feed was not decided as a read of lake.main.t");
	}

	size_t on_metadata = 0;
	bool flush_decided = false;
	for (auto &description : decided) {
		on_metadata += description.find("__ducklake_metadata") != std::string::npos ? 1 : 0;
		flush_decided = flush_decided || description.find("DUCKLAKE_FLUSH_DATA") != std::string::npos;
	}
	if (on_metadata != 0) {
		Fail(std::to_string(on_metadata) + " of DuckLake's metadata queries were decided as tina's statements");
	}
	if (!flush_decided) {
		Fail("tina's flush was not decided");
	}
	internal.Query("DETACH lake");
	fs::remove_all(scratch);

	if (failures == 0) {
		std::cout << "enforcement_extension_test: " << decided.size() << " decisions, all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
