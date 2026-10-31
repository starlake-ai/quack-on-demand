// Describer cases that need a DuckDB extension: a CREATE SECRET (httpfs) never carries its options,
// and a DuckLake flush (ducklake) is a known extension operator over a COPY of the inlined rows.
// Exits 77 (CTest skip) when an extension cannot be installed or loaded here.

#include "describe_harness.hpp"
#include "init_runner.hpp"
#include "json_writer.hpp"
#include "locations.hpp"

#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <string>
#include <thread>

#ifdef _WIN32
#include <process.h>
#define getpid _getpid
#else
#include <unistd.h>
#endif

namespace {

namespace fs = std::filesystem;
using qod_test::TheCapture;

int failures = 0;

void Fail(const std::string &what) {
	std::cerr << "FAIL: " << what << std::endl;
	failures++;
}

bool Load(duckdb::Connection &con, const std::string &extension) {
	return !con.Query("INSTALL " + extension)->HasError() && !con.Query("LOAD " + extension)->HasError();
}

void Expect(const std::string &what, const std::string &description, const std::string &needle) {
	if (description.find(needle) == std::string::npos) {
		Fail(what + ": the description lacks " + needle + "\n  got " + description + " (refused: \"" +
		     TheCapture().refused + "\")");
	}
}

//! The first description captured since `from` that holds `needle`, or every one joined when none does
//! (so a failure shows them all).
std::string DescriptionWith(size_t from, const std::string &needle) {
	auto &all = TheCapture().all;
	std::string joined;
	for (auto i = from; i < all.size(); i++) {
		if (all[i].find(needle) != std::string::npos) {
			return all[i];
		}
		joined += (joined.empty() ? "" : "\n  ") + all[i];
	}
	return joined;
}

//! When set, a post_bind registered before the capture interrupts the statement's client context, so the
//! describer runs on an interrupted context (what a client's cancel does while a statement binds).
bool interrupt_binds = false;

void InterruptBind(duckdb::PlannerExtensionInput &input, duckdb::BoundStatement &) {
	if (interrupt_binds) {
		input.context.Interrupt();
	}
}

} // namespace

int main() {
	duckdb::DuckDB db(nullptr);
	{
		duckdb::PlannerExtension interrupter;
		interrupter.post_bind_function = InterruptBind;
		duckdb::PlannerExtension::Register(duckdb::DBConfig::GetConfig(*db.instance), interrupter);
	}
	qod_test::RegisterCapture(db);
	duckdb::Connection con(db);
	if (!Load(con, "httpfs") || !Load(con, "ducklake")) {
		std::cout << "describer_extension_test: httpfs or ducklake unavailable, skipped" << std::endl;
		return 77;
	}
	auto &capture = TheCapture();
	capture.principal = qod_test::Root();

	// ---- a secret's options never reach a description
	capture.on = true;
	con.Query("CREATE SECRET s1 (TYPE s3, KEY_ID 'AKIAQODFAKEKEYID', SECRET 'qod-not-a-real-secret')");
	capture.on = false;
	const std::string secret =
	    "{\"control\":[{\"op\":\"create_secret\",\"target\":null,\"type\":\"s3\"}],\"functions\":[],\"objects\":[],"
	    "\"settings\":[],\"statement\":\"CREATE_SECRET\",\"v\":1}";
	if (capture.canonical != secret) {
		Fail("CREATE SECRET:\n  expected " + secret + "\n  got      " + capture.canonical);
	}
	if (capture.canonical.find("AKIAQODFAKEKEYID") != std::string::npos ||
	    capture.canonical.find("qod-not-a-real-secret") != std::string::npos) {
		Fail("CREATE SECRET: a secret option reached the description");
	}

	// ---- a DuckLake flush: the known operator, its COPY into the lake's data folder, the table read
	auto scratch = fs::temp_directory_path() / ("qod-describer-lake-" + std::to_string(::getpid()));
	fs::create_directories(scratch);
	auto dir = fs::canonical(scratch).generic_string();
	try {
		qod::RunStrict(con,
		               "ATTACH 'ducklake:" + dir + "/lake.ducklake' AS lake (DATA_PATH '" + dir + "/lake_data/')",
		               "attach");
		qod::RunStrict(con, "CREATE TABLE lake.t (id INTEGER, v VARCHAR)", "create");
		qod::RunStrict(con, "INSERT INTO lake.t VALUES (1, 'a'), (2, 'b')", "insert");
	} catch (std::exception &error) {
		Fail(std::string("DuckLake setup: ") + error.what());
	}
	// DuckLake runs its metadata queries while the flush executes, so they are described after it:
	// judge the description that names the operator.
	auto from = capture.all.size();
	capture.on = true;
	con.Query("CALL ducklake_flush_inlined_data('lake')");
	capture.on = false;
	const std::string flush_op =
	    "{\"op\":\"extension_operator\",\"target\":\"DUCKLAKE_FLUSH_DATA\",\"type\":\"ducklake\"}";
	auto flush = DescriptionWith(from, flush_op);
	// Which ClientContext DuckLake's own metadata queries bind on (CONTRACT Addendum 4 item 7, for Task
	// 10): reported, not asserted.
	size_t nested = 0;
	size_t nested_on_client = 0;
	for (auto i = from; i < capture.all.size(); i++) {
		if (capture.all[i].find("__ducklake_metadata_lake") != std::string::npos) {
			nested++;
			nested_on_client += capture.contexts[i] == con.context.get() ? 1 : 0;
		}
	}
	std::cout << "describer_extension_test: the flush bound " << (capture.all.size() - from)
	          << " plans; " << nested << " read __ducklake_metadata_lake, " << nested_on_client
	          << " of them on the client's own ClientContext" << std::endl;
	Expect("DuckLake flush", flush, flush_op);
	// The data folder as the describer renders a COPY target (ResolveLocalPath: '\\' on Windows, where
	// DuckLake also writes backslash paths), in its JSON spelling, without the closing quote.
	auto lake_data = qod::ResolveLocalPath(dir + "/lake_data/");
	if (!lake_data) {
		Fail("DuckLake flush: the lake's data folder " + dir + "/lake_data/ does not resolve");
	} else {
		auto lake_data_json = qod::CanonicalJson(qod::JsonString(*lake_data));
		lake_data_json.pop_back();
		Expect("DuckLake flush", flush, "{\"op\":\"copy_to\",\"target\":" + lake_data_json);
	}
	Expect("DuckLake flush", flush, "\"catalog\":\"lake\",\"catalogType\":\"ducklake\"");
	Expect("DuckLake flush", flush, "\"kind\":\"table\",\"name\":\"t\"");
	// ---- DuckLake's change feed, for a tenant user: the bind swaps in the named table's own scan, so the
	// client's plan is a read of exactly that table (the catalog and table it bound, at its end snapshot),
	// with no function and no __ducklake_metadata_ object: DuckLake's metadata reads bind on its own
	// ClientContexts. The manager decides it on that table's grant.
	try {
		qod::RunStrict(con, "CREATE TABLE lake.u (id INTEGER, secret VARCHAR)", "create u");
		qod::RunStrict(con, "INSERT INTO lake.u VALUES (7, 'x')", "insert u");
		qod::RunStrict(con, "UPDATE lake.t SET v = 'c' WHERE id = 2", "update t");
		qod::RunStrict(con,
		               "ATTACH 'ducklake:" + dir + "/other.ducklake' AS other_lake (DATA_PATH '" + dir +
		                   "/other_data/')",
		               "attach other");
		qod::RunStrict(con, "CREATE TABLE other_lake.t (id INTEGER, w VARCHAR)", "create other t");
	} catch (std::exception &error) {
		Fail(std::string("DuckLake change feed setup: ") + error.what());
	}
	auto snapshots = con.Query("SELECT max(snapshot_id) FROM ducklake_snapshots('lake')");
	auto last = snapshots->HasError() ? nullptr : snapshots->Fetch();
	auto head = last && last->size() > 0 ? last->GetValue(0, 0).ToString() : "0";
	// The descriptions a statement bound on the client's own context, and whether any reads the metadata.
	auto feed = [&](const std::string &sql, std::string &error) {
		capture.principal = qod_test::Alice();
		auto from = capture.all.size();
		capture.on = true;
		auto result = con.Query(sql);
		capture.on = false;
		capture.principal = qod_test::Root();
		error = result->HasError() ? result->GetError() : "";
		std::vector<std::string> own;
		for (auto i = from; i < capture.all.size(); i++) {
			if (capture.contexts[i] == con.context.get()) {
				own.push_back(capture.all[i]);
			}
		}
		return own;
	};
	auto expect_one = [&](const std::string &what, const std::string &sql, const std::string &golden) {
		std::string error;
		auto own = feed(sql, error);
		if (!error.empty() || own.size() != 1 || own[0] != golden) {
			std::string got = error;
			for (auto &d : own) {
				got += "\n  " + d;
			}
			Fail(what + ":\n  expected " + golden + "\n  got      " + got);
		}
	};
	const std::string t_read = "{\"access\":\"read\",\"catalog\":\"lake\",\"catalogType\":\"ducklake\","
	                           "\"columns\":[\"id\",\"v\"],\"kind\":\"table\",\"name\":\"t\","
	                           "\"schema\":\"main\",\"via\":null}";
	const std::string t_rowids = "{\"access\":\"read\",\"catalog\":\"lake\",\"catalogType\":\"ducklake\","
	                             "\"columns\":[],\"kind\":\"table\",\"name\":\"t\",\"schema\":\"main\","
	                             "\"via\":null}";
	expect_one("the change feed of lake.main.t",
	           "SELECT * FROM ducklake_table_changes('lake', 'main', 't', 1, " + head + ")",
	           "{\"control\":[],\"functions\":[],\"objects\":[" + t_read + "," + t_rowids +
	               "],\"settings\":[],\"statement\":\"PROJECTION\",\"v\":1}");
	// DataDiffSql.summarySql's shape
	expect_one("the data diff summary of lake.main.t",
	           "SELECT change_type, count(*) AS n FROM ducklake_table_changes('lake', 'main', 't', 2, " + head +
	               ") GROUP BY change_type",
	           "{\"control\":[],\"functions\":[{\"args\":[],\"catalog\":\"system\",\"name\":\"count_star\","
	           "\"schema\":\"main\",\"type\":\"aggregate\",\"via\":null}],\"objects\":[" +
	               t_read + "," + t_rowids + "],\"settings\":[],\"statement\":\"PROJECTION\",\"v\":1}");
	expect_one("the insertions of lake.main.t",
	           "SELECT * FROM ducklake_table_insertions('lake', 'main', 't', 1, " + head + ")",
	           "{\"control\":[],\"functions\":[],\"objects\":[" + t_read +
	               "],\"settings\":[],\"statement\":\"PROJECTION\",\"v\":1}");
	// The read is the table the literals name, never another: u's feed is a read of u (whose grant the
	// manager checks), and another lake's t is that lake's.
	expect_one("the change feed of lake.main.u",
	           "SELECT * FROM ducklake_table_deletions('lake', 'main', 'u', 1, " + head + ")",
	           "{\"control\":[],\"functions\":[],\"objects\":[{\"access\":\"read\",\"catalog\":\"lake\","
	           "\"catalogType\":\"ducklake\",\"columns\":[\"id\",\"secret\"],\"kind\":\"table\","
	           "\"name\":\"u\",\"schema\":\"main\",\"via\":null}],\"settings\":[],"
	           "\"statement\":\"PROJECTION\",\"v\":1}");
	expect_one("the change feed of other_lake.main.t",
	           "SELECT * FROM ducklake_table_insertions('other_lake', 'main', 't', 1, 1)",
	           "{\"control\":[],\"functions\":[],\"objects\":[{\"access\":\"read\",\"catalog\":"
	           "\"other_lake\",\"catalogType\":\"ducklake\",\"columns\":[\"id\",\"w\"],\"kind\":\"table\","
	           "\"name\":\"t\",\"schema\":\"main\",\"via\":null}],\"settings\":[],"
	           "\"statement\":\"PROJECTION\",\"v\":1}");
	// Nothing reaches the metadata catalog through the feed: it is no DuckLake catalog, and a snapshot
	// bound cannot be a subquery.
	for (auto *refused : {"SELECT * FROM ducklake_table_changes('__ducklake_metadata_lake', 'main', "
	                      "'ducklake_snapshot', 1, 2)",
	                      "SELECT * FROM ducklake_table_insertions('lake', 'main', 't', 1, "
	                      "(SELECT max(snapshot_id) FROM __ducklake_metadata_lake.main.ducklake_snapshot))"}) {
		std::string error;
		auto own = feed(refused, error);
		if (error.find("Binder Error") == std::string::npos || !own.empty()) {
			Fail(std::string("a change feed that would reach the metadata catalog binds: ") + refused + " -> " +
			     error + (own.empty() ? "" : " " + own[0]));
		}
	}
	con.Query("DETACH other_lake");

	// ---- a DuckLake table at any snapshot is described as the table its stable id names now (DuckLake
	// keeps an entry's oid across snapshots), under its current catalog, schema and name, its columns
	// under their current names (by DuckLake field id), and `renamed` when any of them had another name
	// at that snapshot (review M1: `secret` renamed to `secret_now`, a new `secret` created).
	auto lake_head = [&]() {
		auto rows = con.Query("SELECT max(snapshot_id) FROM ducklake_snapshots('lake')");
		auto chunk = rows->HasError() ? nullptr : rows->Fetch();
		return chunk && chunk->size() > 0 ? chunk->GetValue(0, 0).ToString() : std::string("0");
	};
	std::string old_secret;
	std::string u_before;
	std::string gone_at;
	try {
		u_before = lake_head();
		qod::RunStrict(con, "ALTER TABLE lake.u RENAME COLUMN secret TO hidden", "rename u's column");
		qod::RunStrict(con, "CREATE TABLE lake.secret (id INTEGER, email VARCHAR)", "create secret");
		qod::RunStrict(con, "INSERT INTO lake.secret VALUES (1, 'old@x.io')", "insert secret");
		old_secret = lake_head();
		qod::RunStrict(con, "ALTER TABLE lake.secret RENAME TO secret_now", "rename secret");
		qod::RunStrict(con, "ALTER TABLE lake.secret_now RENAME COLUMN email TO contact", "rename email");
		qod::RunStrict(con, "CREATE TABLE lake.secret (id INTEGER, email VARCHAR)", "create the new secret");
		qod::RunStrict(con, "INSERT INTO lake.secret VALUES (2, 'new@x.io')", "insert the new secret");
		qod::RunStrict(con, "CREATE TABLE lake.gone (id INTEGER)", "create gone");
		qod::RunStrict(con, "INSERT INTO lake.gone VALUES (1)", "insert gone");
		gone_at = lake_head();
		qod::RunStrict(con, "DROP TABLE lake.gone", "drop gone");
	} catch (std::exception &error) {
		Fail(std::string("DuckLake rename setup: ") + error.what());
	}
	auto read_of = [](const std::string &name, const std::string &columns, bool renamed) {
		return "{\"control\":[],\"functions\":[],\"objects\":[{\"access\":\"read\",\"catalog\":\"lake\","
		       "\"catalogType\":\"ducklake\",\"columns\":[" +
		       columns + "],\"kind\":\"table\",\"name\":\"" + name + "\"," + (renamed ? "\"renamed\":true," : "") +
		       "\"schema\":\"main\",\"via\":null}],\"settings\":[],\"statement\":\"PROJECTION\",\"v\":1}";
	};
	expect_one("an AT read of the renamed table's old name",
	           "SELECT id, email FROM lake.secret AT (VERSION => " + old_secret + ")",
	           read_of("secret_now", "\"contact\",\"id\"", true));
	expect_one("an AT read of the renamed table, its renamed column not read",
	           "SELECT id FROM lake.secret AT (VERSION => " + old_secret + ")",
	           read_of("secret_now", "\"id\"", true));
	expect_one("the change feed of the renamed table's old name",
	           "SELECT * FROM ducklake_table_insertions('lake', 'main', 'secret', 1, " + old_secret + ")",
	           read_of("secret_now", "\"contact\",\"id\"", true));
	expect_one("a column renamed since the snapshot", "SELECT secret FROM lake.u AT (VERSION => " + u_before + ")",
	           read_of("u", "\"hidden\"", true));
	expect_one("the new table under the old name, now", "SELECT id, email FROM lake.secret",
	           read_of("secret", "\"email\",\"id\"", false));
	expect_one("the renamed table, now", "SELECT id, contact FROM lake.secret_now",
	           read_of("secret_now", "\"contact\",\"id\"", false));
	expect_one("the new table at its own latest snapshot",
	           "SELECT id, email FROM lake.secret AT (VERSION => " + lake_head() + ")",
	           read_of("secret", "\"email\",\"id\"", false));
	// A table dropped since is judged as a read of its last live name (its name at the snapshot before the
	// drop), with its last live columns, marked `dropped`: readable on that name's grant while no live table
	// holds the name, refused once one does. A table renamed and then dropped is judged on its last name.
	std::string reused_at;
	std::string renamed_at;
	try {
		qod::RunStrict(con, "CREATE TABLE lake.reused (id INTEGER)", "create reused");
		qod::RunStrict(con, "INSERT INTO lake.reused VALUES (1)", "insert reused");
		reused_at = lake_head();
		qod::RunStrict(con, "DROP TABLE lake.reused", "drop reused");
		qod::RunStrict(con, "CREATE TABLE lake.reused (id INTEGER, other VARCHAR)", "reuse the name");
		qod::RunStrict(con, "CREATE TABLE lake.rn (id INTEGER, mail VARCHAR)", "create rn");
		qod::RunStrict(con, "INSERT INTO lake.rn VALUES (1, 'm')", "insert rn");
		renamed_at = lake_head();
		qod::RunStrict(con, "ALTER TABLE lake.rn RENAME TO rn_last", "rename rn");
		qod::RunStrict(con, "ALTER TABLE lake.rn_last RENAME COLUMN mail TO email", "rename rn's column");
		qod::RunStrict(con, "DROP TABLE lake.rn_last", "drop rn_last");
	} catch (std::exception &error) {
		Fail(std::string("DuckLake drop setup: ") + error.what());
	}
	auto dropped_read = [](const std::string &name, const std::string &columns, bool renamed) {
		return "{\"control\":[],\"functions\":[],\"objects\":[{\"access\":\"read\",\"catalog\":\"lake\","
		       "\"catalogType\":\"ducklake\",\"columns\":[" +
		       columns + "],\"dropped\":true,\"kind\":\"table\",\"name\":\"" + name + "\"," +
		       (renamed ? "\"renamed\":true," : "") +
		       "\"schema\":\"main\",\"via\":null}],\"settings\":[],\"statement\":\"PROJECTION\",\"v\":1}";
	};
	expect_one("a dropped table whose name is free", "SELECT id FROM lake.gone AT (VERSION => " + gone_at + ")",
	           dropped_read("gone", "\"id\"", false));
	expect_one("a dropped table's change feed, its name free",
	           "SELECT id FROM ducklake_table_insertions('lake', 'main', 'gone', 1, " + gone_at + ")",
	           dropped_read("gone", "\"id\"", false));
	expect_one("a table renamed, then dropped: its last name and columns",
	           "SELECT id, mail FROM lake.rn AT (VERSION => " + renamed_at + ")",
	           dropped_read("rn_last", "\"email\",\"id\"", true));
	// A replace (CREATE OR REPLACE, QoD's restore): the table that took the name began exactly where the old
	// one ended, so it is the same table, through a chain of replaces: described under the current name.
	std::string rep_first;
	std::string rep_second;
	try {
		qod::RunStrict(con, "CREATE TABLE lake.rep (id INTEGER, v VARCHAR)", "create rep");
		qod::RunStrict(con, "INSERT INTO lake.rep VALUES (1, 'a')", "insert rep");
		rep_first = lake_head();
		qod::RunStrict(con, "CREATE OR REPLACE TABLE lake.rep AS SELECT * FROM lake.rep", "replace rep");
		qod::RunStrict(con, "INSERT INTO lake.rep VALUES (2, 'b')", "insert rep again");
		rep_second = lake_head();
		qod::RunStrict(con, "CREATE OR REPLACE TABLE lake.rep AS SELECT * FROM lake.rep", "replace rep again");
	} catch (std::exception &error) {
		Fail(std::string("DuckLake replace setup: ") + error.what());
	}
	auto replaced_read = [](const std::string &columns) {
		return "{\"control\":[],\"functions\":[],\"objects\":[{\"access\":\"read\",\"catalog\":\"lake\","
		       "\"catalogType\":\"ducklake\",\"columns\":[" +
		       columns +
		       "],\"kind\":\"table\",\"name\":\"rep\",\"replaced\":true,\"schema\":\"main\",\"via\":null}],"
		       "\"settings\":[],\"statement\":\"PROJECTION\",\"v\":1}";
	};
	expect_one("a table replaced twice, read before the first replace",
	           "SELECT id, v FROM lake.rep AT (VERSION => " + rep_first + ")", replaced_read("\"id\",\"v\""));
	expect_one("a table replaced, read between the replaces",
	           "SELECT id FROM lake.rep AT (VERSION => " + rep_second + ")", replaced_read("\"id\""));
	expect_one("a replaced table's change feed across the replace",
	           "SELECT id FROM ducklake_table_insertions('lake', 'main', 'rep', 1, " + rep_first + ")",
	           replaced_read("\"id\""));
	// Review N1: with several dropped tables that once held the bound name, the read's table is the one
	// holding that name at the read's point, never one merely live under another name then. C is created as
	// x, renamed tt and dropped; T is created as a, renamed tt, then secret2, and dropped. A read of tt while
	// T held it is T, judged under its last name secret2: by TIMESTAMP and by the change feed.
	{
		auto snapshot_time = [&](const std::string &id) {
			auto rows = con.Query("SELECT snapshot_time::VARCHAR FROM ducklake_snapshots('lake') WHERE snapshot_id = " + id);
			auto chunk = rows->HasError() ? nullptr : rows->Fetch();
			return chunk && chunk->size() > 0 ? chunk->GetValue(0, 0).ToString() : std::string();
		};
		auto step = [&](const std::string &sql) {
			std::this_thread::sleep_for(std::chrono::milliseconds(5));
			qod::RunStrict(con, sql, sql);
		};
		std::string t_held;
		try {
			step("CREATE TABLE lake.x (id INTEGER, cx VARCHAR)");
			step("INSERT INTO lake.x VALUES (1, 'c')");
			step("CREATE TABLE lake.a (id INTEGER, tv VARCHAR)");
			step("INSERT INTO lake.a VALUES (2, 't')");
			step("ALTER TABLE lake.x RENAME TO tt");
			step("DROP TABLE lake.tt");
			step("ALTER TABLE lake.a RENAME TO tt");
			t_held = lake_head();
			step("ALTER TABLE lake.tt RENAME TO secret2");
			step("DROP TABLE lake.secret2");
			std::this_thread::sleep_for(std::chrono::milliseconds(5));
		} catch (std::exception &error) {
			Fail(std::string("review N1 setup: ") + error.what());
		}
		auto secret2 = "{\"control\":[],\"functions\":[],\"objects\":[{\"access\":\"read\",\"catalog\":\"lake\","
		               "\"catalogType\":\"ducklake\",\"columns\":[\"id\"],\"dropped\":true,\"kind\":\"table\","
		               "\"name\":\"secret2\",\"renamed\":true,\"schema\":\"main\",\"via\":null}],\"settings\":[],"
		               "\"statement\":\"PROJECTION\",\"v\":1}";
		expect_one("a TIMESTAMP read of tt while T held it (review N1)",
		           "SELECT id FROM lake.tt AT (TIMESTAMP => '" + snapshot_time(t_held) + "'::TIMESTAMPTZ)", secret2);
		expect_one("the change feed of tt while T held it (review N1)",
		           "SELECT id FROM ducklake_table_insertions('lake', 'main', 'tt', 1, " + t_held + ")", secret2);
	}
	// Review N4: a schema named like the metadata catalog in the default catalog cannot shadow the metadata
	// read, which names it fully: the dropped table is still judged by its last live name.
	{
		qod::RunStrict(con, "CREATE SCHEMA memory.__ducklake_metadata_lake", "spoof schema");
		expect_one("a dropped table read past a spoofing schema (review N4)",
		           "SELECT id FROM lake.gone AT (VERSION => " + gone_at + ")", dropped_read("gone", "\"id\"", false));
		con.Query("DROP SCHEMA memory.__ducklake_metadata_lake");
	}
	// Review N3: names compare as DuckDB resolves them, without case: CREATE OR REPLACE TABLE RC over rc is a
	// replace, and the read is the replacement's.
	{
		std::string rc_at;
		try {
			qod::RunStrict(con, "CREATE TABLE lake.rc (id INTEGER)", "create rc");
			qod::RunStrict(con, "INSERT INTO lake.rc VALUES (1)", "insert rc");
			rc_at = lake_head();
			qod::RunStrict(con, "CREATE OR REPLACE TABLE lake.RC AS SELECT * FROM lake.rc", "replace RC");
		} catch (std::exception &error) {
			Fail(std::string("review N3 setup: ") + error.what());
		}
		std::string error;
		auto own = feed("SELECT id FROM lake.rc AT (VERSION => " + rc_at + ")", error);
		if (!error.empty() || own.size() != 1 || own[0].find("\"replaced\":true") == std::string::npos ||
		    own[0].find("\"renamed\"") != std::string::npos ||
		    own[0].find("\"name\":\"rc\"") == std::string::npos && own[0].find("\"name\":\"RC\"") == std::string::npos) {
			Fail("a case-only CREATE OR REPLACE (review N3): " + error + (own.empty() ? "" : " " + own[0]));
		}
	}
	// A long replace chain (review I2): describing a read before the first replace walks the chain once,
	// forward, from DuckLake's own table versions, not once per replace; QOD_REPLACE_CHAIN sets its length
	// (default 300 here; 2000 when timed by hand). An interrupt while it is described takes effect.
	{
		auto chain = 300;
		if (auto env = std::getenv("QOD_REPLACE_CHAIN")) {
			chain = std::atoi(env);
		}
		std::string chain_first;
		try {
			qod::RunStrict(con, "CREATE TABLE lake.hourly (id INTEGER)", "create hourly");
			qod::RunStrict(con, "INSERT INTO lake.hourly VALUES (1)", "insert hourly");
			chain_first = lake_head();
			for (int i = 0; i < chain; i++) {
				qod::RunStrict(con, "CREATE OR REPLACE TABLE lake.hourly AS SELECT * FROM lake.hourly", "replace hourly");
			}
		} catch (std::exception &error) {
			Fail(std::string("DuckLake replace chain setup: ") + error.what());
		}
		auto sql = "SELECT id FROM lake.hourly AT (VERSION => " + chain_first + ")";
		auto started = std::chrono::steady_clock::now();
		std::string error;
		auto own = feed(sql, error);
		auto elapsed =
		    std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
		std::cout << "describer_extension_test: a read before " << chain << " replaces described in " << elapsed
		          << " ms" << std::endl;
		const std::string hourly = "{\"control\":[],\"functions\":[],\"objects\":[{\"access\":\"read\","
		                           "\"catalog\":\"lake\",\"catalogType\":\"ducklake\",\"columns\":[\"id\"],"
		                           "\"kind\":\"table\",\"name\":\"hourly\",\"replaced\":true,\"schema\":\"main\","
		                           "\"via\":null}],\"settings\":[],\"statement\":\"PROJECTION\",\"v\":1}";
		if (!error.empty() || own.size() != 1 || own[0] != hourly) {
			Fail("a read before a long replace chain: " + error + (own.empty() ? "" : " " + own[0]));
		}
		if (elapsed > 1000) {
			Fail("a read before " + std::to_string(chain) + " replaces took " + std::to_string(elapsed) + " ms");
		}
		interrupt_binds = true;
		error.clear();
		auto interrupted = feed(sql, error);
		interrupt_binds = false;
		if (!interrupted.empty()) {
			Fail("an interrupt while a dropped table's history is described did not stop the describer: " +
			     interrupted[0]);
		}
		if (error.find("Interrupted") == std::string::npos) {
			Fail("an interrupt while a dropped table's history is described: " + error);
		}
	}
	{
		std::string error;
		auto own = feed("SELECT id FROM lake.reused AT (VERSION => " + reused_at + ")", error);
		if (error.find("QoD: lake.main.reused at that snapshot is a table dropped since, and its name now "
		               "belongs to another table") == std::string::npos ||
		    !own.empty()) {
			Fail("a dropped table whose name was reused, read by a user: " + error +
			     (own.empty() ? "" : " " + own[0]));
		}
		capture.principal = qod_test::Root();
		auto from = capture.all.size();
		capture.on = true;
		auto as_root = con.Query("SELECT id FROM lake.reused AT (VERSION => " + reused_at + ")");
		capture.on = false;
		auto described = false;
		for (auto i = from; i < capture.all.size(); i++) {
			described = described || (capture.contexts[i] == con.context.get() &&
			                          capture.all[i].find("\"name\":\"reused\"") != std::string::npos);
		}
		if (as_root->HasError() || !described) {
			Fail("a dropped table whose name was reused, read by a superuser: " +
			     (as_root->HasError() ? as_root->GetError() : std::string("not described")));
		}
	}
	con.Query("DETACH lake");
	fs::remove_all(scratch);

	// ---- a scalar function an extension loaded at runtime is described by its catalog name, so the
	// manager can refuse it as not classified (fts is not among the functions --list-functions sees)
	if (Load(con, "fts")) {
		capture.principal = qod_test::Alice();
		capture.on = true;
		con.Query("SELECT stem('running', 'porter')");
		capture.on = false;
		Expect("an extension scalar function", capture.canonical,
		       "{\"args\":[],\"catalog\":\"system\",\"name\":\"stem\",\"schema\":\"main\",\"type\":\"scalar\"");
		capture.principal = qod_test::Root();
	} else {
		std::cout << "describer_extension_test: fts unavailable, the extension scalar case skipped" << std::endl;
	}

	if (failures == 0) {
		std::cout << "describer_extension_test: all passed" << std::endl;
	}
	return failures == 0 ? 0 : 1;
}
